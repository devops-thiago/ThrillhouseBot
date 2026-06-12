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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Collapses duplicate findings within a single review response. The model occasionally reports one
 * underlying defect several times — dogfooding produced the same issue three times in one review at
 * three different severities. Findings are considered duplicates when they sit on the same file
 * within a small line tolerance and their titles describe the same thing; the merged finding keeps
 * the richest description and the median severity of the cluster, so one outlier rating (high or
 * low) cannot decide the posted severity.
 */
@ApplicationScoped
public class FindingDeduplicator {

  static final int LINE_TOLERANCE = 3;
  static final double TITLE_SIMILARITY_THRESHOLD = 0.6;

  public ReviewResponse dedupe(ReviewResponse response) {
    List<ReviewResponse.Finding> findings = response.findings();
    if (findings.size() < 2) {
      return response;
    }
    List<List<ReviewResponse.Finding>> clusters = cluster(findings);
    if (clusters.size() == findings.size()) {
      return response;
    }
    var merged = new ArrayList<ReviewResponse.Finding>(clusters.size());
    for (List<ReviewResponse.Finding> cluster : clusters) {
      if (cluster.size() > 1) {
        Log.infof(
            "Merging %d duplicate findings at %s:%d ('%s')",
            cluster.size(), cluster.get(0).file(), cluster.get(0).line(), cluster.get(0).title());
      }
      merged.add(merge(cluster));
    }
    return new ReviewResponse(
        merged,
        response.previousFindingsStatus(),
        FindingVerificationService.recount(response.summary(), merged));
  }

  private static List<List<ReviewResponse.Finding>> cluster(List<ReviewResponse.Finding> findings) {
    var clusters = new ArrayList<List<ReviewResponse.Finding>>();
    for (ReviewResponse.Finding finding : findings) {
      List<ReviewResponse.Finding> home = null;
      for (List<ReviewResponse.Finding> cluster : clusters) {
        // Compare against every member, not just the anchor: chained duplicates can each sit
        // within the line tolerance of their neighbour but beyond it from the first member
        if (cluster.stream().anyMatch(member -> sameDefect(member, finding))) {
          home = cluster;
          break;
        }
      }
      if (home == null) {
        home = new ArrayList<>();
        clusters.add(home);
      }
      home.add(finding);
    }
    return clusters;
  }

  static boolean sameDefect(ReviewResponse.Finding a, ReviewResponse.Finding b) {
    return a.file() != null
        && FilePaths.same(a.file(), b.file())
        && Math.abs(a.line() - b.line()) <= LINE_TOLERANCE
        && titleSimilarity(a.title(), b.title()) >= TITLE_SIMILARITY_THRESHOLD;
  }

  /**
   * Paraphrased re-raises evade title matching (dogfooding measured 0.12 title similarity on a
   * re-raise) but keep most of their substance: the overlap coefficient over combined title and
   * description tokens scored 0.47 and 0.59 on observed re-raise pairs against 0.28 for genuinely
   * distinct same-file claims.
   */
  static final double CONTENT_OVERLAP_THRESHOLD = 0.45;

  /**
   * Below this many tokens the overlap coefficient is statistically meaningless — a five-token
   * title shares three tokens with half the findings in its file. Real findings carry long
   * descriptions; degenerate ones fall back to the strict location-based matching.
   */
  static final int MIN_CONTENT_TOKENS = 8;

  /** Overlap coefficient (intersection over smaller set) of title+description tokens. */
  static double contentOverlap(ReviewResponse.Finding a, ReviewResponse.Finding b) {
    Set<String> tokensA = tokens(combinedText(a));
    Set<String> tokensB = tokens(combinedText(b));
    if (Math.min(tokensA.size(), tokensB.size()) < MIN_CONTENT_TOKENS) {
      return 0.0;
    }
    var intersection = new HashSet<>(tokensA);
    intersection.retainAll(tokensB);
    return (double) intersection.size() / Math.min(tokensA.size(), tokensB.size());
  }

  private static String combinedText(ReviewResponse.Finding f) {
    return (f.title() == null ? "" : f.title())
        + " "
        + (f.description() == null ? "" : f.description());
  }

  /** Token-overlap similarity of normalized titles (Jaccard index), 0.0 when either is blank. */
  static double titleSimilarity(String a, String b) {
    Set<String> tokensA = tokens(a);
    Set<String> tokensB = tokens(b);
    if (tokensA.isEmpty() || tokensB.isEmpty()) {
      return 0.0;
    }
    var intersection = new HashSet<>(tokensA);
    intersection.retainAll(tokensB);
    var union = new HashSet<>(tokensA);
    union.addAll(tokensB);
    return (double) intersection.size() / union.size();
  }

  private static Set<String> tokens(String title) {
    if (title == null || title.isBlank()) {
      return Set.of();
    }
    var tokens = new HashSet<String>();
    for (String token : title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (!token.isEmpty()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  /**
   * Richest description wins; severity is the cluster's median so outliers cannot decide it. When
   * the richest copy carries no suggestion (for example after quote validation stripped it), the
   * suggestion of another cluster member is kept instead of being lost in the merge.
   */
  private static ReviewResponse.Finding merge(List<ReviewResponse.Finding> cluster) {
    if (cluster.size() == 1) {
      return cluster.get(0);
    }
    ReviewResponse.Finding richest =
        cluster.stream()
            .max(
                Comparator.comparingInt(
                    f -> f.description() == null ? 0 : f.description().length()))
            .orElse(cluster.get(0));
    ReviewResponse.Finding suggestionSource =
        richest.suggestionOld() != null
            ? richest
            : cluster.stream().filter(f -> f.suggestionOld() != null).findFirst().orElse(richest);
    var ranked =
        cluster.stream()
            .map(f -> RiskLevel.fromString(f.risk()))
            .sorted(Comparator.naturalOrder())
            .toList();
    RiskLevel median = ranked.get(ranked.size() / 2);
    // Confidence stays paired with the chosen risk: the highest confidence among the members
    // that carry the median risk. Taking it from an arbitrary member could either defuse a
    // blocking critical/high-confidence finding via a hedged duplicate, or synthesize a
    // blocking pair no single member ever asserted. Confidence is declared most-certain-first
    // (HIGH < MEDIUM < LOW in natural order), so min() selects the highest confidence.
    Confidence confidence =
        cluster.stream()
            .filter(f -> RiskLevel.fromString(f.risk()) == median)
            .map(f -> Confidence.fromString(f.confidence()))
            .min(Comparator.naturalOrder())
            .orElse(Confidence.fromString(richest.confidence()));
    return new ReviewResponse.Finding(
        median.name().toLowerCase(Locale.ROOT),
        confidence.name().toLowerCase(Locale.ROOT),
        richest.file(),
        richest.line(),
        richest.title(),
        richest.description(),
        suggestionSource.suggestionOld(),
        suggestionSource.suggestionNew());
  }
}
