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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ClosedThisRoundTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final BotIdentity BOT = new BotIdentity(Set.of("thrillhousebot[bot]"));

  private static ReviewResponse.Finding finding(String file, int line, String title) {
    return new ReviewResponse.Finding("high", "high", file, line, title, "d", null, null);
  }

  private static ReviewResult.PreviousFindingStatus status(int id, String status, String note) {
    return new ReviewResult.PreviousFindingStatus(id, status, note);
  }

  private static ReviewResult.PreviousFindingStatus earlier(
      ReviewResponse.Finding finding, String status, boolean namedByTheRound) {
    return new ReviewResult.PreviousFindingStatus(
        0,
        status,
        FollowUpAnalyzer.FIX_LANDED_NOTE,
        new ReviewResult.EarlierRound(finding, null, List.of(finding), namedByTheRound));
  }

  private static ReviewResult round(ReviewResult.PreviousFindingStatus... statuses) {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, null, false, "", List.of(statuses), List.of(), 0, false);
  }

  @Test
  void resolvedComeBeforeSupersededWithTheBotsOwnReasons() {
    var previous = List.of(finding("a.java", 3, "Left diff"), finding("b.java", 7, "Fixed"));
    var result =
        round(
            status(1, "superseded", FollowUpAnalyzer.SUPERSEDED_NOTE),
            status(2, "resolved", FollowUpAnalyzer.FIX_LANDED_NOTE));

    assertTrue(ClosedThisRound.any(result, previous));
    assertEquals(
        "<details>\n<summary>Closed this round: 2</summary>\n\n"
            + "Checked at "
            + SHA
            + ":\n\n"
            + "- ~~Fixed~~ (`b.java:7`) ✓ Resolved: "
            + MarkdownSafe.inline(FollowUpAnalyzer.FIX_LANDED_NOTE)
            + "\n- ~~Left diff~~ (`a.java:3`) 🗂️ Superseded: "
            + MarkdownSafe.inline(FollowUpAnalyzer.SUPERSEDED_NOTE)
            + "\n\n</details>\n\n",
        ClosedThisRound.render(result, previous, SHA, BOT));
  }

  @Test
  void aRoundThatOnlySupersededStillHasTheList() {
    var previous = List.of(finding("a.java", 3, "Left diff"));

    assertTrue(ClosedThisRound.any(round(status(1, "superseded", null)), previous));
  }

  @Test
  void aNoteTheModelWroteIsNotRepeated() {
    var previous = List.of(finding("a.java", 3, "Fixed"), finding("b.java", 4, "Also fixed"));
    var result = round(status(1, "resolved", "the model says so"), status(2, "resolved", null));

    var list = ClosedThisRound.render(result, previous, " ", BOT);

    assertTrue(list.contains("- ~~Fixed~~ (`a.java:3`) ✓ Resolved\n"), list);
    assertTrue(list.contains("- ~~Also fixed~~ (`b.java:4`) ✓ Resolved\n"), list);
    assertFalse(list.contains("Checked at"), list);
    assertFalse(list.contains("the model says so"), list);
  }

  @Test
  void aTitleCannotBreakTheStrikethroughAndABlankOneLeavesTheLocation() {
    var previous = List.of(finding("a.java", 3, "drop ~~this~~"), finding("b.java", 4, " "));
    var result = round(status(1, "resolved", null), status(2, "resolved", null));

    var list = ClosedThisRound.render(result, previous, null, BOT);

    assertTrue(list.contains("- ~~drop \\~\\~this\\~\\~~~ (`a.java:3`)"), list);
    assertTrue(list.contains("- `b.java:4` ✓ Resolved"), list);
  }

  @Test
  void anEarlierRoundsFindingIsNamedOnce() {
    var named = finding("old.java", 9, "Older");
    var result =
        round(
            earlier(named, "resolved", false),
            earlier(finding("x.java", 1, "Counted already"), "resolved", true));

    var list = ClosedThisRound.render(result, null, SHA, BOT);

    assertTrue(list.contains("Closed this round: 1</summary>"), list);
    assertTrue(list.contains("~~Older~~ (`old.java:9`)"), list);
    assertFalse(list.contains("Counted already"), list);
  }

  @Test
  void aRoundThatClosedNothingNamedHasNoList() {
    var previous = List.of(finding("a.java", 3, "Open"));
    var result =
        round(
            status(1, "unresolved", null),
            status(0, "resolved", null),
            status(5, "resolved", null),
            earlier(finding("b.java", 1, "Held"), "unresolved", false));

    assertFalse(ClosedThisRound.any(result, previous));
    assertEquals("", ClosedThisRound.render(result, previous, SHA, BOT));
  }

  @Test
  void aLongListIsCutAtTen() {
    var previous =
        IntStream.rangeClosed(1, 12).mapToObj(i -> finding("f.java", i, "t" + i)).toList();
    var result =
        round(
            IntStream.rangeClosed(1, 12)
                .mapToObj(i -> status(i, "resolved", null))
                .toArray(ReviewResult.PreviousFindingStatus[]::new));

    var list = ClosedThisRound.render(result, previous, SHA, BOT);

    assertTrue(list.contains("Closed this round: 12</summary>"), list);
    assertTrue(list.contains("~~t10~~"), list);
    assertFalse(list.contains("~~t11~~"), list);
    assertTrue(list.contains("- …and 2 more\n"), list);
  }

  @Test
  void theListNamesWhatTheReviewBodyNamesPastTheCap() {
    var previous =
        IntStream.rangeClosed(1, 22).mapToObj(i -> finding("f.java", i, "t" + i)).toList();
    var result =
        round(
            IntStream.rangeClosed(1, 22)
                .mapToObj(i -> status(i, i <= 11 ? "resolved" : "superseded", null))
                .toArray(ReviewResult.PreviousFindingStatus[]::new));

    var list = ClosedThisRound.render(result, previous, SHA, BOT);
    var body = ClosedFindingNames.reviewBodySection(result, previous);

    assertTrue(list.contains("Closed this round: 22</summary>"), list);
    assertTrue(body.contains("closed 22 previous finding(s)"), body);
    for (var line = 1; line <= 22; line++) {
      var locator = "`f.java:" + line + "`";
      assertEquals(body.contains(locator), list.contains(locator), locator);
    }
  }

  @Test
  void aTitleEndingInABackslashStaysStruckThrough() {
    var previous = List.of(finding("a.java", 3, "Regex ends with \\"));

    var list = ClosedThisRound.render(round(status(1, "resolved", null)), previous, null, BOT);

    assertTrue(list.contains("~~Regex ends with \\\\~~ (`a.java:3`)"), list);
  }

  @Test
  void theSummaryMarksThePlaceOnlyWhenTheRoundClosedSomething() {
    var previous = List.of(finding("a.java", 3, "Fixed"));
    var generator = new PrSummaryGenerator(false);
    var sources = PrSummaryGenerator.DescriptionSources.NONE.withRound(previous, "");

    var closed =
        generator.generate(1, 1, 0, List.of(), null, round(status(1, "resolved", null)), sources);
    var open =
        generator.generate(1, 1, 0, List.of(), null, round(status(1, "unresolved", null)), sources);

    assertTrue(closed.contains(ClosedThisRound.ANCHOR), closed);
    assertFalse(open.contains(ClosedThisRound.ANCHOR), open);
  }

  @ParameterizedTest
  @ValueSource(strings = {"on", "off", "unset"})
  void thePublisherPutsTheListInItsPlaceOrTakesThePlaceOut(String setting) {
    var enabled = "on".equals(setting);
    var commentClient = mock(GitHubCommentClient.class);
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    if (!"unset".equals(setting)) {
      var closedList = mock(ThrillhouseConfig.SummaryClosedListConfig.class);
      when(closedList.enabled()).thenReturn(enabled);
      when(reviewConfig.summaryClosedList()).thenReturn(closedList);
    }
    var publisher =
        new ReviewPublisher(
            mock(GitHubReviewClient.class),
            commentClient,
            mock(ReviewThreadService.class),
            mock(SuggestionFormatter.class),
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BOT,
            new PendingReviewGuard());
    var result =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            null,
            false,
            "before\n\n" + ClosedThisRound.ANCHOR + "after",
            List.of(status(1, "resolved", null)),
            List.of(),
            0,
            false);

    publisher.publishSummary(
        new ReviewPublisher.SummaryRequest(
            "auth",
            "o",
            "r",
            1,
            result,
            true,
            null,
            null,
            SHA,
            List.of(finding("a.java", 3, "Fixed"))));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), any(), eq("o"), eq("r"), eq(1), body.capture());
    var posted = body.getValue().body();
    assertFalse(posted.contains(ClosedThisRound.ANCHOR.strip()), posted);
    assertEquals(enabled, posted.contains("~~Fixed~~ (`a.java:3`)"), posted);
    assertEquals(enabled, posted.contains("Checked at " + SHA), posted);
    assertTrue(posted.contains("before\n\n"), posted);
    assertTrue(posted.endsWith("after"), posted);
  }
}
