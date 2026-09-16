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
 * The character budget one review spends on everything it attaches to its candidate findings for
 * the verifier — the resolved cited location (#650) and the context material a finding rests on
 * (#475).
 *
 * <p>One budget for all of it, held by the review and shared by every resolver that attaches
 * something. Each attached field is a small deterministic addition on its own; a per-resolver cap
 * would let their sum grow with the number of resolvers until this material rivalled the diff the
 * verifier is supposed to be reading. Anything that no longer fits is simply not attached, which is
 * the behaviour the verifier had before any of it existed.
 */
public final class EvidenceBudget {

  /** Character cap on one finding's note. */
  static final int MAX_NOTE_CHARS = 1_200;

  /** Character cap on everything one round attaches, so this evidence never rivals the diff. */
  static final int MAX_TOTAL_CHARS = 6_000;

  /** Characters attached so far, read and written only under {@link #monitor}. */
  private int charsAttached;

  private final Object monitor = new Object();

  /**
   * The note bounded to its own cap and charged to the budget, or {@code null} when what is left no
   * longer fits it.
   */
  String attach(String note) {
    var bounded =
        note.length() > MAX_NOTE_CHARS
            ? ConfigKeyContextResolver.truncate(note, MAX_NOTE_CHARS)
            : note;
    return reserve(bounded.length()) ? bounded : null;
  }

  /**
   * Takes {@code chars} of the budget, or nothing at all when they do not fit. Charging a note
   * before deciding left the counter carrying notes that were dropped, so the first overflow closed
   * the budget for the rest of the round and a note of any size after it was dropped while the real
   * capacity sat unused (#866 review). Batches resolve on their own threads, so the read and the
   * write are one critical section: an add followed by a refund would let a sibling batch see the
   * inflated total in between and drop a note that fits. The section is two arithmetic operations
   * over a handful of notes per round, so the monitor costs nothing measurable and keeps the
   * decision to one branch a test can reach.
   */
  private boolean reserve(int chars) {
    synchronized (monitor) {
      if (charsAttached + chars > MAX_TOTAL_CHARS) {
        return false;
      }
      charsAttached += chars;
      return true;
    }
  }
}
