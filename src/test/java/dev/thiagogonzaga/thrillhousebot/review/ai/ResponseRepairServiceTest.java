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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiNoContent;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiOk;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiTruncated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResponseRepairServiceTest {

  private static final long SESSION = 7L;

  /** A review answer with an unescaped quote in its description and a trailing comma. */
  private static final String BROKEN_REVIEW =
      """
      {"findings": [
        {"risk": "high", "confidence": "high", "file": "app.py", "line": 3,
         "title": "Cursor loop never runs", "description": "cursor is "None" at the start",},
      ], "previous_findings_status": [{"id": 1, "status": "resolved", "note": "fixed"}]}
      """;

  private static final String REPAIRED_REVIEW =
      """
      {"findings": [
        {"risk": "high", "confidence": "high", "file": "app.py", "line": 3,
         "title": "Cursor loop never runs", "description": "cursor is \\"None\\" at the start"}
      ], "previous_findings_status": [{"id": 1, "status": "resolved", "note": "fixed"}]}
      """;

  private final ResponseRepairer reviewRepairer = mock(ResponseRepairer.class);
  private final SummaryResponseRepairer summaryRepairer = mock(SummaryResponseRepairer.class);
  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);
  private final ReviewTokenLedger ledger = mock(ReviewTokenLedger.class);
  private final ReviewResponseParser parser = new ReviewResponseParser(new ObjectMapper());

  private ResponseRepairService service;

  @BeforeEach
  void setUp() {
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.responseRepairEnabled()).thenReturn(true);
    service = newService();
  }

  private ResponseRepairService newService() {
    return new ResponseRepairService(
        reviewRepairer,
        summaryRepairer,
        parser,
        new ObjectMapper(),
        ledger,
        TestResponseCaps.defaults(),
        config);
  }

  private static IllegalArgumentException refusal() {
    return new IllegalArgumentException(
        "Model response is not valid review JSON",
        new IllegalStateException("Unexpected character ('N'): was expecting comma\n at [line 3]"));
  }

  private void reviewAnswer(Result<String> result) {
    when(reviewRepairer.repair(anyString(), anyString(), anyString())).thenReturn(result);
  }

  @Test
  void aRepairedReviewAnswerIsReadByTheReviewParser() {
    reviewAnswer(aiOk(REPAIRED_REVIEW));

    var repaired = service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal());

    assertTrue(repaired.isPresent());
    assertEquals("Cursor loop never runs", repaired.get().response().findings().get(0).title());
    assertEquals("resolved", repaired.get().response().previousFindingsStatus().get(0).status());
    verifyNoInteractions(summaryRepairer);
  }

  @Test
  void theRepairGetsOnlyTheFencedAnswerTheErrorAndTheReviewContract() {
    reviewAnswer(aiOk(REPAIRED_REVIEW));

    service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal());

    var contract = ArgumentCaptor.forClass(String.class);
    var error = ArgumentCaptor.forClass(String.class);
    var answer = ArgumentCaptor.forClass(String.class);
    verify(reviewRepairer).repair(contract.capture(), error.capture(), answer.capture());
    assertEquals(ResponseRepairPrompts.REVIEW_CONTRACT, contract.getValue());
    assertTrue(
        error
            .getValue()
            .startsWith(
                "Expected one JSON object with a \"findings\" array. The parser said: Model"
                    + " response is not valid review JSON: Unexpected character"),
        error.getValue());
    assertFalse(error.getValue().contains("at [line 3]"), "only the cause's first line");
    assertEquals(BROKEN_REVIEW, PromptTemplateEscaper.unfence(answer.getValue()));
    assertNotEquals(BROKEN_REVIEW, answer.getValue(), "the answer arrives fenced");
  }

  @Test
  void aSummaryAnswerIsRepairedOnTheConciseModelWithTheSummaryContract() {
    when(summaryRepairer.repair(anyString(), anyString(), anyString()))
        .thenReturn(aiOk("{\"summary\": {\"pr_purpose\": \"Adds paging.\"}}"));

    var repaired = service.repair(SESSION, ModelLane.CONCISE, "{\"summary\": [", refusal());

    assertEquals("Adds paging.", repaired.orElseThrow().response().summary().prPurpose());
    var contract = ArgumentCaptor.forClass(String.class);
    var error = ArgumentCaptor.forClass(String.class);
    verify(summaryRepairer).repair(contract.capture(), error.capture(), anyString());
    assertEquals(ResponseRepairPrompts.SUMMARY_CONTRACT, contract.getValue());
    assertTrue(error.getValue().startsWith("Expected one JSON object with a \"summary\" object."));
    verifyNoInteractions(reviewRepairer);
  }

  @Test
  void theRepairCallsUsageIsRecordedEvenWhenItsAnswerIsCut() {
    reviewAnswer(
        Result.<String>builder()
            .content(REPAIRED_REVIEW)
            .finishReason(FinishReason.STOP)
            .tokenUsage(new TokenUsage(120, 80))
            .build());

    service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal());

    verify(ledger).recordUsage(SESSION, 120, 80);

    var cut =
        Result.<String>builder()
            .content("{\"findings\": [")
            .finishReason(FinishReason.LENGTH)
            .tokenUsage(new TokenUsage(30, 900))
            .build();
    reviewAnswer(cut);
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());
    verify(ledger).recordUsage(SESSION, 30, 900);
  }

  /**
   * #1169: a repair call whose reasoning spent the whole cap is repeated once, at the concise
   * lane's effort for a summary answer, before the repair gives up.
   */
  @Test
  void aRepairThatReasonedItsCapAwayIsRepeatedOnceAtTheLanesEffort() {
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var reasoning = mock(ThrillhouseConfig.AiPricingConfig.ReasoningConfig.class);
    when(config.ai()).thenReturn(ai);
    when(ai.reasoning()).thenReturn(reasoning);
    when(reasoning.enabled()).thenReturn(false);
    when(reasoning.effort()).thenReturn("max");
    when(reasoning.conciseEffort()).thenReturn(java.util.Optional.empty());
    var effortPerCall = new java.util.ArrayList<String>();
    when(summaryRepairer.repair(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              effortPerCall.add(ReasoningRepeat.current());
              return effortPerCall.size() == 1
                  ? Result.<String>builder().content(null).finishReason(FinishReason.LENGTH).build()
                  : aiOk("{\"summary\": {\"pr_purpose\": \"Adds paging.\"}}");
            });

    var repaired = service.repair(SESSION, ModelLane.CONCISE, "{\"summary\": [", refusal());

    assertEquals("Adds paging.", repaired.orElseThrow().response().summary().prPurpose());
    assertEquals(java.util.Arrays.asList(null, "low"), effortPerCall);
    verify(ledger).recordReasoningStepDown(SESSION);
  }

  @Test
  void aCutEmptyFailedOrUnreadableRepairReturnsNothing() {
    reviewAnswer(aiTruncated("{\"findings\": ["));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());

    reviewAnswer(aiNoContent());
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());

    reviewAnswer(aiOk("still not json"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());

    when(reviewRepairer.repair(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("provider down"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());
  }

  @Test
  void noCallIsMadeWhenTheStepIsOffTheAnswerIsBlankOrTheCeilingIsReached() {
    when(reviewConfig.responseRepairEnabled()).thenReturn(false);
    var off = newService();
    assertFalse(off.enabled());
    assertTrue(off.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());
    assertTrue(off.fileSummaries(SESSION, "app.py: adds paging").isEmpty());

    assertTrue(service.enabled());
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, " ", refusal()).isEmpty());
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, null, refusal()).isEmpty());
    assertTrue(service.fileSummaries(SESSION, " ").isEmpty());
    assertTrue(service.fileSummaries(SESSION, null).isEmpty());

    when(ledger.ceilingReached(SESSION)).thenReturn(true);
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());
    assertTrue(service.fileSummaries(SESSION, "app.py: adds paging").isEmpty());

    verifyNoInteractions(reviewRepairer, summaryRepairer);
  }

  @Test
  void aRepairThatDropsAFindingIsRejected() {
    var twoFindings =
        """
        {"findings": [{"title": "Cursor loop never runs", "risk": "high",},
                      {"title": "Entrypoint names the old path", "risk": "high"}]}
        """;
    reviewAnswer(
        aiOk("{\"findings\": [{\"title\": \"Cursor loop never runs\", \"risk\": \"high\"}]}"));

    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, twoFindings, refusal()).isEmpty());
  }

  @Test
  void aRepairThatTurnsABrokenAnswerIntoACleanOneIsRejected() {
    reviewAnswer(aiOk("{\"findings\": [], \"previous_findings_status\": []}"));

    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());
  }

  @Test
  void aRepairThatAddsAFindingTitleIsRejected() {
    reviewAnswer(
        aiOk(
            """
            {"findings": [
              {"title": "Cursor loop never runs", "risk": "high"},
              {"title": "A finding the answer never had", "risk": "critical"}]}
            """));

    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, BROKEN_REVIEW, refusal()).isEmpty());

    reviewAnswer(aiOk("{\"findings\": [{\"risk\": \"high\"}]}"));
    assertTrue(
        service
            .repair(SESSION, ModelLane.ACTIVE, "{\"findings\": [{\"risk\": \"high\",}]}", refusal())
            .isEmpty(),
        "a finding with no title cannot be matched to the failed answer");
  }

  @Test
  void aRepairThatAddsAStatusIsRejected() {
    reviewAnswer(
        aiOk(
            """
            {"findings": [], "previous_findings_status": [
              {"id": 1, "status": "resolved"}, {"id": 2, "status": "resolved"}]}
            """));

    assertTrue(
        service
            .repair(
                SESSION,
                ModelLane.ACTIVE,
                "{\"findings\": [], \"previous_findings_status\": [{\"id\": 1, \"status\":"
                    + " \"resolved\",}]}",
                refusal())
            .isEmpty());
  }

  @Test
  void aTitleTheRepairUnescapedStillMatchesTheFailedAnswer() {
    var body =
        "{\"findings\": [{\"title\": \"Quote \\\"x\\\" is  unescaped\", \"risk\": \"low\",}]}";
    reviewAnswer(aiOk("{\"findings\": [{\"title\": \"Quote \\\"x\\\" is unescaped\"}]}"));

    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isPresent());
  }

  @Test
  void keysQuotedInsideAStringAreNotCountedAsFindingsOrStatuses() {
    // rc15 review of #1168: a description quoting JSON inflated the guard's counts.
    var body =
        """
        {"findings": [{"title": "Parser drops the status field", "risk": "medium",
          "description": "It reads {\\"title\\": \\"x\\", \\"status\\": \\"open\\"} as empty",},
        ], "previous_findings_status": []}
        """;
    reviewAnswer(
        aiOk(
            """
            {"findings": [{"title": "Parser drops the status field", "risk": "medium",
              "description": "It reads {\\"title\\": \\"x\\", \\"status\\": \\"open\\"} as empty"}],
             "previous_findings_status": []}
            """));

    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isPresent());
  }

  @Test
  void aNestedObjectsTitleIsNotCountedAsAFinding() {
    // rc15 review of #1168: the raw-text count took every "title" key in the answer.
    var body =
        """
        {"findings": [{"title": "Parser drops the status field", "risk": "medium",
          "example": {"title": "a quoted record", "status": "open"},},
        ], "previous_findings_status": []}
        """;
    reviewAnswer(
        aiOk(
            """
            {"findings": [{"title": "Parser drops the status field", "risk": "medium"}],
             "previous_findings_status": []}
            """));

    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isPresent());
  }

  @Test
  void aTitleFromANestedFindingsArrayIsNotOneOfTheFailedAnswers() {
    // rc16 review of #1168: {real, phantom} passed when "phantom" sat in a nested findings array.
    var body =
        "{\"findings\": [{\"title\": \"real\", \"example\": {\"findings\": [{\"title\":"
            + " \"phantom\"}]},}]}";
    reviewAnswer(aiOk("{\"findings\": [{\"title\": \"real\"}, {\"title\": \"phantom\"}]}"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isEmpty());

    reviewAnswer(aiOk("{\"findings\": [{\"title\": \"real\"}]}"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isPresent());
  }

  @Test
  void aTitleThatOnlyAppearsInsideAnotherFieldIsNotTheFailedAnswers() {
    var body =
        """
        {"findings": [{"title": "Cursor loop never runs", "risk": "high",
          "description": "Unlike Entrypoint names the old path, this one is real",}]}
        """;
    reviewAnswer(
        aiOk(
            """
            {"findings": [{"title": "Entrypoint names the old path", "risk": "high"}]}
            """));

    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isEmpty());
  }

  @Test
  void aReAskedRowIsKeptOnlyWhenItsWholePathIsInTheText() {
    // rc15 review of #1168: "a.py" is not named by "data.py: ..." or "a.pyc".
    var text = "data.py: reads the rows; lib/a.pyc is generated; app.py.";
    when(summaryRepairer.repair(anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
                {"file_summaries": [
                  {"path": "a.py", "summary": "Partial match"},
                  {"path": "data.py", "summary": "Reads the rows"},
                  {"path": "lib/a.py", "summary": "Prefix of a.pyc"},
                  {"path": "app.py", "summary": "Ends the sentence"}]}
                """));

    assertEquals(
        List.of(
            new ReviewResponse.FileSummary("data.py", "Reads the rows"),
            new ReviewResponse.FileSummary("app.py", "Ends the sentence")),
        service.fileSummaries(SESSION, text).orElseThrow());
  }

  @Test
  void anAnswerTheScanCannotFollowIsCountedByEveryKeyInTheText() {
    // An unterminated string: the scan gives up, and every "title" key in the text counts.
    var body = "{\"findings\": [{\"title\": \"A\"}, {\"title\": \"B\", \"note\": \"cut";
    reviewAnswer(aiOk("{\"findings\": [{\"title\": \"A\"}]}"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isEmpty());

    reviewAnswer(aiOk("{\"findings\": [{\"title\": \"A\"}, {\"title\": \"B\"}]}"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isPresent());

    reviewAnswer(aiOk("{\"findings\": [{\"title\": \"A\"}, {\"title\": \"Z\"}]}"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, body, refusal()).isEmpty());

    var statusBody =
        "{\"findings\": [], \"previous_findings_status\": [{\"id\": 1, \"status\": \"open";
    reviewAnswer(
        aiOk(
            "{\"findings\": [], \"previous_findings_status\": [{\"id\": 1, \"status\": \"resolved\"}]}"));
    assertTrue(service.repair(SESSION, ModelLane.ACTIVE, statusBody, refusal()).isPresent());
  }

  @Test
  void aPathAtTheStartOrEndOfTheTextIsNamed() {
    assertTrue(ResponseRepairService.namesPath("a.py", "a.py"));
    assertTrue(ResponseRepairService.namesPath("see a.py", "a.py"));
    assertTrue(ResponseRepairService.namesPath("a.py: x", "a.py"));
    assertFalse(ResponseRepairService.namesPath("a.py.bak: x", "a.py"));
    assertFalse(ResponseRepairService.namesPath("ba.py", "a.py"));
    assertTrue(ResponseRepairService.namesPath("a.py. Then b.py", "a.py"));
    assertFalse(ResponseRepairService.namesPath("a.pyx", "a.py"));
    assertFalse(ResponseRepairService.namesPath("a.py-old", "a.py"));
    assertFalse(ResponseRepairService.namesPath("a.py_old", "a.py"));
    assertFalse(ResponseRepairService.namesPath("lib/a.py", "/a.py"));
  }

  @Test
  void aRefusalWithNoCauseOrALongCauseIsStatedClipped() {
    var bare = new IllegalArgumentException("Model response is not a JSON object");
    assertEquals(
        "Expected one JSON object with a \"findings\" array. The parser said: Model response is"
            + " not a JSON object",
        ResponseRepairService.errorText(bare, false));

    var silentCause =
        new IllegalArgumentException("Model response is not valid review JSON", new Exception());
    assertTrue(
        ResponseRepairService.errorText(silentCause, false)
            .endsWith("Model response is not valid review JSON"));

    var longCause =
        new IllegalArgumentException(
            "Model response is not valid review JSON", new Exception("x".repeat(500)));
    var text = ResponseRepairService.errorText(longCause, true);
    assertTrue(text.endsWith("…"), text);
    assertTrue(
        text.length()
            < ResponseRepairService.MAX_ERROR_DETAIL
                + "Expected one JSON object with a \"summary\" object. The parser said: ".length()
                + 2);
  }

  @Test
  void fileSummariesAreReadFromTheReAskAndOnlyPathsTheStringNamesAreKept() {
    var text = "app.py: adds the paging loop; Dockerfile: copies the jar";
    when(summaryRepairer.repair(anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
                {"file_summaries": [
                  {"path": "app.py", "summary": "Adds the paging loop"},
                  {"path": "Dockerfile", "summary": "Copies the jar"},
                  {"path": "src/invented.py", "summary": "Made up"},
                  {"path": "", "summary": "No path"},
                  {"path": "app.py", "summary": ""}]}
                """));

    var rows = service.fileSummaries(SESSION, text).orElseThrow();

    assertEquals(
        List.of(
            new ReviewResponse.FileSummary("app.py", "Adds the paging loop"),
            new ReviewResponse.FileSummary("Dockerfile", "Copies the jar")),
        rows);
    var contract = ArgumentCaptor.forClass(String.class);
    var error = ArgumentCaptor.forClass(String.class);
    var answer = ArgumentCaptor.forClass(String.class);
    verify(summaryRepairer).repair(contract.capture(), error.capture(), answer.capture());
    assertEquals(ResponseRepairPrompts.FILE_SUMMARIES_CONTRACT, contract.getValue());
    assertEquals(ResponseRepairPrompts.FILE_SUMMARIES_ERROR, error.getValue());
    assertEquals(text, PromptTemplateEscaper.unfence(answer.getValue()));
    verifyNoInteractions(reviewRepairer);
  }

  @Test
  void aFileSummariesReAskWithNothingUsableReturnsNothing() {
    var text = "app.py: adds the paging loop";
    when(summaryRepairer.repair(anyString(), anyString(), anyString()))
        .thenReturn(aiOk("{\"file_summaries\": [{\"path\": \"other.py\", \"summary\": \"x\"}]}"))
        .thenReturn(aiOk("not json"))
        .thenReturn(aiNoContent())
        .thenReturn(aiOk("  "))
        .thenReturn(null);

    for (var i = 0; i < 5; i++) {
      assertTrue(service.fileSummaries(SESSION, text).isEmpty());
    }
  }

  @Test
  void oneFileSummaryReadFromTheReAskIsKept() {
    when(summaryRepairer.repair(anyString(), anyString(), anyString()))
        .thenReturn(aiOk("{\"file_summaries\": [{\"path\": \"app.py\", \"summary\": \"Pages\"}]}"));

    assertEquals(
        List.of(new ReviewResponse.FileSummary("app.py", "Pages")),
        service.fileSummaries(SESSION, "app.py: pages").orElseThrow());
  }
}
