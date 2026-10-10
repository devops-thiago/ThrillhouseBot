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

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import dev.thiagogonzaga.thrillhousebot.webhook.ReviewTriggerFilter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Applies the {@code webhook.triggers} label gates to the labels a pull request carries now, read
 * from the API (#1021). The webhook payload is a snapshot: Dependabot opens a pull request and adds
 * its labels in a second call a second or two later, so the {@code opened} payload has none, and a
 * label can also be added while a review runs or while its approval is held on CI. Every check here
 * is skipped, with no API call, when no label gate is configured.
 *
 * <p>A read either returns every label or nothing: unlike {@link PrLabeler}'s capping read, a
 * partial set is never used, because a page lost to a failure could be the one that carries the
 * excluded label. A failed or empty-bodied page makes the whole set unreadable, and each caller
 * decides what that means for it. The only bound is {@link PrLabeler#MAX_LABEL_PAGES} pages of 100,
 * far past the labels GitHub lets one issue carry.
 *
 * <p>A run or held approval the gates reject stands down the same way from either caller ({@link
 * #standDown}): the skip is counted under the gate's reason and the check run concluded as skipped,
 * with nothing posted to the pull request.
 */
@ApplicationScoped
public class PullRequestLabelGate {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final int LABELS_PER_PAGE = 100;

  /** SKIPPED check-run title when a label gate stopped a post or a held approval. */
  static final String CHECK_TITLE = "Review skipped by a label gate";

  /**
   * Where a stand-down happens, so the check run can be concluded: the pull request, the check run
   * (none when not positive), the dashboard link it carries, and what was not posted, for the log.
   */
  public record StandDown(
      String auth,
      String owner,
      String repo,
      int prNumber,
      long checkRunId,
      String detailsUrl,
      String notPosted) {}

  private final ReviewTriggerFilter triggerFilter;
  private final GitHubAuthClient authClient;
  private final GitHubLabelClient labelClient;
  private final CheckRunManager checkRunManager;
  private final ReviewSkipEmitter skipEmitter;

  @Inject
  public PullRequestLabelGate(
      ReviewTriggerFilter triggerFilter,
      GitHubAuthClient authClient,
      @RestClient GitHubLabelClient labelClient,
      CheckRunManager checkRunManager,
      ReviewSkipEmitter skipEmitter) {
    this.triggerFilter = triggerFilter;
    this.authClient = authClient;
    this.labelClient = labelClient;
    this.checkRunManager = checkRunManager;
    this.skipEmitter = skipEmitter;
  }

  /** Whether a required or excluded label is configured; nothing is read when not. */
  public boolean configured() {
    return triggerFilter.hasLabelGates();
  }

  /** The label gates applied to {@code labels}; see {@link ReviewTriggerFilter#labelSkipReason}. */
  public Optional<ReviewTriggerFilter.Skip> skipFor(Collection<String> labels) {
    return triggerFilter.labelSkipReason(labels);
  }

  /**
   * The label gate that stops an automatic review before its first write, applied to the labels the
   * pull request carries now; stands the run down ({@link #standDown}) when it rejects. Never for a
   * manual {@code /review}, which the gates do not apply to, and no read is made when no label gate
   * is configured. Fails open on unreadable labels, like the orchestrator's head guard: a finished
   * review is never lost to its own guard, and the webhook already gated the dispatch.
   *
   * @return whether the run was stood down
   */
  public boolean standsDownBeforePublish(ReviewOrchestrator.ReviewRequest req, StandDown where) {
    if (req.isManualTrigger() || !configured()) {
      return false;
    }
    var skip =
        currentLabels(where.auth(), where.owner(), where.repo(), where.prNumber())
            .flatMap(this::skipFor);
    skip.ifPresent(s -> standDown(s, where));
    return skip.isPresent();
  }

  /**
   * Counts the skip under the gate's reason and concludes the check run as skipped. Concluding the
   * check run is best-effort: the skip is already decided and nothing was posted.
   */
  public void standDown(ReviewTriggerFilter.Skip skip, StandDown where) {
    skipEmitter.recordSkip(
        skip.reason(),
        where.owner(),
        where.repo(),
        where.prNumber(),
        skip.detail() + " — not posting " + where.notPosted());
    if (where.checkRunId() <= 0) {
      return;
    }
    try {
      checkRunManager.updateCheckRun(
          new CheckRunManager.CheckRunUpdate(
              where.auth(),
              where.owner(),
              where.repo(),
              where.checkRunId(),
              "completed",
              "skipped",
              CHECK_TITLE,
              checkSummary(skip),
              where.detailsUrl()));
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Failed to mark check run %d of %s/%s #%d as skipped by a label gate",
          where.checkRunId(),
          where.owner(),
          where.repo(),
          where.prNumber());
    }
  }

  /** The SKIPPED check-run summary for {@link #CHECK_TITLE}. */
  static String checkSummary(ReviewTriggerFilter.Skip skip) {
    return skip.detail()
        + " (webhook.triggers label gates), so this result was not posted. A manual /review"
        + " still reviews the pull request.";
  }

  /**
   * The names of every label on the pull request, authenticating as the installation.
   *
   * @return empty when the token or any page of labels could not be read
   */
  public Optional<List<String>> currentLabels(
      long installationId, String owner, String repo, int prNumber) {
    String auth;
    try {
      auth = authClient.getAuthHeader(installationId);
    } catch (RuntimeException e) {
      Log.warnf(e, "Could not authenticate to read the labels on %s/%s #%d", owner, repo, prNumber);
      return Optional.empty();
    }
    return currentLabels(auth, owner, repo, prNumber);
  }

  /**
   * The names of every label on the pull request, walking pages up to {@link
   * PrLabeler#MAX_LABEL_PAGES}.
   *
   * @return empty when any page could not be read or came back without a body
   */
  public Optional<List<String>> currentLabels(
      String auth, String owner, String repo, int prNumber) {
    var names = new ArrayList<String>();
    try {
      List<GitHubLabelClient.Label> page = null;
      for (int p = 1;
          p <= PrLabeler.MAX_LABEL_PAGES && (page == null || page.size() == LABELS_PER_PAGE);
          p++) {
        page = labelClient.listIssueLabels(auth, ACCEPT, owner, repo, prNumber, LABELS_PER_PAGE, p);
        if (page == null) {
          return Optional.empty();
        }
        page.forEach(label -> names.add(label.name()));
      }
    } catch (RuntimeException e) {
      Log.warnf(e, "Could not read the labels on %s/%s #%d", owner, repo, prNumber);
      return Optional.empty();
    }
    return Optional.of(names);
  }
}
