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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which files changed since the head each earlier round reviewed (#1319), so a finding whose file
 * holds the same content as in the round that raised it is held open without reading its anchor or
 * asking the model.
 *
 * <p>Each round's head is the commit its stored session records. One comparison per distinct head
 * ({@code GET /compare/{round head}...{head}}) lists the files that differ; a file it does not list
 * is unchanged. The comparison is three-dot, from the merge base, so it is read only when the
 * current head is {@code ahead} of the round's head or {@code identical} to it. A force-push that
 * rewrote the round's commit leaves the two diverged, and then nothing is known. So does a failed
 * call, a listing at GitHub's file cap, and anything past {@link #MAX_COMPARISONS} heads per
 * review: every such answer is {@link FileChange#UNKNOWN}, and the content checks decide as they
 * did before.
 */
final class RoundFileChanges implements DiffLineResolver.RoundChanges {

  /** Distinct round heads compared per review. */
  static final int MAX_COMPARISONS = 8;

  /** GitHub lists at most this many files on a comparison; a full listing may have been cut. */
  static final int MAX_LISTED_FILES = 300;

  private static final String ACCEPT = "application/vnd.github+json";

  private final GitHubPullRequestClient prClient;
  private final String auth;
  private final String owner;
  private final String repo;
  private final String head;
  private final List<String> roundHeads;
  private final Map<String, Optional<Changed>> changedByBase = new ConcurrentHashMap<>();
  private final AtomicInteger comparisons = new AtomicInteger();

  private RoundFileChanges(
      GitHubPullRequestClient prClient,
      String auth,
      String owner,
      String repo,
      String head,
      List<String> roundHeads) {
    this.prClient = prClient;
    this.auth = auth;
    this.owner = owner;
    this.repo = repo;
    this.head = head;
    this.roundHeads = new ArrayList<>(roundHeads);
  }

  /**
   * The changes since each prior round, or {@link DiffLineResolver.RoundChanges#NONE} when the
   * rounds' heads cannot be told: a history whose heads do not line up with its responses, findings
   * carried from a run that stood down (they join a round that has no single head), or no head.
   *
   * @param rounds the prior rounds the review read
   */
  static DiffLineResolver.RoundChanges of(
      GitHubPullRequestClient prClient,
      String auth,
      String owner,
      String repo,
      String head,
      Rounds rounds) {
    if (rounds.carried()
        || rounds.heads().size() != rounds.count()
        || head == null
        || head.isBlank()) {
      return DiffLineResolver.RoundChanges.NONE;
    }
    return new RoundFileChanges(prClient, auth, owner, repo, head, rounds.heads());
  }

  /**
   * What one comparison lists.
   *
   * @param paths the paths that differ, a renamed file under both its names
   * @param patches each listed file's patch by its current name, for the files GitHub gave one
   */
  private record Changed(Set<String> paths, Map<String, String> patches) {}

  /**
   * The prior rounds a review read.
   *
   * @param heads each prior round's head, newest first, beside the prior responses
   * @param count how many prior responses the review read
   * @param carried whether findings were carried from a run that stood down
   */
  record Rounds(List<String> heads, int count, boolean carried) {}

  @Override
  public FileChange since(int round, String path) {
    if (round < 0 || round >= roundHeads.size()) {
      return FileChange.UNKNOWN;
    }
    var base = roundHeads.get(round);
    if (base == null || base.isBlank()) {
      return FileChange.UNKNOWN;
    }
    if (base.equalsIgnoreCase(head)) {
      return FileChange.UNCHANGED;
    }
    return changedByBase
        .computeIfAbsent(base.toLowerCase(Locale.ROOT), this::changedFiles)
        .map(changed -> changes(changed.paths(), path))
        .orElse(FileChange.UNKNOWN);
  }

  /**
   * The added lines of {@code path}'s patch in the comparison with the round's head. The head is
   * ahead of the round's head, so the patch's new side is the head file and its line numbers are
   * the ones the review's diff uses. A file the comparison lists with no patch (binary, or too
   * large to diff) is not known, and neither is a path variant that fits two listed files: another
   * file's lines would say nothing about this one.
   */
  @Override
  public Optional<Set<Integer>> addedLinesSince(int round, String path) {
    return switch (since(round, path)) {
      case UNCHANGED -> Optional.of(Set.of());
      case UNKNOWN -> Optional.empty();
      case CHANGED ->
          changedByBase
              .get(roundHeads.get(round).toLowerCase(Locale.ROOT))
              .flatMap(changed -> patchOf(changed.patches(), path))
              .map(RoundFileChanges::addedLines);
    };
  }

  /** The patch listed under {@code path}, or under the one listed name that is a variant of it. */
  private static Optional<String> patchOf(Map<String, String> patches, String path) {
    var exact = patches.get(path);
    if (exact != null) {
      return Optional.of(exact);
    }
    var variants = patches.keySet().stream().filter(name -> FilePaths.same(name, path)).toList();
    return variants.size() == 1 ? Optional.of(patches.get(variants.get(0))) : Optional.empty();
  }

  private static Set<Integer> addedLines(String patch) {
    var added = new HashSet<Integer>();
    for (var line : PatchIndex.parse(patch).rightSide()) {
      if (line.added()) {
        added.add(line.rightLine());
      }
    }
    return added;
  }

  /** A path the comparison lists, under its own name or a model's shorter or longer variant. */
  private static FileChange changes(Set<String> changed, String path) {
    return changed.contains(path) || changed.stream().anyMatch(name -> FilePaths.same(name, path))
        ? FileChange.CHANGED
        : FileChange.UNCHANGED;
  }

  /** The paths that differ between {@code base} and the head, or empty when that is not known. */
  private Optional<Changed> changedFiles(String base) {
    if (comparisons.incrementAndGet() > MAX_COMPARISONS) {
      return Optional.empty();
    }
    try {
      var comparison = prClient.compareCommits(auth, ACCEPT, owner, repo, base, head);
      var status = comparison.status();
      if (!"ahead".equals(status) && !"identical".equals(status)) {
        Log.infof(
            "Not reading file changes since %s: the head is %s it, not ahead",
            LogSafe.oneLine(base), LogSafe.oneLine(status));
        return Optional.empty();
      }
      if (comparison.files().size() >= MAX_LISTED_FILES) {
        return Optional.empty();
      }
      var changed = new HashSet<String>();
      var patches = new HashMap<String, String>();
      for (var file : comparison.files()) {
        if ("renamed".equals(file.status()) && file.changes() == 0) {
          // A pure rename moves the content without changing it.
          continue;
        }
        changed.add(file.filename());
        if (file.previousFilename() != null) {
          changed.add(file.previousFilename());
        }
        if (file.patch() != null) {
          patches.put(file.filename(), file.patch());
        }
      }
      return Optional.of(new Changed(changed, patches));
    } catch (RuntimeException e) {
      Log.warnf(
          "Could not compare %s with the head; file changes since that round are unknown: %s",
          LogSafe.oneLine(base), LogSafe.oneLine(e.getMessage()));
      return Optional.empty();
    }
  }
}
