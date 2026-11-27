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
import java.io.Serial;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.kohsuke.github.connector.GitHubConnector;
import org.kohsuke.github.connector.GitHubConnectorRequest;
import org.kohsuke.github.connector.GitHubConnectorResponse;

/**
 * The transport of the hub4j calls, those made with the App's own credential and those {@link
 * GitHubTokenReads} makes with an installation token, in place of the extension's.
 *
 * <p>The extension's connector sets no request timeout, and hub4j reports a refused call as an
 * {@code IOException} whose message is the response body, after sleeping through a rate limit. The
 * callers here are written against the REST clients, so this connector keeps their contract: one
 * attempt, bounded by the configured request timeout from sending to the last byte of the body, a
 * refusal thrown as the {@link ClientWebApplicationException} a REST client throws (with no part of
 * the body in its message) and a transport failure as a {@link ProcessingException}. Both are
 * unchecked, so hub4j passes them through without retrying.
 *
 * <p>It also covers two calls hub4j has no method for, so they do not need a REST client:
 *
 * <ul>
 *   <li>An artifact or job log download that GitHub does not refuse is thrown as a {@link
 *       DownloadRedirect}, because hub4j follows the redirect itself and does not give out the
 *       pre-signed URL (<a
 *       href="https://github.com/hub4j/github-api/issues/2351">hub4j/github-api#2351</a>). Its body
 *       is never kept: only the address is needed, and the size of an artifact is the uploader's to
 *       choose.
 *   <li>A connector made by {@link #forUserInstallation} sends hub4j's installation repository
 *       listing to {@code /user/installations/{installation_id}/repositories} (<a
 *       href="https://github.com/hub4j/github-api/issues/2352">hub4j/github-api#2352</a>).
 * </ul>
 */
final class GitHubAppConnector implements GitHubConnector {

  private static final List<String> DIAGNOSTIC_HEADERS =
      List.of("Retry-After", "x-ratelimit-remaining", "x-ratelimit-reset", "x-ratelimit-resource");

  private static final String FAILED = "GitHub App request failed";

  /** The endpoints that answer with a pre-signed URL rather than the bytes. */
  private static final Pattern DOWNLOAD_PATH =
      Pattern.compile("/actions/(?:artifacts/\\d+/zip|jobs/\\d+/logs)$");

  private static final String INSTALLATION_REPOSITORIES = "/installation/repositories";

  /** Keeps a refusal's body for the diagnostics and discards every other one as it arrives. */
  static final HttpResponse.BodyHandler<byte[]> REFUSAL_BODY_ONLY =
      answer ->
          answer.statusCode() >= Response.Status.BAD_REQUEST.getStatusCode()
              ? HttpResponse.BodySubscribers.ofByteArray()
              : HttpResponse.BodySubscribers.replacing(new byte[0]);

  private final HttpClient httpClient;
  private final Duration requestTimeout;
  private final GitHubErrorLogger errorLogger = new GitHubErrorLogger();

  private final OptionalLong userInstallation;

  GitHubAppConnector(HttpClient httpClient, Duration requestTimeout) {
    this(httpClient, requestTimeout, OptionalLong.empty());
  }

  private GitHubAppConnector(
      HttpClient httpClient, Duration requestTimeout, OptionalLong userInstallation) {
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.userInstallation = userInstallation;
  }

  /**
   * A connector for a user access token, under which hub4j's {@code
   * getInstallation().listRepositories()} lists what that user can read in one installation.
   */
  GitHubAppConnector forUserInstallation(long installationId) {
    return new GitHubAppConnector(httpClient, requestTimeout, OptionalLong.of(installationId));
  }

  @Override
  public GitHubConnectorResponse send(GitHubConnectorRequest request) throws IOException {
    var target = target(request);
    var download = DOWNLOAD_PATH.matcher(target.getPath()).find();
    var body = download ? REFUSAL_BODY_ONLY : HttpResponse.BodyHandlers.ofByteArray();
    var answer = new BufferedResponse(request, exchange(httpRequest(request, target), body));
    if (answer.statusCode() >= Response.Status.BAD_REQUEST.getStatusCode()) {
      throw refused(answer);
    }
    if (download) {
      var redirected = answer.statusCode() >= Response.Status.MOVED_PERMANENTLY.getStatusCode();
      var location = redirected ? answer.header("Location") : null;
      throw new DownloadRedirect(location == null ? null : target.resolve(location));
    }
    return answer;
  }

  private URI target(GitHubConnectorRequest request) {
    try {
      var uri = request.url().toURI();
      if (userInstallation.isEmpty() || !uri.getPath().endsWith(INSTALLATION_REPOSITORIES)) {
        return uri;
      }
      return URI.create(
          uri.toString()
              .replaceFirst(
                  INSTALLATION_REPOSITORIES,
                  "/user/installations/" + userInstallation.getAsLong() + "/repositories"));
    } catch (URISyntaxException e) {
      throw new ProcessingException(FAILED + ": invalid request URL", e);
    }
  }

  private static HttpRequest httpRequest(GitHubConnectorRequest request, URI target)
      throws IOException {
    var builder = HttpRequest.newBuilder(target);
    request.allHeaders().forEach((name, values) -> builder.header(name, String.join(",", values)));
    try (var content = request.body()) {
      var body =
          content == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(content.readAllBytes());
      return builder.method(request.method(), body).build();
    }
  }

  private HttpResponse<byte[]> exchange(
      HttpRequest request, HttpResponse.BodyHandler<byte[]> body) {
    var pending = httpClient.sendAsync(request, body);
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

  /**
   * A download endpoint's answer, handed to the caller in place of hub4j acting on it; it has no
   * location unless GitHub redirected. The message never carries the address, which grants access
   * on its own.
   */
  static final class DownloadRedirect extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final URI location;

    private DownloadRedirect(URI location) {
      super("GitHub answered a download", null, false, false);
      this.location = location;
    }

    /** Where GitHub serves the bytes; empty when it named no address. */
    Optional<URI> location() {
      return Optional.ofNullable(location);
    }
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
