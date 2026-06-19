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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTriggerFilterTest {

  // ── defaults preserve original behavior ─────────────────────────────────

  @Test
  void shouldReviewEveryPrWithDefaultConfig() {
    var filter = filter(false, List.of(), List.of(), List.of(), List.of());

    assertTrue(filter.skipReason(pr("main", false)).isEmpty());
    assertTrue(filter.skipReason(pr("main", true)).isEmpty(), "drafts review by default");
    assertTrue(filter.skipReason(pr("release/1.0", false, "wip")).isEmpty());
  }

  @Test
  void shouldReviewWhenPayloadHasNoLabelsField() {
    var filter = filter(false, List.of(), List.of(), List.of(), List.of());
    var pr = new WebhookPayload.PullRequest(1, "t", null, base("main"), null, "", false, null);

    assertTrue(filter.skipReason(pr).isEmpty());
  }

  @Test
  void shouldReturnEmptyForNullPullRequest() {
    var filter = filter(true, List.of("ai"), List.of("wip"), List.of("main"), List.of("dev"));

    assertTrue(filter.skipReason(null).isEmpty());
  }

  // ── skip-drafts ─────────────────────────────────────────────────────────

  @Test
  void shouldSkipDraftWhenConfigured() {
    var filter = filter(true, List.of(), List.of(), List.of(), List.of());

    assertTrue(filter.skipReason(pr("main", true)).isPresent());
    assertTrue(filter.skipReason(pr("main", false)).isEmpty());
  }

  // ── label gating ────────────────────────────────────────────────────────

  @Test
  void shouldRequireAtLeastOneRequiredLabel() {
    var filter = filter(false, List.of("ai-review"), List.of(), List.of(), List.of());

    assertTrue(filter.skipReason(pr("main", false)).isPresent(), "no labels -> skip");
    assertTrue(filter.skipReason(pr("main", false, "other")).isPresent());
    assertTrue(filter.skipReason(pr("main", false, "ai-review")).isEmpty());
    assertTrue(filter.skipReason(pr("main", false, "other", "ai-review")).isEmpty());
  }

  @Test
  void shouldMatchLabelsCaseInsensitively() {
    var filter = filter(false, List.of("AI-Review"), List.of("No-AI"), List.of(), List.of());

    assertTrue(filter.skipReason(pr("main", false, "ai-review")).isEmpty());
    assertTrue(filter.skipReason(pr("main", false, "no-ai")).isPresent());
  }

  @Test
  void shouldSkipWhenExcludedLabelPresent() {
    var filter = filter(false, List.of(), List.of("no-ai-review"), List.of(), List.of());

    assertTrue(filter.skipReason(pr("main", false, "no-ai-review")).isPresent());
    assertTrue(filter.skipReason(pr("main", false, "other")).isEmpty());
  }

  @Test
  void shouldLetExcludedLabelWinOverRequiredLabel() {
    var filter = filter(false, List.of("ai-review"), List.of("hold"), List.of(), List.of());

    // The PR satisfies the required label but also carries the excluded one — exclusion wins.
    assertTrue(filter.skipReason(pr("main", false, "ai-review", "hold")).isPresent());
  }

  // ── base-branch filters ─────────────────────────────────────────────────

  @Test
  void shouldAllowOnlyMatchingBaseBranches() {
    var filter = filter(false, List.of(), List.of(), List.of("main", "release/*"), List.of());

    assertTrue(filter.skipReason(pr("main", false)).isEmpty());
    assertTrue(filter.skipReason(pr("release/1.2", false)).isEmpty());
    assertTrue(filter.skipReason(pr("feature/x", false)).isPresent());
  }

  @Test
  void shouldSkipIgnoredBaseBranches() {
    var filter = filter(false, List.of(), List.of(), List.of(), List.of("dependabot/**"));

    assertTrue(filter.skipReason(pr("dependabot/npm/lodash", false)).isPresent());
    assertTrue(filter.skipReason(pr("main", false)).isEmpty());
  }

  @Test
  void shouldLetIgnoredBaseBranchWinOverAllowlist() {
    var filter = filter(false, List.of(), List.of(), List.of("**"), List.of("legacy"));

    assertTrue(filter.skipReason(pr("legacy", false)).isPresent());
    assertTrue(filter.skipReason(pr("main", false)).isEmpty());
  }

  @Test
  void shouldSkipWhenBaseBranchMissingAndAllowlistSet() {
    var filter = filter(false, List.of(), List.of(), List.of("main"), List.of());
    var pr = new WebhookPayload.PullRequest(1, "t", null, null, null, "", false, List.of());

    assertTrue(filter.skipReason(pr).isPresent());
  }

  // ── robustness: blank/invalid entries are ignored ───────────────────────

  @Test
  void shouldIgnoreBlankConfigEntries() {
    // Mirrors the empty-string default that comes from an unset env var.
    var filter = filter(false, List.of(""), List.of("  "), List.of(""), List.of(" "));

    assertTrue(filter.skipReason(pr("anything", false)).isEmpty());
    assertTrue(filter.skipReason(pr("anything", false, "anything")).isEmpty());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static ReviewTriggerFilter filter(
      boolean skipDrafts,
      List<String> required,
      List<String> excluded,
      List<String> baseBranches,
      List<String> ignoredBaseBranches) {
    return new ReviewTriggerFilter(
        skipDrafts, required, excluded, baseBranches, ignoredBaseBranches);
  }

  private static WebhookPayload.PullRequest pr(String baseRef, boolean draft, String... labels) {
    var labelList = new java.util.ArrayList<WebhookPayload.Label>();
    for (String name : labels) {
      labelList.add(new WebhookPayload.Label(name));
    }
    return new WebhookPayload.PullRequest(
        1, "Test PR", null, base(baseRef), null, "", draft, labelList);
  }

  private static WebhookPayload.Base base(String ref) {
    return new WebhookPayload.Base("sha", ref);
  }
}
