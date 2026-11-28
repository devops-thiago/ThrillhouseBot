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

import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which files changed since each earlier round, read from the stored finding rows first (#1319): a
 * row keeps its file's blob SHA at the head of the round that raised it, and the pull request's
 * file listing gives the blob SHA at this head, so the same SHA means the same content without a
 * call to GitHub. Where no row knows the file at that round, or the file is not in this head's
 * listing, the answer is {@code fallback}'s (the comparison of the two heads, {@link
 * RoundFileChanges}).
 *
 * <p>The rows also say how each finding was anchored in the round that raised it ({@link
 * #raisedAnchor}), whatever the rounds' heads are.
 */
final class StoredRoundChanges implements DiffLineResolver.RoundChanges {

  private final Map<String, String> blobsAtRaise = new HashMap<>();
  private final Map<String, DiffLineResolver.RaisedAnchor> anchors = new HashMap<>();
  private final List<String> roundHeads;
  private final Map<String, String> headBlobs;
  private final DiffLineResolver.RoundChanges fallback;

  /**
   * @param rows the pull request's stored rows
   * @param roundHeads each prior round's head, newest first; empty when the rounds' heads cannot be
   *     told apart from their responses
   * @param headBlobs each changed file's blob SHA at this head
   * @param fallback what answers where the rows do not
   */
  StoredRoundChanges(
      Iterable<FindingLedger.Row> rows,
      List<String> roundHeads,
      Map<String, String> headBlobs,
      DiffLineResolver.RoundChanges fallback) {
    // A round with no recorded head holds a null here, so no List.copyOf.
    this.roundHeads = new ArrayList<>(roundHeads);
    this.headBlobs = new HashMap<>(headBlobs);
    this.fallback = fallback;
    for (var row : rows) {
      var raisedHead = row.status().raisedHead();
      var file = row.subject().file();
      if (raisedHead != null && file != null && row.blobSha() != null) {
        blobsAtRaise.put(at(raisedHead, file), row.blobSha());
      }
      if (row.anchor() != null) {
        anchors.put(row.key(), row.anchor());
      }
    }
  }

  @Override
  public FileChange since(int round, String path) {
    var head = round >= 0 && round < roundHeads.size() ? roundHeads.get(round) : null;
    var then = head == null || path == null ? null : blobsAtRaise.get(at(head, path));
    var now = path == null ? null : headBlobs.get(path);
    if (then == null || now == null) {
      return fallback.since(round, path);
    }
    return then.equalsIgnoreCase(now) ? FileChange.UNCHANGED : FileChange.CHANGED;
  }

  /**
   * No line of a file the blobs show unchanged was added since the round. For any other file the
   * blobs cannot say which lines changed, so the answer is {@code fallback}'s.
   */
  @Override
  public Optional<Set<Integer>> addedLinesSince(int round, String path) {
    return since(round, path) == FileChange.UNCHANGED
        ? Optional.of(Set.of())
        : fallback.addedLinesSince(round, path);
  }

  @Override
  public Optional<DiffLineResolver.RaisedAnchor> raisedAnchor(String key) {
    return Optional.ofNullable(anchors.get(key));
  }

  private static String at(String head, String path) {
    return head.toLowerCase(Locale.ROOT) + '\0' + path;
  }
}
