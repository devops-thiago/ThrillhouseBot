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

import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Watches the reasoning step-down's repeat (#839) for a model that writes its deliberation into the
 * response once reasoning is disabled (#893).
 *
 * <p>The step-down repeats a call that stopped at its length cap with no content, on the premise
 * that the reasoning tail spent the allowance and a call without reasoning cannot. That holds where
 * the provider keeps reasoning in its own channel. A model that instead deliberates in {@code
 * content} when told not to reason charges the same deliberation to the same allowance: production
 * saw the repeat write 252,380 characters of prose before the answer opened, hit the cap again, and
 * the review was billed the full cap twice with nothing posted.
 *
 * <p>The two shapes differ from the first characters. Every call that reaches this path answers in
 * JSON and is told to write nothing else, so a model that answers opens an object with a quoted key
 * at once, or after a short lead-in or a code fence. A model deliberating in content writes prose.
 * Once the repeat's content reaches {@link #BOUND_CHARS} with no object opening anywhere in it, the
 * repeat is the inlined shape, and the first call has already shown that this call's deliberation
 * outruns the whole cap; letting it run on would only bill the cap a second time. The guard decides
 * once, at the bound, and never again for the call.
 *
 * <p>The test leans towards letting the repeat run. Anything that looks like an object with a
 * quoted key counts as the answer opening, so deliberation that quotes a JSON excerpt from the diff
 * before the bound is left alone and ends as the repeat always did; only content that plainly never
 * began an answer is stopped. The bound is a few thousand characters — about 2,000 tokens, a small
 * fraction of any review cap — so the price of detecting the shape is small beside the cap it
 * saves.
 */
final class InlinedDeliberationGuard {

  /**
   * How much content the repeat may produce without opening the answer before it is stopped. A
   * well-formed answer opens within its first few characters, and a lead-in sentence or a code
   * fence adds a few dozen more; 8,000 characters is far past either and still a small fraction of
   * a review's output allowance (the repeat in #893 ran to 256,302 characters before the cap cut
   * it).
   */
  static final int BOUND_CHARS = 8_000;

  /** An object that opens on a quoted key: how every answer on this path begins. */
  private static final Pattern ANSWER_OPENING = Pattern.compile("\\{\\s*\"[A-Za-z_]+\"\\s*:");

  private boolean decided;

  /** Whether the guard has judged the call; it judges once, and never again after that. */
  boolean decided() {
    return decided;
  }

  /**
   * Decides, once, whether the repeat's content so far is deliberation with no answer begun.
   * Returns the content's length when it has reached the bound without an object opening, and empty
   * otherwise — before the bound, and on every call after the guard has decided.
   *
   * @param contentSoFar the repeat's content so far, or {@code null} when it has not yet reached
   *     the bound
   */
  OptionalInt check(String contentSoFar) {
    if (decided || contentSoFar == null) {
      return OptionalInt.empty();
    }
    decided = true;
    return ANSWER_OPENING.matcher(contentSoFar).find()
        ? OptionalInt.empty()
        : OptionalInt.of(contentSoFar.length());
  }

  /**
   * The repeat was stopped because its content ran {@link #chars()} characters with no answer
   * begun. Raised on the stream and turned into the first call's truncation by {@link
   * AiReviewService}, which adds what the repeat showed to the report; it never leaves that class.
   */
  static final class Stopped extends AiReviewException {

    private final int chars;

    Stopped(int chars) {
      super(
          "The repeat with reasoning disabled wrote "
              + chars
              + " characters of deliberation into its response with no answer begun",
          1,
          null);
      this.chars = chars;
    }

    int chars() {
      return chars;
    }
  }
}
