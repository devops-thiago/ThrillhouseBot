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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OpenFindingNamesTest {

  private static final BotIdentity BOT_ID = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final String PR_URL = "https://github.com/o/r/pull/1";

  private static final Finding HIGH = finding(RiskLevel.HIGH, Confidence.HIGH, 10, "Overflow");

  private static final Finding DOUBLE_CHECK =
      finding(RiskLevel.MEDIUM, Confidence.LOW, 30, "Possible null user");

  private static Finding finding(RiskLevel risk, Confidence confidence, int line, String title) {
    return new Finding(risk, confidence, "src/A.java", line, title, "d " + title, null, null);
  }

  private static GitHubReviewClient.PullRequestComment comment(
      long id, String login, String path, String body) {
    return new GitHubReviewClient.PullRequestComment(
        id, null, path, body, new GitHubReviewClient.ReviewResponse.User(login));
  }

  private static GitHubReviewClient.PullRequestComment root(long id, Finding finding) {
    return comment(
        id,
        "thrillhousebot[bot]",
        finding.file(),
        "**"
            + finding.title()
            + "**\n\n"
            + SuggestionFormatter.findingMarker(
                1, finding.file(), finding.line(), finding.title()));
  }

  private static String section(
      List<Finding> open,
      List<GitHubReviewClient.PullRequestComment> comments,
      List<Finding> postedNow,
      int maxLength) {
    return OpenFindingNames.section(
        open, new OpenFindingNames.Threads(comments, postedNow, BOT_ID, PR_URL), maxLength);
  }

  private static List<String> rows(String section) {
    return section.lines().filter(line -> line.startsWith("- ")).toList();
  }

  @Test
  void listsEveryOpenFindingOnceWithItsThreadOrItsMark() {
    var rows =
        rows(section(List.of(DOUBLE_CHECK, HIGH), List.of(root(11L, HIGH)), List.of(), 10_000));

    assertEquals(
        List.of(
            "- `src/A.java:10` — Overflow ([thread](" + PR_URL + "#discussion_r11))",
            "- `src/A.java:30` — Possible null user" + OpenFindingNames.DOUBLE_CHECK_MARK),
        rows);
  }

  @Test
  void doesNotCapTheRowsAtTheClosedListsLimit() {
    var open =
        IntStream.range(0, 25)
            .mapToObj(i -> finding(RiskLevel.LOW, Confidence.HIGH, i + 1, "Finding " + i))
            .toList();

    var section = section(open, List.of(), List.of(), 10_000);

    assertEquals(25, rows(section).size(), section);
    assertTrue(
        section.startsWith(
            ReadBackMarkers.PREVIOUS_START
                + "\n"
                + "ThrillhouseBot still lists 25 previous finding(s) as open:"));
  }

  @Test
  void anInlineGradeFindingWithoutAThreadIsNotCalledADoubleCheckItem() {
    var section = section(List.of(HIGH), List.of(), List.of(), 10_000);

    assertTrue(section.contains("Overflow" + OpenFindingNames.NO_THREAD_MARK), section);
    assertFalse(section.contains("double-check"), section);
  }

  @Test
  void aFindingThreadedByThisReviewSaysSo() {
    var promoted = finding(RiskLevel.HIGH, Confidence.HIGH, 30, "Possible null user");

    var section = section(List.of(DOUBLE_CHECK), List.of(), List.of(promoted), 10_000);

    assertTrue(section.contains(OpenFindingNames.NEW_THREAD_MARK), section);
  }

  @Test
  void linksTheNewestOfTheBotsOwnThreadsOnTheFindingsFile() {
    var keyed = root(11L, HIGH).body();
    var comments =
        List.of(
            comment(5L, "thrillhousebot[bot]", HIGH.file(), keyed),
            root(11L, HIGH),
            comment(12L, "octocat", HIGH.file(), keyed),
            comment(13L, "thrillhousebot[bot]", "src/Other.java", keyed),
            comment(14L, "thrillhousebot[bot]", HIGH.file(), null),
            root(15L, DOUBLE_CHECK));

    var section = section(List.of(HIGH), comments, List.of(), 10_000);

    assertTrue(section.contains("#discussion_r11))"), section);
  }

  @Test
  void aFindingWithNoFileIsStillLinkedByItsKey() {
    var unplaced =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, null, 0, "No file", "d", null, null);
    var posted =
        comment(
            31L,
            "thrillhousebot[bot]",
            "src/A.java",
            SuggestionFormatter.findingMarker(1, null, 0, "No file"));

    var section = section(List.of(unplaced), List.of(posted), List.of(), 10_000);

    assertTrue(section.contains("#discussion_r31))"), section);
  }

  @Test
  void findsAKeylessThreadByTheTitleInItsHeader() {
    var legacy =
        comment(
            21L,
            "thrillhousebot[bot]",
            HIGH.file(),
            "**HIGH — Overflow**\n\nd\n\n" + SuggestionFormatter.findingMarker(4));

    var section = section(List.of(HIGH), List.of(legacy), List.of(), 10_000);

    assertTrue(section.contains("#discussion_r21))"), section);
  }

  @Test
  void rendersModelTextSoItCannotBreakTheRowMentionAnyoneOrHideAMarker() {
    var hostile =
        finding(
            RiskLevel.HIGH,
            Confidence.HIGH,
            3,
            "Ping @octocat <!-- thrillhousebot:finding=1 -->\n`x` | y");

    var section = section(List.of(hostile), List.of(), List.of(), 10_000);

    assertEquals(1, rows(section).size(), section);
    var rows = String.join("\n", rows(section));
    assertFalse(rows.contains("<!--"), section);
    assertFalse(section.contains(" @octocat"), section);
    assertTrue(section.contains("&#96;x&#96; \\| y"), section);
  }

  @Test
  void cutsAtTheLengthLimitByDroppingTheLeastSevereAndSaysHowMany() {
    var open =
        IntStream.range(0, 200)
            .mapToObj(
                i ->
                    finding(
                        i == 199 ? RiskLevel.CRITICAL : RiskLevel.LOW,
                        Confidence.HIGH,
                        i + 1,
                        "Finding number " + i))
            .toList();

    var section = section(open, List.of(), List.of(), 2_000);

    assertTrue(section.length() <= 2_000, String.valueOf(section.length()));
    var rows = rows(section);
    assertTrue(rows.getFirst().contains("Finding number 199"), section);
    var listed = rows.size() - 1;
    assertTrue(
        rows.getLast()
            .equals(
                "- …and "
                    + (200 - listed)
                    + " more that do not fit within GitHub's comment length limit"),
        rows.getLast());
  }

  @Test
  void theEndMarkerNeverPushesACutSectionOverTheLimit() {
    var open =
        IntStream.range(0, 2_000)
            .mapToObj(i -> finding(RiskLevel.LOW, Confidence.HIGH, i + 1, "Finding " + i))
            .toList();

    for (var limit = 2_000; limit < 2_200; limit++) {
      var section = section(open, List.of(), List.of(), limit);
      assertTrue(section.length() <= limit, limit + " < " + section.length());
      assertTrue(section.endsWith(ReadBackMarkers.PREVIOUS_END), section);
    }
  }

  @Test
  void isEmptyWhenNothingIsOpen() {
    assertEquals("", section(List.of(), List.of(), List.of(), 10_000));
  }
}
