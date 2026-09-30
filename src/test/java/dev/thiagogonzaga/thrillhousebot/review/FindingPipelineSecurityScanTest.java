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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerifier;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TestResponseCaps;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import dev.thiagogonzaga.thrillhousebot.review.ai.TruncatedResponseSalvager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #60: the deterministic security scan wired into the review pipeline. Its findings join after the
 * second-pass verification, so a round where the verifier returned nothing (#885) marks the model's
 * findings unverified and leaves the scan's alone; the matched value never reaches the persisted
 * session (the dashboard's source) or a log record.
 */
class FindingPipelineSecurityScanTest {

  private final FakeCredentials fake = new FakeCredentials(885);

  private AiReviewService aiReviewService;
  private ReviewTokenLedger tokenLedger;
  private FindingPipeline pipeline;

  @BeforeEach
  void setUp() {
    aiReviewService = mock(AiReviewService.class);
    tokenLedger = mock(ReviewTokenLedger.class);
    var quoteValidator = mock(FindingQuoteValidator.class);
    var frameworkFilter = mock(FrameworkFalsePositiveFilter.class);
    var followUpAnalyzer = mock(FollowUpAnalyzer.class);
    var budgetPlanner = mock(DiffBudgetPlanner.class);
    when(quoteValidator.validate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(frameworkFilter.filter(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(followUpAnalyzer.dropRepliedDuplicates(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(budgetPlanner.perCallInputBudget()).thenReturn(Integer.MAX_VALUE);
    lenient()
        .when(aiReviewService.summarize(any(), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    // A real verification service whose every call is skipped at the spend ceiling: the fail-open
    // path that marks each candidate unverified (#885).
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.verifierEnabled()).thenReturn(true);
    when(tokenLedger.ceilingReached(anyLong())).thenReturn(true);
    when(tokenLedger.tokensSpent(anyLong())).thenReturn(106_000L);
    when(tokenLedger.ceiling()).thenReturn(100_000L);
    var verification =
        new FindingVerificationService(
            mock(FindingVerifier.class),
            config,
            new ObjectMapper(),
            tokenLedger,
            new TruncatedResponseSalvager(new ObjectMapper()),
            TestResponseCaps.defaults());

    pipeline =
        new FindingPipeline(
            aiReviewService,
            quoteValidator,
            frameworkFilter,
            new FindingDeduplicator(),
            verification,
            new VerifierRejectionMemory(),
            followUpAnalyzer,
            new ObjectMapper(),
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            budgetPlanner,
            new TokenCounter(),
            tokenLedger,
            new TruncatedResponseSalvager(new ObjectMapper()),
            ReviewDimensionRouter.disabled(),
            new SecurityScan(true, true, 3.5, List.of()));
  }

  private static ReviewSession session() {
    var session = ReviewSession.create("owner/repo", 60, "PR", "sha");
    session.id = 60L;
    return session;
  }

  private static FileDiff added(String name, String line) {
    return new FileDiff(name, "added", 1, 0, 1, "@@ -0,0 +1,1 @@\n+" + line + "\n");
  }

  private static ReviewContextLoader.ReviewContext context(List<FileDiff> reviewable) {
    return new ReviewContextLoader.ReviewContext(
        reviewable,
        "",
        "",
        0,
        List.of(),
        List.of(),
        List.of(),
        true,
        false,
        null,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        reviewable,
        () -> new DiffLineResolver(Map.of()),
        null);
  }

  private static DiffBudgetPlanner.DiffBatch batch(FileDiff file) {
    return new DiffBudgetPlanner.DiffBatch("### " + file.filename() + "\n", List.of(file), 10);
  }

  private static List<String> logsDuring(Runnable body) {
    var captured = new ArrayList<String>();
    var logger = Logger.getLogger("dev.thiagogonzaga.thrillhousebot");
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            var text = new StringBuilder(String.valueOf(entry.getMessage()));
            if (entry.getParameters() != null) {
              for (var parameter : entry.getParameters()) {
                text.append(' ').append(parameter);
              }
            }
            captured.add(text.toString());
          }

          @Override
          public void flush() {
            // nothing is buffered
          }

          @Override
          public void close() {
            // nothing to release
          }
        };
    logger.addHandler(handler);
    try {
      body.run();
    } finally {
      logger.removeHandler(handler);
    }
    return captured;
  }

  @Test
  void singleCallScanFindingsSkipTheVerifierAndItsUnverifiedMarking() {
    var token = fake.githubToken();
    var secretFile = added("deploy/app.env", "GITHUB_TOKEN=" + token);
    var modelFinding =
        new ReviewResponse.Finding(
            "high",
            "high",
            "src/App.java",
            3,
            "Missing null check",
            "The token " + token + " is read without a null check.",
            null,
            null);
    when(aiReviewService.review(any(), any()))
        .thenReturn(new ReviewResponse(List.of(modelFinding), List.of(), null));
    var session = session();
    var ctx = context(List.of(secretFile));
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(batch(secretFile)), List.of(), List.of(), true, null, null, null, null);

    var holder = new ArrayList<ReviewResponse>();
    var logs =
        logsDuring(
            () ->
                holder.add(
                    pipeline.run(
                        session,
                        new AiReviewService.PromptInputs("d", "ctx", "", "", "", "", ""),
                        ctx,
                        plan,
                        new DiffLineResolver(Map.of()),
                        ReviewEvidence.NONE)));
    var result = holder.get(0);

    assertEquals(2, result.findings().size());
    var model = result.findings().get(0);
    var scanned = result.findings().get(1);
    assertEquals("medium", model.confidence(), "the unscreened model finding is capped (#885)");
    assertTrue(model.description().contains(FindingVerificationService.UNVERIFIED_NOTE));
    assertEquals("critical", scanned.risk());
    assertEquals("high", scanned.confidence(), "a pattern match is not capped");
    assertFalse(scanned.description().contains(FindingVerificationService.UNVERIFIED_NOTE));

    assertNotNull(session.getAiResponseJson());
    assertFalse(session.getAiResponseJson().contains(token), "the dashboard's copy is redacted");
    assertFalse(model.description().contains(token));
    assertTrue(logs.stream().anyMatch(line -> line.startsWith("Security scan:")));
    assertTrue(logs.stream().noneMatch(line -> line.contains(token)), String.join("\n", logs));
  }

  @Test
  void multiCallMergesTheScanAfterTheBatches() {
    var key = fake.stripeLiveKey();
    var secretFile = added("billing/config.env", "STRIPE_KEY=" + key);
    var podFile = added("k8s/pod.yaml", "  hostNetwork: true");
    when(aiReviewService.reviewBatch(any(), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var session = session();
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(batch(secretFile), batch(podFile)),
            List.of(),
            List.of(),
            true,
            null,
            null,
            null,
            null);

    var result =
        pipeline.run(
            session,
            new AiReviewService.PromptInputs("d", "ctx", "", "", "", "", ""),
            context(List.of(secretFile, podFile)),
            plan,
            new DiffLineResolver(Map.of()),
            ReviewEvidence.NONE);

    assertEquals(
        List.of(
            "Security scan: hardcoded Stripe live key (sk_l…, 32 chars)",
            "Security scan: pod shares a host namespace"),
        result.findings().stream().map(ReviewResponse.Finding::title).toList());
    assertFalse(session.getAiResponseJson().contains(key));
  }
}
