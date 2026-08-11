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

import java.util.Optional;

/** The one bounded cause-chain walk shared by every layer that reacts to a wrapped failure. */
public final class Throwables {

  /** Cause-chain links inspected before giving up, so a cyclic chain cannot spin. */
  private static final int MAX_CAUSE_DEPTH = 16;

  private Throwables() {}

  /**
   * The first throwable of the given type in a failure's cause chain — the failure itself included
   * — or empty when none is found within the bound.
   *
   * <p>Bounded rather than walked to {@code null}: a cause chain can cycle — a {@link Throwable}
   * overriding {@code getCause()}, or plain {@code A caused-by B caused-by A} — and an unbounded
   * walk would spin forever on the review thread. The bound is far above any real chain (the
   * failure arrives wrapped at depth 2 in practice), so a match is never missed for depth.
   */
  public static <T extends Throwable> Optional<T> findCause(Throwable failure, Class<T> type) {
    var cause = failure;
    for (var depth = 0;
        cause != null && depth < MAX_CAUSE_DEPTH;
        depth++, cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return Optional.of(type.cast(cause));
      }
    }
    return Optional.empty();
  }
}
