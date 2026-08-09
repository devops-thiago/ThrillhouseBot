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

/**
 * Bounds an outgoing comment body to GitHub's hard {@value #MAX_LENGTH}-character limit. GitHub
 * rejects a longer issue-comment, review, inline-comment, or reply body with a 422; that rejection
 * is swallowed by the fail-soft posting wrapper, so an over-long comment posts <em>nothing</em> —
 * no comment, no error. Capping at the client boundary turns that silent total failure into a
 * posted-but-truncated comment carrying an honest {@link #TRUNCATION_NOTICE}.
 *
 * <p>Applied uniformly in the compact constructor of every body-bearing request record in {@link
 * GitHubCommentClient} and {@link GitHubReviewClient}, so every poster is protected the same way
 * and a future post path inherits the guard for free.
 */
final class CommentBodyLimit {

  /**
   * GitHub's hard maximum comment-body length, in characters. A longer body is rejected with 422.
   */
  static final int MAX_LENGTH = 65_536;

  /**
   * Appended when a body is truncated. Self-explanatory so the maintainer learns the output was cut
   * rather than seeing a body that just stops. Its own length is reserved when truncating, so the
   * final string — notice included — never exceeds {@link #MAX_LENGTH}.
   */
  static final String TRUNCATION_NOTICE = "\n\n… (truncated at GitHub's 65,536-character limit)";

  private CommentBodyLimit() {}

  /**
   * Returns {@code body} unchanged when it is {@code null} or already within {@link #MAX_LENGTH}
   * characters (byte-identical pass-through, including a body sitting exactly on the boundary);
   * otherwise truncates it so the result — with {@link #TRUNCATION_NOTICE} appended — is at most
   * {@link #MAX_LENGTH} characters. The cut never splits a UTF-16 surrogate pair.
   */
  static String cap(String body) {
    if (body == null || body.length() <= MAX_LENGTH) {
      return body;
    }
    int keep = MAX_LENGTH - TRUNCATION_NOTICE.length();
    // Never leave a dangling high surrogate at the cut point — that would corrupt a code point.
    if (keep > 0 && Character.isHighSurrogate(body.charAt(keep - 1))) {
      keep--;
    }
    return body.substring(0, keep) + TRUNCATION_NOTICE;
  }
}
