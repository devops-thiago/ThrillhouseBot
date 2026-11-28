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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.CommentVisibilityService;
import dev.thiagogonzaga.thrillhousebot.github.CommentVisibilityService.Entry;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLException;
import dev.thiagogonzaga.thrillhousebot.github.GitHubWriteBudget;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Minimizes the bot's superseded output on a pull request once a review is posted (#1441), so the
 * conversation shows the current round. Nothing is deleted. Only the bot's own comments and reviews
 * are touched, and these stay visible: the newest summary comment, the newest review, a request for
 * changes that still stands, and the answers to what a user asked for.
 */
@ApplicationScoped
public class OutdatedOutputHider {

  /** Openings the writers of a notice build it from, so a reword cannot stop the hiding. */
  public static final String REVIEW_FAILED_OPENING =
      "⚠️ **ThrillhouseBot review could not be completed.**";

  public static final String PAUSE_OPENING = "⏸️ ThrillhouseBot is ";
  public static final String RESUMED_NOTICE = "▶️ ThrillhouseBot resumed on this PR.";
  public static final String NOT_PAUSED_NOTICE = "ThrillhouseBot was not paused on this PR.";
  public static final String RESOLVED_OPENING = "✅ Resolved ";
  public static final String NOTHING_TO_RESOLVE_NOTICE =
      "No open ThrillhouseBot finding threads to resolve.";
  public static final String FINDING_THREADS = " ThrillhouseBot finding thread(s)";
  public static final String LEFT_OPEN_OPENING = "⚠️ ";
  public static final String NO_OUTPUT_OPENING = "🤖 ThrillhouseBot has no `";
  public static final String DISABLED_OPENING = "🚫 `";
  public static final String NEXT_REVIEW_OPENING = "The next review will ";
  public static final String NO_OPEN_FINDING_OPENING =
      "This comment names no open finding on record";

  private static final Pattern THREADS_LEFT_OPEN =
      Pattern.compile("^" + LEFT_OPEN_OPENING + "\\d+" + Pattern.quote(FINDING_THREADS));

  /**
   * How the bot's short status notices and command acknowledgements open. They say what happened to
   * one round or command, which a later review supersedes. Answers carrying content someone asked
   * for ({@code /help}, {@code /describe}, a reply to a question) are not listed.
   */
  private static final List<String> NOTICE_OPENINGS =
      List.of(
          REVIEW_FAILED_OPENING,
          "⚠️ GitHub does not let this App resolve review threads",
          "⚠️ GitHub did not return this pull request's review threads",
          PAUSE_OPENING,
          RESUMED_NOTICE,
          NOT_PAUSED_NOTICE,
          "🔒 This pull request is closed or merged",
          "📋 A ThrillhouseBot summary is already on this PR",
          RESOLVED_OPENING,
          NOTHING_TO_RESOLVE_NOTICE,
          NO_OUTPUT_OPENING,
          DISABLED_OPENING,
          NEXT_REVIEW_OPENING,
          NO_OPEN_FINDING_OPENING,
          "That comment names no finding",
          "A line number followed by a spaced separator",
          "Clearing a finding takes write access",
          "Declining a finding takes write access");

  private final ThrillhouseConfig config;
  private final CommentVisibilityService visibility;
  private final BotIdentity botIdentity;

  public OutdatedOutputHider(
      ThrillhouseConfig config, CommentVisibilityService visibility, BotIdentity botIdentity) {
    this.config = config;
    this.visibility = visibility;
    this.botIdentity = botIdentity;
  }

  /**
   * Hides what the review that started at {@code startedAt} superseded. A notice posted since then
   * may answer something said while it ran, so it is left for the next round. {@code
   * refusedThreadRoots} are the root comments of addressed threads GitHub would not resolve. A
   * review body that is the newest to describe one of {@code stillOpen} in full stays, since a
   * later round names that finding by title only. Best-effort: a refusal is logged with GitHub's
   * reason.
   */
  void hide(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      Instant startedAt,
      List<String> refusedThreadRoots,
      List<Finding> stillOpen) {
    if (!enabled()) {
      return;
    }
    GitHubWriteBudget.within(
        req.owner() + "/" + req.repo() + " #" + req.prNumber(),
        config.github().writeRetryBudget(),
        () -> minimize(auth, req, targets(auth, req, startedAt, refusedThreadRoots, stillOpen)));
  }

  private boolean enabled() {
    var review = config.review();
    if (review == null) {
      return false;
    }
    var hideOutdated = review.hideOutdated();
    return hideOutdated != null && hideOutdated.enabled();
  }

  private LinkedHashSet<String> targets(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      Instant startedAt,
      List<String> refusedThreadRoots,
      List<Finding> stillOpen) {
    var comments = ownBy(visibility.comments(auth, req.owner(), req.repo(), req.prNumber()));
    var reviews =
        ownBy(visibility.reviews(auth, req.owner(), req.repo(), req.prNumber())).stream()
            .filter(r -> !"PENDING".equals(r.state()))
            .toList();
    var targets = new LinkedHashSet<String>();
    var summaries =
        comments.stream().filter(c -> ReviewContextLoader.isBotSummaryComment(c.body())).toList();
    summaries.stream().limit(Math.max(0, summaries.size() - 1)).forEach(c -> add(targets, c));
    comments.stream()
        .filter(c -> c.createdAt().isBefore(startedAt) && isNotice(c.body()))
        .forEach(c -> add(targets, c));
    var keep = newestFullTextOf(stillOpen, reviews, comments);
    Stream.concat(
            supersededReviews(reviews).stream(),
            supersededRefusedReviews(comments, reviews).stream())
        .filter(e -> !keep.contains(e.id()))
        .forEach(e -> add(targets, e));
    targets.addAll(refusedThreadRoots);
    return targets;
  }

  private List<Entry> ownBy(List<Entry> entries) {
    return entries.stream().filter(e -> botIdentity.matches(e.login())).toList();
  }

  private static void add(LinkedHashSet<String> targets, Entry entry) {
    if (!entry.minimized()) {
      targets.add(entry.id());
    }
  }

  /** Whether {@code body} opens as one of the bot's status notices or acknowledgements. */
  public static boolean isNotice(String body) {
    return NOTICE_OPENINGS.stream().anyMatch(body::startsWith)
        || (body.startsWith("A review of `") && body.contains("covers this `/review`"))
        || THREADS_LEFT_OPEN.matcher(body).find();
  }

  /**
   * Every review with a body except the newest and a standing request for changes. A bodiless
   * comment review is what GitHub files a thread reply under, so it supersedes nothing.
   */
  private static List<Entry> supersededReviews(List<Entry> reviews) {
    var newest =
        reviews.stream()
            .filter(r -> !"COMMENTED".equals(r.state()) || !r.body().isBlank())
            .reduce((first, second) -> second)
            .orElse(null);
    var standing =
        reviews.stream()
            .filter(r -> "APPROVED".equals(r.state()) || "CHANGES_REQUESTED".equals(r.state()))
            .reduce((first, second) -> second)
            .filter(r -> "CHANGES_REQUESTED".equals(r.state()))
            .orElse(null);
    return reviews.stream()
        .filter(r -> !r.body().isBlank())
        .filter(r -> !Objects.equals(r, newest) && !Objects.equals(r, standing))
        .toList();
  }

  /** For each finding, the newest review body that describes it in full. */
  private static Set<String> newestFullTextOf(
      List<Finding> findings, List<Entry> reviews, List<Entry> comments) {
    var kept = new HashSet<String>();
    var candidates = Stream.concat(reviews.stream(), refusedReviews(comments).stream()).toList();
    for (var finding : PromptLabelScrubber.scrubFindings(findings)) {
      var listing = ReviewPublisher.listingKey(finding);
      candidates.stream()
          .filter(e -> e.body().contains(listing))
          .max(Comparator.comparing(Entry::createdAt))
          .ifPresent(e -> kept.add(e.id()));
    }
    return kept;
  }

  private static List<Entry> refusedReviews(List<Entry> comments) {
    return comments.stream()
        .filter(c -> c.body().startsWith(ReviewPublisher.REVIEW_REFUSED_NOTE))
        .toList();
  }

  /** Review bodies GitHub refused as reviews and that something newer has since replaced. */
  private static List<Entry> supersededRefusedReviews(List<Entry> comments, List<Entry> reviews) {
    var refused = refusedReviews(comments);
    var newestReplacement =
        Stream.concat(refused.stream(), reviews.stream().filter(r -> !r.body().isBlank()))
            .map(Entry::createdAt)
            .max(Comparator.naturalOrder())
            .orElse(Instant.MIN);
    return refused.stream().filter(c -> c.createdAt().isBefore(newestReplacement)).toList();
  }

  private void minimize(
      String auth, ReviewOrchestrator.ReviewRequest req, LinkedHashSet<String> targets) {
    var hidden = new ArrayList<String>();
    try {
      for (var id : targets) {
        try {
          visibility.minimize(auth, id);
          hidden.add(id);
        } catch (GitHubGraphQLException e) {
          Log.warnf(
              "GitHub refused to minimize %s on %s/%s #%d: %s",
              id, req.owner(), req.repo(), req.prNumber(), e.refusal());
          if (e.isNotPermitted()) {
            break;
          }
        }
      }
    } finally {
      if (!hidden.isEmpty()) {
        Log.infof(
            "Minimized %d outdated comment(s) on %s/%s #%d: %s",
            hidden.size(), req.owner(), req.repo(), req.prNumber(), String.join(", ", hidden));
      }
    }
  }
}
