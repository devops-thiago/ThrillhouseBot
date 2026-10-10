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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #932: a model finding that quoted a credential is stored with the literal redacted — its anchor
 * included — by the security scan. Across rounds it must behave like any other finding: on an
 * unchanged head it stays open, in the summary's counts and Key Findings; only when the head moves
 * and the line is gone is it superseded. Each round runs the real {@link SecurityScan}, {@link
 * VerdictBuilder}, {@link FollowUpAnalyzer} and {@link PrSummaryGenerator}.
 */
class RedactedSecretAcrossRoundsTest {

  private static final String FILE = "python/marketplace/config.py";

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  private static final String TITLE =
      "Checkout settings module ignores documented runtime configuration";

  private final String value = new FakeCredentials(932).genericSecret(40);

  private final String secretLine = "PAYMENT_API_KEY = \"" + value + "\"";

  private final ObjectMapper mapper = new ObjectMapper();

  private final SecurityScan scan = new SecurityScan(true, false, 3.5, List.of());

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(mapper),
          BOT,
          BlockingStrictness.BALANCED);

  private final DiffBudgetPlanner.BudgetPlan plan =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  /** The head both rounds review when it does not move. */
  private FileDiff head() {
    return added("import os", "", "DEBUG = False", "", secretLine, "TIMEOUT = 30");
  }

  /** The head after a push that reads the key from the environment instead. */
  private static FileDiff movedHead() {
    return added(
        "import os",
        "",
        "DEBUG = False",
        "",
        "PAYMENT_API_KEY = os.environ[\"PAYMENT_API_KEY\"]",
        "TIMEOUT = 30");
  }

  /** The model's own finding on the secret's line, quoting it — a defect the scan does not own. */
  private ReviewResponse.Finding modelFinding() {
    return new ReviewResponse.Finding(
        "critical",
        "high",
        FILE,
        5,
        TITLE,
        "Docs promise operators configure PAYMENT_API_KEY through deployment variables;"
            + " config.py hardcodes `"
            + secretLine
            + "` instead.",
        secretLine,
        "PAYMENT_API_KEY = os.environ[\"PAYMENT_API_KEY\"]");
  }

  @Test
  void aRedactedModelFindingStaysOpenOnAnUnchangedHeadWhenTheModelReportsIt() throws Exception {
    var first = roundOne();

    var round =
        scan.merge(
            new ReviewResponse(
                List.of(),
                List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
                null),
            scan.scan(List.of(head())),
            first.findings(),
            Set.of());
    // Even with no head comparison to lean on, the redacted anchor is found in the diff.
    var result = builder.build(context(List.of(first), head(), false), round, CI_CLEAR, plan);

    assertStillOpen(result);
  }

  @Test
  void aRedactedModelFindingTheModelOmitsIsHeldByTheBackstopOnAnUnchangedHead() throws Exception {
    var first = roundOne();

    var round =
        scan.merge(
            new ReviewResponse(List.of(), List.of(), null),
            scan.scan(List.of(head())),
            first.findings(),
            Set.of());
    var result = builder.build(context(List.of(first), head(), true), round, CI_CLEAR, plan);

    assertStillOpen(result);
  }

  @Test
  void aFindingWhoseAnchorCannotBeLocatedIsNeverSupersededOnAnUnchangedHead() throws Exception {
    // A quote that does not match the diff at all (drifted, or mangled beyond the redacted form):
    // on the head its round reviewed it still cannot have left the diff.
    var stored = roundOne();
    var unlocatable = stored.findings().get(0);
    var drifted =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    unlocatable.risk(),
                    unlocatable.confidence(),
                    unlocatable.file(),
                    unlocatable.line(),
                    unlocatable.title(),
                    unlocatable.description(),
                    "PAYMENT_KEY = settings.get(\"key\")",
                    unlocatable.suggestionNew()),
                stored.findings().get(1)),
            List.of(),
            null);
    var reported =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
            null);
    var omitted = new ReviewResponse(List.of(), List.of(), null);

    for (var round : List.of(reported, omitted)) {
      var unchanged = builder.build(context(List.of(drifted), head(), true), round, CI_CLEAR, plan);
      assertFalse(unchanged.hasSupersededPrevious(), "nothing is superseded on an unchanged head");
      assertTrue(
          unchanged.openPreviousFindings().stream().anyMatch(f -> TITLE.equals(f.title())),
          "the finding stays open");

      // The same round on a head that moved does supersede it: the guard is keyed on the head.
      var moved = builder.build(context(List.of(drifted), head(), false), round, CI_CLEAR, plan);
      assertTrue(moved.hasSupersededPrevious());
    }
  }

  @Test
  void aRedactedModelFindingIsSupersededOnceTheHeadMovesAndItsLineIsGone() throws Exception {
    var first = roundOne();

    var round =
        scan.merge(
            new ReviewResponse(
                List.of(),
                List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
                null),
            scan.scan(List.of(movedHead())),
            first.findings(),
            Set.of());
    var result = builder.build(context(List.of(first), movedHead(), false), round, CI_CLEAR, plan);
    var body = summary(result);

    assertTrue(result.hasSupersededPrevious());
    assertTrue(body.contains("| 🗂️ Superseded (targeted code left the diff) | 1 |"), body);
    assertTrue(body.contains("| ✅ Resolved | 1 |"), "the scan's own finding: no longer detected");
    assertTrue(body.contains("| 🔴 Critical | 0 |"), body);
    assertFalse(body.contains(TITLE), body);
  }

  /**
   * Round one: the model's finding quotes the secret; the scan raises its own on the same line and
   * scrubs the literal out of the model's. Returns the response as persisted.
   */
  private ReviewResponse roundOne() throws Exception {
    var stored =
        scan.merge(
            new ReviewResponse(List.of(modelFinding()), List.of(), null),
            scan.scan(List.of(head())),
            List.of(),
            Set.of());
    assertEquals(2, stored.findings().size(), "a different defect on the line is kept");
    var model = stored.findings().get(0);
    assertEquals(TITLE, model.title());
    assertTrue(model.suggestionOld().contains("[redacted: "), model.suggestionOld());
    var persisted = mapper.writeValueAsString(stored);
    assertFalse(persisted.contains(value.substring(4)), "the literal is never stored");
    return mapper.readValue(persisted, ReviewResponse.class);
  }

  private void assertStillOpen(ReviewResult result) {
    assertFalse(result.hasSupersededPrevious(), "nothing is superseded on an unchanged head");
    assertTrue(
        result.openPreviousFindings().stream().anyMatch(f -> TITLE.equals(f.title())),
        "the redacted finding is still open");
    var body = summary(result);
    assertFalse(body.contains("Superseded"), body);
    assertTrue(body.contains("| 🔴 Critical | 1 |"), body);
    assertTrue(body.contains("| 🟠 High | 1 |"), "the scan's own finding is still open: " + body);
    var keyFindings = body.substring(body.indexOf("### Key Findings"));
    assertTrue(keyFindings.contains(TITLE), body);
    assertFalse(body.contains(value.substring(4)), "the literal never reaches the summary");
  }

  private String summary(ReviewResult result) {
    var commentClient = mock(GitHubCommentClient.class);
    var publisher =
        new ReviewPublisher(
            mock(GitHubReviewClient.class),
            commentClient,
            mock(ReviewThreadService.class),
            mock(SuggestionFormatter.class),
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            mock(ThrillhouseConfig.class),
            BOT);
    assertTrue(publisher.publishSummary("auth", "o", "r", 1, result, true));
    var captor = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), captor.capture());
    return captor.getValue().body();
  }

  private ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds, FileDiff file, boolean headUnchanged) throws Exception {
    var jsons = new java.util.ArrayList<String>();
    for (var round : priorRounds) {
      jsons.add(mapper.writeValueAsString(round));
    }
    return new ReviewContextLoader.ReviewContext(
        List.of(file),
        "",
        "",
        0,
        List.of(),
        jsons,
        priorRounds,
        false,
        true,
        jsons.get(0),
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        List.of(file),
        () -> new DiffLineResolver(Map.of(FILE, file.patch())),
        null,
        List.of(),
        List.of(),
        SupersededFindingsCarryover.Carried.NONE,
        "",
        headUnchanged);
  }

  private static FileDiff added(String... lines) {
    var sb = new StringBuilder("@@ -0,0 +1," + lines.length + " @@\n");
    for (var line : lines) {
      sb.append('+').append(line).append('\n');
    }
    return new FileDiff(FILE, "added", lines.length, 0, lines.length, sb.toString());
  }
}
