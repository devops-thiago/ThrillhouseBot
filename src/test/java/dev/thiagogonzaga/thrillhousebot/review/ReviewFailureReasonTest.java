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

import dev.langchain4j.exception.HttpException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiApiKeyRejectedException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** The failure reason a failed review session stores (#1329). */
class ReviewFailureReasonTest {

  @Test
  void aRejectedKeyIsNamedHoweverDeepTheReviewWrappedIt() {
    var rejection =
        AiApiKeyRejectedException.classify(new HttpException(401, "Unauthorized")).orElseThrow();

    assertEquals(
        "The AI provider rejected the API key (HTTP 401); check AI_API_KEY",
        ReviewOrchestrator.failureReason(new CompletionException("batch 1 failed", rejection)));
  }

  @Test
  void anyOtherFailureKeepsItsOwnMessageCutAt200Characters() {
    assertEquals("boom", ReviewOrchestrator.failureReason(new IllegalStateException("boom")));
    assertEquals(
        200, ReviewOrchestrator.failureReason(new IllegalStateException("x".repeat(300))).length());
    assertEquals("Unknown error", ReviewOrchestrator.failureReason(new IllegalStateException()));
  }
}
