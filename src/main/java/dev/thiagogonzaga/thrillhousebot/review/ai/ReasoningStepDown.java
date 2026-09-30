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

/**
 * How the reasoning step-down went for one review's calls, as the posted summary discloses it. A
 * call that stopped at its length cap with no content is repeated once with reasoning disabled
 * (#839); on a model that writes its deliberation into the response instead, that repeat is stopped
 * with no answer begun (#893). A review makes several calls — batches, the summary — on up to two
 * models, so both can happen in one review and each is stated.
 *
 * @param ranWithReasoningDisabled a repeat with reasoning disabled ran to its end: the review, or
 *     part of it, was produced at less than the configured effort
 * @param repeatStopped a repeat was stopped because the model wrote its deliberation into the
 *     response with no answer begun, so the call it repeated delivered nothing
 */
public record ReasoningStepDown(boolean ranWithReasoningDisabled, boolean repeatStopped) {

  /** No call stepped down. */
  public static final ReasoningStepDown NONE = new ReasoningStepDown(false, false);
}
