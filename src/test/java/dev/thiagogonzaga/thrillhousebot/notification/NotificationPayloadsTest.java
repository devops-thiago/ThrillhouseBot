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
package dev.thiagogonzaga.thrillhousebot.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.review.Confidence;
import dev.thiagogonzaga.thrillhousebot.review.Finding;
import dev.thiagogonzaga.thrillhousebot.review.ReviewResult;
import dev.thiagogonzaga.thrillhousebot.review.ReviewState;
import dev.thiagogonzaga.thrillhousebot.review.RiskLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationPayloadsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
  private static final String SESSION = "https://bot.example/session/abc";
  private static final String SHA = "0123456789abcdef";

  /** Prose the default payload must never carry. */
  private static final String DESCRIPTION = "DESCRIPTION-PROSE";

  private static final String SUGGESTION = "SUGGESTED-CODE";

  private static Finding finding(RiskLevel risk, String file, int line, String title) {
    return new Finding(
        risk, Confidence.HIGH, file, line, title, DESCRIPTION, SUGGESTION, SUGGESTION + "-new");
  }

  private static ReviewResult result(
      List<Finding> findings, int critical, int high, int medium, int low, ReviewState state) {
    return new ReviewResult(
        findings,
        critical,
        high,
        medium,
        low,
        RiskLevel.HIGH,
        state,
        false,
        "SUMMARY-MARKDOWN",
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "resolved", null),
            new ReviewResult.PreviousFindingStatus(2, "unresolved", null),
            new ReviewResult.PreviousFindingStatus(3, "unresolved", null)),
        List.of(),
        2);
  }

  private static ReviewResult withFindings() {
    return result(
        List.of(
            finding(RiskLevel.LOW, "b.java", 9, "Low one"),
            finding(RiskLevel.CRITICAL, "a.java", 3, "Critical <!channel> *bold*")),
        1,
        0,
        0,
        1,
        ReviewState.REQUEST_CHANGES);
  }

  /** Nothing but a CI hold stood between this verdict and APPROVE (#825). */
  private static ReviewResult heldOnCi() {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), List.of(), 0, true);
  }

  private static ReviewOutcome completed(ReviewResult result) {
    return ReviewOutcome.completed("octo", "repo", 42, SHA, "PR title <here>", SESSION, result);
  }

  private static ReviewOutcome failed() {
    return ReviewOutcome.failed(
        "octo", "repo", 42, SHA, "PR title", SESSION, FailureCategory.AI_TIMEOUT);
  }

  private static JsonNode parse(byte[] body) throws Exception {
    return MAPPER.readTree(body);
  }

  @Test
  void jsonCompletedCarriesMetadataOnlyByDefault() throws Exception {
    var body =
        NotificationPayloads.render(
            completed(withFindings()), NotificationFormat.JSON, false, NOW, "0.7.0");
    var json = parse(body);

    assertThat(json.get("schema_version").asInt()).isEqualTo(NotificationPayloads.SCHEMA_VERSION);
    assertThat(json.get("event").asText()).isEqualTo("review.completed");
    assertThat(json.get("timestamp").asText()).isEqualTo("2026-09-29T12:00:00Z");
    assertThat(json.at("/bot/name").asText()).isEqualTo("thrillhousebot");
    assertThat(json.at("/bot/version").asText()).isEqualTo("0.7.0");
    assertThat(json.get("repository").asText()).isEqualTo("octo/repo");
    assertThat(json.at("/pull_request/number").asInt()).isEqualTo(42);
    assertThat(json.at("/pull_request/head_sha").asText()).isEqualTo(SHA);
    assertThat(json.at("/pull_request/url").asText())
        .isEqualTo("https://github.com/octo/repo/pull/42");
    assertThat(json.at("/pull_request/title").isMissingNode()).isTrue();
    assertThat(json.get("session_url").asText()).isEqualTo(SESSION);
    assertThat(json.has("failure")).isFalse();
  }

  @Test
  void jsonCompletedCarriesTheVerdictAndCounts() throws Exception {
    var json =
        parse(
            NotificationPayloads.render(
                completed(withFindings()), NotificationFormat.JSON, false, NOW, "0.7.0"));

    assertThat(json.at("/review/verdict").asText()).isEqualTo("REQUEST_CHANGES");
    assertThat(json.at("/review/check_conclusion").asText()).isEqualTo("failure");
    assertThat(json.at("/review/highest_risk").asText()).isEqualTo("HIGH");
    assertThat(json.at("/review/held_on_ci").asBoolean()).isFalse();
    assertThat(json.at("/review/partial_coverage").asBoolean()).isTrue();
    assertThat(json.at("/review/first_review").asBoolean()).isFalse();
    assertThat(json.at("/review/findings/total").asInt()).isEqualTo(2);
    assertThat(json.at("/review/findings/critical").asInt()).isEqualTo(1);
    assertThat(json.at("/review/findings/high").asInt()).isZero();
    assertThat(json.at("/review/findings/medium").asInt()).isZero();
    assertThat(json.at("/review/findings/low").asInt()).isEqualTo(1);
    assertThat(json.at("/review/previous_findings/resolved").asInt()).isEqualTo(1);
    assertThat(json.at("/review/previous_findings/unresolved").asInt()).isEqualTo(2);
    assertThat(json.at("/review/finding_list").isMissingNode()).isTrue();
  }

  @Test
  void jsonCompletedLeavesAllContentOutByDefault() {
    var body =
        NotificationPayloads.render(
            completed(withFindings()), NotificationFormat.JSON, false, NOW, "0.7.0");

    var text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(text)
        .doesNotContain("Critical")
        .doesNotContain("a.java")
        .doesNotContain("PR title")
        .doesNotContain(DESCRIPTION)
        .doesNotContain(SUGGESTION)
        .doesNotContain("SUMMARY-MARKDOWN");
  }

  @Test
  void jsonWithContentAddsTitleAndFindingHeadlinesButNeverProseOrCode() throws Exception {
    var body =
        NotificationPayloads.render(
            completed(withFindings()), NotificationFormat.JSON, true, NOW, "0.7.0");
    var json = parse(body);

    assertThat(json.at("/pull_request/title").asText()).isEqualTo("PR title <here>");
    var list = json.at("/review/finding_list");
    assertThat(list.size()).isEqualTo(2);
    // Most severe first.
    assertThat(list.get(0).get("severity").asText()).isEqualTo("CRITICAL");
    assertThat(list.get(0).get("file").asText()).isEqualTo("a.java");
    assertThat(list.get(0).get("line").asInt()).isEqualTo(3);
    assertThat(list.get(0).get("title").asText()).isEqualTo("Critical <!channel> *bold*");
    assertThat(list.get(1).get("severity").asText()).isEqualTo("LOW");
    assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain(DESCRIPTION)
        .doesNotContain(SUGGESTION);
  }

  @Test
  void jsonContentWithoutAPrTitleOmitsIt() throws Exception {
    var outcome = ReviewOutcome.completed("o", "r", 1, SHA, null, SESSION, heldOnCi());
    var json = parse(NotificationPayloads.render(outcome, NotificationFormat.JSON, true, NOW, "v"));

    assertThat(json.at("/pull_request/title").isMissingNode()).isTrue();
    assertThat(json.at("/review/held_on_ci").asBoolean()).isTrue();
    assertThat(json.at("/review/highest_risk").isNull()).isTrue();
    assertThat(json.at("/review/finding_list").size()).isZero();
  }

  @Test
  void findingListIsCapped() throws Exception {
    var many = new ArrayList<Finding>();
    for (var i = 0; i < NotificationPayloads.MAX_CONTENT_FINDINGS + 5; i++) {
      many.add(finding(i % 2 == 0 ? RiskLevel.MEDIUM : null, "f.java", i, "t" + i));
    }
    var outcome = completed(result(many, 0, 0, many.size(), 0, ReviewState.COMMENT));
    var json = parse(NotificationPayloads.render(outcome, NotificationFormat.JSON, true, NOW, "v"));

    var list = json.at("/review/finding_list");
    assertThat(list.size()).isEqualTo(NotificationPayloads.MAX_CONTENT_FINDINGS);
    // A finding without a severity sorts last and is sent as null rather than guessed.
    assertThat(list.get(0).get("severity").asText()).isEqualTo("MEDIUM");
    assertThat(list.get(list.size() - 1).get("severity").isNull()).isTrue();
  }

  @Test
  void jsonFailedCarriesTheCategoryAndNoReview() throws Exception {
    var json =
        parse(NotificationPayloads.render(failed(), NotificationFormat.JSON, false, NOW, "v"));

    assertThat(json.get("event").asText()).isEqualTo("review.failed");
    assertThat(json.at("/failure/category").asText()).isEqualTo("ai_timeout");
    assertThat(json.has("review")).isFalse();
  }

  @Test
  void slackCompletedMessageLinksThePrAndTheSession() throws Exception {
    var json =
        parse(
            NotificationPayloads.render(
                completed(withFindings()), NotificationFormat.SLACK, false, NOW, "v"));

    assertThat(json.fieldNames()).toIterable().containsExactly("text");
    assertThat(json.get("text").asText())
        .isEqualTo(
            "ThrillhouseBot review of <https://github.com/octo/repo/pull/42|octo/repo#42>:"
                + " *changes requested* — 2 findings (critical 1, high 0, medium 0, low 1) at"
                + " 0123456\n"
                + "<"
                + SESSION
                + "|Open the review session>");
  }

  @Test
  void slackContentIsEscapedSoItCannotMentionOrLink() {
    var text = NotificationPayloads.slack(completed(withFindings()), true).get("text").asText();

    assertThat(text)
        .contains("\nPR title &lt;here&gt;")
        .contains("\n• [CRITICAL] a.java:3 — Critical &lt;!channel&gt; *bold*")
        .contains("\n• [LOW] b.java:9 — Low one")
        .doesNotContain("<!channel>");
    assertThat(text.indexOf("CRITICAL")).isLessThan(text.indexOf("[LOW]"));
  }

  @Test
  void slackFailedMessageNamesTheCategory() {
    var text = NotificationPayloads.slack(failed(), true).get("text").asText();

    assertThat(text)
        .startsWith(
            "ThrillhouseBot review of <https://github.com/octo/repo/pull/42|octo/repo#42>"
                + " *failed* (ai_timeout) at 0123456")
        .contains("\nPR title\n");
  }

  @Test
  void contentWithoutATitleOrAResultAddsNothing() {
    var outcome = ReviewOutcome.failed("o", "r", 1, SHA, null, SESSION, FailureCategory.INTERNAL);

    assertThat(NotificationPayloads.slack(outcome, true).get("text").asText())
        .isEqualTo(NotificationPayloads.slack(outcome, false).get("text").asText());
    assertThat(NotificationPayloads.discord(outcome, true).get("content").asText())
        .isEqualTo(NotificationPayloads.discord(outcome, false).get("content").asText());
  }

  @Test
  void discordMessageDisablesMentions() throws Exception {
    var json =
        parse(
            NotificationPayloads.render(
                completed(heldOnCi()), NotificationFormat.DISCORD, false, NOW, "v"));

    assertThat(json.get("content").asText())
        .isEqualTo(
            "ThrillhouseBot review of [octo/repo#42](<https://github.com/octo/repo/pull/42>):"
                + " **approval held until CI passes** — no new findings at 0123456\n"
                + "[Open the review session](<"
                + SESSION
                + ">)");
    assertThat(json.at("/allowed_mentions/parse").isArray()).isTrue();
    assertThat(json.at("/allowed_mentions/parse").size()).isZero();
  }

  @Test
  void discordContentIsEscaped() {
    var content = NotificationPayloads.discord(completed(withFindings()), true).get("content");

    assertThat(content.asText())
        .contains("\nPR title \\<here\\>")
        .contains("\\[CRITICAL\\] a.java:3 — Critical \\<!channel\\> \\*bold\\*");
  }

  @Test
  void discordFailedMessageUsesItsBoldMarker() {
    assertThat(NotificationPayloads.discord(failed(), false).get("content").asText())
        .startsWith(
            "ThrillhouseBot review of [octo/repo#42](<https://github.com/octo/repo/pull/42>)"
                + " **failed** (ai_timeout) at 0123456\n");
  }

  @Test
  void discordContentNeverExceedsTheLimit() {
    // Escaping doubles a title made of markdown characters; the whole message is clipped anyway.
    var title = "*".repeat(5000);
    var findings = new ArrayList<Finding>();
    for (var i = 0; i < 5; i++) {
      findings.add(finding(RiskLevel.HIGH, "f", i, "_".repeat(5000)));
    }
    var outcome =
        ReviewOutcome.completed(
            "o",
            "r",
            1,
            SHA,
            title,
            SESSION,
            result(findings, 0, 5, 0, 0, ReviewState.REQUEST_CHANGES));

    var content = NotificationPayloads.discord(outcome, true).get("content").asText();

    assertThat(content).hasSize(NotificationPayloads.DISCORD_MAX_CONTENT).endsWith("…");
  }

  @Test
  void chatLinesAreClipped() {
    var outcome = ReviewOutcome.completed("o", "r", 1, SHA, "x".repeat(500), SESSION, heldOnCi());

    var text = NotificationPayloads.slack(outcome, true).get("text").asText();

    assertThat(text).contains("x".repeat(NotificationPayloads.MAX_CHAT_LINE - 1) + "…\n");
  }

  @Test
  void verdictLabels() {
    assertThat(
            NotificationPayloads.verdictLabel(result(List.of(), 0, 0, 0, 0, ReviewState.APPROVE)))
        .isEqualTo("approved");
    assertThat(
            NotificationPayloads.verdictLabel(result(List.of(), 0, 0, 0, 0, ReviewState.COMMENT)))
        .isEqualTo("commented");
    assertThat(
            NotificationPayloads.verdictLabel(
                result(List.of(), 0, 0, 0, 0, ReviewState.REQUEST_CHANGES)))
        .isEqualTo("changes requested");
    assertThat(NotificationPayloads.verdictLabel(heldOnCi()))
        .isEqualTo("approval held until CI passes");
  }

  @Test
  void singleFindingIsSingular() {
    var outcome =
        completed(
            result(
                List.of(finding(RiskLevel.MEDIUM, "a", 1, "t")), 0, 0, 1, 0, ReviewState.COMMENT));

    assertThat(NotificationPayloads.slack(outcome, false).get("text").asText())
        .contains("*commented* — 1 finding (critical 0, high 0, medium 1, low 0)");
  }

  @Test
  void shortAndMissingShasRenderReadably() {
    var shortSha = ReviewOutcome.completed("o", "r", 1, "abc", null, SESSION, heldOnCi());
    var blankSha = ReviewOutcome.completed("o", "r", 1, " ", null, SESSION, heldOnCi());
    var nullSha = ReviewOutcome.completed("o", "r", 1, null, null, SESSION, heldOnCi());

    assertThat(NotificationPayloads.slack(shortSha, false).get("text").asText())
        .contains(" at abc\n");
    assertThat(NotificationPayloads.slack(blankSha, false).get("text").asText())
        .contains(" at an unknown commit\n");
    assertThat(NotificationPayloads.slack(nullSha, false).get("text").asText())
        .contains(" at an unknown commit\n");
  }

  @Test
  void escapersHandleEveryControlCharacter() {
    assertThat(NotificationPayloads.slackEscape("a & <b> c")).isEqualTo("a &amp; &lt;b&gt; c");
    assertThat(NotificationPayloads.discordEscape("\\*_~`|<>[]()#-x <t:1696100000:R>"))
        .isEqualTo("\\\\\\*\\_\\~\\`\\|\\<\\>\\[\\]\\(\\)\\#\\-x \\<t:1696100000:R\\>");
  }
}
