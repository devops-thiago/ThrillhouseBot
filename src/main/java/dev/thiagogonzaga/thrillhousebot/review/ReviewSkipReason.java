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

/**
 * Structured reason code for why an automatic review was skipped. Every automatic skip path emits
 * one of these through {@link ReviewSkipEmitter} so operators can tell "the bot chose not to run"
 * apart from "the bot missed findings".
 */
public enum ReviewSkipReason {
  /** GitHub redelivered a webhook with an already-seen delivery id. */
  DUPLICATE_DELIVERY,
  /** The PR was silenced with {@code /pause}. */
  PAUSED,
  /** The PR is a draft and {@code webhook.triggers.skip-drafts} is enabled. */
  DRAFT,
  /** The base branch matches {@code webhook.triggers.ignored-base-branches}. */
  IGNORED_BASE_BRANCH,
  /** The base branch is not in the {@code webhook.triggers.base-branches} allowlist. */
  BASE_BRANCH_NOT_ALLOWED,
  /** The PR carries a label from {@code webhook.triggers.excluded-labels}. */
  EXCLUDED_LABEL,
  /** The PR has none of the {@code webhook.triggers.required-labels}. */
  MISSING_REQUIRED_LABEL,
  /** An automatic review completed within {@code review.auto-review-min-interval}. */
  RATE_LIMITED,
  /** The review executor rejected the task (saturated or shutting down). */
  DISPATCH_REJECTED
}
