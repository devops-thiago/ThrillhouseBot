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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.Arrays;
import java.util.List;
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
  void shouldDetectEachSlashCommand() {
    assertEquals(CommentCommand.REVIEW, detector.detectCommand("/review"));
    assertEquals(CommentCommand.HELP, detector.detectCommand("please /help"));
    assertEquals(CommentCommand.SUMMARY, detector.detectCommand("/summary this PR"));
    assertEquals(CommentCommand.DESCRIBE, detector.detectCommand("/describe"));
    assertEquals(CommentCommand.CHANGELOG, detector.detectCommand("/changelog"));
    assertEquals(CommentCommand.ADD_DOCS, detector.detectCommand("/add-docs please"));
    assertEquals(CommentCommand.RESOLVE, detector.detectCommand("/resolve"));
    assertEquals(CommentCommand.PAUSE, detector.detectCommand("hey /pause"));
    assertEquals(CommentCommand.RESUME, detector.detectCommand("/resume now"));
  }

  @Test
  void shouldDetectEachMentionCommand() {
    assertEquals(CommentCommand.REVIEW, detector.detectCommand("@Thrillhousebot review"));
    assertEquals(CommentCommand.HELP, detector.detectCommand("@thrillhousebot help"));
    assertEquals(CommentCommand.SUMMARY, detector.detectCommand("@thrillhousebot summary please"));
    assertEquals(CommentCommand.DESCRIBE, detector.detectCommand("@thrillhousebot describe"));
    assertEquals(CommentCommand.CHANGELOG, detector.detectCommand("@thrillhousebot changelog"));
    assertEquals(CommentCommand.ADD_DOCS, detector.detectCommand("@thrillhousebot add-docs"));
    assertEquals(CommentCommand.RESOLVE, detector.detectCommand("@thrillhousebot resolve"));
    assertEquals(CommentCommand.PAUSE, detector.detectCommand("@thrillhousebot pause"));
    assertEquals(CommentCommand.RESUME, detector.detectCommand("@thrillhousebot resume"));
  }

  @Test
  void shouldNotConfuseSimilarCommandWords() {
    // /resume and /resolve must not match the /review pattern, and vice versa.
    assertEquals(CommentCommand.RESUME, detector.detectCommand("/resume"));
    assertEquals(CommentCommand.RESOLVE, detector.detectCommand("/resolve"));
    assertEquals(CommentCommand.REVIEW, detector.detectCommand("/review"));
  }

  @Test
  void shouldReturnNoneForNonCommandComments() {
    assertEquals(CommentCommand.NONE, detector.detectCommand("Looks good!"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("review this"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("@thrillhousebot hello"));
    assertEquals(CommentCommand.NONE, detector.detectCommand(""));
    assertEquals(CommentCommand.NONE, detector.detectCommand(null));
  }

  @Test
  void shouldPreferReviewWhenMultipleCommandsPresent() {
    // review has detection precedence so the original trigger behavior is preserved.
    assertEquals(CommentCommand.REVIEW, detector.detectCommand("/help and /review"));
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
    assertFalse(detector.isBotComment(null));
  }

  @Test
  void shouldNotDetectCommandInsideFencedCodeBlock() {
    assertEquals(CommentCommand.NONE, detector.detectCommand("```\n/pause\n```"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("~~~\n/resolve\n~~~"));
  }

  @Test
  void shouldNotDetectCommandInsideBlockquote() {
    assertEquals(CommentCommand.NONE, detector.detectCommand("> /pause"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("  > someone said /resume"));
  }

  @Test
  void shouldNotDetectCommandOrMentionInsideInlineCode() {
    assertEquals(CommentCommand.NONE, detector.detectCommand("use `/review` to trigger a review"));
    assertFalse(detector.containsBotMention("ping `@thrillhousebot` here"));
  }

  @Test
  void shouldStillDetectRealCommandAlongsideQuotedOne() {
    // A genuine command outside the quoted block still fires.
    assertEquals(CommentCommand.REVIEW, detector.detectCommand("> quoting a /pause\n/review"));
  }

  @Test
  void shouldUseConfiguredBotLogins() {
    var config = mock(ThrillhouseConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(github);
    when(github.botLogins()).thenReturn(List.of("my-app[bot]", " Other[Bot] "));
    var configured = new TriggerDetector(config);

    assertTrue(configured.isBotComment("my-app[bot]"));
    assertTrue(configured.isBotComment("other[bot]")); // normalized: trimmed + case-insensitive
    assertFalse(configured.isBotComment("thrillhousebot[bot]"));
  }

  @Test
  void shouldFallBackToDefaultLoginsWhenConfiguredListIsEmpty() {
    // An empty list would make isBotComment always false and let the bot loop on its own replies.
    var withEmptyConfig = new TriggerDetector(List.of());
    assertTrue(withEmptyConfig.isBotComment("thrillhousebot[bot]"));
  }

  @Test
  void shouldFallBackToDefaultLoginsWhenConfiguredListIsNull() {
    var withNullConfig = new TriggerDetector((List<String>) null);
    assertTrue(withNullConfig.isBotComment("thrillhousebot[bot]"));
  }

  @Test
  void shouldIgnoreNullAndBlankConfiguredLogins() {
    var configured = new TriggerDetector(Arrays.asList("keep[bot]", null, "   "));
    assertTrue(configured.isBotComment("keep[bot]"));
    assertFalse(configured.isBotComment("thrillhousebot[bot]")); // replaced by the configured list
  }
}
