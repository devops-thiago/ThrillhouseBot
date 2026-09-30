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

import dev.thiagogonzaga.thrillhousebot.review.ReviewLearnings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Default profile: cross-review learnings (#38) are off, so an untouched deployment neither stores
 * nor replays maintainer decisions. Asserted through the resolved configuration and the wired bean,
 * so the {@code thrillhousebot.review.learnings.*} property wiring is covered too.
 */
@QuarkusTest
class LearningsDefaultOffTest {

  @Inject ThrillhouseConfig config;
  @Inject ReviewLearnings learnings;

  @Test
  void learningsAreOffByDefaultWithTheDocumentedCaps() {
    var settings = config.review().learnings();
    assertFalse(settings.enabled());
    assertEquals(100, settings.maxPerRepo());
    assertEquals(10, settings.promptMaxItems());
    assertEquals(3000, settings.promptMaxChars());
    assertTrue(config.review().declineRecheckEnabled(), "the re-check learnings depend on");
  }

  @Test
  void theWiredStoreInjectsNothingWhileOff() {
    assertFalse(learnings.enabled());
    assertEquals("", learnings.promptSection(1L, "o", "r", List.of("a.java")));
  }
}
