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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import jakarta.ws.rs.core.Response;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The rate-limit headers reach {@link GitHubApiError#diagnostics()} the same way the body does, and
 * on the hosts this class's threat model names — a GHES or reverse-proxy error page, a
 * misconfigured base URL, a compromised endpoint — they are response data from the same untrusted
 * place. They were being interpolated verbatim while the body one field away was collapsed.
 */
class GitHubApiErrorHeaderCollapseTest {

  private static String diagnosticsWithHeader(String name, String value) {
    return GitHubApiError.from(
            Response.status(403).header(name, value).entity("{\"message\":\"no\"}").build())
        .diagnostics();
  }

  private static Stream<Arguments> headersAReaderCouldTakeForAControl() {
    return Stream.of(
        arguments("Retry-After", "NEL", "30" + (char) 0x0085 + "forged WARN approved"),
        arguments("x-ratelimit-remaining", "ESC", "0" + (char) 0x001B + "[2Kcleared"),
        arguments("x-ratelimit-reset", "LINE SEPARATOR", "0" + (char) 0x2028 + "forged"),
        arguments("x-ratelimit-resource", "NUL", "core" + (char) 0x0000 + "forged"));
  }

  @ParameterizedTest(name = "{0} carrying {1}")
  @MethodSource("headersAReaderCouldTakeForAControl")
  void collapsesEveryRateLimitHeaderOnItsWayIntoTheLine(String header, String name, String value) {
    var line = diagnosticsWithHeader(header, value);
    assertFalse(
        line.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)
            || line.indexOf(0x2028) >= 0,
        name + " survived into the diagnostics line: " + line);
  }

  /** The collapse must not blank the header — its value is what the operator came for. */
  @Test
  void keepsTheHeaderValueItCollapsed() {
    assertEquals(
        "status=403 retry-after=30 forged body={\"message\":\"no\"}",
        diagnosticsWithHeader("Retry-After", "30" + (char) 0x0085 + "forged"));
  }

  /** A control: an ordinary header is untouched. */
  @Test
  void leavesAnOrdinaryHeaderAlone() {
    assertEquals(
        "status=403 retry-after=30 body={\"message\":\"no\"}",
        diagnosticsWithHeader("Retry-After", "30"));
  }
}
