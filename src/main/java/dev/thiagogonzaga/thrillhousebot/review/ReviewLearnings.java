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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Cross-review learnings (#38): what the review remembers of a repository's maintainers, and the
 * three places that memory is touched.
 *
 * <ul>
 *   <li><b>Capture</b> ({@link #captureSurvivingDeclines}) runs after a review is posted. A prior
 *       finding the round recorded {@code justified}, whose decline passes {@link
 *       FollowUpAnalyzer#survivingDeclines} — the #169 re-check plus the stricter bar a durable
 *       memory needs — and whose author holds write access, is stored with its reason.
 *   <li><b>Recall</b> ({@link #promptSection}) runs before the review call. The active learnings of
 *       the same installation and repository are ranked by how close their file is to the files the
 *       pull request changes, and the best ones, bounded in count and characters, ride the review
 *       call's trailing guidance as a fenced list.
 *   <li><b>Governance</b> ({@link #rememberConvention}, and the list/retract reads the comment
 *       commands and dashboard use) lets a maintainer add, see and take back what is remembered.
 * </ul>
 *
 * <p>Everything here is best-effort: a database or GitHub failure is logged and the review goes on
 * exactly as it would with the feature off.
 */
@ApplicationScoped
public class ReviewLearnings {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final Set<String> WRITE_PERMISSIONS = Set.of("admin", "maintain", "write");
  private static final String STATUS_JUSTIFIED = "justified";

  /** The switches and caps, from {@link ThrillhouseConfig.LearningsConfig}. */
  record Settings(boolean enabled, int maxPerRepo, int promptMaxItems, int promptMaxChars) {}

  private final ReviewLearningService store;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final BotIdentity botIdentity;
  private final GitHubInstallationClient installationClient;
  private final Settings settings;

  @Inject
  public ReviewLearnings(
      ReviewLearningService store,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity,
      @RestClient GitHubInstallationClient installationClient,
      ThrillhouseConfig config) {
    this(
        store,
        followUpAnalyzer,
        botIdentity,
        installationClient,
        new Settings(
            config.review().learnings().enabled(),
            config.review().learnings().maxPerRepo(),
            config.review().learnings().promptMaxItems(),
            config.review().learnings().promptMaxChars()));
  }

  /** Visible for tests: the switches and caps are passed directly. */
  ReviewLearnings(
      ReviewLearningService store,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity,
      GitHubInstallationClient installationClient,
      Settings settings) {
    this.store = store;
    this.followUpAnalyzer = followUpAnalyzer;
    this.botIdentity = botIdentity;
    this.installationClient = installationClient;
    this.settings = settings;
  }

  /** Whether the store is switched on for this deployment. */
  public boolean enabled() {
    return settings.enabled();
  }

  /** Active learnings a repository may hold. */
  public int maxPerRepo() {
    return settings.maxPerRepo();
  }

  // --- recall ---------------------------------------------------------------------------------

  /**
   * The unfenced list of prior maintainer decisions relevant to {@code changedPaths}, or blank when
   * the feature is off, the repository has none, or the store could not be read. The caller fences
   * it; see {@link ReviewPromptAssembler#learningsSection}.
   */
  public String promptSection(
      long installationId, String owner, String repo, List<String> changedPaths) {
    if (!settings.enabled()) {
      return "";
    }
    try {
      var active = store.listActive(installationId, owner + "/" + repo, settings.maxPerRepo());
      return render(
          select(active, changedPaths, settings.promptMaxItems(), settings.promptMaxChars()));
    } catch (RuntimeException e) {
      Log.warnf(e, "Could not read review learnings for %s/%s (continuing without)", owner, repo);
      return "";
    }
  }

  /**
   * The learnings to show, most relevant first, bounded by {@code maxItems} and by {@code maxChars}
   * of rendered text. Relevance is the closeness of the learning's file to a changed file ({@link
   * #relevance}); a learning no changed file comes near is left out, and ties go to the newest. A
   * learning too long for the characters left is skipped rather than ending the list, so one long
   * entry does not crowd out shorter ones. One carrying a credential-shaped value is never shown,
   * even though capture already refuses those — the patterns may have grown since it was stored.
   */
  static List<ReviewLearningService.LearningView> select(
      List<ReviewLearningService.LearningView> active,
      List<String> changedPaths,
      int maxItems,
      int maxChars) {
    var paths = changedPaths == null ? Set.<String>of() : new HashSet<>(changedPaths);
    var dirs = new HashSet<String>();
    var extensions = new HashSet<String>();
    for (var path : paths) {
      dirs.add(directoryOf(path));
      extensions.add(extensionOf(path));
    }
    // Scored once per learning, so the sort compares stored scores instead of re-deriving them.
    var ranked =
        active.stream()
            .filter(l -> !carriesCredential(l))
            .map(l -> new Scored(l, relevance(l, paths, dirs, extensions)))
            .filter(scored -> scored.score() > 0)
            .sorted(
                Comparator.comparingInt(Scored::score)
                    .reversed()
                    .thenComparing(
                        scored -> scored.learning().createdAt(), Comparator.reverseOrder()))
            .map(Scored::learning)
            .toList();
    var chosen = new ArrayList<ReviewLearningService.LearningView>();
    var used = 0;
    for (var learning : ranked) {
      if (chosen.size() >= maxItems) {
        break;
      }
      var length = entry(learning).length() + 1;
      if (used + length <= maxChars) {
        chosen.add(learning);
        used += length;
      }
    }
    return chosen;
  }

  /**
   * How close a learning is to this pull request: 3 for a changed file itself, 2 for a file in the
   * same directory as one, or for a repository-wide convention, 1 for a file of a changed file's
   * type, 0 otherwise. A platform fact learned on one Java file (GitHub review threads are flat)
   * still reaches a pull request that changes other Java files, while a decline about a workflow
   * file stays out of a documentation-only change.
   */
  /** Whether any replayed field of the learning — text, title or path — is credential-shaped. */
  private static boolean carriesCredential(ReviewLearningService.LearningView learning) {
    return LearningText.containsCredential(learning.text())
        || LearningText.containsCredential(learning.findingTitle())
        || LearningText.containsCredential(learning.path());
  }

  private record Scored(ReviewLearningService.LearningView learning, int score) {}

  static int relevance(
      ReviewLearningService.LearningView learning,
      Set<String> paths,
      Set<String> dirs,
      Set<String> extensions) {
    var path = learning.path();
    if (path == null || path.isBlank()) {
      return 2;
    }
    if (paths.contains(path)) {
      return 3;
    }
    if (dirs.contains(directoryOf(path))) {
      return 2;
    }
    var extension = extensionOf(path);
    return !extension.isEmpty() && extensions.contains(extension) ? 1 : 0;
  }

  private static String directoryOf(String path) {
    var slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static String extensionOf(String path) {
    var name = path.substring(path.lastIndexOf('/') + 1);
    var dot = name.lastIndexOf('.');
    return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** The chosen learnings as the lines the prompt carries; blank when there are none. */
  static String render(List<ReviewLearningService.LearningView> chosen) {
    if (chosen.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder();
    for (var learning : chosen) {
      sb.append(entry(learning)).append('\n');
    }
    return sb.toString().stripTrailing();
  }

  /** One learning as two lines: what it is about, then the maintainer's words. */
  static String entry(ReviewLearningService.LearningView learning) {
    var head = new StringBuilder("- [L").append(learning.id()).append("] ");
    if (ReviewLearning.KIND_DECLINE.equals(learning.kind())) {
      head.append("Declined finding \"").append(learning.findingTitle()).append('"');
      if (learning.findingRisk() != null) {
        head.append(" (").append(learning.findingRisk()).append(')');
      }
    } else {
      head.append("Convention");
    }
    head.append(" on ")
        .append(learning.path() == null ? "the whole repository" : learning.path())
        .append(" — @")
        .append(learning.author())
        .append(", PR #")
        .append(learning.sourcePrNumber())
        .append(":\n  ")
        .append(learning.text());
    return head.toString();
  }

  // --- capture --------------------------------------------------------------------------------

  /** Everything {@link #captureSurvivingDeclines} reads off one finished review. */
  public record DeclineCapture(
      String auth,
      long installationId,
      String owner,
      String repo,
      int prNumber,
      List<ReviewResponse.Finding> previous,
      List<ReviewResult.PreviousFindingStatus> statuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<GitHubCommentClient.IssueComment> conversationComments,
      Supplier<String> reviewedCode) {

    public DeclineCapture {
      previous = previous == null ? List.of() : List.copyOf(previous);
      statuses = statuses == null ? List.of() : List.copyOf(statuses);
      inlineComments = inlineComments == null ? List.of() : List.copyOf(inlineComments);
      conversationComments =
          conversationComments == null ? List.of() : List.copyOf(conversationComments);
    }
  }

  /**
   * Stores this round's surviving declines as learnings; returns how many new ones were stored. The
   * author's write access is confirmed against the collaborator-permission API, not just the
   * comment's author association (a {@code MEMBER} of the organization may hold only read access to
   * the repository), because a learning outlives the pull request it was taught on.
   */
  public int captureSurvivingDeclines(DeclineCapture capture) {
    if (!settings.enabled()) {
      return 0;
    }
    var justifiedIds =
        capture.statuses().stream()
            .filter(s -> STATUS_JUSTIFIED.equalsIgnoreCase(s.status()))
            .map(ReviewResult.PreviousFindingStatus::id)
            .toList();
    if (justifiedIds.isEmpty()) {
      return 0;
    }
    var surviving =
        followUpAnalyzer.survivingDeclines(
            capture.previous(),
            justifiedIds,
            capture.inlineComments(),
            capture.conversationComments(),
            botIdentity,
            capture.reviewedCode());
    var permissionCache = new HashMap<String, Boolean>();
    var stored = 0;
    for (var decline : surviving) {
      if (!hasWriteAccess(capture, decline.author(), permissionCache)) {
        continue;
      }
      var finding = decline.finding();
      var outcome = store.save(declineInput(capture, decline), settings.maxPerRepo());
      Log.infof(
          "Learning from the decline of '%s' (%s) on %s/%s #%d: %s",
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          capture.owner(),
          capture.repo(),
          capture.prNumber(),
          outcome);
      if (outcome == ReviewLearningService.RecordOutcome.STORED) {
        stored++;
      }
    }
    return stored;
  }

  private static ReviewLearningService.LearningInput declineInput(
      DeclineCapture capture, FollowUpAnalyzer.SurvivingDecline decline) {
    var finding = decline.finding();
    return new ReviewLearningService.LearningInput(
        capture.installationId(),
        capture.owner() + "/" + capture.repo(),
        ReviewLearning.KIND_DECLINE,
        finding.title(),
        finding.risk(),
        finding.file(),
        decline.reason(),
        capture.prNumber(),
        sourceUrl(capture, decline),
        decline.author());
  }

  private boolean hasWriteAccess(
      DeclineCapture capture, String login, Map<String, Boolean> permissionCache) {
    return permissionCache.computeIfAbsent(
        login.toLowerCase(Locale.ROOT),
        ignored -> {
          try {
            var permission =
                installationClient.collaboratorPermission(
                    capture.auth(), ACCEPT, capture.owner(), capture.repo(), login);
            var level = permission == null ? null : permission.permission();
            return level != null
                && WRITE_PERMISSIONS.contains(level.strip().toLowerCase(Locale.ROOT));
          } catch (RuntimeException e) {
            Log.debugf(
                e,
                "Could not confirm @%s's access on %s/%s; not learning from their decline",
                login,
                capture.owner(),
                capture.repo());
            return false;
          }
        });
  }

  private static String sourceUrl(DeclineCapture capture, FollowUpAnalyzer.SurvivingDecline d) {
    return pullRequestUrl(capture.owner(), capture.repo(), capture.prNumber())
        + (d.onThread() ? "#discussion_r" : "#issuecomment-")
        + d.sourceCommentId();
  }

  static String pullRequestUrl(String owner, String repo, int prNumber) {
    return "https://github.com/" + owner + "/" + repo + "/pull/" + prNumber;
  }

  // --- governance -----------------------------------------------------------------------------

  /**
   * Stores an explicit {@code /remember} convention. The caller has already confirmed the author
   * holds write access; the text is the maintainer's own words after the command.
   */
  public ReviewLearningService.RecordOutcome rememberConvention(
      long installationId,
      String owner,
      String repo,
      int prNumber,
      long commentId,
      String author,
      String text) {
    return store.save(
        new ReviewLearningService.LearningInput(
            installationId,
            owner + "/" + repo,
            ReviewLearning.KIND_CONVENTION,
            null,
            null,
            null,
            text,
            prNumber,
            pullRequestUrl(owner, repo, prNumber) + "#issuecomment-" + commentId,
            author),
        settings.maxPerRepo());
  }

  /** Active learnings of a repository, newest first, for {@code /learnings}. */
  public List<ReviewLearningService.LearningView> list(
      long installationId, String owner, String repo) {
    return store.listActive(installationId, owner + "/" + repo, settings.maxPerRepo());
  }

  /** Retracts one learning of this repository, for {@code /forget}. */
  public ReviewLearningService.RetractOutcome forget(
      long installationId, String owner, String repo, long id, String login) {
    return store.retract(installationId, owner + "/" + repo, id, login);
  }
}
