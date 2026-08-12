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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pins that moving the summary, verifier, and reply calls onto the {@code concise} named model
 * (#498) did not detach them from the truncation detection of #492/#495/#497: a {@code
 * finish_reason: length} stop on a concise-bound call must still surface as {@link
 * AiResponseTruncatedException} — naming the cap — rather than as an unparseable or half-posted
 * body. Drives the real AI services against mocked {@code @ModelName("concise")} model beans, so
 * the test also fails if a service silently rebinds to the default model.
 */
@QuarkusTest
class ConciseModelTruncationTest {

  @InjectMock
  @ModelName("concise")
  ChatModel conciseChatModel;

  @InjectMock
  @ModelName("concise")
  StreamingChatModel conciseStreamingChatModel;

  @Inject AiReviewService aiReviewService;
  @Inject FindingVerifier findingVerifier;
  @Inject ReplyAssistant replyAssistant;

  @Test
  void aLengthStopOnTheConciseSummaryCallRaisesTheTruncationError() {
    doAnswer(
            inv -> {
              StreamingChatResponseHandler handler = inv.getArgument(1);
              handler.onCompleteResponse(lengthStoppedResponse("{\"summary\":\"cut mid-obj"));
              return null;
            })
        .when(conciseStreamingChatModel)
        .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

    var session = reviewSession();
    var inputs = new AiReviewService.SummaryInputs("ctx", "[]", "files", "", "");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class, () -> aiReviewService.summarize(session, inputs));

    assertTrue(
        thrown.getMessage().contains("max-output-tokens"),
        "the message must name the knob an operator has to change: " + thrown.getMessage());
  }

  @Test
  void aLengthStopOnTheConciseVerifierCallRaisesTheTruncationError() {
    when(conciseChatModel.chat(any(ChatRequest.class)))
        .thenReturn(lengthStoppedResponse("[{\"id\":1,\"verdi"));

    var result = findingVerifier.verify("[]", "diff", "", "");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(
                    result, "Finding verification", AiResponses.ModelLane.CONCISE));

    assertTrue(
        thrown.getMessage().contains("REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
        "the verifier runs on the concise model, so its cap is the one to name: "
            + thrown.getMessage());
  }

  @Test
  void aLengthStopOnTheConciseReplyCallRaisesTheTruncationError() {
    when(conciseChatModel.chat(any(ChatRequest.class)))
        .thenReturn(lengthStoppedResponse("A reply cut mid-sen"));

    var result = replyAssistant.reply("question", "", "", "", "");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(
                    result, "Maintainer reply", AiResponses.ModelLane.CONCISE));

    assertTrue(
        thrown.getMessage().contains("REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
        "the reply lane runs on the concise model, so its cap is the one to name: "
            + thrown.getMessage());
  }

  private static ChatResponse lengthStoppedResponse(String partialText) {
    return ChatResponse.builder()
        .aiMessage(AiMessage.from(partialText))
        .finishReason(FinishReason.LENGTH)
        .build();
  }

  private static ReviewSession reviewSession() {
    var session = new ReviewSession();
    session.id = 4242L;
    session.setRepository("owner/repo");
    session.setPrNumber(1);
    session.setPrTitle("Concise truncation");
    session.setCommitSha("abc");
    session.setTimestamp(Instant.parse("2025-06-01T12:00:00Z"));
    return session;
  }
}
