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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class TriggerDetector {

  private static final Set<Pattern> TRIGGER_PATTERNS =
      Set.of(
          Pattern.compile(
              ".*(?:^|\\s)/review(?:\\s|$).*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
          Pattern.compile(
              ".*@thrillhousebot\\s+review\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));

  /**
   * Checks whether a comment body contains a review trigger keyword. Triggers: "/review" or
   * "@Thrillhousebot review"
   */
  public boolean isReviewTrigger(String commentBody) {
    if (commentBody == null || commentBody.isBlank()) {
      return false;
    }
    return TRIGGER_PATTERNS.stream().anyMatch(p -> p.matcher(commentBody).matches());
  }

  /** Checks whether the comment author is the bot itself. Prevents infinite review loops. */
  public boolean isBotComment(String authorLogin) {
    if (authorLogin == null) return false;
    return ("thrillhousebot[bot]".equalsIgnoreCase(authorLogin)
        || "thrillhouse-bot[bot]".equalsIgnoreCase(authorLogin));
  }
}
