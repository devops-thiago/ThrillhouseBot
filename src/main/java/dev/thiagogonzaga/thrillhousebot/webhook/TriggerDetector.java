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
import java.util.Locale;
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
   * GitHub {@code author_association} values that grant write access to the repository and are
   * therefore allowed to spend the operator's API budget on a manual review.
   */
  private static final Set<String> AUTHORIZED_ASSOCIATIONS =
      Set.of("OWNER", "MEMBER", "COLLABORATOR");

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

  /**
   * Checks whether a commenter is authorized to manually trigger a review based on their GitHub
   * {@code author_association}. Only owners, organization members, and collaborators (users with
   * write access) may run a manual review; everyone else — {@code CONTRIBUTOR}, {@code
   * FIRST_TIME_CONTRIBUTOR}, {@code NONE}, etc. — is rejected so that arbitrary users on public
   * repositories cannot spend the operator's API budget.
   */
  public boolean isAuthorizedToTrigger(String authorAssociation) {
    if (authorAssociation == null) return false;
    return AUTHORIZED_ASSOCIATIONS.contains(authorAssociation.trim().toUpperCase(Locale.ROOT));
  }
}
