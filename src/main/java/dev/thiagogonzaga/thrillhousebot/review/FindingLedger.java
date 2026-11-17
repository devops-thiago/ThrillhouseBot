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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out a pull request's finding rows after a review round (#1319), without touching the
 * database: the rows the round adds or changes, and whether any previous finding's status moved.
 *
 * <p>Each row is one finding, keyed by the key its thread marker carries ({@link
 * SuggestionFormatter#findingKey}): the same file, line and title in a later round is the same
 * finding, so a re-raise updates its row instead of adding one. A round's own findings get a row
 * each, open, with the side of the diff its quote was on, whether the quote was there in that round
 * and the file's blob SHA at the round's head. The round's statuses on previous findings update
 * those findings' rows; a status is written only when it differs from the stored one. A previous
 * finding with no row yet (a pull request reviewed before the table existed) gets one from the
 * round, with nothing known about how it was anchored, so the checks fall back to what they did
 * before.
 */
final class FindingLedger {

  static final String OPEN = "open";
  static final String RESOLVED = "resolved";
  static final String JUSTIFIED = "justified";
  static final String SUPERSEDED = "superseded";

  /**
   * A "Things to double-check" item this round raised again as a finding of its own, which carries
   * it from then on (#969, #1312). That finding usually comes back under another key, so this
   * item's row would otherwise stay open beside the replacement's until the pull request closed.
   */
  static final String REPLACED = "replaced";

  private static final Set<String> CLOSED = Set.of(RESOLVED, JUSTIFIED, SUPERSEDED);

  private FindingLedger() {}

  /**
   * What a finding row says about its finding.
   *
   * @param file the file, as the diff spells it
   * @param line the head-side line the marker key was computed over
   * @param quote the code the finding quotes ({@code suggestion_old}); {@code null} when none
   */
  record Subject(
      String file, int line, String risk, String confidence, String title, String quote) {

    static Subject of(ReviewResponse.Finding finding) {
      return new Subject(
          finding.file(),
          finding.line(),
          finding.risk(),
          finding.confidence(),
          finding.title(),
          finding.suggestionOld());
    }

    static Subject of(Finding finding) {
      return new Subject(
          finding.file(),
          finding.line(),
          finding.risk() == null ? null : finding.risk().name().toLowerCase(Locale.ROOT),
          finding.confidence().name().toLowerCase(Locale.ROOT),
          finding.title(),
          finding.suggestionOld());
    }

    String key() {
      return SuggestionFormatter.findingKey(file, line, title);
    }
  }

  /**
   * A finding's status and where it was set.
   *
   * @param value {@link #OPEN}, {@link #RESOLVED}, {@link #JUSTIFIED} or {@link #SUPERSEDED}
   * @param raisedHead the head of the round that raised the finding; {@code null} when not known
   * @param changedHead the head of the round that last changed the status
   */
  record Status(String value, String raisedHead, String changedHead) {}

  /**
   * The finding's inline thread.
   *
   * @param id the thread's root comment id; {@code null} while the finding has no thread (a "Things
   *     to double-check" item, or a comment GitHub refused)
   * @param head the commit the thread was posted on, the head of the round that posted it
   */
  record Thread(Long id, String head) {
    static final Thread NONE = new Thread(null, null);
  }

  /**
   * One finding's row.
   *
   * @param anchor how the finding was anchored in the round that raised it; {@code null} when not
   *     known
   * @param blobSha the blob SHA of its file at the head of the round that raised it; {@code null}
   *     when not known
   */
  record Row(
      String key,
      Subject subject,
      DiffLineResolver.RaisedAnchor anchor,
      String blobSha,
      Status status,
      Thread thread) {

    Row withStatus(String value, String head) {
      return new Row(
          key, subject, anchor, blobSha, new Status(value, status.raisedHead(), head), thread);
    }

    Row withThread(Thread newThread) {
      return new Row(key, subject, anchor, blobSha, status, newThread);
    }
  }

  /**
   * What a round tells the ledger.
   *
   * @param head the head commit the round reviewed
   * @param raised the round's own findings, as posted
   * @param statuses each previous finding's status this round, by key ({@link #statuses})
   * @param blobs each changed file's blob SHA at {@code head}
   * @param lineResolver the round's diff, for the side and presence of each new finding's quote
   * @param threads each finding's thread found on the pull request, by key ({@link #threads})
   */
  record Round(
      String head,
      List<Finding> raised,
      Map<String, Reported> statuses,
      Map<String, String> blobs,
      DiffLineResolver lineResolver,
      Map<String, Thread> threads) {}

  /** A previous finding and the status a round gave it. */
  record Reported(Subject subject, String status) {}

  /**
   * The rows a round adds or changes.
   *
   * @param rows the new and changed rows, by key
   * @param statusMoved whether a previous finding's status differs from its stored one, or a
   *     finding the round gave a status had no row to compare with
   */
  record Update(Map<String, Row> rows, boolean statusMoved) {}

  /** The rows {@code round} adds to or changes in {@code stored}. */
  static Update afterRound(Map<String, Row> stored, Round round) {
    var changed = new LinkedHashMap<String, Row>();
    var moved = false;
    for (var entry : round.statuses().entrySet()) {
      var row = stored.get(entry.getKey());
      var status = entry.getValue().status();
      if (row == null) {
        moved = true;
        changed.put(
            entry.getKey(),
            new Row(
                entry.getKey(),
                entry.getValue().subject(),
                null,
                null,
                new Status(status, null, round.head()),
                Thread.NONE));
      } else if (!row.status().value().equals(status)) {
        moved = true;
        changed.put(entry.getKey(), row.withStatus(status, round.head()));
      }
    }
    for (var finding : round.raised()) {
      var subject = Subject.of(finding);
      var key = subject.key();
      var row = changed.getOrDefault(key, stored.get(key));
      if (row == null) {
        changed.put(key, raisedRow(key, subject, round));
      } else if (!OPEN.equals(row.status().value())) {
        changed.put(key, row.withStatus(OPEN, round.head()));
      }
    }
    attachThreads(stored, changed, round.threads());
    return new Update(changed, moved);
  }

  private static Row raisedRow(String key, Subject subject, Round round) {
    return new Row(
        key,
        subject,
        raisedAnchor(subject, round.lineResolver()),
        subject.file() == null ? null : round.blobs().get(subject.file()),
        new Status(OPEN, round.head(), round.head()),
        Thread.NONE);
  }

  /** Gives a row with no thread yet the thread found for its key. */
  private static void attachThreads(
      Map<String, Row> stored, Map<String, Row> changed, Map<String, Thread> threads) {
    for (var thread : threads.entrySet()) {
      var row = changed.getOrDefault(thread.getKey(), stored.get(thread.getKey()));
      if (row != null && row.thread().id() == null) {
        changed.put(thread.getKey(), row.withThread(thread.getValue()));
      }
    }
  }

  /**
   * The side of the diff a new finding's quote is on and whether it is there: the base side when
   * the quote is nowhere on the head side but among the lines the pull request removes, as the
   * publisher posts it (#1321).
   */
  static DiffLineResolver.RaisedAnchor raisedAnchor(
      Subject subject, DiffLineResolver lineResolver) {
    var quote = subject.quote();
    var side = DiffLineResolver.Side.RIGHT;
    if (quote != null
        && !quote.isBlank()
        && lineResolver.locateQuote(subject.file(), quote, subject.line()).isEmpty()
        && lineResolver.locateRemovedQuote(subject.file(), quote, subject.line()).isPresent()) {
      side = DiffLineResolver.Side.LEFT;
    }
    return new DiffLineResolver.RaisedAnchor(
        side, subject.file() != null && lineResolver.isFindingPresent(subject.file(), quote, side));
  }

  /**
   * Each previous finding's status this round, by key: the effective previous round's findings by
   * their id in {@code previous}, an earlier round's by the copies its status accounts for. A
   * status that closes a finding wins over one that holds another copy of it open.
   */
  static Map<String, Reported> statuses(
      List<ReviewResult.PreviousFindingStatus> statuses, List<ReviewResponse.Finding> previous) {
    var byKey = new LinkedHashMap<String, Reported>();
    for (var status : statuses) {
      var value = rowStatus(status.status(), status.note());
      if (value.isEmpty()) {
        continue;
      }
      if (status.fromEarlierRound()) {
        status.earlierRound().members().forEach(member -> put(byKey, member, value.get()));
      } else if (status.id() >= 1 && status.id() <= previous.size()) {
        put(byKey, previous.get(status.id() - 1), value.get());
      }
    }
    return byKey;
  }

  private static void put(
      Map<String, Reported> byKey, ReviewResponse.Finding finding, String status) {
    var subject = Subject.of(finding);
    var key = subject.key();
    var current = byKey.get(key);
    if (current == null || OPEN.equals(current.status())) {
      byKey.put(key, new Reported(subject, status));
    }
  }

  /**
   * As {@link #rowStatus(String)}, except that an item held open as replaced by this round's own
   * finding ({@link FollowUpAnalyzer#REPLACED_NOTE}) is {@link #REPLACED}: its replacement's row is
   * the open one (#1312).
   */
  static Optional<String> rowStatus(String status, String note) {
    var value = rowStatus(status);
    return value.isPresent()
            && OPEN.equals(value.get())
            && FollowUpAnalyzer.REPLACED_NOTE.equals(note)
        ? Optional.of(REPLACED)
        : value;
  }

  /** The row status a round's status sets, or empty for one the ledger does not know. */
  static Optional<String> rowStatus(String status) {
    if (status == null) {
      return Optional.empty();
    }
    var value = status.toLowerCase(Locale.ROOT);
    if ("unresolved".equals(value)) {
      return Optional.of(OPEN);
    }
    return CLOSED.contains(value) ? Optional.of(value) : Optional.empty();
  }

  /**
   * The bot's own threads on the pull request, by the key their marker carries: the oldest thread
   * for a key, at the commit it was posted on, or at {@code head} when GitHub gives none.
   */
  static Map<String, Thread> threads(
      List<GitHubReviewClient.PullRequestComment> comments, BotIdentity bot, String head) {
    var byKey = new HashMap<String, Thread>();
    for (var comment : comments) {
      if (comment.inReplyToId() != null
          || comment.user() == null
          || !bot.matches(comment.user().login())) {
        continue;
      }
      SuggestionFormatter.parseFindingKey(comment.body())
          .ifPresent(
              key ->
                  byKey.merge(
                      key,
                      new Thread(
                          comment.id(),
                          comment.originalCommitId() == null ? head : comment.originalCommitId()),
                      (first, later) -> first.id() <= later.id() ? first : later));
    }
    return byKey;
  }
}
