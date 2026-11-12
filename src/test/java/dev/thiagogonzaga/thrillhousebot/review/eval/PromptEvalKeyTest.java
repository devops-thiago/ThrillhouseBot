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
package dev.thiagogonzaga.thrillhousebot.review.eval;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/** How the eval stops on a rejected key, without a model (#1329). */
class PromptEvalKeyTest {

  @Test
  void aRecordedOrThrownKeyRejectionFailsTheSuiteNamingBothKeys() {
    var recorded =
        assertThrows(
            AssertionFailedError.class, () -> PromptEvalTest.failOnRejectedKey(true, null));
    assertTrue(recorded.getMessage().contains("QUARKUS_LANGCHAIN4J_OPENAI_API_KEY"));
    assertTrue(recorded.getMessage().contains("QUARKUS_LANGCHAIN4J_OPENAI_CONCISE_API_KEY"));

    var streamed = new ExecutionException(new HttpException(401, "Unauthorized"));
    assertThrows(
        AssertionFailedError.class, () -> PromptEvalTest.failOnRejectedKey(false, streamed));
    assertThrows(
        AssertionFailedError.class,
        () -> PromptEvalTest.failOnRejectedKey(false, new AuthenticationException("bad key")));
  }

  @Test
  void anyOtherFailureLeavesTheSampleToItsOwnRetry() {
    assertDoesNotThrow(() -> PromptEvalTest.failOnRejectedKey(false, null));
    assertDoesNotThrow(
        () ->
            PromptEvalTest.failOnRejectedKey(
                false, new ExecutionException(new HttpException(503, "Unavailable"))));
  }

  @Test
  void theConciseKeyIsRequired() {
    assertTrue(PromptEvalTest.missingConciseKey(null).isPresent());
    assertTrue(
        PromptEvalTest.missingConciseKey(" ")
            .orElseThrow()
            .contains("QUARKUS_LANGCHAIN4J_OPENAI_CONCISE_API_KEY"));
    assertFalse(PromptEvalTest.missingConciseKey("sk-real").isPresent());
  }

  @Test
  void theRecorderKeepsAKeyRejectionAcrossSamples() {
    var recorder = new EvalCallRecorder();
    recorder.onError(errorContext(new HttpException(500, "boom")));
    assertFalse(recorder.keyRejected());

    recorder.onError(errorContext(new HttpException(403, "Forbidden")));
    recorder.reset();
    assertTrue(recorder.keyRejected(), "a verifier call that failed open still stops the suite");
  }

  private static ChatModelErrorContext errorContext(Throwable error) {
    return new ChatModelErrorContext(
        error,
        ChatRequest.builder().messages(UserMessage.from("verify")).build(),
        ModelProvider.OPEN_AI,
        Map.of());
  }
}
