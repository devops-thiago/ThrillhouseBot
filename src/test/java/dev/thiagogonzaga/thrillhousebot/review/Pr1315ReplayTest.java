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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Replays the four review rounds of devops-thiago/ThrillhouseBot#1315 (#1319): every round runs the
 * status screen, the open-thread guard and the verdict over the rounds before it, and records the
 * earlier rounds' closes with its response, as the pipeline and the orchestrator do.
 *
 * <p>The data, in {@code src/test/resources/followup/pr1315}:
 *
 * <ul>
 *   <li>{@code round3-response.json}, {@code round4-statuses.json} and {@code round4-patches.json}:
 *       stored by the bot (session 9636) and served by GitHub, as #1322 recorded them.
 *   <li>{@code round1-findings.json} and {@code round2-findings.json}: <em>reconstructed</em> from
 *       the bot's own posts on the pull request, since the stored sessions are not available: each
 *       inline finding from its review comment, its line the one whose key the comment's marker
 *       carries, and round 1's four lower-confidence items from the round's review body. Their
 *       {@code suggestion_old} is unknown and left empty, as round 1 #3's really was (#1316).
 *   <li>{@code round2-patches.json} and {@code round3-patches.json}: the pull request's patches at
 *       those heads, as GitHub's comparison with the base {@code cc71e8ba} serves them, for the
 *       files the findings sit in.
 *   <li>{@link #CHANGED}: the files each comparison of two round heads lists, as GitHub served
 *       them.
 * </ul>
 *
 * <p>The model's statuses are the real ones where they exist: round 2 resolved round 1 #1 and held
 * the rest (its delta comment), round 3 held round 2's three (stored), round 4 is stored. Round 2's
 * and round 3's are given the keys #1322 makes the model echo. Round 3's statuses on round 1's
 * findings are <em>synthetic</em>: the prompt did not list them then (#1316). They report round 1
 * #3 resolved, as its fix at {@code a6310507} made it, and the others unresolved.
 */
class Pr1315ReplayTest {

  private static final Path PR1315 = Path.of("src/test/resources/followup/pr1315");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));
  private static final GitHubReviewClient.ReviewResponse.User BOT_USER =
      new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");

  private static final String RESOLVER =
      "src/main/java/dev/thiagogonzaga/thrillhousebot/github/RepoSettingsResolver.java";
  private static final String RESOLVER_TEST =
      "src/test/java/dev/thiagogonzaga/thrillhousebot/github/RepoSettingsResolverTest.java";

  private static final String HEAD_1 = "48dd5bb5";
  private static final String HEAD_2 = "0d787cc5";
  private static final String HEAD_3 = "a6310507";
  private static final String HEAD_4 = "d8f86021";

  /** The files GitHub's comparison of each two round heads lists (fetched 2026-11-14). */
  private static final Map<String, Set<String>> CHANGED =
      Map.of(
          HEAD_1 + "..." + HEAD_2, Set.of(RESOLVER),
          HEAD_1 + "..." + HEAD_3, Set.of(RESOLVER, RESOLVER_TEST),
          HEAD_1 + "..." + HEAD_4, Set.of(RESOLVER, RESOLVER_TEST),
          HEAD_2 + "..." + HEAD_3, Set.of(RESOLVER_TEST),
          HEAD_2 + "..." + HEAD_4, Set.of(RESOLVER, RESOLVER_TEST),
          HEAD_3 + "..." + HEAD_4, Set.of(RESOLVER));

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  private static final DiffBudgetPlanner.BudgetPlan PLAN =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(MAPPER),
          BOT,
          BlockingStrictness.BALANCED);

  /**
   * One replayed round: the verdict, the response as stored, and the counts each surface states.
   */
  private record Round(
      ReviewResult result,
      ReviewResponse stored,
      List<ReviewResponse.Finding> previous,
      String summary,
      String delta,
      String checkRun) {}

  private static ReviewResponse read(String name) throws IOException {
    return MAPPER.readValue(Files.readString(PR1315.resolve(name)), ReviewResponse.class);
  }

  private static Map<String, String> patches(String name) throws IOException {
    return MAPPER.readValue(Files.readString(PR1315.resolve(name)), new TypeReference<>() {});
  }

  private static String keyOf(ReviewResponse.Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }

  /** What changed since each prior round (newest first, {@code heads}) at the head {@code head}. */
  private static DiffLineResolver.RoundChanges changes(List<String> heads, String head) {
    return (round, path) ->
        CHANGED.get(heads.get(round) + "..." + head).contains(path)
            ? FileChange.CHANGED
            : FileChange.UNCHANGED;
  }

  /** The bot's root comment for an inline finding, as it posts one, with its keyed marker. */
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
      List<String> heads,
      String head,
      Map<String, String> patches) {
    var jsons = priorRounds.stream().map(round -> MAPPER.valueToTree(round).toString()).toList();
    var files = new ArrayList<FileDiff>();
    patches.forEach((path, patch) -> files.add(new FileDiff(path, "modified", 1, 1, 2, patch)));
    var roundChanges = changes(heads, head);
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
                patches, patches.keySet(), DiffLineResolver.HeadFiles.NONE, roundChanges),
        null,
        List.of(),
        List.of());
  }

  /**
   * The model's answer for a round: a status for every finding the prompt lists, echoing its key,
   * {@code resolved} for those {@code resolves} names, and the given status of the effective
   * round's findings where {@code effective} has one.
   */
  private static List<ReviewResponse.PreviousFindingStatus> answer(
      PriorListing listing,
      Map<ReviewResponse.Finding, String> effective,
      Predicate<ReviewResponse.Finding> resolves) {
    var statuses = new ArrayList<ReviewResponse.PreviousFindingStatus>();
    for (var i = 0; i < listing.findings().size(); i++) {
      var finding = listing.findings().get(i);
      var status =
          resolves.test(finding) ? "resolved" : effective.getOrDefault(finding, "unresolved");
      statuses.add(new ReviewResponse.PreviousFindingStatus(i + 1, status, "note", keyOf(finding)));
    }
    return statuses;
  }

  /**
   * Runs one round over {@code priorRounds} (newest first): the screen, the guard, the verdict,
   * then the response stored with the earlier rounds' closes, and each surface's text.
   */
  private Round replay(
      List<ReviewResponse> priorRounds,
      List<String> heads,
      String head,
      Map<String, String> patches,
      List<ReviewResponse.Finding> raised,
      Map<ReviewResponse.Finding, String> effectiveStatuses,
      Predicate<ReviewResponse.Finding> resolves) {
    var ctx = context(priorRounds, heads, head, patches);
    var lineResolver = ctx.lineResolver();
    var listing = FollowUpAnalyzer.priorListing(ctx, () -> lineResolver, BOT);
    // The pipeline scopes statuses over the same list the prompt numbered.
    assertEquals(
        listing.findings(), FollowUpAnalyzer.listedPreviousFindings(ctx, lineResolver, BOT));
    var raw = new ReviewResponse(raised, answer(listing, effectiveStatuses, resolves), null);
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
    var stored = guarded.withClosedEarlierRound(result.earlierRoundClosedMembers());
    var previous = ctx.previousFindingsList();
    return new Round(
        result,
        stored,
        previous,
        new PrSummaryGenerator(false).generate(1, 1, 0, List.of(), null, result),
        FollowUpDeltaSummary.render(result, previous).orElse(""),
        VerdictBuilder.checkSummaryForResult(result));
  }

  private static int count(String text, String label) {
    var at = text.indexOf(label);
    assertTrue(at >= 0, "no '" + label + "' in:\n" + text);
    var from = at + label.length();
    var end = from;
    while (end < text.length() && Character.isDigit(text.charAt(end))) {
      end++;
    }
    return Integer.parseInt(text.substring(from, end));
  }

  /** The total the summary's risk table counts. */
  private static int summaryTotal(String summary) {
    return count(summary, "| 🔴 Critical | ")
        + count(summary, "| 🟠 High | ")
        + count(summary, "| 🟡 Medium | ")
        + count(summary, "| 🔵 Low | ");
  }

  /** The summary, the delta comment and the check run state the same totals (#1199). */
  private static void assertSurfacesAgree(Round round) {
    var newCount = count(round.delta(), "- **New findings this round:** ");
    var stillOpen = count(round.delta(), "- **Previous findings still open:** ");
    assertEquals(newCount + stillOpen, summaryTotal(round.summary()), round.summary());
    assertEquals(stillOpen, count(round.summary(), "| ⚠️ Still present | "), round.summary());
    assertEquals(
        count(round.delta(), "- **Previous findings resolved:** "),
        count(round.summary(), "| ✅ Resolved | "),
        round.summary());
    assertTrue(
        round
            .checkRun()
            .startsWith((newCount + stillOpen) + " open findings: " + newCount + " new"),
        round.checkRun());
  }

  private static boolean titled(ReviewResponse.Finding finding, String title) {
    return finding.title().startsWith(title);
  }

  private static ReviewResponse.Finding titled(
      List<ReviewResponse.Finding> findings, String title) {
    return findings.stream().filter(f -> titled(f, title)).findFirst().orElseThrow();
  }

  @Test
  void theFourRoundsResolveWhatWasFixedSupersedeNothingAndCountEachFindingOnce()
      throws IOException {
    var round1 = read("round1-findings.json");
    var round1Fix404 = round1.findings().get(0);
    var round1StubsJaxRs = round1.findings().get(2);
    var round1LookupInLoop = round1.findings().get(6);
    var round1CacheAsserts = round1.findings().get(4);

    // Round 2 (0d787cc5): resolves round 1 #1, holds the other nine, raises three.
    var r2 =
        replay(
            List.of(round1),
            List.of(HEAD_1),
            HEAD_2,
            patches("round2-patches.json"),
            read("round2-findings.json").findings(),
            Map.of(),
            finding -> finding == round1Fix404);
    assertEquals("resolved", r2.result().previousStatuses().get(0).status());
    assertCounts(r2, 1, 3, 9);

    // Round 3 (a6310507): round 1 #3 was fixed (stubMissing now throws GHFileNotFoundException).
    var round3 = read("round3-response.json");
    var r3 =
        replay(
            List.of(r2.stored(), round1),
            List.of(HEAD_2, HEAD_1),
            HEAD_3,
            patches("round3-patches.json"),
            round3.findings(),
            Map.of(),
            finding -> finding == round1StubsJaxRs);
    // #1316: the round-1 finding fixed between round 2 and round 3 is resolved in round 3...
    assertEquals(List.of(round1StubsJaxRs), r3.result().earlierRoundCloses("resolved"));
    assertEquals(List.of(round1StubsJaxRs), r3.stored().closedEarlierRound());
    // ...and leaves the still-open count: round 2's nine and round 2's own three, less it.
    // #1317: round 3 #1 restates round 1 #7 (the same four resolvers), so it is not posted again,
    // and round 1 #5's double-check item is carried by round 3's inline copy, counted once.
    assertFalse(
        r3.stored().findings().stream()
            .anyMatch(f -> titled(f, "A fresh installation-client repository lookup")),
        "round 3 #1 is round 1 #7 again");
    assertTrue(
        r3.result().openPreviousFindings().stream()
            .anyMatch(f -> f.title().equals(round1LookupInLoop.title())));
    assertFalse(
        r3.result().openPreviousFindings().stream()
            .anyMatch(f -> f.title().equals(round1CacheAsserts.title())),
        "round 1 #5 is counted as the round-3 finding that replaced it");
    assertCounts(r3, 1, 4, 11);

    // Round 4 (d8f86021): only RepoSettingsResolver.java changed. The stored answer, keyed.
    var stored3 = r3.stored().findings();
    var deadBranch = titled(stored3, "Dead 404 classification");
    var raised4 =
        List.of(
            new ReviewResponse.Finding(
                "medium",
                "low",
                RESOLVER,
                192,
                "404 catch is keyed on java.io.FileNotFoundException, not the hub4j 404 type",
                "fetchAndParse classifies a missing config file with catch"
                    + " (java.io.FileNotFoundException e).",
                "",
                ""));
    var r4 =
        replay(
            List.of(r3.stored(), r2.stored(), round1),
            List.of(HEAD_3, HEAD_2, HEAD_1),
            HEAD_4,
            patches("round4-patches.json"),
            raised4,
            Map.of(),
            finding -> finding == deadBranch);
    var statuses4 = r4.result().previousStatuses();
    // Round 3 #6 is resolved; nothing is superseded, round 3 #4 included (#1318).
    assertTrue(
        statuses4.stream()
            .anyMatch(
                s ->
                    "resolved".equals(s.status())
                        && s.id() == stored3.indexOf(deadBranch) + 1
                        && !s.fromEarlierRound()));
    assertEquals(0, r4.result().supersededPreviousCount());
    assertTrue(
        r4.result().openPreviousFindings().stream()
            .anyMatch(f -> f.title().startsWith("Caching tests had their API-call verifies")));
    assertCounts(r4, 1, 1, 14);
  }

  /** The round's delta counts, and every surface agreeing with them. */
  private static void assertCounts(Round round, int resolved, int raised, int stillOpen) {
    assertEquals(resolved, count(round.delta(), "- **Previous findings resolved:** "));
    assertEquals(raised, count(round.delta(), "- **New findings this round:** "));
    assertEquals(stillOpen, count(round.delta(), "- **Previous findings still open:** "));
    assertSurfacesAgree(round);
  }
}
