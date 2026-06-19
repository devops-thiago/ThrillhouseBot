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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Opt-in context-aware PR labelling (#61). Reconciles the labels the model suggested against the
 * repository's existing label set and either applies them to the PR or posts them as a suggestion,
 * depending on configuration. Every step is best-effort: a labelling failure never fails a review.
 */
@ApplicationScoped
public class PrLabeler {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final int LABELS_PER_PAGE = 100;

  // GitHub serves at most 100 labels per page; stop on a short page or after this many pages.
  static final int MAX_LABEL_PAGES = 10;

  // Color for labels the bot creates when allow-create is enabled (GitHub's neutral grey).
  private static final String DEFAULT_NEW_LABEL_COLOR = "ededed";
  private static final String CREATED_LABEL_DESCRIPTION = "Added by ThrillhouseBot";

  private final ThrillhouseConfig config;
  private final GitHubLabelClient labelClient;
  private final GitHubCommentClient commentClient;

  @Inject
  public PrLabeler(
      ThrillhouseConfig config,
      @RestClient GitHubLabelClient labelClient,
      @RestClient GitHubCommentClient commentClient) {
    this.config = config;
    this.labelClient = labelClient;
    this.commentClient = commentClient;
  }

  /** Whether the feature is switched on at all. */
  public boolean enabled() {
    return config.review().labels().enabled();
  }

  /**
   * Fetches every label defined on the repository, paginating up to {@link #MAX_LABEL_PAGES}.
   * Returns an empty list when the feature is disabled or the lookup fails — labelling then simply
   * does not happen.
   */
  public List<GitHubLabelClient.Label> fetchExistingLabels(String auth, String owner, String repo) {
    if (!enabled()) {
      return List.of();
    }
    var all = new ArrayList<GitHubLabelClient.Label>();
    try {
      List<GitHubLabelClient.Label> page = null;
      for (int p = 1;
          p <= MAX_LABEL_PAGES && (page == null || page.size() == LABELS_PER_PAGE);
          p++) {
        page = labelClient.listLabels(auth, ACCEPT, owner, repo, LABELS_PER_PAGE, p);
        if (page == null) {
          break;
        }
        all.addAll(page);
      }
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch repository labels; skipping label suggestions", e);
      return List.of();
    }
    return all;
  }

  /**
   * Renders the repo's labels as a prompt section ({@code - name: description} lines) the model
   * chooses from. Empty string when there are none, which suppresses the prompt section entirely.
   */
  public static String formatAvailableLabels(List<GitHubLabelClient.Label> labels) {
    if (labels == null || labels.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder();
    for (var label : labels) {
      if (label.name() == null || label.name().isBlank()) {
        continue;
      }
      sb.append("- ").append(label.name().strip());
      if (label.description() != null && !label.description().isBlank()) {
        sb.append(": ").append(label.description().strip());
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /** Coordinates one labelling action. */
  public record LabelRequest(
      String auth,
      String owner,
      String repo,
      int prNumber,
      boolean isFirstReview,
      List<String> suggested,
      List<GitHubLabelClient.Label> existing) {
    public LabelRequest {
      suggested = suggested == null ? List.of() : List.copyOf(suggested);
      existing = existing == null ? List.of() : List.copyOf(existing);
    }
  }

  /**
   * Applies (or, in suggest-only mode, comments) the labels the model suggested for the PR. No-op
   * when disabled or when nothing reconciles to the repository's label set.
   */
  public void applyOrSuggest(LabelRequest request) {
    if (!enabled()) {
      return;
    }
    try {
      var resolved = reconcile(request.suggested(), request.existing());
      if (resolved.isEmpty()) {
        Log.debugf(
            "No repository labels matched the suggestions for %s/%s #%d",
            request.owner(), request.repo(), request.prNumber());
        return;
      }
      if (config.review().labels().apply()) {
        applyLabels(request, resolved);
      } else if (request.isFirstReview()) {
        suggestLabels(request, resolved);
      }
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Label suggestion step failed for %s/%s #%d (continuing)",
          request.owner(),
          request.repo(),
          request.prNumber());
    }
  }

  /**
   * Matches the model's suggestions to the repo's existing labels case-insensitively, keeping the
   * repo's canonical casing, de-duplicating, and capping at {@code max-labels}. Suggestions with no
   * matching label are dropped unless {@code allow-create} is set, in which case they survive
   * verbatim so {@link #applyLabels} can create them.
   */
  List<String> reconcile(List<String> suggested, List<GitHubLabelClient.Label> existing) {
    if (suggested == null || suggested.isEmpty()) {
      return List.of();
    }
    Map<String, String> canonical = new HashMap<>();
    if (existing != null) {
      for (var label : existing) {
        if (label.name() != null && !label.name().isBlank()) {
          canonical.putIfAbsent(
              label.name().strip().toLowerCase(Locale.ROOT), label.name().strip());
        }
      }
    }
    boolean allowCreate = config.review().labels().allowCreate();
    int max = Math.max(0, config.review().labels().maxLabels());

    var result = new ArrayList<String>();
    var seen = new HashSet<String>();
    for (var suggestion : suggested) {
      if (result.size() >= max) {
        break;
      }
      if (suggestion == null || suggestion.isBlank()) {
        continue;
      }
      var key = suggestion.strip().toLowerCase(Locale.ROOT);
      if (!seen.add(key)) {
        continue;
      }
      var match = canonical.get(key);
      if (match != null) {
        result.add(match);
      } else if (allowCreate) {
        result.add(suggestion.strip());
      }
    }
    return result;
  }

  private void applyLabels(LabelRequest request, List<String> resolved) {
    if (config.review().labels().allowCreate()) {
      ensureLabelsExist(request, resolved);
    }
    labelClient.addLabels(
        request.auth(),
        ACCEPT,
        request.owner(),
        request.repo(),
        request.prNumber(),
        new GitHubLabelClient.AddLabelsRequest(resolved));
    Log.infof(
        "Applied %d label(s) to %s/%s #%d: %s",
        resolved.size(), request.owner(), request.repo(), request.prNumber(), resolved);
  }

  /** Creates any reconciled label that is not already defined in the repo (allow-create path). */
  private void ensureLabelsExist(LabelRequest request, List<String> resolved) {
    Set<String> existingNames =
        (request.existing() == null ? List.<GitHubLabelClient.Label>of() : request.existing())
            .stream()
                .filter(label -> label.name() != null)
                .map(label -> label.name().strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    for (var name : resolved) {
      if (existingNames.contains(name.toLowerCase(Locale.ROOT))) {
        continue;
      }
      try {
        labelClient.createLabel(
            request.auth(),
            ACCEPT,
            request.owner(),
            request.repo(),
            new GitHubLabelClient.CreateLabelRequest(
                name, DEFAULT_NEW_LABEL_COLOR, CREATED_LABEL_DESCRIPTION));
        Log.infof("Created repository label '%s' on %s/%s", name, request.owner(), request.repo());
      } catch (RuntimeException e) {
        Log.debugf(e, "Could not create label '%s' (it may already exist)", name);
      }
    }
  }

  private void suggestLabels(LabelRequest request, List<String> resolved) {
    var body =
        "🏷️ **Suggested labels:** "
            + resolved.stream().map(label -> "`" + label + "`").collect(Collectors.joining(", "))
            + "\n\n*Set `thrillhousebot.review.labels.apply=true` to have ThrillhouseBot add these"
            + " automatically.*";
    commentClient.createComment(
        request.auth(),
        ACCEPT,
        request.owner(),
        request.repo(),
        request.prNumber(),
        new GitHubCommentClient.CreateCommentRequest(body));
    Log.infof(
        "Posted label suggestion comment on %s/%s #%d: %s",
        request.owner(), request.repo(), request.prNumber(), resolved);
  }
}
