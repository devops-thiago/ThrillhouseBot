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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pins the guidance the related context (#55) depends on. */
class RelatedContextPromptsContentTest {

  @Test
  void theSelectionPromptNamesTheFourReasonsAndTheAnswerShape() {
    for (var reason :
        new String[] {
          "\"caller\"", "\"interface\"", "\"sanitizer-or-validator\"", "\"config-consumer\""
        }) {
      assertTrue(RelatedContextPrompts.SYSTEM.contains(reason), reason);
    }
    assertTrue(RelatedContextPrompts.SYSTEM.contains("{\"related\": [{\"id\": 1"));
    assertTrue(RelatedContextPrompts.SYSTEM.contains("untrusted data"));
    assertTrue(RelatedContextPrompts.USER.contains("{{changedFiles}}"));
    assertTrue(RelatedContextPrompts.USER.contains("{{candidates}}"));
    assertTrue(RelatedContextPrompts.USER.contains("{{maxFiles}}"));
    assertTrue(RelatedContextPrompts.templates().length() > RelatedContextPrompts.SYSTEM.length());
  }

  @Test
  void theReviewRequestKeepsFindingsOnChangedLinesAndAsksForTheEvidence() {
    var request = RelatedContextPrompts.REVIEW_REQUEST;

    assertTrue(request.startsWith("## Related context (read-only, not part of the change)"));
    assertTrue(request.contains("untrusted data"));
    assertTrue(request.contains("Never anchor a finding to a file shown here"));
    assertTrue(request.contains("name the file and quote the"));
  }
}
