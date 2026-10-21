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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SentenceBreaksTest {

  @Test
  void aPeriodBeforeABlankOrTheEndAndAQuestionOrExclamationMarkEndASentence() {
    assertTrue(SentenceBreaks.endsSentenceAt("It runs. Then", 8));
    assertTrue(SentenceBreaks.endsSentenceAt("It runs.", 8));
    assertTrue(SentenceBreaks.endsSentenceAt("Does it?Yes", 8));
    assertTrue(SentenceBreaks.endsSentenceAt("It does!", 8));
  }

  @Test
  void abbreviationsDecimalsAndDottedNamesEndNoSentence() {
    for (var text :
        new String[] {"a guard, e.g. x", "one, i.e. x", "tags, etc. x", "a vs. b", "see cf. x"}) {
      assertFalse(SentenceBreaks.endsSentenceAt(text, text.indexOf(". ") + 1), text);
    }
    for (var text :
        new String[] {
          "Dr. x", "Mr. x", "Mrs. x", "Ms. x", "Prof. x", "St. x", "Jr. x", "Sr. x", "(Dr. x"
        }) {
      assertFalse(SentenceBreaks.endsSentenceAt(text, text.indexOf(". ") + 1), text);
    }
    assertFalse(SentenceBreaks.endsSentenceAt("ratio 1.5", 7));
    assertFalse(SentenceBreaks.endsSentenceAt("Foo.bar", 4));
    assertFalse(SentenceBreaks.endsSentenceAt("Client.java", 7));
    assertFalse(SentenceBreaks.endsSentenceAt("a, b", 2));
  }

  @Test
  void aWordOnlyShapedLikeATitleEndsASentence() {
    for (var text :
        new String[] {"500 ms. x", "the dr. x", "the CIDR. x", "an MS. x", "the prof. x"}) {
      assertTrue(SentenceBreaks.endsSentenceAt(text, text.lastIndexOf('.') + 1), text);
    }
  }

  @Test
  void anIndexOutsideTheTextEndsNothing() {
    assertFalse(SentenceBreaks.endsSentenceAt("It runs.", 0));
    assertFalse(SentenceBreaks.endsSentenceAt("It runs.", 9));
  }
}
