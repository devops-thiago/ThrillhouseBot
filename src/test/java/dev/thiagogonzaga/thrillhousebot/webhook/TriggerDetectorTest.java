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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TriggerDetectorTest {

  private final TriggerDetector detector = new TriggerDetector();

  @Test
  void shouldDetectSlashReview() {
    assertTrue(detector.isReviewTrigger("/review"));
    assertTrue(detector.isReviewTrigger("please /review this PR"));
    assertTrue(detector.isReviewTrigger("Hey, /review"));
  }

  @Test
  void shouldDetectMentionReview() {
    assertTrue(detector.isReviewTrigger("@Thrillhousebot review"));
    assertTrue(detector.isReviewTrigger("@ThrillhouseBot review"));
    assertTrue(detector.isReviewTrigger("Hey @thrillhousebot review this please"));
  }

  @Test
  void shouldIgnoreNonTriggerComments() {
    assertFalse(detector.isReviewTrigger("Looks good!"));
    assertFalse(detector.isReviewTrigger("review this"));
    assertFalse(detector.isReviewTrigger("@thrillhousebot hello"));
    assertFalse(detector.isReviewTrigger(""));
    assertFalse(detector.isReviewTrigger(null));
  }

  @Test
  void shouldDetectBotMention() {
    assertTrue(detector.containsBotMention("@thrillhousebot hello"));
    assertTrue(detector.containsBotMention("hey @Thrillhousebot what about this?"));
    assertTrue(detector.containsBotMention("@thrillhousebot review")); // still a mention
    assertTrue(detector.containsBotMention("line one\n@thrillhousebot wdyt"));
  }

  @Test
  void shouldNotDetectMentionWithoutBot() {
    assertFalse(detector.containsBotMention("looks good"));
    assertFalse(
        detector.containsBotMention("email me at me@thrillhousebottle.com")); // word boundary
    assertFalse(detector.containsBotMention(""));
    assertFalse(detector.containsBotMention(null));
  }

  @Test
  void shouldDetectBotLogin() {
    assertTrue(detector.isBotComment("thrillhousebot[bot]"));
    assertTrue(detector.isBotComment("thrillhouse-bot[bot]"));
    assertTrue(detector.isBotComment("THRILLHOUSEBOT[BOT]"));
  }

  @Test
  void shouldNotFlagHumanUser() {
    assertFalse(detector.isBotComment("octocat"));
    assertFalse(detector.isBotComment("thrillhousebot"));
    assertFalse(detector.isBotComment(""));
  }
}
