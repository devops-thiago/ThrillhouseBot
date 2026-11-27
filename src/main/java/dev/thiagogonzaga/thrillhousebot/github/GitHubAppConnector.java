/*
 * Copyright 2026 Thiago Gonzaga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.thiagogonzaga.thrillhousebot.github;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.kohsuke.github.connector.GitHubConnector;
import org.kohsuke.github.connector.GitHubConnectorRequest;
import org.kohsuke.github.connector.GitHubConnectorResponse;

/**
 * The transport of the calls made with the App's own credential, in place of the extension's.
 *
 * <p>The extension's connector sets no request timeout, and hub4j reports a refused call as an
 * {@code IOException} whose message is the response body, after sleeping through a rate limit. The
 * callers here are written against the REST clients, so this connector keeps their contract: one
 * attempt, bounded by the configured request timeout from sending to the last byte of the body, a
 * refusal thrown as the {@link ClientWebApplicationException} a REST client throws (with no part of
 * the body in its message) and a transport failure as a {@link ProcessingException}. Both are
 * unchecked, so hub4j passes them through without retrying.
 */
final class GitHubAppConnector implements GitHubConnector {

  private static final List<String> DIAGNOSTIC_HEADERS =
      List.of("Retry-After", "x-ratelimit-remaining", "x-ratelimit-reset", "x-ratelimit-resource");

  private static final String FAILED = "GitHub App request failed";

  private final HttpClient httpClient;
  private final Duration requestTimeout;
  private final GitHubErrorLogger errorLogger = new GitHubErrorLogger();

  GitHubAppConnector(HttpClient httpClient, Duration requestTimeout) {
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  @Override
  public GitHubConnectorResponse send(GitHubConnectorRequest request) throws IOException {
    var answer = new BufferedResponse(request, exchange(httpRequest(request)));
    if (answer.statusCode() >= Response.Status.BAD_REQUEST.getStatusCode()) {
      throw refused(answer);
    }
    return answer;
  }

  private static HttpRequest httpRequest(GitHubConnectorRequest request) throws IOException {
    try {
      var builder = HttpRequest.newBuilder(request.url().toURI());
      request
          .allHeaders()
          .forEach((name, values) -> builder.header(name, String.join(",", values)));
      try (var content = request.body()) {
        var body =
            content == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(content.readAllBytes());
        return builder.method(request.method(), body).build();
      }
    } catch (URISyntaxException e) {
      throw new ProcessingException(FAILED + ": invalid request URL", e);
    }
  }

  private HttpResponse<byte[]> exchange(HttpRequest request) {
    var pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    try {
      return pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      var interrupted = new InterruptedIOException("interrupted while waiting for GitHub");
      interrupted.initCause(e);
      throw new ProcessingException(FAILED + ": interrupted", interrupted);
    } catch (TimeoutException _) {
      pending.cancel(true);
      throw new ProcessingException(
          FAILED + ": timed out", new HttpTimeoutException("request timed out"));
    } catch (ExecutionException e) {
      throw new ProcessingException(FAILED, e.getCause());
    }
  }

  /** The exception a REST client would have raised, after the same diagnostics log line. */
  private ClientWebApplicationException refused(GitHubConnectorResponse answer) throws IOException {
    var response = Response.status(answer.statusCode());
    for (var name : DIAGNOSTIC_HEADERS) {
      var value = answer.header(name);
      if (value != null) {
        response.header(name, value);
      }
    }
    try (var body = answer.bodyStream()) {
      response.entity(new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }
    var built = response.build();
    errorLogger.toThrowable(built);
    return new ClientWebApplicationException(FAILED + " with status " + answer.statusCode(), built);
  }

  /** A response whose body has already been read, decompressed by hub4j when it is read back. */
  private static final class BufferedResponse extends GitHubConnectorResponse.ByteArrayResponse {

    private final byte[] body;

    BufferedResponse(GitHubConnectorRequest request, HttpResponse<byte[]> response) {
      super(request, response.statusCode(), response.headers().map());
      this.body = response.body().clone();
    }

    @Override
    protected InputStream rawBodyStream() {
      return new ByteArrayInputStream(body);
    }
  }
}
