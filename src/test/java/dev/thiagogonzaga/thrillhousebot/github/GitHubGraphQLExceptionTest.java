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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** #1434: a GraphQL refusal is read off the response's {@code errors} array. */
class GitHubGraphQLExceptionTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private GitHubGraphQLException from(String errors) throws Exception {
    return GitHubGraphQLException.from(mapper.readTree(errors));
  }

  @Test
  void joinsEachDistinctMessageOnOneLine() throws Exception {
    var failure =
        from(
            "[{\"message\":\"Path\\nmissing\"},{\"message\":\"Path missing\"},{\"message\":\"x\"}]");

    assertEquals("Path missing; x", failure.refusal());
    assertFalse(failure.isThrottled());
    assertTrue(failure.getMessage().contains("Path missing; x"));
  }

  @Test
  void aRateLimitedTypeIsAThrottle() throws Exception {
    assertTrue(from("[{\"type\":\"RATE_LIMITED\",\"message\":\"API rate limit\"}]").isThrottled());
  }

  @Test
  void rateLimitWordingIsAThrottle() throws Exception {
    assertTrue(from("[{\"message\":\"was submitted too quickly\"}]").isThrottled());
  }

  @Test
  void noReadableMessageSaysSo() throws Exception {
    assertEquals("no data and no error message", from("[{\"type\":\"X\"}]").refusal());
    assertEquals(
        "no data and no error message",
        GitHubGraphQLException.from(mapper.missingNode()).refusal());
  }

  @Test
  void aLongRefusalIsBounded() throws Exception {
    var failure = from("[{\"message\":\"" + "a".repeat(400) + "\"}]");

    assertEquals(GitHubGraphQLException.MAX_REFUSAL_CHARS + 1, failure.refusal().length());
    assertTrue(failure.refusal().endsWith("…"));
  }
}
