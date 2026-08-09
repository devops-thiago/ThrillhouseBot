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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Direct tests for the body cap. The client tests cover it through each request record; these pin
 * the helper's own contract — in particular the surrogate-safe cut, which no realistic body built
 * from ASCII filler would ever exercise.
 */
class CommentBodyLimitTest {

  @Test
  void returnsNullUnchanged() {
    assertNull(CommentBodyLimit.cap(null));
  }

  @Test
  void returnsAShortBodyByIdentity() {
    var body = "a short comment";

    assertSame(body, CommentBodyLimit.cap(body), "an in-limit body must pass through untouched");
  }

  @Test
  void returnsABodyExactlyOnTheLimitByIdentity() {
    var body = "x".repeat(CommentBodyLimit.MAX_LENGTH);

    assertSame(
        body,
        CommentBodyLimit.cap(body),
        "a body sitting exactly on the limit is valid and must not be truncated");
  }

  @Test
  void truncatesOneCharacterOverTheLimit() {
    var capped = CommentBodyLimit.cap("x".repeat(CommentBodyLimit.MAX_LENGTH + 1));

    assertEquals(CommentBodyLimit.MAX_LENGTH, capped.length());
    assertTrue(capped.endsWith(CommentBodyLimit.TRUNCATION_NOTICE), "the notice must be appended");
  }

  @Test
  void doesNotSplitASurrogatePairAtTheCutPoint() {
    // Place an astral code point (U+1F600, a high/low surrogate pair) so its HIGH surrogate lands
    // exactly on the last kept character. Cutting there would emit a lone high surrogate and
    // corrupt the code point, so the cut must step back one char.
    var keep = CommentBodyLimit.MAX_LENGTH - CommentBodyLimit.TRUNCATION_NOTICE.length();
    var body = "x".repeat(keep - 1) + "😀" + "y".repeat(CommentBodyLimit.MAX_LENGTH);

    var capped = CommentBodyLimit.cap(body);
    var kept = capped.substring(0, capped.length() - CommentBodyLimit.TRUNCATION_NOTICE.length());

    assertFalse(
        Character.isHighSurrogate(kept.charAt(kept.length() - 1)),
        "the kept text must not end in a dangling high surrogate");
    assertEquals(
        keep - 1, kept.length(), "the cut steps back one character to keep the pair intact");
    assertTrue(capped.endsWith(CommentBodyLimit.TRUNCATION_NOTICE));
  }

  @Test
  void keepsAWholeSurrogatePairWhenItEndsBeforeTheCut() {
    // The pair sits fully inside the kept region, so no step-back is needed — the false side of
    // the surrogate check.
    var keep = CommentBodyLimit.MAX_LENGTH - CommentBodyLimit.TRUNCATION_NOTICE.length();
    var body = "x".repeat(keep - 2) + "😀" + "y".repeat(CommentBodyLimit.MAX_LENGTH);

    var capped = CommentBodyLimit.cap(body);

    assertEquals(CommentBodyLimit.MAX_LENGTH, capped.length());
    assertTrue(
        Character.isLowSurrogate(capped.charAt(keep - 1)),
        "the pair ends on the last kept character, so nothing is trimmed");
  }
}
