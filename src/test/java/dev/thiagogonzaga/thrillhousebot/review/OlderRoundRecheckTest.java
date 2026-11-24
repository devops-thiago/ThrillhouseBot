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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Older rounds' open findings are put back to the model each round (#1316), and its answer can
 * close them. Rebuilt from a large private PR's rounds on v0.9.2: finding {@code 6645243e}, a
 * docs-versus-code mismatch anchored on a code line that never changed while the fix landed in the
 * document, and finding {@code 16d6e397}, a round-1 "Things to double-check" item no later round
 * asked about. Each round runs the status screen, the open-thread guard and the verdict over the
 * rounds before it and records the earlier rounds' closes with its response, as the pipeline and
 * the orchestrator do.
 */
class OlderRoundRecheckTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));
  private static final GitHubReviewClient.ReviewResponse.User BOT_USER =
      new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");

  private static final String CODE = "src/main/java/app/credits/CreditRepository.java";
  private static final String DOC = "docs/DATABASE.md";
  private static final String OTHER = "src/main/java/app/credits/CreditService.java";

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  private static final DiffBudgetPlanner.BudgetPlan PLAN =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  private static final Map<String, String> PATCHES =
      Map.of(
          CODE,
          """
          @@ -40,0 +40,2 @@
          +    "INSERT INTO credit_transactions (user_id, credits, type, description)"
          +        + " VALUES (:u, :c, 'reversal', :d)";\
          """,
          DOC,
          """
          @@ -12,0 +12,1 @@
          +| type | text | purchase, usage, refund, bonus, reversal |\
          """,
          OTHER,
          """
          @@ -140,0 +140,4 @@
          +    var balance = repository.balance(userId);
          +    repository.reverse(userId, credits);
          +    audit.log(userId, credits);
          +    return balance - credits;\
          """);

  /** The finding of {@code 6645243e}: on the code, fixed in the document. */
  private static final ReviewResponse.Finding DOCS_MISMATCH =
      new ReviewResponse.Finding(
          "medium",
          "high",
          CODE,
          41,
          "DATABASE.md lists 4 credit_transactions.type values but code writes 'reversal'",
          "The schema reference documents purchase, usage, refund and bonus only.",
          "        + \" VALUES (:u, :c, 'reversal', :d)\";",
          null);

  /** The item of {@code 16d6e397}: low confidence, so listed under "Things to double-check". */
  private static final ReviewResponse.Finding DOUBLE_CHECK =
      new ReviewResponse.Finding(
          "medium",
          "low",
          OTHER,
          141,
          "Reversal may run twice when the audit call fails",
          "A retry after a failed audit write reverses the credits again.",
          "",
          null);

  private static ReviewResponse.Finding raised(int line, String title) {
    return new ReviewResponse.Finding(
        "high", "high", OTHER, line, title, title + ".", "audit.log(userId, credits);", null);
  }

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(MAPPER),
          BOT,
          BlockingStrictness.BALANCED);

  private record Round(ReviewResult result, ReviewResponse stored, PriorListing listing) {}

  private static String keyOf(ReviewResponse.Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }

  private static List<GitHubReviewClient.PullRequestComment> threads(
      List<ReviewResponse> roundsNewestFirst) {
    var comments = new ArrayList<GitHubReviewClient.PullRequestComment>();
    for (var round : roundsNewestFirst) {
      for (var i = 0; i < round.findings().size(); i++) {
        var finding = round.findings().get(i);
        if (Finding.fromAiResponse(finding).postsInline()) {
          comments.add(
              new GitHubReviewClient.PullRequestComment(
                  1000L + comments.size(),
                  null,
                  finding.file(),
                  "**"
                      + finding.title()
                      + "**\n\n"
                      + SuggestionFormatter.findingMarker(
                          i + 1, finding.file(), finding.line(), finding.title()),
                  BOT_USER,
                  null));
        }
      }
    }
    return comments;
  }

  private static ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds,
      DiffLineResolver.RoundChanges changes,
      List<GitHubCommentClient.IssueComment> conversation) {
    var jsons = priorRounds.stream().map(round -> MAPPER.valueToTree(round).toString()).toList();
    var files = new ArrayList<FileDiff>();
    PATCHES.forEach((path, patch) -> files.add(new FileDiff(path, "modified", 1, 0, 1, patch)));
    return new ReviewContextLoader.ReviewContext(
        files,
        "",
        "",
        0,
        List.of(),
        jsons,
        priorRounds,
        false,
        true,
        jsons.get(0),
        threads(priorRounds),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        files,
        () ->
            new DiffLineResolver(
                PATCHES, PATCHES.keySet(), DiffLineResolver.HeadFiles.NONE, changes),
        null,
        conversation,
        List.of());
  }

  private Round replay(
      List<ReviewResponse> priorRounds,
      DiffLineResolver.RoundChanges changes,
      List<GitHubCommentClient.IssueComment> conversation,
      List<ReviewResponse.Finding> raised,
      Predicate<ReviewResponse.Finding> resolves) {
    var ctx = context(priorRounds, changes, conversation);
    var lineResolver = ctx.lineResolver();
    var listing = FollowUpAnalyzer.priorListing(ctx, () -> lineResolver, BOT);
    var statuses = new ArrayList<ReviewResponse.PreviousFindingStatus>();
    for (var i = 0; i < listing.findings().size(); i++) {
      var finding = listing.findings().get(i);
      statuses.add(
          new ReviewResponse.PreviousFindingStatus(
              i + 1, resolves.test(finding) ? "resolved" : "unresolved", "note", keyOf(finding)));
    }
    var raw = new ReviewResponse(raised, statuses, null);
    var screened = FollowUpAnalyzer.screenModelStatuses(raw, ctx, lineResolver, BOT);
    var guarded =
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            screened,
            priorRounds,
            ctx.inlineComments(),
            lineResolver,
            VerdictBuilder.renameTargets(ctx.files()),
            BOT);
    var result = builder.build(ctx, guarded, CI_CLEAR, PLAN);
    return new Round(
        result, guarded.withClosedEarlierRound(result.earlierRoundClosedMembers()), listing);
  }

  /** Only {@code changed} files changed since any earlier round. */
  private static DiffLineResolver.RoundChanges changedOnly(Set<String> changed) {
    return (round, path) -> changed.contains(path) ? FileChange.CHANGED : FileChange.UNCHANGED;
  }

  /**
   * {@code 6645243e}: the code line it quotes never changes, the fix lands in {@code DATABASE.md}.
   * Two rounds later it is asked about again, and the model's {@code resolved} closes it.
   */
  @Test
  void aDocsFindingFixedInTheDocumentIsAskedAgainAndClosed() {
    var round1 = new ReviewResponse(List.of(DOCS_MISMATCH), List.of(), null);
    var nothingChanged = changedOnly(Set.of());
    var r2 =
        replay(
            List.of(round1),
            nothingChanged,
            List.of(),
            List.of(raised(142, "Audit failure is swallowed")),
            finding -> false);

    var fixedInDoc = changedOnly(Set.of(DOC, OTHER));
    var r3 =
        replay(
            List.of(r2.stored(), round1),
            fixedInDoc,
            List.of(),
            List.of(raised(143, "Balance read before the reversal")),
            finding -> finding.equals(DOCS_MISMATCH));

    assertTrue(r3.listing().findings().contains(DOCS_MISMATCH), "round 3 asks about it again");
    assertEquals(List.of(DOCS_MISMATCH), r3.result().earlierRoundCloses("resolved"));
    assertEquals(List.of(DOCS_MISMATCH), r3.stored().closedEarlierRound());
    assertTrue(
        r3.result().openPreviousFindings().stream()
            .noneMatch(f -> f.title().equals(DOCS_MISMATCH.title())));
  }

  /** With its file and the document unchanged, nothing could bear out a resolved: it is held. */
  @Test
  void aDocsFindingWithNothingItNamesChangedIsHeldWithoutAsking() {
    var round1 = new ReviewResponse(List.of(DOCS_MISMATCH), List.of(), null);
    var r2 =
        replay(
            List.of(round1),
            changedOnly(Set.of()),
            List.of(),
            List.of(raised(142, "Audit failure is swallowed")),
            finding -> false);

    var r3 =
        replay(
            List.of(r2.stored(), round1),
            changedOnly(Set.of(OTHER)),
            List.of(),
            List.of(raised(143, "Balance read before the reversal")),
            finding -> finding.equals(DOCS_MISMATCH));

    assertFalse(r3.listing().findings().contains(DOCS_MISMATCH));
    assertTrue(
        r3.result().openPreviousFindings().stream()
            .anyMatch(f -> f.title().equals(DOCS_MISMATCH.title())));
  }

  /**
   * {@code 16d6e397}: a round-1 double-check item, carried through rounds that each raise something
   * new while what changed since round 1 is not known (more round heads than the review compares).
   * It is asked about every round, stays open while the model says so, and the maintainer's
   * directive naming it closes it once.
   */
  @Test
  void aRoundOneDoubleCheckItemGetsAStatusEveryRoundAndADirectiveClosesIt() {
    var round1 = new ReviewResponse(List.of(DOUBLE_CHECK), List.of(), null);
    var unknown = DiffLineResolver.RoundChanges.NONE;
    var priors = new ArrayList<ReviewResponse>(List.of(round1));
    for (var line = 142; line <= 144; line++) {
      var round =
          replay(
              List.copyOf(priors),
              unknown,
              List.of(),
              List.of(raised(line, "New defect raised on line " + line)),
              finding -> false);
      assertTrue(round.listing().findings().contains(DOUBLE_CHECK), "round " + (priors.size() + 1));
      assertTrue(
          round.result().openPreviousFindings().stream()
              .anyMatch(f -> f.title().equals(DOUBLE_CHECK.title())));
      priors.addFirst(round.stored());
    }

    var directive =
        new GitHubCommentClient.IssueComment(
            900L,
            "@thrillhousebot resolved `" + OTHER + ":141` — " + DOUBLE_CHECK.title(),
            new GitHubReviewClient.ReviewResponse.User("maintainer"),
            "MEMBER");
    var cleared =
        replay(
            List.copyOf(priors),
            unknown,
            List.of(directive),
            List.of(raised(145, "Another new defect")),
            finding -> false);
    assertEquals(List.of(DOUBLE_CHECK), cleared.result().earlierRoundCloses("resolved"));
    assertTrue(
        cleared.result().openPreviousFindings().stream()
            .noneMatch(f -> f.title().equals(DOUBLE_CHECK.title())));
    priors.addFirst(cleared.stored());

    var after =
        replay(
            List.copyOf(priors),
            unknown,
            List.of(directive),
            List.of(raised(146, "One more new defect")),
            finding -> false);
    assertFalse(after.listing().findings().contains(DOUBLE_CHECK));
    assertEquals(0, after.result().resolvedPreviousCount());
  }
}
