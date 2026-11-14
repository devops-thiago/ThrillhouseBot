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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService.Candidate;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService.Reason;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService.Request;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService.Selected;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RelatedContextSelectionServiceTest {

  private static final long SESSION = 42L;

  private static final List<Candidate> CANDIDATES =
      List.of(
          new Candidate("src/A.java", "imported", List.of("src/Handler.java"), List.of("A")),
          new Candidate("src/B.java", "named in added lines", List.of(), List.of()),
          new Candidate("src/C.java", "imported", List.of("src/Handler.java"), List.of("C")));

  private final RelatedContextSelector selector = mock(RelatedContextSelector.class);
  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
  private final ReviewTokenLedger ledger = mock(ReviewTokenLedger.class);
  private final RelatedContextSelectionService service =
      new RelatedContextSelectionService(
          selector,
          config,
          new ObjectMapper(),
          ledger,
          TestResponseCaps.defaults(),
          new TokenCounter());

  private static Request request(int maxFiles) {
    return new Request(
        List.of("- src/Handler.java (modified, +3/-1)\n    @@ -1 +1 @@"),
        CANDIDATES,
        maxFiles,
        Integer.MAX_VALUE);
  }

  private void answers(String json) {
    when(selector.select(anyString(), anyString(), anyInt())).thenReturn(aiOk(json));
  }

  @Test
  void picksTheNumberedCandidatesWithTheirReasonsAndMetersTheCall() {
    when(selector.select(anyString(), anyString(), anyInt()))
        .thenReturn(
            Result.<String>builder()
                .content(
                    "{\"related\": [{\"id\": 3, \"reason\": \"interface\"},"
                        + " {\"id\": \"#1\", \"reason\": \"Sanitizer_Or_Validator\"}]}")
                .finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(120, 30))
                .build());

    var outcome = service.select(SESSION, request(4));

    assertTrue(outcome.called());
    assertEquals(
        List.of(
            new Selected("src/C.java", Reason.INTERFACE),
            new Selected("src/A.java", Reason.SANITIZER_OR_VALIDATOR)),
        outcome.selected());
    verify(ledger).open(SESSION);
    verify(ledger).recordUsage(SESSION, 120, 30);
    var changed = ArgumentCaptor.forClass(String.class);
    var candidates = ArgumentCaptor.forClass(String.class);
    verify(selector).select(changed.capture(), candidates.capture(), anyInt());
    assertTrue(changed.getValue().startsWith(PromptTemplateEscaper.fencePrefix()));
    assertTrue(
        PromptTemplateEscaper.unfence(candidates.getValue())
            .startsWith(
                "1. src/A.java (imported; referenced by src/Handler.java; as A)\n"
                    + "2. src/B.java (named in added lines)\n"),
        candidates.getValue());
  }

  @Test
  void anAnswerNeverSelectsWhatIsNotACandidateOrMoreThanTheLimit() {
    answers(
        "{\"related\": [{\"id\": 9, \"reason\": \"caller\"},"
            + " {\"path\": \"../../etc/passwd\"},"
            + " {\"path\": \"src/B.java\", \"reason\": \"nonsense\"},"
            + " {\"id\": 2, \"reason\": \"caller\"},"
            + " {\"id\": 1, \"reason\": \"config-consumer\"},"
            + " {\"id\": 3}]}");

    var outcome = service.select(SESSION, request(2));

    assertEquals(
        List.of(
            new Selected("src/B.java", Reason.RELATED),
            new Selected("src/A.java", Reason.CONFIG_CONSUMER)),
        outcome.selected());
  }

  @Test
  void anEmptyCutMalformedOrFailedAnswerSelectsNothingAndStillCountsAsACall() {
    for (var result :
        List.of(aiNoContent(), aiTruncated("{\"related\": [{\"id\": 1"), aiOk("no"))) {
      when(selector.select(anyString(), anyString(), anyInt())).thenReturn(result);
      var outcome = service.select(SESSION, request(4));
      assertTrue(outcome.called());
      assertEquals(List.of(), outcome.selected());
    }
    answers("{\"related\": \"none\"}");
    assertEquals(List.of(), service.select(SESSION, request(4)).selected());
    when(selector.select(anyString(), anyString(), anyInt()))
        .thenThrow(new IllegalStateException("provider down"));
    var failed = service.select(SESSION, request(4));
    assertTrue(failed.called());
    assertEquals(List.of(), failed.selected());
  }

  @Test
  void noCallIsMadeWithoutCandidatesAtTheSpendCeilingOrOverTheInputBudget() {
    var none = service.select(SESSION, new Request(List.of(), List.of(), 4, Integer.MAX_VALUE));
    assertFalse(none.called());
    verifyNoInteractions(ledger);
    assertFalse(service.select(SESSION, request(0)).called());

    assertFalse(service.select(SESSION, new Request(List.of("- f"), CANDIDATES, 4, 10)).called());

    when(ledger.ceilingReached(SESSION)).thenReturn(true);
    assertFalse(service.select(SESSION, request(4)).called());
    verifyNoInteractions(selector);
  }

  @Test
  void releaseClearsTheLedgerEntryAndReasonsReadTheirLabels() {
    service.release(SESSION);

    verify(ledger).clear(SESSION);
    assertEquals(Reason.CALLER, Reason.of(" Caller "));
    assertEquals(Reason.RELATED, Reason.of(null));
    assertEquals("config-consumer", Reason.CONFIG_CONSUMER.label());
  }

  @Test
  void anAnswerPickingNothingOrNamingAnOversizedNumberSelectsNothing() {
    answers("{\"related\": []}");
    assertEquals(List.of(), service.select(SESSION, request(4)).selected());
    answers("{\"related\": [{\"id\": \"123456\"}, {\"id\": \"none\"}]}");
    assertEquals(List.of(), service.select(SESSION, request(4)).selected());
    when(selector.select(anyString(), anyString(), anyInt())).thenReturn(aiOk("   "));
    assertEquals(List.of(), service.select(SESSION, request(4)).selected());
    when(selector.select(anyString(), anyString(), anyInt())).thenReturn(null);
    var absent = service.select(SESSION, request(4));
    assertTrue(absent.called());
    assertEquals(List.of(), absent.selected());
  }

  @Test
  void aCallThatReasonedItsCapAwayIsRepeatedOnceAtTheLanesEffort() {
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var reasoning = mock(ThrillhouseConfig.AiPricingConfig.ReasoningConfig.class);
    when(config.ai()).thenReturn(ai);
    when(ai.reasoning()).thenReturn(reasoning);
    when(reasoning.enabled()).thenReturn(false);
    when(reasoning.effort()).thenReturn("max");
    when(reasoning.conciseEffort()).thenReturn(java.util.Optional.empty());
    when(reasoning.repeatEffort()).thenReturn(java.util.Optional.empty());
    var efforts = new java.util.ArrayList<String>();
    when(selector.select(anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              efforts.add(ReasoningRepeat.current());
              return efforts.size() == 1
                  ? Result.<String>builder().content(null).finishReason(FinishReason.LENGTH).build()
                  : aiOk("{\"related\": [{\"id\": 2, \"reason\": \"caller\"}]}");
            });

    var outcome = service.select(SESSION, request(4));

    assertEquals(List.of(new Selected("src/B.java", Reason.CALLER)), outcome.selected());
    assertEquals(java.util.Arrays.asList(null, "low"), efforts);
    verify(ledger).recordReasoningStepDown(SESSION);
  }
}
