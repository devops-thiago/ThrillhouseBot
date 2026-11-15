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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A follow-up round that resolves a prior finding and reports the gap its fix left (#1388), built
 * from #1342: the fix of "member calls are attributed to every candidate" replaced the attribution
 * with {@code Call.madeOn}, which still attributes a call by substring.
 */
class GapLeftByFixTest {

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final String FILE =
      "src/main/java/dev/thiagogonzaga/thrillhousebot/review/RelatedReferences.java";

  private static final String PRIOR_QUOTE =
      "    referred.forEach(entry -> entry.members.addAll(members));";

  private static final String GAP_QUOTE =
      "        if (receiver == null ? line.contains(symbol) : namedAfter(receiver, symbol)) {";

  private static final String PATCH =
      """
      @@ -270,1 +270,8 @@
      +    for (var entry : referred) {
      +      calls.stream()
      +          .filter(call -> call.madeOn(entry.symbols))
      +          .forEach(call -> entry.members.add(call.member()));
      +    }
      +      for (var symbol : symbols) {
      +        if (receiver == null ? line.contains(symbol) : namedAfter(receiver, symbol)) {
      +          return true;
      """;

  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

  private final ReviewResponse.Finding prior =
      finding(
          270,
          "Member calls are attributed to every candidate a changed file referred to",
          PRIOR_QUOTE);

  private final ReviewResponse.Finding gap =
      finding(
          272,
          "Member calls are attributed to candidates a changed file only names by substring",
          GAP_QUOTE);

  private final String priorKey = key(prior);

  private final ReviewResponse roundOne = new ReviewResponse(List.of(prior), List.of(), null);

  /** The prior finding's thread, answered by the maintainer. */
  private final List<GitHubReviewClient.PullRequestComment> threads =
      List.of(
          comment(
              11,
              null,
              "**🟡 MEDIUM — "
                  + prior.title()
                  + "**\n\nWhy it matters.\n\n"
                  + SuggestionFormatter.findingMarker(1),
              "thrillhousebot[bot]"),
          comment(12, 11L, "Replaced with Call.madeOn in 0e6f2e78.", "maintainer"));

  @Test
  void theGapIsTheSameDefectByTheFollowUpMatching() {
    assertTrue(
        FollowUpAnalyzer.isSameFinding(gap, prior),
        "the gap must read as a re-raise, or this suite tests nothing");
  }

  @Test
  void aGapLeftByTheFixOfAResolvedFindingIsKeptBesideTheResolution() throws Exception {
    var resolved = status("resolved", priorKey);
    var response = new ReviewResponse(List.of(gap), List.of(resolved), null);

    var kept = analyzer.dropRepliedDuplicates(response, json(roundOne), threads, BOT);
    var guarded =
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            kept,
            List.of(roundOne),
            threads,
            new DiffLineResolver(Map.of(FILE, PATCH)),
            Map.of(),
            BOT);

    assertEquals(List.of(gap), guarded.findings(), "the gap posts as a new finding");
    assertEquals(
        List.of(resolved), guarded.previousFindingsStatus(), "the prior finding stays resolved");
  }

  @Test
  void aRestatementOfAFindingLeftUnresolvedIsStillDropped() throws Exception {
    var response = new ReviewResponse(List.of(gap), List.of(status("unresolved", priorKey)), null);

    assertTrue(
        analyzer
            .dropRepliedDuplicates(response, json(roundOne), threads, BOT)
            .findings()
            .isEmpty());
  }

  @Test
  void aResolutionOfAnotherFindingDoesNotKeepTheRestatement() throws Exception {
    var response = new ReviewResponse(List.of(gap), List.of(status("resolved", "0badc0de")), null);

    assertTrue(
        analyzer
            .dropRepliedDuplicates(response, json(roundOne), threads, BOT)
            .findings()
            .isEmpty());
  }

  @Test
  void aResolutionThatEchoesNoKeyDoesNotKeepTheRestatement() throws Exception {
    var response = new ReviewResponse(List.of(gap), List.of(status("resolved", null)), null);

    assertTrue(
        analyzer
            .dropRepliedDuplicates(response, json(roundOne), threads, BOT)
            .findings()
            .isEmpty());
  }

  @Test
  void aFindingQuotingThePriorCodeIsARestatementEvenWhenReindented() throws Exception {
    var sameCode = finding(gap.line(), gap.title(), "  " + PRIOR_QUOTE.strip() + "\n");
    var response =
        new ReviewResponse(List.of(sameCode), List.of(status("resolved", priorKey)), null);

    assertTrue(
        analyzer
            .dropRepliedDuplicates(response, json(roundOne), threads, BOT)
            .findings()
            .isEmpty());
  }

  @Test
  void aFindingWithNoQuoteCannotShowItIsAboutOtherCode() {
    var resolvedNow = Set.of(priorKey);

    assertFalse(FollowUpAnalyzer.leftByFix(finding(272, gap.title(), null), prior, resolvedNow));
    assertFalse(FollowUpAnalyzer.leftByFix(finding(272, gap.title(), " \n"), prior, resolvedNow));
    assertTrue(FollowUpAnalyzer.leftByFix(gap, prior, resolvedNow));
  }

  @Test
  void aPriorWithNoQuoteIsLeftByAFixWhoseGapQuotesCode() {
    var unquoted = finding(prior.line(), prior.title(), null);

    assertTrue(FollowUpAnalyzer.leftByFix(gap, unquoted, Set.of(key(unquoted))));
  }

  @Test
  void anEchoedKeyIsReadWhateverItsCaseAndPadding() throws Exception {
    var response =
        new ReviewResponse(
            List.of(gap), List.of(status("RESOLVED", " " + priorKey.toUpperCase() + " ")), null);

    assertEquals(
        List.of(gap),
        analyzer.dropRepliedDuplicates(response, json(roundOne), threads, BOT).findings());
  }

  private static ReviewResponse.PreviousFindingStatus status(String status, String key) {
    return new ReviewResponse.PreviousFindingStatus(1, status, "note", key);
  }

  private static String key(ReviewResponse.Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }

  private static List<String> json(ReviewResponse round) throws JsonProcessingException {
    return List.of(new ObjectMapper().writeValueAsString(round));
  }

  private static ReviewResponse.Finding finding(int line, String title, String quote) {
    return new ReviewResponse.Finding(
        "medium",
        "high",
        FILE,
        line,
        title,
        "Calls on a changed file's added lines are attributed to candidate files, so a candidate"
            + " inherits members the changed code never called on it and its excerpt is clipped"
            + " to unrelated declarations.",
        quote,
        null);
  }

  private static GitHubReviewClient.PullRequestComment comment(
      long id, Long inReplyTo, String body, String author) {
    return new GitHubReviewClient.PullRequestComment(
        id, inReplyTo, FILE, body, new GitHubReviewClient.ReviewResponse.User(author), "MEMBER");
  }
}
