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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * The opening of a review body that says whether anything in it blocks the merge (#1542): for a
 * change request, how many findings block, which ones and how to clear them; otherwise that none
 * does.
 *
 * <p>It is the bot's own prose between two hidden markers, so a later round drops it from a review
 * body before reading that body as previous findings (#455). Only a block that opens the body is
 * dropped, which is where the bot puts it.
 */
final class VerdictDigest {

  static final String START = "<!-- thrillhousebot:section=verdict -->";

  static final String END = "<!-- thrillhousebot:section-end -->";

  /** How many blocking findings are named before the rest are given as a count. */
  static final int NAMED_LIMIT = 10;

  private static final String CLEAR_ON_THREAD =
      "To clear a finding, push a fix, or reply on its thread with the reason for declining it.";

  private static final String CLEAR_WITHOUT_THREAD =
      " For one with no thread, comment `@thrillhousebot declined <path>:<line> — <title>` on the"
          + " pull request, with the reason on the next line.";

  private VerdictDigest() {}

  /**
   * The findings that make the review a change request: the set the verdict is computed over, this
   * round's findings and every earlier one still open, most severe first.
   */
  static List<Finding> blocking(
      List<Finding> raised, List<Finding> openPrevious, BlockingStrictness strictness) {
    var thisRound = new HashSet<>(raised);
    return Stream.concat(
            raised.stream(), openPrevious.stream().filter(open -> !thisRound.contains(open)))
        .filter(strictness::isBlocking)
        .sorted(Comparator.comparing(Finding::risk))
        .toList();
  }

  /** The opening of a change-request review whose blocking findings are {@code blocking}. */
  static String changesRequested(List<Finding> blocking, OpenFindingNames.Threads threads) {
    if (blocking.isEmpty()) {
      return wrap("**Changes requested.** See the inline comments on the diff.");
    }
    var count = blocking.size();
    var text =
        new StringBuilder("**Changes requested: ")
            .append(count)
            .append(count == 1 ? " finding blocks" : " findings block")
            .append(" this merge.**\n\n");
    var threadless = false;
    var rows = new ArrayList<String>();
    for (var finding : blocking) {
      var reference = OpenFindingNames.threadReference(finding, threads);
      threadless |= reference.equals(OpenFindingNames.NO_THREAD_MARK);
      rows.add(
          "- **"
              + finding.risk().name()
              + "** "
              + ClosedFindingNames.describe(finding.file(), finding.line(), finding.title())
              + reference
              + "\n");
    }
    rows.stream().limit(NAMED_LIMIT).forEach(text::append);
    if (count > NAMED_LIMIT) {
      text.append("- …and ").append(count - NAMED_LIMIT).append(" more\n");
    }
    text.append('\n').append(CLEAR_ON_THREAD);
    if (threadless) {
      text.append(CLEAR_WITHOUT_THREAD);
    }
    return wrap(text.toString());
  }

  /**
   * The opening of a comment review. {@code changeRequestStands} adds that an earlier change
   * request of the bot's is still in force, since GitHub keeps one standing under a later comment
   * review.
   */
  static String nothingBlocks(boolean changeRequestStands) {
    var text = "**Nothing in this review blocks the merge.**";
    return wrap(
        changeRequestStands
            ? text
                + " The earlier change request stays until the bot approves or someone dismisses"
                + " it."
            : text);
  }

  /**
   * {@code section} folded under a "not blocking" label when none of the {@code listed} findings
   * blocks, and unchanged when one does.
   */
  static String foldUnlessBlocking(
      String section, List<Finding> listed, BlockingStrictness strictness) {
    if (listed.stream().anyMatch(strictness::isBlocking)) {
      return section;
    }
    return "<details>\n<summary>Not blocking: "
        + listed.size()
        + (listed.size() == 1 ? " finding" : " findings")
        + " without an inline thread</summary>\n\n"
        + section.strip()
        + "\n\n</details>";
  }

  /** {@code body} without the verdict block that opens it, if one does. */
  static String without(String body) {
    var text = body.stripLeading();
    var end = text.startsWith(START) ? text.indexOf(END) : -1;
    return end < 0 ? body : text.substring(end + END.length()).stripLeading();
  }

  private static String wrap(String text) {
    return START + "\n" + text + "\n" + END;
  }
}
