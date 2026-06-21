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
 * Opt-in context-aware PR labelling. Reconciles the labels the model suggested against the
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

  /** Whether the model may propose labels that don't exist yet (the allow-create path). */
  public boolean allowNewLabels() {
    return config.review().labels().allowCreate();
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
   * Builds the full "Available Repository Labels" prompt section — heading, instructions, the
   * {@code - name: description} list, and a closing line whose wording depends on {@code
   * allowNewLabels} (restrict the model to the listed labels, or let it propose a new one). Returns
   * an empty string when there are no usable labels, which suppresses the section entirely. The
   * orchestrator folds this into the {@code repoInstructions} prompt variable.
   */
  public static String buildLabelGuidance(
      List<GitHubLabelClient.Label> labels, boolean allowNewLabels) {
    if (labels == null || labels.isEmpty()) {
      return "";
    }
    var list = new StringBuilder();
    for (var label : labels) {
      if (label.name() == null || label.name().isBlank()) {
        continue;
      }
      list.append("- ").append(label.name().strip());
      if (label.description() != null && !label.description().isBlank()) {
        list.append(": ").append(label.description().strip());
      }
      list.append('\n');
    }
    if (list.isEmpty()) {
      return "";
    }
    return "## Available Repository Labels\n"
        + "These labels already exist in the repository. Pick the ones that best describe this PR"
        + " (area, change type, risk) and return them, by their exact name, in"
        + " summary.suggested_labels; prefer the few most relevant (typically 1-3).\n"
        + list
        + (allowNewLabels
            ? "If none of these fit well, you may propose a short, lower-case, hyphenated new"
                + " label name (for example \"area/api\"); keep new labels to a minimum.\n"
            : "Choose only labels from the list above — do not invent new ones.\n");
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
    var canonical = canonicalNames(existing);
    var allowCreate = config.review().labels().allowCreate();
    var max = Math.max(0, config.review().labels().maxLabels());

    var result = new ArrayList<String>();
    var seen = new HashSet<String>();
    for (var suggestion : suggested) {
      if (result.size() >= max) {
        break;
      }
      var label = resolveSuggestion(suggestion, canonical, allowCreate, seen);
      if (label != null) {
        result.add(label);
      }
    }
    return result;
  }

  /**
   * Lower-cased existing label name → its canonical (repo) casing; blank/null names are dropped.
   */
  private static Map<String, String> canonicalNames(List<GitHubLabelClient.Label> existing) {
    var canonical = new HashMap<String, String>();
    if (existing == null) {
      return canonical;
    }
    for (var label : existing) {
      var name = label.name();
      if (name != null && !name.isBlank()) {
        canonical.putIfAbsent(name.strip().toLowerCase(Locale.ROOT), name.strip());
      }
    }
    return canonical;
  }

  /**
   * Resolves one suggestion to the label to apply, or {@code null} to skip it (blank, a duplicate
   * of one already taken, or an unknown label while creation is disabled).
   */
  private static String resolveSuggestion(
      String suggestion, Map<String, String> canonical, boolean allowCreate, Set<String> seen) {
    if (suggestion == null || suggestion.isBlank()) {
      return null;
    }
    var key = suggestion.strip().toLowerCase(Locale.ROOT);
    if (!seen.add(key)) {
      return null;
    }
    var match = canonical.get(key);
    if (match != null) {
      return match;
    }
    return allowCreate ? suggestion.strip() : null;
  }

  private void applyLabels(LabelRequest request, List<String> resolved) {
    // GitHub's add-labels endpoint is additive, so without this guard a re-review with shifting
    // suggestions would keep piling labels onto the PR — pushing it well past max-labels over time
    // (max-labels is documented as a per-PR bound). Bound the additions against the labels already
    // on the PR: skip ones already present and only top the PR up to max-labels in total.
    var current = currentLabelKeys(request);
    int max = Math.max(0, config.review().labels().maxLabels());
    int budget = Math.max(0, max - current.size());
    if (budget == 0) {
      return;
    }
    // Skip labels already on the PR (re-adding is a no-op that wastes budget) and take only enough
    // new ones to reach max-labels.
    var toAdd =
        resolved.stream()
            .filter(name -> !current.contains(name.toLowerCase(Locale.ROOT)))
            .limit(budget)
            .toList();
    if (toAdd.isEmpty()) {
      return;
    }
    // In allow-create mode, drop any label whose creation failed — GitHub 422s the whole
    // add-labels request if a single name doesn't exist, which would apply nothing at all.
    List<String> applicable =
        config.review().labels().allowCreate() ? ensureLabelsExist(request, toAdd) : toAdd;
    if (applicable.isEmpty()) {
      return;
    }
    labelClient.addLabels(
        request.auth(),
        ACCEPT,
        request.owner(),
        request.repo(),
        request.prNumber(),
        new GitHubLabelClient.AddLabelsRequest(applicable));
    Log.infof(
        "Applied %d label(s) to %s/%s #%d: %s",
        applicable.size(), request.owner(), request.repo(), request.prNumber(), applicable);
  }

  /**
   * Lower-cased names of the labels currently on the PR, used to keep additive re-reviews from
   * exceeding max-labels. Best-effort: if the lookup fails we return an empty set, which simply
   * falls back to applying the reconciled suggestions for this review.
   */
  private Set<String> currentLabelKeys(LabelRequest request) {
    try {
      var labels =
          labelClient.listIssueLabels(
              request.auth(),
              ACCEPT,
              request.owner(),
              request.repo(),
              request.prNumber(),
              LABELS_PER_PAGE,
              1);
      if (labels == null) {
        return Set.of();
      }
      var keys = new HashSet<String>();
      for (var label : labels) {
        var name = label.name();
        if (name != null && !name.isBlank()) {
          keys.add(name.strip().toLowerCase(Locale.ROOT));
        }
      }
      return keys;
    } catch (RuntimeException e) {
      Log.debugf(
          e,
          "Could not read current labels for %s/%s #%d; applying suggestions without a cap check",
          request.owner(),
          request.repo(),
          request.prNumber());
      return Set.of();
    }
  }

  /**
   * Creates any reconciled label that is not already defined in the repo (allow-create path) and
   * returns the labels safe to apply: those that already existed plus those just created. A label
   * whose creation fails is dropped so it cannot 422 the subsequent add-labels call.
   */
  private List<String> ensureLabelsExist(LabelRequest request, List<String> resolved) {
    // request.existing() is never null — the record's constructor normalizes it.
    Set<String> existingNames =
        request.existing().stream()
            .filter(label -> label.name() != null)
            .map(label -> label.name().strip().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    var applicable = new ArrayList<String>();
    for (var name : resolved) {
      if (existingNames.contains(name.toLowerCase(Locale.ROOT))) {
        applicable.add(name);
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
        applicable.add(name);
        Log.infof("Created repository label '%s' on %s/%s", name, request.owner(), request.repo());
      } catch (RuntimeException e) {
        Log.debugf(e, "Could not create label '%s'; skipping it", name);
      }
    }
    return applicable;
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
