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

import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiOk;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerifier;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import dev.thiagogonzaga.thrillhousebot.review.ai.TruncatedResponseSalvager;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * #742/#755. Every log line that interpolates a model-supplied {@code title} or {@code file} is one
 * record an operator reads, and the model chooses those strings. A path or title carrying a line
 * terminator splits the record and forges a second one; one carrying a bidi override or an ANSI
 * escape reorders or erases what the operator is shown.
 *
 * <p>#744 routed the two {@link ReviewPublisher} warn lines through {@link MarkdownSafe#oneLine},
 * whose collapse class is the ASCII {@code \s} six — so NEL (U+0085), LINE SEPARATOR (U+2028),
 * PARAGRAPH SEPARATOR (U+2029), NUL, ESC and RLO went straight through it — and left the six INFO
 * lines that interpolate the same model strings untouched. Every one of those is on in a default
 * install: there is no {@code quarkus.log} level configuration in the repository.
 *
 * <p>Each test drives the real production path and captures the {@link LogRecord} the class emits —
 * the object every handler (console, file, syslog, JSON/ECS shipper) is handed — so the assertion
 * does not depend on which handler happens to be installed.
 */
class ModelSuppliedTextInLogLinesTest {

  private static String ch(int codePoint) {
    return String.valueOf((char) codePoint);
  }

  private static final String NEL = ch(0x85);
  private static final String LS = ch(0x2028);
  private static final String PS = ch(0x2029);
  private static final String NUL = ch(0x00);
  private static final String ESC = ch(0x1B);
  private static final String RLO = ch(0x202E);

  private static final List<String> FORGERY_CHARACTERS = List.of(NEL, LS, PS, NUL, ESC, RLO);

  /** A model string that forges a second record and then reorders what is left of the first. */
  private static final String FORGED =
      NEL
          + "2026-08-16 12:00:00 WARN [thrillhousebot] approved the pull request, 0 findings"
          + LS
          + "forged-by-line-separator"
          + PS
          + "forged-by-paragraph-separator"
          + NUL
          + "after-nul"
          + ESC
          + "[2Kescaped"
          + RLO
          + "desrever";

  /** A crafted path: a real-looking prefix so the record stays plausible, then the forgery. */
  private static final String FORGED_PATH = "app.js" + FORGED;

  /** A crafted finding title, the value the six INFO lines all interpolate. */
  private static final String FORGED_TITLE = "Missing null check" + FORGED;

  private static String visible(String text) {
    return text.replace(NEL, "<NEL>")
        .replace(LS, "<U+2028>")
        .replace(PS, "<U+2029>")
        .replace(NUL, "<NUL>")
        .replace(ESC, "<ESC>")
        .replace(RLO, "<RLO>");
  }

  /**
   * The record as a handler sees it: the message and every parameter the formatter would splice.
   */
  private static String text(LogRecord entry) {
    var joined = new StringBuilder(String.valueOf(entry.getMessage()));
    var parameters = entry.getParameters();
    if (parameters != null) {
      for (var parameter : parameters) {
        joined.append(' ').append(parameter);
      }
    }
    return joined.toString();
  }

  /**
   * Runs {@code body} with a handler attached to {@code source}'s logger and returns its records.
   */
  private static List<String> logsOf(Class<?> source, Runnable body) {
    var captured = new ArrayList<String>();
    var logger = Logger.getLogger(source.getName());
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            captured.add(text(entry));
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

  /**
   * The line must have been emitted, must still identify the finding, and must carry no forgery.
   */
  private static void assertRecordCannotBeForged(List<String> captured, String anchor) {
    assertFalse(captured.isEmpty(), "the log line under test did not run");
    var joined = String.join("\n", captured);
    assertTrue(joined.contains(anchor), "the line must still name the finding: " + visible(joined));
    for (var forbidden : FORGERY_CHARACTERS) {
      assertFalse(
          joined.contains(forbidden),
          "U+"
              + String.format("%04X", (int) forbidden.charAt(0))
              + " reached the log line:\n"
              + visible(joined));
    }
  }

  private static ReviewResponse.Finding finding(
      String file, int line, String title, String suggestionOld) {
    return new ReviewResponse.Finding(
        "medium", "high", file, line, title, "description", suggestionOld, "new");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(
        List.of(findings),
        List.of(),
        new ReviewResponse.Summary(
            findings.length, 0, 0, findings.length, 0, "assessment", "purpose", List.of()));
  }

  /**
   * Both {@link ReviewPublisher} warn lines at once: the line-anchored comment is refused, so the
   * first fires, and the file-level fallback is refused too, so the second does.
   */
  @Test
  void aCraftedPathCannotForgeARecordFromEitherPublisherRejectionWarning() {
    var reviewClient = mock(GitHubReviewClient.class);
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    var formatter = mock(SuggestionFormatter.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    when(formatter.formatReviewComment(any(), anyBoolean(), anyInt())).thenReturn("body");
    when(reviewClient.createPullRequestComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenThrow(new WebApplicationException("nope", 422));
    var publisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            formatter,
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BotIdentity.of("thrillhousebot"));
    var result =
        new ReviewResult(
            List.of(
                new Finding(RiskLevel.HIGH, FORGED_PATH, 2, "title", "description", null, null)),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "summary",
            List.of(),
            List.of(),
            0);
    var resolver = new DiffLineResolver(Map.of(FORGED_PATH, "@@ -0,0 +1,3 @@\n+a\n+b\n+c\n"));

    var captured =
        logsOf(
            ReviewPublisher.class,
            () -> publisher.postInlineComments("auth", "o", "r", 1, "sha", result, resolver));

    assertRecordCannotBeForged(captured, "app.js");
  }

  /** {@link FindingQuoteValidator} demoting a finding whose quote is nowhere in the diff. */
  @Test
  void aCraftedTitleCannotForgeARecordFromTheQuoteValidatorDemotion() {
    var diff =
        """
        diff --git a/src/Main.java b/src/Main.java
        --- a/src/Main.java
        +++ b/src/Main.java
        @@ -1,3 +1,3 @@
         public class Main {
        +    var repos = new ArrayList<RepoRef>(snapshot);
         }
        """;
    var validator = new FindingQuoteValidator();
    var response =
        response(finding("src/Main.java", 2, FORGED_TITLE, "nothing like this is in the diff"));

    var captured = logsOf(FindingQuoteValidator.class, () -> validator.validate(response, diff));

    assertRecordCannotBeForged(captured, "Missing null check");
  }

  /** {@link FrameworkFalsePositiveFilter} dropping a no-arg-constructor claim the diff refutes. */
  @Test
  void aCraftedTitleCannotForgeARecordFromTheFrameworkFilterDrop() {
    var diff =
        """
        ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +6 -0)
        ```diff
        @@ -10,3 +10,9 @@
         public class PrSummaryGenerator {
        +  private final AiReviewService aiReviewService;
        +
        +  @Inject
        +  public PrSummaryGenerator(AiReviewService aiReviewService) {
        +    this.aiReviewService = aiReviewService;
        +  }
         }
        ```
        """;
    var filter = new FrameworkFalsePositiveFilter();
    var claim =
        new ReviewResponse.Finding(
            "medium",
            "medium",
            "src/main/java/dev/example/PrSummaryGenerator.java",
            12,
            "Missing no-arg constructor" + FORGED,
            "CDI requires a bean to be proxyable; add a no-arg constructor.",
            null,
            null);

    var captured =
        logsOf(FrameworkFalsePositiveFilter.class, () -> filter.filter(response(claim), diff));

    assertRecordCannotBeForged(captured, "Missing no-arg constructor");
  }

  /** {@link FindingDeduplicator} merging a cluster — it names the cluster's file and title. */
  @Test
  void aCraftedPathAndTitleCannotForgeARecordFromTheDeduplicatorMerge() {
    var deduplicator = new FindingDeduplicator();
    var response =
        response(
            finding(FORGED_PATH, 42, FORGED_TITLE, "old"),
            finding(FORGED_PATH, 43, FORGED_TITLE, "old"));

    var captured = logsOf(FindingDeduplicator.class, () -> deduplicator.dedupe(response));

    assertRecordCannotBeForged(captured, "Missing null check");
  }

  /** {@link FollowUpAnalyzer} dropping a re-raise a maintainer already answered. */
  @Test
  void aCraftedTitleCannotForgeARecordFromTheRepliedDuplicateDrop() {
    var analyzer = new FollowUpAnalyzer(new ObjectMapper());
    var priorJson =
        "{\"findings\": [{\"risk\": \"medium\", \"file\": \"src/B.java\", \"line\": 5,"
            + " \"title\": "
            + new ObjectMapper().valueToTree(FORGED_TITLE)
            + ", \"description\": \"d\"}]}";
    var botComment =
        new GitHubReviewClient.PullRequestComment(
            100L,
            null,
            "src/B.java",
            "**MEDIUM — " + FORGED_TITLE + "**",
            new GitHubReviewClient.ReviewResponse.User("thrillhousebot"),
            "MEMBER");
    var maintainerReply =
        new GitHubReviewClient.PullRequestComment(
            101L,
            100L,
            "src/B.java",
            "Declining.",
            new GitHubReviewClient.ReviewResponse.User("maintainer"),
            "MEMBER");
    var reRaised = response(finding("src/B.java", 5, FORGED_TITLE, null));

    var captured =
        logsOf(
            FollowUpAnalyzer.class,
            () ->
                analyzer.dropRepliedDuplicates(
                    reRaised,
                    List.of(priorJson),
                    List.of(botComment, maintainerReply),
                    BotIdentity.of("thrillhousebot")));

    assertRecordCannotBeForged(captured, "Missing null check");
  }

  /** {@link FindingPipeline} filling in a content anchor the model left blank. */
  @Test
  void aCraftedPathAndTitleCannotForgeARecordFromTheAnchorBackfill() {
    var pipeline =
        new FindingPipeline(
            mock(AiReviewService.class),
            mock(FindingQuoteValidator.class),
            mock(FrameworkFalsePositiveFilter.class),
            mock(FindingDeduplicator.class),
            mock(FindingVerificationService.class),
            mock(FollowUpAnalyzer.class),
            new ObjectMapper(),
            BotIdentity.of("thrillhousebot"),
            mock(DiffBudgetPlanner.class),
            new TokenCounter(),
            mock(ReviewTokenLedger.class),
            new TruncatedResponseSalvager(new ObjectMapper()));
    var response = response(finding(FORGED_PATH, 1, FORGED_TITLE, null));
    var resolver = new DiffLineResolver(Map.of(FORGED_PATH, "@@ -0,0 +1,1 @@\n+var a = 1;\n"));

    var captured =
        logsOf(FindingPipeline.class, () -> pipeline.populateMissingAnchors(response, resolver));

    assertRecordCannotBeForged(captured, "Missing null check");
  }

  /**
   * A finding-shaped forgery with no prose: the control and format characters alone. The hedging
   * and injection-sink screens below read the finding's own words to decide whether to fire, so a
   * crafted title that also carried sentences could change which branch runs rather than what the
   * branch logs.
   */
  private static final String BARE_FORGERY = NEL + LS + PS + NUL + ESC + RLO;

  /** A verification service whose verifier is off, so only the deterministic screens run. */
  private static FindingVerificationService screeningOnlyService() {
    var mapper = new ObjectMapper();
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    return new FindingVerificationService(
        mock(FindingVerifier.class),
        config,
        mapper,
        mock(ReviewTokenLedger.class),
        new TruncatedResponseSalvager(mapper));
  }

  /** {@link FindingVerificationService} demoting a finding whose own wording hedges the defect. */
  @Test
  void aCraftedTitleCannotForgeARecordFromTheHedgedDemotion() {
    var hedged =
        new ReviewResponse.Finding(
            "high",
            "high",
            "src/Main.java",
            10,
            "Underscore variable may not compile" + FORGED,
            "If the project targets Java 17, compilation could fail.",
            null,
            null);

    var captured =
        logsOf(
            FindingVerificationService.class,
            () -> screeningOnlyService().verify(42L, response(hedged), "diff", "stack", ""));

    assertRecordCannotBeForged(captured, "Underscore variable may not compile");
  }

  /** {@link FindingVerificationService} raising an unmitigated injection sink to high risk. */
  @Test
  void aCraftedTitleCannotForgeARecordFromTheInjectionSinkFloor() {
    var sink =
        new ReviewResponse.Finding(
            "medium",
            "low",
            "src/components/Comment.tsx",
            14,
            "User comment written to innerHTML" + BARE_FORGERY,
            "Nothing sanitizes the value before innerHTML receives it.",
            null,
            null);

    var captured =
        logsOf(
            FindingVerificationService.class,
            () -> screeningOnlyService().verify(42L, response(sink), "diff", "stack", ""));

    assertRecordCannotBeForged(captured, "User comment written to innerHTML");
  }

  /**
   * {@link FollowUpAnalyzer} clearing a prior finding a maintainer named in the PR conversation.
   * This line carries two model-supplied values, not one: the title, and the {@code path:line}
   * locator composed from the finding's own file and line a few statements earlier. A fix that
   * wrapped the title alone would leave the path forging records, so the crafted value here is the
   * path and the assertion is on the composed locator.
   */
  @Test
  void aCraftedPathCannotForgeARecordFromTheConversationClear() {
    var prior =
        new ReviewResponse.Finding(
            "medium", "low", FORGED_PATH, 7, "Unbounded retry loop", "description", null, null);
    var clearing =
        new GitHubCommentClient.IssueComment(
            901L,
            "@thrillhousebot resolved `" + FORGED_PATH + ":7` — Unbounded retry loop",
            new GitHubReviewClient.ReviewResponse.User("maintainer"),
            "MEMBER");
    var current = response(finding("src/Other.java", 3, "A later finding", null));

    var captured =
        logsOf(
            FollowUpAnalyzer.class,
            () ->
                FollowUpAnalyzer.withoutPreviouslyLitigated(
                    current,
                    List.of(new ReviewResponse(List.of(prior), List.of(), null)),
                    List.of(),
                    List.of(clearing),
                    BotIdentity.of("thrillhousebot")));

    assertRecordCannotBeForged(captured, "app.js");
  }

  /** {@link FindingVerificationService} logging the verdict that rejected a finding. */
  @Test
  void aCraftedTitleCannotForgeARecordFromTheVerifierRejection() {
    var mapper = new ObjectMapper();
    var verifier = mock(FindingVerifier.class);
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.verifierEnabled()).thenReturn(true);
    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk("{\"verdicts\": [{\"id\": 1, \"verdict\": \"rejected\", \"reason\": \"fp\"}]}"));
    var service =
        new FindingVerificationService(
            verifier,
            config,
            mapper,
            mock(ReviewTokenLedger.class),
            new TruncatedResponseSalvager(mapper));
    var response = response(finding("src/Main.java", 10, FORGED_TITLE, "old"));

    var captured =
        logsOf(
            FindingVerificationService.class,
            () -> service.verify(42L, response, "diff", "stack", ""));

    assertRecordCannotBeForged(captured, "Missing null check");
  }
}
