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
    assertEquals(CommentCommand.IMPROVE, detector.detectCommand("/improve"));
    assertEquals(CommentCommand.GENERATE_TESTS, detector.detectCommand("/generate-tests"));
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
    assertEquals(CommentCommand.IMPROVE, detector.detectCommand("@thrillhousebot improve"));
    assertEquals(
        CommentCommand.GENERATE_TESTS,
        detector.detectCommand("@thrillhousebot generate-tests for this"));
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

  /**
   * The mention gate fronts the conversational-reply path, including the {@code resolved} clear
   * directive's acknowledgements, so it must answer to the configured bot login — a hardcoded slug
   * would leave a custom-login install's maintainers unable to address their bot at all (#679).
   */
  @Test
  void shouldDetectMentionOfTheConfiguredBotLoginNotAHardcodedSlug() {
    var custom = new TriggerDetector(List.of("my-review-bot[bot]"));

    assertTrue(custom.containsBotMention("@my-review-bot resolved src/A.java:10 — title"));
    assertTrue(custom.containsBotMention("hey @My-Review-Bot what about this?"));
    assertFalse(
        custom.containsBotMention("@thrillhousebot resolved src/A.java:10 — title"),
        "the default slug is not this install's bot");
    assertTrue(
        detector.containsBotMention("@thrillhouse-bot wdyt"),
        "the shipped alternate slug is a first-class mention under the default identity");
  }

  /** An email address's local part never mentions the bot; a real mention's {@code @} does. */
  @Test
  void shouldNotDetectMentionInsideAnEmailAddress() {
    var custom = new TriggerDetector(List.of("my-review-bot[bot]"));

    assertFalse(custom.containsBotMention("email foo@my-review-bot.example"));
    assertFalse(detector.containsBotMention("mail root@thrillhousebot please"));
    assertTrue(detector.containsBotMention("(@thrillhousebot wdyt?)"));
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
  void shouldNotDetectImproveInsideQuotedContext() {
    // Documenting or quoting /improve must never run it.
    assertEquals(CommentCommand.NONE, detector.detectCommand("```\n/improve\n```"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("> they said /improve"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("run `/improve` to get suggestions"));
    assertEquals(
        CommentCommand.NONE,
        detector.detectCommand("`@thrillhousebot improve` is the mention form"));
  }

  @Test
  void shouldDetectImproveOutsideAQuotedMention() {
    assertEquals(CommentCommand.IMPROVE, detector.detectCommand("> quoting `/improve`\n/improve"));
  }

  @Test
  void shouldNotConfuseImproveWithOtherWords() {
    assertEquals(CommentCommand.NONE, detector.detectCommand("improve this please"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("/improvements"));
  }

  @Test
  void shouldNotDetectGenerateTestsInsideQuotedContext() {
    // Documenting or quoting the command must never run it — it generates content and spends
    // the operator's AI budget.
    assertEquals(CommentCommand.NONE, detector.detectCommand("```\n/generate-tests\n```"));
    assertEquals(
        CommentCommand.NONE, detector.detectCommand("run `/generate-tests` to propose tests"));
    // Padded inside the backticks, the slash form satisfies the whitespace boundary on its own,
    // so this case rests on inline-code stripping rather than on the pattern.
    assertEquals(CommentCommand.NONE, detector.detectCommand("run ` /generate-tests ` for tests"));
    // Same for the mention form, which has no leading-slash boundary to fall back on.
    assertEquals(
        CommentCommand.NONE, detector.detectCommand("run `@thrillhousebot generate-tests` please"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("> someone said /generate-tests"));
    assertEquals(
        CommentCommand.NONE, detector.detectCommand("~~~\n@thrillhousebot generate-tests\n~~~"));
  }

  @Test
  void shouldNotConfuseGenerateTestsWithOtherWords() {
    assertEquals(CommentCommand.NONE, detector.detectCommand("generate-tests this please"));
    assertEquals(CommentCommand.NONE, detector.detectCommand("/generate-tests-now"));
  }

  @Test
  void shouldStillDetectRealCommandAlongsideQuotedOne() {
    // A genuine command outside the quoted block still fires.
    assertEquals(CommentCommand.REVIEW, detector.detectCommand("> quoting a /pause\n/review"));
  }

  @Test
  void shouldStillDetectGenerateTestsAlongsideAQuotedOne() {
    // A genuine invocation next to a quoted one still fires.
    assertEquals(
        CommentCommand.GENERATE_TESTS,
        detector.detectCommand("```\n/generate-tests\n```\n\n/generate-tests"));
    // A higher-precedence command that appears only inside a quote must not win over the real one.
    assertEquals(
        CommentCommand.GENERATE_TESTS,
        detector.detectCommand("> please run /review first\n/generate-tests"));
  }

  @Test
  void shouldNotLetAQuotedNeighborCommandStealARealOne() {
    // /improve and /generate-tests are adjacent entries in the ordered pattern map, and /improve
    // is matched first. Quoting either one must never divert the other's genuine invocation.
    assertEquals(
        CommentCommand.GENERATE_TESTS,
        detector.detectCommand("```\n/improve\n```\n\n/generate-tests"));
    // Padded inside the span, so this rests on inline-code stripping rather than on the slash
    // pattern's whitespace boundary.
    assertEquals(
        CommentCommand.GENERATE_TESTS,
        detector.detectCommand("run ` /improve ` some day\n\n/generate-tests"));
    assertEquals(
        CommentCommand.GENERATE_TESTS,
        detector.detectCommand("> they asked for /improve\n/generate-tests"));
    assertEquals(
        CommentCommand.IMPROVE, detector.detectCommand("```\n/generate-tests\n```\n\n/improve"));
  }

  @Test
  void shouldResolveACommentCarryingBothImproveAndGenerateTestsToTheFirstEntry() {
    // Quoted context is stripped before any pattern runs, so map order never decides a
    // quoted-vs-genuine contest. It only decides a genuine-vs-genuine one: two real commands in
    // one comment resolve to whichever comes first in the map. Pinned so a reorder cannot
    // silently re-route an invocation to the other command's AI spend.
    assertEquals(CommentCommand.IMPROVE, detector.detectCommand("/improve\n/generate-tests"));
    assertEquals(CommentCommand.IMPROVE, detector.detectCommand("/generate-tests\n/improve"));
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
