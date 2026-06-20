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
package dev.thiagogonzaga.thrillhousebot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotIdentityTest {

  @Test
  void shouldMatchAnyConfiguredLoginCaseInsensitively() {
    var identity = BotIdentity.of("my-app[bot]", "Other[Bot]");

    assertTrue(identity.matches("my-app[bot]"));
    assertTrue(identity.matches("MY-APP[BOT]"));
    assertTrue(identity.matches("other[bot]")); // normalized on the way in
    assertFalse(identity.matches("thrillhousebot[bot]"));
  }

  @Test
  void shouldNeverMatchNull() {
    assertFalse(BotIdentity.of("my-app[bot]").matches(null));
  }

  @Test
  void shouldTrimAndDeduplicateLogins() {
    var identity = BotIdentity.of("  Bot[Bot]  ", "bot[bot]");

    assertEquals(1, identity.logins().size());
    assertTrue(identity.logins().contains("bot[bot]"));
    assertTrue(identity.matches("bot[bot]"));
  }

  @Test
  void shouldFallBackToDefaultsWhenNull() {
    var identity = BotIdentity.from(null);

    assertTrue(identity.matches("thrillhousebot[bot]"));
    assertTrue(identity.matches("thrillhouse-bot[bot]"));
  }

  @Test
  void shouldFallBackToDefaultsWhenEmptyOrAllBlank() {
    // An empty identity would make matches() always false and let the bot loop on its own replies.
    assertTrue(BotIdentity.from(List.of()).matches("thrillhousebot[bot]"));
    assertTrue(BotIdentity.from(Arrays.asList(null, "  ", "")).matches("thrillhousebot[bot]"));
  }

  @Test
  void identitiesWithTheSameLoginsAreEqual() {
    assertEquals(
        BotIdentity.of("thrillhousebot[bot]"), BotIdentity.from(List.of("thrillhousebot[bot]")));
  }
}
