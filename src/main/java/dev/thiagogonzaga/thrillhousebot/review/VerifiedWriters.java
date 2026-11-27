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

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The comment authors GitHub confirmed hold write access to the repository under review, resolved
 * once per review by {@link VerifiedWritersResolver}. Every step that lets a comment change a
 * finding's status reads this: a reply that answers a finding, a clear or decline directive, the
 * rebuttal the decline re-check weighs, and a decline the model reports as {@code justified}.
 *
 * <p>A comment's {@code author_association} is not enough on its own. {@code MEMBER} says the
 * author belongs to the organization and {@code COLLABORATOR} that they were invited to the
 * repository; either can hold read or triage access only. The association stays as a cheap first
 * check, so nobody outside those values is ever looked up, and the collaborator-permission answer
 * decides.
 *
 * <p>An author who is not in the set is not a writer, whatever the reason: no permission to act, a
 * lookup that failed, or a review that could not ask at all. That is the fail-closed direction: a
 * finding stays open until someone whose access was confirmed closes it.
 */
public final class VerifiedWriters {

  /**
   * GitHub {@code author_association} values a write-access holder can present. Any other value
   * provably lacks write access, since association precedence never reports a collaborator or
   * member as one of those.
   */
  private static final Set<String> WRITE_CAPABLE_ASSOCIATIONS =
      Set.of("OWNER", "MEMBER", "COLLABORATOR");

  private static final VerifiedWriters NONE = new VerifiedWriters(Set.of());

  private final Set<String> logins;

  private VerifiedWriters(Set<String> logins) {
    this.logins = logins;
  }

  /** Nobody confirmed: every clear, decline and reply gate stays shut. */
  public static VerifiedWriters none() {
    return NONE;
  }

  /** The given logins, each confirmed to hold write access; case does not matter. */
  public static VerifiedWriters of(Collection<String> logins) {
    var normalized =
        logins.stream()
            .filter(Objects::nonNull)
            .map(VerifiedWriters::normalize)
            .filter(login -> !login.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    return normalized.isEmpty() ? NONE : new VerifiedWriters(normalized);
  }

  /**
   * Whether a comment by {@code login} carrying {@code authorAssociation} is a maintainer's: the
   * association can hold write access and GitHub confirmed that the login does.
   */
  public boolean isWriter(String login, String authorAssociation) {
    return login != null
        && mayHoldWriteAccess(authorAssociation)
        && logins.contains(normalize(login));
  }

  /**
   * The cheap first check: whether {@code authorAssociation} can belong to someone with write
   * access. It never grants anything on its own; it only spares a permission lookup for an author
   * who cannot pass one.
   */
  static boolean mayHoldWriteAccess(String authorAssociation) {
    return authorAssociation != null
        && WRITE_CAPABLE_ASSOCIATIONS.contains(authorAssociation.strip().toUpperCase(Locale.ROOT));
  }

  private static String normalize(String login) {
    return login.strip().toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VerifiedWriters that && logins.equals(that.logins);
  }

  @Override
  public int hashCode() {
    return logins.hashCode();
  }

  @Override
  public String toString() {
    return "VerifiedWriters" + logins;
  }
}
