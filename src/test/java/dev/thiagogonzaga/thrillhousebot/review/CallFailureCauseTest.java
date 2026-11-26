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
import dev.langchain4j.exception.RateLimitException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiContextWindowExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewTimeoutException;
import dev.thiagogonzaga.thrillhousebot.review.ai.MalformedAnswers;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class CallFailureCauseTest {

  private static Throwable wrapped(Throwable lastFailure) {
    return new CompletionException(
        new AiReviewException("AI review failed after 3 attempts", 3, lastFailure));
  }

  @Test
  void aTimedOutLastAttemptIsATimeout() {
    var timeout = new AiReviewTimeoutException("timed out", 1, Duration.ofMinutes(15), null);

    assertEquals(CallFailureCause.TIMED_OUT, CallFailureCause.describe(wrapped(timeout)));
  }

  @Test
  void aContextWindowRejectionIsNamed() {
    var rejection = new AiContextWindowExceededException("too long", null);

    assertEquals(CallFailureCause.CONTEXT_WINDOW, CallFailureCause.describe(wrapped(rejection)));
  }

  @Test
  void anHttpFailureNamesTheStatusAndNeverTheProvidersBody() {
    var http = new HttpException(503, "upstream said sk-secret-key");

    assertEquals("the AI provider returned HTTP 503", CallFailureCause.describe(wrapped(http)));
  }

  @Test
  void aRateLimitWithoutAStatusIsNamed() {
    var limited = new RateLimitException("slow down");

    assertEquals(CallFailureCause.RATE_LIMITED, CallFailureCause.describe(wrapped(limited)));
  }

  @Test
  void anyOtherFailureIsLeftToTheServerLog() {
    var other = new IllegalStateException("sk-secret-key");

    assertEquals(CallFailureCause.UNCLASSIFIED, CallFailureCause.describe(wrapped(other)));
  }

  @Test
  void anAnswerTheParserRefusedIsNamed() {
    var refused = wrapped(MalformedAnswers.refused());

    assertEquals(CallFailureCause.UNREADABLE_ANSWER, CallFailureCause.describe(refused));
  }
}
