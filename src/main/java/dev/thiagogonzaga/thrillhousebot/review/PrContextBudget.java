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

import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import io.quarkus.logging.Log;

/**
 * Bounds the PR title-and-description block to a share of the per-call input budget before it is
 * sent on a call that does no other budget arithmetic — today the finding-verification call (#736).
 *
 * <p>The block is author-authored, untrusted, and unbounded at its source: GitHub accepts a 65,536
 * character description, which measures 34% of the shipped 35,008-token per-call budget as ordinary
 * prose and roughly 141% of it as dense text. The verifier call carries it on top of a diff that
 * was already sized to fill the budget, so a long enough description pushes the request past the
 * model's context window. That fails open — the round keeps its unverified findings and reports
 * NONE coverage — which is exactly the problem: the author of a pull request can switch
 * verification off for their own pull request, deterministically, by writing a large enough
 * description.
 *
 * <p>This is the same treatment {@code DiffBudgetPlanner#boundPreviousFindings} gives the other
 * untrusted, author-influenced overhead section, and it degrades the same way: a prefix survives, a
 * disclosure the model can read replaces what was cut, and the operator gets a {@code WARN} naming
 * the sizes. Only the mechanism differs — a description has no entry structure to condense to, so
 * what is kept is the longest prefix that fits rather than one line per entry.
 */
final class PrContextBudget {

  private PrContextBudget() {}

  /**
   * Share of the per-call input budget the PR context may occupy on the verifier call. A tenth of
   * the shipped budget is ~3,500 tokens — around 14,000 characters of prose, which holds any real
   * description with room to spare — while leaving the diff, the candidate findings and the
   * previous findings the rest of the call. Deliberately tighter than the previous-findings share:
   * that block is the review's own accumulated state and condensing it costs the model the prose it
   * judges a finding resolved by, whereas the description is a fixed-purpose section whose tail is
   * the least load-bearing text in the prompt.
   */
  private static final double BUDGET_SHARE = 0.10;

  /**
   * The disclosure appended after the cut. It sits outside the untrusted fence, so it reads as
   * instruction rather than as more author-supplied data, and it forbids the one inference the cut
   * would otherwise invite: the verifier is told the description is provided precisely so a
   * description-versus-code candidate is checkable against it (#711), so a candidate about text
   * that was cut has to come back unverifiable rather than refuted.
   */
  static final String TRUNCATION_NOTICE =
      "(The pull request description above was truncated to fit this call's token budget; the rest"
          + " of it is not shown. Text you cannot see here is not thereby absent from the"
          + " description: judge a candidate that turns on what the description says only from the"
          + " part shown, and leave it unverified rather than rejected when the part it refers to"
          + " was cut.)";

  /**
   * The block to send, bounded to {@link #BUDGET_SHARE} of {@code perCallInputBudget} and disclosed
   * when the bound bites. Returns the very same instance when it already fits, so the common case
   * costs one token estimate and nothing else.
   *
   * <p>Budgeting being disabled needs no special case: the planner reports {@link
   * Integer#MAX_VALUE} for it, whose tenth is a cap no description of any size can reach, so the
   * operator's explicit no-cap choice is honored by the arithmetic itself. The cap floors at zero
   * for the same reason — a budget too small to have a share leaves an absent or empty block
   * untouched instead of replacing it with a notice about nothing.
   */
  static String bound(String prContext, int perCallInputBudget, TokenCounter tokenCounter) {
    var cap = Math.max(0, (int) (perCallInputBudget * BUDGET_SHARE));
    var before = tokenCounter.estimateTokens(prContext);
    if (before <= cap) {
      return prContext;
    }
    var bounded = clip(prContext, cap, tokenCounter);
    Log.warnf(
        "PR description context (%d tokens) exceeds its %d-token share of the per-call input"
            + " budget; sending the first %d tokens of it with the truncation disclosed",
        before, cap, tokenCounter.estimateTokens(bounded));
    return bounded;
  }

  /**
   * Cuts the block to {@code capTokens}, keeping the untrusted fence intact around what survives.
   * The fence lines are structural rather than content: carrying them across the cut is what keeps
   * the region from ending unterminated with the disclosure — our own instruction — swallowed
   * inside it. The cut itself lands wherever the budget runs out, since a description has no entry
   * or line structure the model relies on, and the disclosure says so.
   */
  private static String clip(String prContext, int capTokens, TokenCounter tokenCounter) {
    var lines = prContext.split("\n", -1);
    var fenced = lines.length > 2 && lines[0].equals(lines[lines.length - 1]);
    var open = fenced ? lines[0] + "\n" : "";
    var close = fenced ? "\n" + lines[0] : "";
    var body = prContext.substring(open.length(), prContext.length() - close.length());
    // The disclosure has to survive the cap that forced it, so its cost — and the fences' — comes
    // off the top rather than being what gets dropped. Each part is measured as it will appear,
    // separated by the surviving prose: measuring them joined would undercount by whatever the
    // tokenizer merges across a seam the result does not have.
    var reserve =
        tokenCounter.estimateTokens(open)
            + tokenCounter.estimateTokens(close)
            + tokenCounter.estimateTokens("\n" + TRUNCATION_NOTICE);
    return open
        + longestPrefixWithin(body, capTokens - reserve, tokenCounter)
        + close
        + "\n"
        + TRUNCATION_NOTICE;
  }

  /**
   * The longest prefix of {@code text} whose estimated cost stays within {@code capTokens}, found
   * by bisection over code points so the cut can never split a surrogate pair. Every candidate the
   * search accepts was measured, so the result is within the cap whatever the estimator does at the
   * boundary; a cap of zero or less yields the empty prefix.
   */
  private static String longestPrefixWithin(String text, int capTokens, TokenCounter tokenCounter) {
    var low = 0;
    var high = text.codePointCount(0, text.length());
    while (low < high) {
      var mid = (low + high + 1) >>> 1;
      if (tokenCounter.estimateTokens(prefix(text, mid)) <= capTokens) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return prefix(text, low);
  }

  /** The first {@code codePoints} code points of {@code text}. */
  private static String prefix(String text, int codePoints) {
    return text.substring(0, text.offsetByCodePoints(0, codePoints));
  }
}
