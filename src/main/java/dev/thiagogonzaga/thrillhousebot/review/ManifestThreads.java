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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Which entries of the summary's findings manifest have an inline thread (#1433): what a round
 * expects to open before its review posts, and what the review did open. {@link ReviewPublisher}
 * writes the manifest line from the first and corrects it from the second.
 */
final class ManifestThreads {

  private ManifestThreads() {}

  /**
   * The open set the summary comment's manifest lists for {@code result} (#1433), over the same
   * scrubbed findings, so its keys match the comment's. {@code posted} is what {@link
   * ReviewPublisher#postReview} returned for the round, so {@code thread} says which threads the
   * review opened.
   */
  static OpenFindings openFindings(ReviewResult result, PostedThreads posted) {
    var scrubbed = PromptLabelScrubber.scrub(result);
    return FindingsManifest.collect(
        PrSummaryGenerator.currentState(scrubbed), posted(scrubbed, posted));
  }

  /**
   * The round's findings whose thread reached the pull request, by {@link #manifestKey}: what
   * {@link ReviewPublisher#postReview} opened, as opposed to what {@link #announced} said before it
   * ran.
   */
  record PostedThreads(Set<String> keys) {

    /** A round that opened no thread. */
    static final PostedThreads NONE = new PostedThreads(Set.of());

    PostedThreads {
      keys = Set.copyOf(keys);
    }

    static PostedThreads of(List<Finding> threaded) {
      return new PostedThreads(
          threaded.stream().map(ManifestThreads::manifestKey).collect(Collectors.toSet()));
    }
  }

  /**
   * Which manifest entries {@link ReviewPublisher#postReview} is set to open an inline thread for
   * (#1433): of the round's own findings, in the order they are posted, those that post inline on a
   * changed file ({@link DiffLineResolver#isChangedFile}), up to {@code maxReviewComments}, the
   * same rules the publisher's inline posting applies. Entries are matched by {@link
   * SuggestionFormatter#findingKey}, so a re-raised finding the summary lists at an earlier
   * severity still matches. An entry carried from an earlier round, which this round does not post,
   * keeps {@link Finding#postsInline}. The summary is written before the review, so this is what
   * the round expects: it cannot know which posts GitHub will refuse, nor that a refusal frees a
   * cap slot for a finding it put past the cap. The publisher rewrites the line from {@link
   * #posted} once the review has posted and the two disagree. The cap is read only when there is a
   * diff to place threads on.
   */
  static Predicate<Finding> announced(
      ReviewResult round, DiffLineResolver lineResolver, IntSupplier maxReviewComments) {
    if (lineResolver == null) {
      return Finding::postsInline;
    }
    var threadKeys = new HashSet<String>();
    var slots = maxReviewComments.getAsInt();
    for (var finding : round.findings()) {
      if (slots > 0 && finding.postsInline() && lineResolver.isChangedFile(finding.file())) {
        threadKeys.add(manifestKey(finding));
        slots--;
      }
    }
    return roundRule(round, threadKeys);
  }

  /**
   * {@link #announced} after the review posted: a finding of the round has a thread when one landed
   * for it. A carried entry still keeps {@link Finding#postsInline}.
   */
  static Predicate<Finding> posted(ReviewResult round, PostedThreads opened) {
    return roundRule(round, opened.keys());
  }

  private static Predicate<Finding> roundRule(ReviewResult round, Set<String> threadKeys) {
    var roundKeys =
        round.findings().stream().map(ManifestThreads::manifestKey).collect(Collectors.toSet());
    return finding -> {
      var key = manifestKey(finding);
      return roundKeys.contains(key) ? threadKeys.contains(key) : finding.postsInline();
    };
  }

  private static String manifestKey(Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }
}
