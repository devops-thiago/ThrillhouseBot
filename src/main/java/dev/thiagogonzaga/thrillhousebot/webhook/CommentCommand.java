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
package dev.thiagogonzaga.thrillhousebot.webhook;

/**
 * A command parsed from a PR comment. {@link #NONE} means the comment carried no recognized
 * command. Detected by {@link TriggerDetector#detectCommand(String)} and routed by {@link
 * WebhookController}.
 */
public enum CommentCommand {
  /** No recognized command in the comment. */
  NONE,
  /** Run (or re-run) a full review. */
  REVIEW,
  /** List the available commands. */
  HELP,
  /** Regenerate the PR summary (only when none has been posted yet). */
  SUMMARY,
  /** Suggest an improved PR title and description generated from the diff. */
  DESCRIBE,
  /** Generate docstrings/inline docs for changed symbols as committable suggestions. */
  ADD_DOCS,
  /** Resolve the bot's outstanding finding threads on the PR. */
  RESOLVE,
  /** Silence the bot on the PR until {@link #RESUME}. */
  PAUSE,
  /** Re-enable the bot on a paused PR. */
  RESUME
}
