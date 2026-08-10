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
package dev.thiagogonzaga.thrillhousebot.github;

import java.util.Locale;

/**
 * Bounds an outgoing text field to the hard character limit GitHub enforces for it — {@value
 * #MAX_LENGTH} for a comment body, and its own per-field limit for anything else. GitHub rejects an
 * over-long issue-comment, review, inline-comment, reply body, or check-run output field with a
 * 422; that rejection is swallowed by the fail-soft posting wrapper, so an over-long comment posts
 * <em>nothing</em> and an over-long check-run output updates nothing — no output, no error. Capping
 * at the client boundary turns that silent total failure into a posted-but-truncated value carrying
 * an honest truncation notice.
 *
 * <p>Applied uniformly in the compact constructor of every text-bearing request record in {@link
 * GitHubCommentClient}, {@link GitHubReviewClient} and {@link GitHubCheckRunClient}, so every
 * poster is protected the same way and a future post path inherits the guard for free.
 */
final class CommentBodyLimit {

  /**
   * GitHub's hard maximum comment-body length, in characters. A longer body is rejected with 422.
   */
  static final int MAX_LENGTH = 65_536;

  /**
   * Appended when a comment body is truncated. Self-explanatory so the maintainer learns the output
   * was cut rather than seeing a body that just stops. Its own length is reserved when truncating,
   * so the final string — notice included — never exceeds {@link #MAX_LENGTH}.
   */
  static final String TRUNCATION_NOTICE = truncationNotice(MAX_LENGTH);

  private CommentBodyLimit() {}

  /**
   * Returns {@code body} unchanged when it is {@code null} or already within {@link #MAX_LENGTH}
   * characters (byte-identical pass-through, including a body sitting exactly on the boundary);
   * otherwise truncates it so the result — with {@link #TRUNCATION_NOTICE} appended — is at most
   * {@link #MAX_LENGTH} characters. The cut never splits a UTF-16 surrogate pair.
   */
  static String cap(String body) {
    return cap(body, MAX_LENGTH);
  }

  /**
   * The same guard against an arbitrary {@code limit}, for fields GitHub bounds differently from a
   * comment body. Returns {@code value} unchanged when it is {@code null} or already within {@code
   * limit} characters; otherwise truncates it so the result — with a notice naming that limit
   * appended — is at most {@code limit} characters, again never splitting a surrogate pair.
   *
   * <p>{@code limit} is expected to be comfortably larger than the notice it produces (every call
   * site passes a GitHub limit of at least a few hundred characters); a limit shorter than its own
   * notice is a programming error, not a runtime condition to defend against.
   */
  static String cap(String value, int limit) {
    if (value == null || value.length() <= limit) {
      return value;
    }
    var notice = truncationNotice(limit);
    // The notice is far shorter than any limit passed here, so keep is well above zero; and value
    // is longer than limit to reach here, so charAt(keep - 1) is always in range.
    int keep = limit - notice.length();
    // Never leave a dangling high surrogate at the cut point — that would corrupt a code point.
    if (Character.isHighSurrogate(value.charAt(keep - 1))) {
      keep--;
    }
    return value.substring(0, keep) + notice;
  }

  /**
   * The notice for a given limit, naming the limit so a reader of the truncated value can tell
   * which GitHub boundary cut it. {@link Locale#ROOT} keeps the grouping separator a comma whatever
   * the host locale is, so the wording is stable.
   */
  private static String truncationNotice(int limit) {
    return "\n\n… (truncated at GitHub's "
        + String.format(Locale.ROOT, "%,d", limit)
        + "-character limit)";
  }
}
