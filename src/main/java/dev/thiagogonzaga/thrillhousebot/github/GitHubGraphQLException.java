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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.thiagogonzaga.thrillhousebot.LogSafe;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/**
 * A GraphQL operation GitHub answered without doing it. GraphQL reports a refusal in the response's
 * {@code errors} array on an HTTP 200, so nothing in the REST client raises it; this carries
 * GitHub's own words for the refusal, and whether they say GitHub is rate limiting rather than
 * refusing the payload, so a caller can treat it as it treats a refused REST write.
 */
public final class GitHubGraphQLException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Longest refusal text kept: it is named in a log line and in a review body. */
  static final int MAX_REFUSAL_CHARS = 300;

  /** GitHub's rate-limit wording, as {@link GitHubApiError} reads it on a REST response. */
  private static final Pattern THROTTLE_WORDING =
      Pattern.compile("(?i)secondary rate limit|rate limit exceeded|submitted too quickly");

  private static final int TOO_MANY_REQUESTS = 429;

  private final String refusal;
  private final boolean throttled;

  private GitHubGraphQLException(String refusal, boolean throttled) {
    super("GitHub refused the GraphQL operation: " + refusal);
    this.refusal = refusal;
    this.throttled = throttled;
  }

  /**
   * Reads the {@code errors} array of a response that did not carry the expected data. Each entry's
   * {@code message} is kept once, in order; a response with no readable message says so.
   */
  public static GitHubGraphQLException from(JsonNode errors) {
    var messages = new LinkedHashSet<String>();
    var throttled = false;
    for (var error : errors) {
      var message = LogSafe.oneLine(error.path("message").asText(""));
      if (!message.isEmpty()) {
        messages.add(message);
      }
      throttled |=
          "RATE_LIMITED".equals(error.path("type").asText(""))
              || THROTTLE_WORDING.matcher(message).find();
    }
    var refusal = messages.isEmpty() ? "no data and no error message" : String.join("; ", messages);
    if (refusal.length() > MAX_REFUSAL_CHARS) {
      refusal = refusal.substring(0, MAX_REFUSAL_CHARS) + "…";
    }
    return new GitHubGraphQLException(refusal, throttled);
  }

  /** GitHub's words for the refusal, on one line and bounded. */
  public String refusal() {
    return refusal;
  }

  /** Whether GitHub said it was rate limiting the call rather than refusing it. */
  public boolean isThrottled() {
    return throttled;
  }

  /**
   * This refusal as the HTTP failure the REST write path raises for a throttle: a 429 whose body
   * carries GitHub's words as its {@code message}, with this exception as its cause. {@link
   * GitHubWriteRetry} backs it off and {@link GitHubLostWrites} counts it exactly as it does a
   * throttled REST write.
   */
  WebApplicationException asHttpThrottle() {
    return new WebApplicationException(
        this,
        Response.status(TOO_MANY_REQUESTS)
            .entity(JsonNodeFactory.instance.objectNode().put("message", refusal).toString())
            .build());
  }
}
