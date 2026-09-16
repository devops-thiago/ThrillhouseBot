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

import java.time.Duration;

/**
 * A streaming attempt that reached its client-side deadline ({@code
 * THRILLHOUSEBOT_REVIEW_AI_TIMEOUT_SECONDS}) without a complete response, and the failure of a call
 * that ended because too many of its attempts did (#862).
 *
 * <p>Named apart from a plain {@link AiReviewException} so the retry loop can tell a deadline from
 * the other transient failures it retries on the same terms. A deadline says something about the
 * request and not only about the provider: a prompt that did not finish inside it is unlikely to
 * finish inside another one, and every repeat costs the whole deadline while the review holds its
 * pull request's place in the dispatcher. Production spent 75 minutes of wall clock that way on a
 * single 503-file pull request, so {@link AiReviewService} bounds how many attempts of one logical
 * call may end here.
 *
 * <p>Still an {@link AiReviewException}: to everything downstream this is a call that was attempted
 * and did not produce a response, so the degradations that keep a review's paid work when its
 * summary call fails (#851) and the batch lane's disclosure of the files it could not read apply
 * unchanged.
 */
public class AiReviewTimeoutException extends AiReviewException {

  private final Duration waited;

  public AiReviewTimeoutException(String message, int attempts, Duration waited, Throwable cause) {
    super(message, attempts, cause);
    this.waited = waited;
  }

  /**
   * How long the attempts this failure describes spent at the deadline: one attempt's wait when a
   * single attempt timed out, the sum over the call's timed-out attempts when the bound ended it.
   */
  public Duration waited() {
    return waited;
  }
}
