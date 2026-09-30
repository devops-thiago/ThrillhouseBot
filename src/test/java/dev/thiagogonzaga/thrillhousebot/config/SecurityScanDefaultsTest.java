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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Default profile: both halves of the deterministic security scan (#60) are off, so an untouched
 * deployment gets exactly the findings and verdicts it gets today. Asserted through the resolved
 * configuration so the {@code thrillhousebot.review.security-scan.*} wiring in {@code
 * application.properties} is covered, not just the {@code @WithDefault} annotations.
 */
@QuarkusTest
class SecurityScanDefaultsTest {

  @Inject ThrillhouseConfig config;

  @Test
  void bothHalvesAreOffByDefault() {
    assertFalse(config.review().securityScan().secretsEnabled());
    assertFalse(config.review().securityScan().iacEnabled());
  }

  @Test
  void thresholdAndSkippedFilesMatchTheDocumentedDefaults() {
    assertEquals(3.5, config.review().securityScan().entropyThreshold());
    assertEquals(
        List.of(
            "**/fixtures/**",
            "**/__fixtures__/**",
            "**/testdata/**",
            "**/test-data/**",
            "**/__snapshots__/**",
            "**/*.snap"),
        config.review().securityScan().skippedFiles());
  }
}
