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
 * excluded label. Each caller decides what an unreadable set means for it.
 */
@ApplicationScoped
public class PullRequestLabelGate {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final int LABELS_PER_PAGE = 100;

  private final ReviewTriggerFilter triggerFilter;
  private final GitHubAuthClient authClient;
  private final GitHubLabelClient labelClient;

  @Inject
  public PullRequestLabelGate(
      ReviewTriggerFilter triggerFilter,
      GitHubAuthClient authClient,
      @RestClient GitHubLabelClient labelClient) {
    this.triggerFilter = triggerFilter;
    this.authClient = authClient;
    this.labelClient = labelClient;
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
   * @return empty when any page could not be read
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
          break;
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
