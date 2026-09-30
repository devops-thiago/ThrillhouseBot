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
package dev.thiagogonzaga.thrillhousebot.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** #60: the rule catalogue's grades and the titles a later round recognizes. */
class SecurityRuleTest {

  @Test
  void everyRuleIsRecognizedFromTheTitleItPublishes() {
    for (var rule : SecurityRule.values()) {
      var title =
          rule.category() == SecurityRule.Category.IAC
              ? rule.iacTitle()
              : rule.secretTitleHead() + " (abcd…, 20 chars)";
      assertEquals(rule, SecurityRule.fromTitle(title), title);
    }
    assertEquals(
        SecurityRule.GENERIC_SECRET,
        SecurityRule.fromTitle(
            SecurityRule.GENERIC_SECRET.secretTitleHead() + " in db_password (q8…, 8 chars)"));
  }

  @Test
  void aTitleTheScanDidNotWriteIsNotRecognized() {
    assertNull(SecurityRule.fromTitle(null));
    assertNull(SecurityRule.fromTitle("Hardcoded AWS credentials"));
    assertNull(SecurityRule.fromTitle(SecurityRule.TITLE_PREFIX + "something else"));
    assertNull(SecurityRule.fromTitle(SecurityRule.PRIVILEGED_CONTAINER.iacTitle() + " (again)"));
  }

  @Test
  void gradesFollowTheRule() {
    assertEquals("critical", SecurityRule.GITHUB_TOKEN.risk());
    assertEquals("high", SecurityRule.GITHUB_TOKEN.confidence());
    assertEquals("high", SecurityRule.JWT.risk());
    assertEquals("high", SecurityRule.GENERIC_SECRET.risk());
    // #773: the same grade SeverityCalibrator gives the model's missing-privilege-drop class.
    assertEquals("medium", SecurityRule.ROOT_USER.risk());
    assertEquals("medium", SecurityRule.ROOT_USER.confidence());
    for (var rule : SecurityRule.values()) {
      assertFalse(rule.topic().isEmpty(), rule.name());
      assertEquals(
          rule.category() == SecurityRule.Category.IAC, !rule.explanation().isEmpty(), rule.name());
      assertEquals(rule.label(), rule.label().strip());
    }
  }
}
