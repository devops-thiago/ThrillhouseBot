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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Default profile: the large-PR nudge is off, so an untouched deployment renders exactly the
 * summary comment it renders today even on a huge finding-free PR. Its thresholds still resolve, so
 * an operator who flips the one switch gets the documented 20 files / 1000 changed lines without
 * setting anything else. Asserted through the resolved configuration rather than the
 * {@code @WithDefault} annotations, so the {@code thrillhousebot.review.large-pr-nudge.*} property
 * wiring in {@code application.properties} is covered too.
 */
@QuarkusTest
class LargePrNudgeDefaultsTest {

  @Inject ThrillhouseConfig config;

  @Test
  void largePrNudgeIsOffByDefault() {
    assertFalse(config.review().largePrNudge().enabled());
  }

  @Test
  void largePrNudgeThresholdsMatchTheDocumentedDefaults() {
    assertEquals(20, config.review().largePrNudge().minFiles());
    assertEquals(1000, config.review().largePrNudge().minChangedLines());
  }
}
