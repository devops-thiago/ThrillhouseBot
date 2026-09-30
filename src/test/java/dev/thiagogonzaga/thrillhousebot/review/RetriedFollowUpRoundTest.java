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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FakeTokenStream;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerifier;
import dev.thiagogonzaga.thrillhousebot.review.ai.ModelCallGate;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewer;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrSummarizer;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponseParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TestResponseCaps;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import dev.thiagogonzaga.thrillhousebot.review.ai.TruncatedResponseSalvager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #939, end to end on ThrillhouseBot-test#146's shape: round one posted its findings (two of them
 * from the security scan), round two on the same head raised one new finding, and round three's
 * first model attempt answered with something that was not review JSON. The retry must carry the
 * same prompt, and the round it produces must post no second thread for anything already open —
 * neither a finding the model raised again nor one the scan detects again.
 *
 * <p>Runs the real {@link AiReviewService} retry loop and parser, the real {@link FindingPipeline}
 * with the real {@link SecurityScan} and {@link FollowUpAnalyzer}, the real {@link VerdictBuilder},
 * and the real {@link ReviewPublisher} thread posting; only the model and GitHub are stubbed.
 */
class RetriedFollowUpRoundTest {

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final String READING = "rust/src/reading.rs";
  private static final String CLIENT = "rust/src/client.rs";
  private static final String DOCKERFILE = "rust/Dockerfile";

  private static final String AVERAGE_LINE = "let tail = &r[r.len() - n + 1..];";
  private static final String EXCURSION_LINE = "pub fn is_excursion(t: f64) -> bool { t > 8.0 }";
  private static final String SYNC_LINE =
      "pub fn sync_sensors() { let page = fetch(1); store(page); }";
  private static final String COPY_LINE = "COPY target/release/coldchain /app/coldchain";

  private final ObjectMapper mapper = new ObjectMapper();
  private final String token = new FakeCredentials(939).githubToken();
  private final SecurityScan scan = new SecurityScan(true, true, 3.5, List.of());
  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(mapper);
  private final SuggestionFormatter formatter = new SuggestionFormatter();

  private final List<FileDiff> files =
      List.of(
          added(
              READING,
              "pub fn recent_average(r: &[f64], n: usize) -> f64 {",
              "    " + AVERAGE_LINE,
              "    tail.iter().sum::<f64>() / n as f64",
              "}",
              EXCURSION_LINE),
          added(CLIENT, "const API_TOKEN: &str = \"" + token + "\";", SYNC_LINE),
          added(DOCKERFILE, "FROM rust:1.80", COPY_LINE, "USER root"));

  private final ReviewResponse.Finding offByOne =
      finding(
          READING,
          2,
          "recent_average off-by-one returns last n-1 readings and panics on empty input",
          "Slicing from len - n + 1 keeps n - 1 readings; an empty slice underflows.",
          AVERAGE_LINE);

  private final ReviewResponse.Finding pagination =
      finding(
          CLIENT,
          2,
          "sync_sensors fetches only the first registry page",
          "Only page 1 is fetched; sensors past the first page are never synced.",
          SYNC_LINE);

  private final ReviewResponse.Finding artifact =
      finding(
          DOCKERFILE,
          2,
          "Dockerfile copies a binary no stage produces",
          "No build stage produces target/release/coldchain, so the COPY fails the build.",
          COPY_LINE);

  private PrReviewer prReviewer;
  private ReviewPublisher publisher;
  private GitHubReviewClient reviewClient;
  private FindingPipeline pipeline;
  private VerdictBuilder verdictBuilder;

  @BeforeEach
  void setUp() {
    prReviewer = mock(PrReviewer.class);
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    var aiConfig = mock(ThrillhouseConfig.AiPricingConfig.class);
    var reasoning = mock(ThrillhouseConfig.AiPricingConfig.ReasoningConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(config.ai()).thenReturn(aiConfig);
    when(aiConfig.reasoning()).thenReturn(reasoning);
    when(reasoning.conciseEffort()).thenReturn(Optional.empty());
    when(reviewConfig.maxAiRetries()).thenReturn(5);
    when(reviewConfig.aiRetryBaseDelayMs()).thenReturn(1L);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(5);
    when(reviewConfig.maxReviewComments()).thenReturn(50);
    when(reviewConfig.verifierEnabled()).thenReturn(true);
    var aiService =
        new AiReviewService(
            prReviewer,
            mock(PrSummarizer.class),
            new ReviewResponseParser(mapper),
            config,
            mock(SessionEventBroadcaster.class),
            mock(ReviewTokenLedger.class),
            new ModelCallGate(config),
            TestResponseCaps.defaults());

    // The pipeline's ledger is at its ceiling: the verifier fails open and the summary call is
    // skipped, so the only model calls this test makes are the review call's two attempts.
    var pipelineLedger = mock(ReviewTokenLedger.class);
    when(pipelineLedger.ceilingReached(anyLong())).thenReturn(true);
    var quoteValidator = mock(FindingQuoteValidator.class);
    when(quoteValidator.validate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    var frameworkFilter = mock(FrameworkFalsePositiveFilter.class);
    when(frameworkFilter.filter(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    pipeline =
        new FindingPipeline(
            aiService,
            quoteValidator,
            frameworkFilter,
            new FindingDeduplicator(),
            new FindingVerificationService(
                mock(FindingVerifier.class),
                config,
                mapper,
                pipelineLedger,
                new TruncatedResponseSalvager(mapper),
                TestResponseCaps.defaults()),
            new VerifierRejectionMemory(),
            analyzer,
            mapper,
            BOT,
            mock(DiffBudgetPlanner.class),
            new TokenCounter(),
            pipelineLedger,
            new TruncatedResponseSalvager(mapper),
            ReviewDimensionRouter.disabled(),
            scan);
    verdictBuilder =
        new VerdictBuilder(
            new PrSummaryGenerator(false), analyzer, BOT, BlockingStrictness.BALANCED);
    reviewClient = mock(GitHubReviewClient.class);
    publisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            formatter,
            analyzer,
            mock(PrLabeler.class),
            config,
            BOT);
  }

  @Test
  void aFollowUpRoundWhoseFirstAttemptIsNotReviewJsonPostsNoDuplicateThreadOnTheRetry()
      throws Exception {
    // Round one: the model's two findings, then the scan's two (the token, the root user).
    var roundOne =
        persisted(
            scan.merge(
                new ReviewResponse(List.of(offByOne, pagination), List.of(), null),
                scan.scan(files),
                List.of(),
                Set.of()));
    assertEquals(4, roundOne.findings().size());
    // Round two, same head: one new finding, every round-one finding reported unresolved.
    var roundTwo =
        persisted(
            new ReviewResponse(
                List.of(artifact),
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
                    new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there"),
                    new ReviewResponse.PreviousFindingStatus(3, "unresolved", "still there"),
                    new ReviewResponse.PreviousFindingStatus(4, "unresolved", "still there")),
                null));
    var threads = new ArrayList<GitHubReviewClient.PullRequestComment>();
    postedThreads(threads, roundOne, 100);
    postedThreads(threads, roundTwo, 200);
    var ctx = context(List.of(roundTwo, roundOne), threads);

    // Round three: the first answer is prose; the retry re-raises every open model finding,
    // reworded or not, and adds one genuinely new finding.
    var nan =
        finding(
            READING,
            5,
            "NaN and infinite temperatures silently evade excursion detection",
            "t > 8.0 is false for NaN, so a failed probe never reports an excursion.",
            EXCURSION_LINE);
    var retried =
        new ReviewResponse(
            List.of(
                finding(
                    READING,
                    2,
                    "recent_average is off by one and panics on empty input",
                    offByOne.description(),
                    AVERAGE_LINE),
                pagination,
                finding(
                    DOCKERFILE,
                    2,
                    "Dockerfile copies a binary that no stage produces",
                    artifact.description(),
                    COPY_LINE),
                nan),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
            null);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenAnswer(inv -> new FakeTokenStream("I reviewed the diff; here are my findings."))
        .thenAnswer(inv -> new FakeTokenStream(mapper.writeValueAsString(retried)));
    var previousFindings =
        analyzer.buildPreviousFindingsContext(
            ctx.previousFindingsList(),
            true,
            List.of(),
            threads,
            List.of(roundOne),
            BOT,
            Set.of(),
            FollowUpAnalyzer.openEarlierRoundFindings(
                ctx.priorAiResponses(), ctx.lineResolver(), Map.of()));
    var session = ReviewSession.create("owner/repo", 146, "Cold chain", "1d16c26");
    session.id = 5428L;

    var response =
        pipeline.run(
            session,
            new AiReviewService.PromptInputs("diff", "ctx", "", "", "", previousFindings, ""),
            ctx,
            new DiffBudgetPlanner.BudgetPlan(
                List.of(), List.of(), List.of(), false, null, null, null, null),
            ctx.lineResolver(),
            ReviewEvidence.NONE);

    // Both attempts carried the follow-up context, earlier rounds' open findings included.
    var sent = ArgumentCaptor.forClass(String.class);
    verify(prReviewer, times(2))
        .reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            sent.capture(),
            anyString(),
            anyString());
    assertEquals(List.of(previousFindings, previousFindings), sent.getAllValues());
    assertTrue(previousFindings.contains("Still open from earlier rounds"), previousFindings);
    assertTrue(previousFindings.contains(offByOne.title()), previousFindings);

    assertEquals(List.of(nan.title()), titles(response.findings()), "only the new finding");

    var result =
        verdictBuilder.build(
            ctx, response, new CiStatusEvaluator.CiEvaluation(List.of(), false), plan());
    var inline =
        publisher.postInlineComments(
            "auth", "owner", "repo", 146, "1d16c26", result, ctx.lineResolver());

    assertEquals(1, inline.posted());
    var posted = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(reviewClient, atLeastOnce())
        .createPullRequestComment(
            eq("auth"), anyString(), eq("owner"), eq("repo"), eq(146), posted.capture());
    assertEquals(1, posted.getAllValues().size(), "one new thread, for the new finding");
    assertTrue(posted.getValue().body().contains(nan.title()), posted.getValue().body());
    // The findings it did not re-post are still open, and still hold the verdict.
    assertTrue(result.unresolvedPreviousCount() > 0);
    var stillOpen = titles(result.openPreviousFindings());
    for (var open : List.of(offByOne, pagination, artifact)) {
      assertTrue(stillOpen.contains(open.title()), open.title() + " open in " + stillOpen);
    }
  }

  private static DiffBudgetPlanner.BudgetPlan plan() {
    return new DiffBudgetPlanner.BudgetPlan(
        List.of(), List.of(), List.of(), false, null, null, null, null);
  }

  /** The round as it is stored and read back: through its JSON, as the session history holds it. */
  private ReviewResponse persisted(ReviewResponse round) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(round), ReviewResponse.class);
  }

  /** The bot's thread for each of a round's findings, carrying that round's marker. */
  private void postedThreads(
      List<GitHubReviewClient.PullRequestComment> threads, ReviewResponse round, long firstId) {
    for (int i = 0; i < round.findings().size(); i++) {
      var posted = round.findings().get(i);
      threads.add(
          new GitHubReviewClient.PullRequestComment(
              firstId + i,
              null,
              posted.file(),
              formatter.formatReviewComment(Finding.fromAiResponse(posted), true, i + 1),
              new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    }
  }

  private ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds, List<GitHubReviewClient.PullRequestComment> threads)
      throws Exception {
    var jsons = new ArrayList<String>();
    for (var round : priorRounds) {
      jsons.add(mapper.writeValueAsString(round));
    }
    var patches = new java.util.HashMap<String, String>();
    files.forEach(file -> patches.put(file.filename(), file.patch()));
    var resolver = new DiffLineResolver(patches);
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
        threads,
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        files,
        () -> resolver,
        null,
        List.of(),
        List.of(),
        SupersededFindingsCarryover.Carried.NONE,
        "",
        true);
  }

  private static List<String> titles(List<?> findings) {
    return findings.stream()
        .map(f -> f instanceof ReviewResponse.Finding raw ? raw.title() : ((Finding) f).title())
        .toList();
  }

  private static ReviewResponse.Finding finding(
      String file, int line, String title, String description, String anchor) {
    return new ReviewResponse.Finding("high", "high", file, line, title, description, anchor, null);
  }

  private static FileDiff added(String name, String... lines) {
    var sb = new StringBuilder("@@ -0,0 +1," + lines.length + " @@\n");
    for (var line : lines) {
      sb.append('+').append(line).append('\n');
    }
    return new FileDiff(name, "added", lines.length, 0, lines.length, sb.toString());
  }
}
