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

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.ResponseRepairService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Default profile: the repair call (#1079) is on. Asserted through the resolved configuration and
 * the wired bean, so the {@code thrillhousebot.review.response-repair-enabled} line in {@code
 * application.properties} is covered, not only the {@code @WithDefault} annotation.
 */
@QuarkusTest
class ResponseRepairDefaultsTest {

  @Inject ThrillhouseConfig config;
  @Inject ResponseRepairService responseRepair;

  @Test
  void theStepIsOnByDefault() {
    assertTrue(config.review().responseRepairEnabled());
    assertTrue(responseRepair.enabled());
  }
}
