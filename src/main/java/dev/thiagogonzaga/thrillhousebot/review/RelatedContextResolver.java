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
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Builds the opt-in related context (#55): repository code the diff refers to but does not change,
 * for the review call to read beside the diff.
 *
 * <p>Three steps, the first and the last deterministic. {@link RelatedReferences} finds the
 * candidate files from the diff's import lines and type names, against the repository listing the
 * review already reads at most once. One call on the concise model ({@link
 * RelatedContextSelectionService}) picks at most {@code max-files} of them by number. The picked
 * files are read at the head commit and clipped to the regions the changed code uses: the members
 * its added lines call and the names it refers to the file by. The result is capped per file and,
 * as a whole, at a fixed share of the per-call input budget ({@link
 * DiffBudgetPlanner#relatedContextTokenBudget}).
 *
 * <p>Only paths of the repository listing are read, so no path the model or the diff text writes
 * reaches the contents API. Best-effort throughout: off, no candidate, a failed selection or an
 * unreadable file each leave the review as it is without this.
 */
@ApplicationScoped
public class RelatedContextResolver {

  /** Changed files listed to the selection call; the rest are counted. */
  static final int MAX_LISTED_FILES = 40;

  /** Hunk headers listed per changed file. */
  static final int MAX_HUNK_HEADERS = 6;

  /** Characters of one hunk header. */
  static final int MAX_HEADER_CHARS = 160;

  /** Character cap on one file's excerpt. */
  static final int MAX_FILE_CHARS = 4_000;

  /** Character cap on one rendered source line, so a minified file cannot fill the section. */
  static final int MAX_LINE_CHARS = 200;

  /** Places of one file the excerpt is built around. */
  static final int MAX_HITS = 8;

  /** Lines shown before a place the changed code uses. */
  static final int LINES_BEFORE = 3;

  /** Lines shown after it: a declaration is followed by its body. */
  static final int LINES_AFTER = 14;

  /** Lines shown from the top of a file in which nothing the changed code names was found. */
  static final int HEAD_LINES = 40;

  private final boolean enabled;
  private final int maxFiles;
  private final int maxAiCalls;
  private final RelatedContextSelectionService selection;
  private final DiffBudgetPlanner budgetPlanner;
  private final TokenCounter tokenCounter;
  private final Predicate<String> ignored;

  @Inject
  public RelatedContextResolver(
      ThrillhouseConfig config,
      RelatedContextSelectionService selection,
      DiffBudgetPlanner budgetPlanner,
      TokenCounter tokenCounter,
      ReviewDiffFormatter diffFormatter) {
    this(
        config.review().relatedContext().enabled(),
        config.review().relatedContext().maxFiles(),
        config.review().maxAiCalls(),
        selection,
        budgetPlanner,
        tokenCounter,
        diffFormatter.ignoreGlobs(List.of())::matches);
  }

  /** Visible for tests: the switches and caps are passed directly. */
  @SuppressWarnings("java:S107")
  RelatedContextResolver(
      boolean enabled,
      int maxFiles,
      int maxAiCalls,
      RelatedContextSelectionService selection,
      DiffBudgetPlanner budgetPlanner,
      TokenCounter tokenCounter,
      Predicate<String> ignored) {
    this.enabled = enabled;
    this.maxFiles = maxFiles;
    this.maxAiCalls = maxAiCalls;
    this.selection = selection;
    this.budgetPlanner = budgetPlanner;
    this.tokenCounter = tokenCounter;
    this.ignored = ignored;
  }

  /** The feature switched off, for callers that construct the orchestrator without it. */
  static RelatedContextResolver disabled() {
    return new RelatedContextResolver(false, 0, 0, null, null, null, path -> false);
  }

  /** Whether the related context is switched on. */
  boolean enabled() {
    return enabled;
  }

  /**
   * The related context for one review, or {@link RelatedContext#NONE} when the feature is off, the
   * diff refers to no file outside it, or the context could not be built.
   *
   * @param repository the review's repository round, whose listing and file cache are shared
   */
  RelatedContext resolve(
      ReviewSession session,
      ReviewContextLoader.ReviewContext ctx,
      CitedLocationResolver.Round repository) {
    // The selection call is counted in the cap beside one review call and the summary call; the
    // startup validator refuses a smaller allowance, so this only guards a caller that skipped it.
    if (!enabled
        || maxAiCalls < ThrillhouseConfig.RelatedContextConfig.MIN_AI_CALLS
        || ctx.reviewableFiles().isEmpty()) {
      return RelatedContext.NONE;
    }
    var calls = 0;
    try {
      var listing = repository.repositoryPaths();
      if (listing.isEmpty()) {
        return RelatedContext.NONE;
      }
      var candidates =
          RelatedReferences.extract(
              ctx.reviewableFiles(), changedPaths(ctx.files()), listing.get().paths(), ignored);
      if (candidates.isEmpty()) {
        return RelatedContext.NONE;
      }
      var outcome =
          selection.select(
              ReviewTokenLedger.keyFor(session),
              new RelatedContextSelectionService.Request(
                  changedFileList(ctx.reviewableFiles()),
                  candidates.stream().map(RelatedContextResolver::row).toList(),
                  maxFiles,
                  budgetPlanner.perCallInputBudget()));
      calls = outcome.called() ? 1 : 0;
      var byPath = new LinkedHashMap<String, RelatedReferences.Candidate>();
      candidates.forEach(candidate -> byPath.put(candidate.path(), candidate));
      var files = new ArrayList<RelatedContext.RelatedFile>();
      for (var picked : outcome.selected()) {
        var candidate = byPath.get(picked.path());
        var lines = candidate == null ? null : repository.relatedFileLines(picked.path());
        if (lines != null && lines.isPresent()) {
          files.add(
              new RelatedContext.RelatedFile(
                  candidate.path(),
                  picked.reason().label(),
                  candidate.referencedBy(),
                  lines.get().size(),
                  regions(lines.get(), candidate)));
        }
      }
      return new RelatedContext(withinBudget(files), calls);
    } catch (RuntimeException e) {
      Log.warn("Related context could not be built, continuing without it", e);
      return new RelatedContext(List.of(), calls);
    }
  }

  /**
   * Releases what {@link #resolve} holds for a review that is over: the spend-ledger entry the
   * selection call opened, which the pipeline clears itself when the review reaches it.
   */
  void close(ReviewSession session) {
    if (selection != null) {
      selection.release(ReviewTokenLedger.keyFor(session));
    }
  }

  private static Set<String> changedPaths(List<GitHubPullRequestClient.FileDiff> files) {
    var paths = new HashSet<String>();
    for (var file : files) {
      paths.add(file.filename());
      paths.add(file.previousFilename());
    }
    return paths;
  }

  private static RelatedContextSelectionService.Candidate row(
      RelatedReferences.Candidate candidate) {
    return new RelatedContextSelectionService.Candidate(
        candidate.path(),
        candidate.via() == RelatedReferences.Via.IMPORT ? "imported" : "named in added lines",
        candidate.referencedBy(),
        candidate.symbols());
  }

  /** The changed files as the selection call reads them: path, status and hunk headers. */
  static List<String> changedFileList(List<GitHubPullRequestClient.FileDiff> files) {
    var blocks = new ArrayList<String>();
    for (var file : files.subList(0, Math.min(files.size(), MAX_LISTED_FILES))) {
      var sb =
          new StringBuilder("- ")
              .append(file.filename())
              .append(" (")
              .append(file.status())
              .append(", +")
              .append(file.additions())
              .append("/-")
              .append(file.deletions())
              .append(')');
      for (var header :
          RelatedReferences.hunkHeaders(file.patch(), MAX_HUNK_HEADERS, MAX_HEADER_CHARS)) {
        sb.append("\n    ").append(header);
      }
      blocks.add(sb.toString());
    }
    if (files.size() > MAX_LISTED_FILES) {
      blocks.add("(" + (files.size() - MAX_LISTED_FILES) + " more changed file(s) not listed)");
    }
    return blocks;
  }

  /**
   * The parts of a file the changed code uses, in file order, within {@link #MAX_FILE_CHARS}: a
   * window around each place a called member is declared, then around the first use of each name
   * the diff refers to the file by; the top of the file when neither is found.
   */
  static List<RelatedContext.Region> regions(
      List<String> lines, RelatedReferences.Candidate candidate) {
    if (lines.isEmpty()) {
      return List.of();
    }
    var hits = new TreeSet<Integer>();
    addHits(lines, declarationOf(candidate.members()), hits, false);
    addHits(lines, wordOf(candidate.symbols()), hits, true);
    var windows = new ArrayList<int[]>();
    if (hits.isEmpty()) {
      windows.add(new int[] {0, Math.min(lines.size(), HEAD_LINES) - 1});
    }
    for (var hit : hits) {
      var from = Math.max(0, hit - LINES_BEFORE);
      var to = Math.min(lines.size() - 1, hit + LINES_AFTER);
      var last = windows.isEmpty() ? null : windows.get(windows.size() - 1);
      if (last != null && from <= last[1] + 1) {
        last[1] = Math.max(last[1], to);
      } else {
        windows.add(new int[] {from, to});
      }
    }
    var regions = new ArrayList<RelatedContext.Region>();
    var room = MAX_FILE_CHARS;
    for (var window : windows) {
      var shown = new ArrayList<String>();
      for (var i = window[0]; i <= window[1] && room > 0; i++) {
        var text = lines.get(i);
        var clipped =
            text.length() > MAX_LINE_CHARS ? text.substring(0, MAX_LINE_CHARS) + "…" : text;
        room -= clipped.length() + 1;
        shown.add(clipped);
      }
      regions.add(new RelatedContext.Region(window[0] + 1, shown));
      if (room <= 0) {
        break;
      }
    }
    return regions;
  }

  /** Adds the matching line indexes, at most {@link #MAX_HITS} in all; one per name if asked. */
  private static void addHits(
      List<String> lines, Pattern pattern, Set<Integer> hits, boolean firstOfEach) {
    if (pattern == null) {
      return;
    }
    var seen = new HashSet<String>();
    for (var i = 0; i < lines.size() && hits.size() < MAX_HITS; i++) {
      var line = lines.get(i);
      if (line.length() > RelatedReferences.MAX_LINE_CHARS) {
        continue;
      }
      var matcher = pattern.matcher(line);
      if (matcher.find() && (!firstOfEach || seen.add(matcher.group(1)))) {
        hits.add(i);
      }
    }
  }

  /** A member name opening a call or a declaration, not reached through a receiver. */
  private static Pattern declarationOf(List<String> members) {
    return members.isEmpty()
        ? null
        : Pattern.compile("(?<![.\\w])(" + alternation(members) + ")\\s*\\(");
  }

  private static Pattern wordOf(List<String> symbols) {
    return symbols.isEmpty()
        ? null
        : Pattern.compile("(?<!\\w)(" + alternation(symbols) + ")(?!\\w)");
  }

  private static String alternation(List<String> names) {
    return String.join("|", names.stream().map(Pattern::quote).toList());
  }

  /**
   * The files that fit the section's share of the per-call input budget, in order. A file that does
   * not fit whole is cut back region by region; one whose first region does not fit is left out,
   * and a smaller file after it may still be kept.
   */
  private List<RelatedContext.RelatedFile> withinBudget(List<RelatedContext.RelatedFile> files) {
    long left = budgetPlanner.relatedContextTokenBudget();
    var kept = new ArrayList<RelatedContext.RelatedFile>();
    for (var file : files) {
      var fitted = file;
      while (!fitted.regions().isEmpty()
          && tokenCounter.estimateTokens(fitted.render()) + 2L > left) {
        fitted = fitted.withRegions(fitted.regions().subList(0, fitted.regions().size() - 1));
      }
      if (fitted.regions().isEmpty()) {
        Log.info(
            "Related context leaves out a picked file: it does not fit the section's share of"
                + " the input budget");
        continue;
      }
      left -= tokenCounter.estimateTokens(fitted.render()) + 2L;
      kept.add(fitted);
    }
    return kept;
  }
}
