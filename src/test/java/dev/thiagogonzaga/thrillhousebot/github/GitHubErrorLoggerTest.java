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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitHubErrorLogger} — the missing half of #568. The bot logged {@code status code
 * 403} and nothing else, so a secondary rate limit and a missing App permission read identically
 * and neither the maintainer nor an operator could tell which had happened.
 */
class GitHubErrorLoggerTest {

  private static final String SECONDARY_LIMIT_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit.\"}";

  private final GitHubErrorLogger mapper = new GitHubErrorLogger();

  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private Logger julLogger;
  private Handler capture;
  private Level originalLevel;

  @BeforeEach
  void captureLogging() {
    julLogger = Logger.getLogger(GitHubErrorLogger.class.getName());
    originalLevel = julLogger.getLevel();
    julLogger.setLevel(Level.ALL);
    capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logged.add(record);
          }

          @Override
          public void flush() {
            // Nothing is buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    julLogger.addHandler(capture);
  }

  @AfterEach
  void restoreLogging() {
    julLogger.removeHandler(capture);
    julLogger.setLevel(originalLevel);
  }

  private String warnings() {
    return logged.stream()
        .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
        .map(record -> record.getMessage() + " " + Arrays.toString(record.getParameters()))
        .reduce("", (left, right) -> left + right);
  }

  private static Response response(int status, String body, String... headers) {
    var builder = Response.status(status);
    for (int i = 0; i < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    return builder.entity(body).build();
  }

  @Test
  void handlesEveryErrorStatusAndNothingBelowIt() {
    var headers = new MultivaluedHashMap<String, Object>();

    assertFalse(mapper.handles(200, headers));
    assertFalse(mapper.handles(399, headers));
    assertTrue(mapper.handles(400, headers));
    assertTrue(mapper.handles(403, headers));
    assertTrue(mapper.handles(500, headers));
  }

  @Test
  void outranksTheRuntimeDefaultMapperSoItSeesTheResponseFirst() {
    assertTrue(mapper.getPriority() < Integer.MAX_VALUE);
  }

  @Test
  void raisesNoExceptionOfItsOwnSoCallersKeepTheHandlingTheyHave() {
    // Returning null hands the response on to the default mapper, which builds the exception the
    // fail-soft posting wrappers already catch. This class changes the log and nothing else.
    assertNull(mapper.toThrowable(response(403, SECONDARY_LIMIT_BODY)));
  }

  @Test
  void warnsWithTheBodyAndTheHeadersThatIdentifyAThrottle() {
    mapper.toThrowable(
        response(403, SECONDARY_LIMIT_BODY, "Retry-After", "60", "x-ratelimit-remaining", "0"));

    var warnings = warnings();
    assertTrue(warnings.contains("secondary rate limit"), warnings);
    assertTrue(warnings.contains("retry-after=60"), warnings);
    assertTrue(warnings.contains("x-ratelimit-remaining=0"), warnings);
  }

  @Test
  void warnsWithTheBodyThatIdentifiesAPermissionRefusalInstead() {
    mapper.toThrowable(response(403, "{\"message\":\"Resource not accessible by integration\"}"));

    var warnings = warnings();
    assertTrue(warnings.contains("Resource not accessible by integration"), warnings);
    assertFalse(warnings.contains("retry-after"), warnings);
  }

  @Test
  void staysSilentAndStillMapsTheResponseWhenLoggingIsOff() {
    // Both lines sit behind a level check, because diagnostics() builds its string eagerly and a
    // parameter placeholder only defers the toString. With the logger off neither branch may log,
    // and the mapper must still hand the response on to the runtime's default mapper.
    julLogger.setLevel(Level.OFF);

    assertNull(mapper.toThrowable(response(403, SECONDARY_LIMIT_BODY)));
    assertNull(mapper.toThrowable(response(404, "{\"message\":\"Not Found\"}")));

    assertTrue(logged.isEmpty(), logged.toString());
  }

  @Test
  void doesNotWarnAboutAnAbsentOptionalRepositoryFile() {
    // The bot probes for .github/instructions and the settings file and reads 404 as "absent";
    // warning on those would bury the failures that matter under ordinary traffic.
    assertNull(mapper.toThrowable(response(404, "{\"message\":\"Not Found\"}")));

    assertEquals("", warnings());
    assertTrue(
        logged.stream().anyMatch(record -> record.getLevel().intValue() < Level.INFO.intValue()),
        "still recorded, just at debug");
  }
}
