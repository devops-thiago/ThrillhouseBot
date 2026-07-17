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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrLabelerTest {

  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;
  @Mock private ThrillhouseConfig.LabelsConfig labelsConfig;
  @Mock private GitHubLabelClient labelClient;
  @Mock private GitHubCommentClient commentClient;

  @InjectMocks private PrLabeler labeler;

  @BeforeEach
  void setUp() {
    lenient().when(config.review()).thenReturn(reviewConfig);
    lenient().when(reviewConfig.labels()).thenReturn(labelsConfig);
    lenient().when(labelsConfig.enabled()).thenReturn(true);
    lenient().when(labelsConfig.apply()).thenReturn(true);
    lenient().when(labelsConfig.allowCreate()).thenReturn(false);
    lenient().when(labelsConfig.maxLabels()).thenReturn(3);
  }

  private static GitHubLabelClient.Label label(String name) {
    return new GitHubLabelClient.Label(name, null, "ededed");
  }

  private static GitHubLabelClient.Label label(String name, String description) {
    return new GitHubLabelClient.Label(name, description, "ededed");
  }

  private PrLabeler.LabelRequest request(
      boolean firstReview, List<String> suggested, List<GitHubLabelClient.Label> existing) {
    return new PrLabeler.LabelRequest("token", "octo", "repo", 7, firstReview, suggested, existing);
  }

  @Test
  void labelRequestShouldNormalizeNullCollectionsToEmpty() {
    var req = new PrLabeler.LabelRequest("t", "o", "r", 1, true, null, null);
    assertTrue(req.suggested().isEmpty());
    assertTrue(req.existing().isEmpty());
  }

  @Test
  void addLabelsRequestShouldNormalizeNullLabelsToEmpty() {
    var req = new GitHubLabelClient.AddLabelsRequest(null);
    assertTrue(req.labels().isEmpty());
  }

  @Test
  void allowNewLabelsShouldReflectConfig() {
    assertFalse(labeler.allowNewLabels());
    when(labelsConfig.allowCreate()).thenReturn(true);
    assertTrue(labeler.allowNewLabels());
  }

  @Nested
  class Reconcile {

    @Test
    void shouldMatchCaseInsensitivelyAndKeepRepoCasing() {
      var resolved =
          labeler.reconcile(
              List.of("Bug", "ENHANCEMENT"), List.of(label("bug"), label("enhancement")));
      assertEquals(List.of("bug", "enhancement"), resolved);
    }

    @Test
    void shouldDropSuggestionsNotInTheRepoWhenCreateDisabled() {
      var resolved = labeler.reconcile(List.of("bug", "made-up"), List.of(label("bug")));
      assertEquals(List.of("bug"), resolved);
    }

    @Test
    void shouldKeepUnmatchedSuggestionsWhenCreateEnabled() {
      when(labelsConfig.allowCreate()).thenReturn(true);
      var resolved = labeler.reconcile(List.of("bug", "area/api"), List.of(label("bug")));
      assertEquals(List.of("bug", "area/api"), resolved);
    }

    @Test
    void shouldCapAtMaxLabels() {
      when(labelsConfig.maxLabels()).thenReturn(2);
      var resolved =
          labeler.reconcile(List.of("a", "b", "c"), List.of(label("a"), label("b"), label("c")));
      assertEquals(List.of("a", "b"), resolved);
    }

    @Test
    void shouldDeduplicateSuggestions() {
      var resolved = labeler.reconcile(List.of("bug", "BUG", " bug "), List.of(label("bug")));
      assertEquals(List.of("bug"), resolved);
    }

    @Test
    void shouldIgnoreBlankAndNullSuggestions() {
      var resolved =
          labeler.reconcile(java.util.Arrays.asList("bug", "", null), List.of(label("bug")));
      assertEquals(List.of("bug"), resolved);
    }

    @Test
    void shouldReturnEmptyForNoSuggestions() {
      assertTrue(labeler.reconcile(List.of(), List.of(label("bug"))).isEmpty());
      assertTrue(labeler.reconcile(null, List.of(label("bug"))).isEmpty());
    }

    @Test
    void shouldDropEverythingWhenExistingIsNullAndCreateDisabled() {
      assertTrue(labeler.reconcile(List.of("bug"), null).isEmpty());
    }

    @Test
    void shouldIgnoreExistingLabelsWithNullOrBlankNames() {
      var resolved =
          labeler.reconcile(
              List.of("bug"), java.util.Arrays.asList(label(null), label(" "), label("bug")));
      assertEquals(List.of("bug"), resolved);
    }
  }

  @Nested
  class ApplyOrSuggest {

    @Test
    void shouldDoNothingWhenDisabled() {
      when(labelsConfig.enabled()).thenReturn(false);
      labeler.applyOrSuggest(request(true, List.of("bug"), List.of(label("bug"))));
      verifyNoInteractions(labelClient, commentClient);
    }

    @Test
    void shouldDoNothingWhenNoLabelMatches() {
      labeler.applyOrSuggest(request(true, List.of("nope"), List.of(label("bug"))));
      verifyNoInteractions(labelClient, commentClient);
    }

    @Test
    void shouldAddLabelsInApplyMode() {
      labeler.applyOrSuggest(request(false, List.of("Bug"), List.of(label("bug"))));

      var captor = ArgumentCaptor.forClass(GitHubLabelClient.AddLabelsRequest.class);
      verify(labelClient)
          .addLabels(eq("token"), anyString(), eq("octo"), eq("repo"), eq(7), captor.capture());
      assertEquals(List.of("bug"), captor.getValue().labels());
      verifyNoInteractions(commentClient);
    }

    @Test
    void shouldNotCreateLabelsInApplyModeWhenCreateDisabled() {
      labeler.applyOrSuggest(request(false, List.of("bug"), List.of(label("bug"))));
      verify(labelClient, never())
          .createLabel(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldCreateMissingLabelsThenAddWhenCreateEnabled() {
      when(labelsConfig.allowCreate()).thenReturn(true);
      // A null-named label in the set is filtered out while building the "already exists" view.
      labeler.applyOrSuggest(
          request(
              false,
              List.of("bug", "area/api"),
              java.util.Arrays.asList(label(null), label("bug"))));

      // Only the missing label is created; the existing one is left alone.
      var createCaptor = ArgumentCaptor.forClass(GitHubLabelClient.CreateLabelRequest.class);
      verify(labelClient)
          .createLabel(eq("token"), anyString(), eq("octo"), eq("repo"), createCaptor.capture());
      assertEquals("area/api", createCaptor.getValue().name());
      verify(labelClient)
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldPostSuggestionCommentInSuggestModeOnFirstReview() {
      when(labelsConfig.apply()).thenReturn(false);
      labeler.applyOrSuggest(request(true, List.of("bug"), List.of(label("bug"))));

      var captor = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
      verify(commentClient)
          .createComment(anyString(), anyString(), eq("octo"), eq("repo"), eq(7), captor.capture());
      assertTrue(captor.getValue().body().contains("`bug`"));
      verify(labelClient, never())
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldNotCommentInSuggestModeOnFollowUpReview() {
      when(labelsConfig.apply()).thenReturn(false);
      labeler.applyOrSuggest(request(false, List.of("bug"), List.of(label("bug"))));
      verifyNoInteractions(commentClient, labelClient);
    }

    @Test
    void shouldSwallowClientFailures() {
      when(labelClient.addLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
          .thenThrow(new RuntimeException("boom"));
      assertDoesNotThrow(
          () -> labeler.applyOrSuggest(request(false, List.of("bug"), List.of(label("bug")))));
    }

    @Test
    void shouldNotCallAddLabelsWhenEveryCreationFails() {
      when(labelsConfig.allowCreate()).thenReturn(true);
      when(labelClient.createLabel(anyString(), anyString(), anyString(), anyString(), any()))
          .thenThrow(new RuntimeException("rate limited"));

      labeler.applyOrSuggest(request(false, List.of("area/api"), List.of()));

      // The only label could not be created, so there is nothing GitHub would accept.
      verify(labelClient, never())
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldApplyOnlyTheLabelsThatExistOrWereCreated() {
      when(labelsConfig.allowCreate()).thenReturn(true);
      when(labelClient.createLabel(anyString(), anyString(), anyString(), anyString(), any()))
          .thenThrow(new RuntimeException("boom"));

      // "bug" already exists; creating "area/api" fails — only "bug" may be applied, so the
      // failed name never reaches addLabels (which would 422 the whole request).
      labeler.applyOrSuggest(request(false, List.of("bug", "area/api"), List.of(label("bug"))));

      var captor = ArgumentCaptor.forClass(GitHubLabelClient.AddLabelsRequest.class);
      verify(labelClient)
          .addLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), captor.capture());
      assertEquals(List.of("bug"), captor.getValue().labels());
    }

    @Test
    void shouldNotAddLabelsWhenPrIsAlreadyAtMax() {
      when(labelsConfig.maxLabels()).thenReturn(3);
      // The PR already carries max labels from earlier reviews — adding more would exceed the cap.
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenReturn(List.of(label("a"), label("b"), label("c")));

      labeler.applyOrSuggest(request(false, List.of("d"), List.of(label("d"))));

      verify(labelClient, never())
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldOnlyTopUpToMaxAgainstLabelsAlreadyOnThePr() {
      when(labelsConfig.maxLabels()).thenReturn(3);
      // Two labels already present, so only one more may be added regardless of how many match.
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenReturn(List.of(label("a"), label("b")));

      labeler.applyOrSuggest(request(false, List.of("c", "d"), List.of(label("c"), label("d"))));

      var captor = ArgumentCaptor.forClass(GitHubLabelClient.AddLabelsRequest.class);
      verify(labelClient)
          .addLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), captor.capture());
      assertEquals(List.of("c"), captor.getValue().labels());
    }

    @Test
    void shouldSkipLabelsAlreadyOnThePrWithoutSpendingBudget() {
      when(labelsConfig.maxLabels()).thenReturn(3);
      // "bug" is already on the PR; re-adding it is a no-op, so its budget slot frees up for
      // "docs".
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenReturn(List.of(label("bug")));

      labeler.applyOrSuggest(
          request(false, List.of("bug", "docs"), List.of(label("bug"), label("docs"))));

      var captor = ArgumentCaptor.forClass(GitHubLabelClient.AddLabelsRequest.class);
      verify(labelClient)
          .addLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), captor.capture());
      assertEquals(List.of("docs"), captor.getValue().labels());
    }

    @Test
    void shouldNotCallAddLabelsWhenEverySuggestionIsAlreadyOnThePr() {
      when(labelsConfig.maxLabels()).thenReturn(3);
      // The only matching suggestion is already on the PR, so there is nothing new to add.
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenReturn(List.of(label("bug")));

      labeler.applyOrSuggest(request(false, List.of("bug"), List.of(label("bug"))));

      verify(labelClient, never())
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldIgnoreCurrentLabelsWithNullOrBlankNames() {
      when(labelsConfig.maxLabels()).thenReturn(3);
      // Defensive: a PR label with a null/blank name must not count against the budget.
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenReturn(java.util.Arrays.asList(label(null), label(" "), label("bug")));

      labeler.applyOrSuggest(request(false, List.of("docs"), List.of(label("docs"))));

      var captor = ArgumentCaptor.forClass(GitHubLabelClient.AddLabelsRequest.class);
      verify(labelClient)
          .addLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), captor.capture());
      assertEquals(List.of("docs"), captor.getValue().labels());
    }

    @Test
    void shouldApplyWhenCurrentLabelLookupReturnsNull() {
      // GitHub may deserialize an empty body to null; treat it as "no labels yet" and apply.
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenReturn(null);

      labeler.applyOrSuggest(request(false, List.of("bug"), List.of(label("bug"))));

      var captor = ArgumentCaptor.forClass(GitHubLabelClient.AddLabelsRequest.class);
      verify(labelClient)
          .addLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), captor.capture());
      assertEquals(List.of("bug"), captor.getValue().labels());
    }

    @Test
    void shouldStillApplyWhenCurrentLabelLookupFails() {
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("rate limited"));

      labeler.applyOrSuggest(request(false, List.of("bug"), List.of(label("bug"))));

      var captor = ArgumentCaptor.forClass(GitHubLabelClient.AddLabelsRequest.class);
      verify(labelClient)
          .addLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), captor.capture());
      assertEquals(List.of("bug"), captor.getValue().labels());
    }

    @Test
    void shouldTreatLabelsBeyondTheFirstPageAsAlreadyApplied() {
      // max is set well above the label count so the budget stays positive — this isolates the
      // pagination check: the suggestion is skipped only because it is recognised as already on the
      // PR (page 2), not because the cap was hit. Page one must be full (100) or the walk stops
      // before requesting page two, so the only label the PR could exceed one page with lives
      // there.
      when(labelsConfig.maxLabels()).thenReturn(200);
      var fullFirstPage = new java.util.ArrayList<GitHubLabelClient.Label>();
      for (int i = 0; i < 100; i++) {
        fullFirstPage.add(label("page1-" + i));
      }
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), eq(1)))
          .thenReturn(fullFirstPage);
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), eq(2)))
          .thenReturn(List.of(label("page2-only")));

      labeler.applyOrSuggest(request(false, List.of("page2-only"), List.of(label("page2-only"))));

      // A single-page fetch would miss "page2-only" and re-add it; walking to page 2 sees it as
      // already present, so nothing new is applied.
      verify(labelClient, never())
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      // Both pages were walked to build the current-label set.
      verify(labelClient, times(2))
          .listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void shouldStopWalkingCurrentLabelsAtThePageCap() {
      // Every page of PR labels comes back full, so only the MAX_LABEL_PAGES guard can end the
      // walk.
      var fullPage = new java.util.ArrayList<GitHubLabelClient.Label>();
      for (int i = 0; i < 100; i++) {
        fullPage.add(label("label-" + i));
      }
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
          .thenReturn(fullPage);

      labeler.applyOrSuggest(request(false, List.of("bug"), List.of(label("bug"))));

      verify(labelClient, times(PrLabeler.MAX_LABEL_PAGES))
          .listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void shouldKeepEarlierPageLabelsWhenALaterPageFails() {
      when(labelsConfig.maxLabels()).thenReturn(3);
      // Page 1 already fills the budget; a transient failure fetching page 2 must not discard the
      // page-1 labels, or the current count would collapse to zero and re-open the budget — adding
      // labels to a PR that already sits well past max. The pages read so far still apply the cap.
      var fullFirstPage = new java.util.ArrayList<GitHubLabelClient.Label>();
      for (int i = 0; i < 100; i++) {
        fullFirstPage.add(label("page1-" + i));
      }
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), eq(1)))
          .thenReturn(fullFirstPage);
      when(labelClient.listIssueLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), eq(2)))
          .thenThrow(new RuntimeException("rate limited"));

      labeler.applyOrSuggest(request(false, List.of("docs"), List.of(label("docs"))));

      verify(labelClient, never())
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }
  }

  @Nested
  class FetchExistingLabels {

    @Test
    void shouldReturnEmptyWhenDisabled() {
      when(labelsConfig.enabled()).thenReturn(false);
      assertTrue(labeler.fetchExistingLabels("token", "octo", "repo").isEmpty());
      verifyNoInteractions(labelClient);
    }

    @Test
    void shouldStopPagingOnShortPage() {
      when(labelClient.listLabels(
              anyString(), anyString(), anyString(), anyString(), eq(100), eq(1)))
          .thenReturn(List.of(label("bug"), label("docs")));
      var labels = labeler.fetchExistingLabels("token", "octo", "repo");
      assertEquals(2, labels.size());
      verify(labelClient, times(1))
          .listLabels(anyString(), anyString(), anyString(), anyString(), eq(100), anyInt());
    }

    @Test
    void shouldReturnEmptyOnFailure() {
      when(labelClient.listLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("rate limited"));
      assertTrue(labeler.fetchExistingLabels("token", "octo", "repo").isEmpty());
    }

    @Test
    void shouldPaginateUntilAShortPage() {
      var fullPage = new java.util.ArrayList<GitHubLabelClient.Label>();
      for (int i = 0; i < 100; i++) {
        fullPage.add(label("label-" + i));
      }
      when(labelClient.listLabels(
              anyString(), anyString(), anyString(), anyString(), eq(100), eq(1)))
          .thenReturn(fullPage);
      when(labelClient.listLabels(
              anyString(), anyString(), anyString(), anyString(), eq(100), eq(2)))
          .thenReturn(List.of(label("last")));

      var labels = labeler.fetchExistingLabels("token", "octo", "repo");

      assertEquals(101, labels.size());
      verify(labelClient, times(2))
          .listLabels(anyString(), anyString(), anyString(), anyString(), eq(100), anyInt());
    }

    @Test
    void shouldStopWhenAPageComesBackNull() {
      when(labelClient.listLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
          .thenReturn(null);
      assertTrue(labeler.fetchExistingLabels("token", "octo", "repo").isEmpty());
    }

    @Test
    void shouldStopAtThePageCapWhenEveryPageIsFull() {
      var fullPage = new java.util.ArrayList<GitHubLabelClient.Label>();
      for (int i = 0; i < 100; i++) {
        fullPage.add(label("label-" + i));
      }
      // Every page comes back full, so only the MAX_LABEL_PAGES guard can end the loop.
      when(labelClient.listLabels(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
          .thenReturn(fullPage);

      var labels = labeler.fetchExistingLabels("token", "octo", "repo");

      assertEquals(100 * PrLabeler.MAX_LABEL_PAGES, labels.size());
      verify(labelClient, times(PrLabeler.MAX_LABEL_PAGES))
          .listLabels(anyString(), anyString(), anyString(), anyString(), eq(100), anyInt());
    }
  }

  @Nested
  class BuildLabelGuidance {

    @Test
    void shouldReturnEmptyForNoLabels() {
      assertEquals("", PrLabeler.buildLabelGuidance(List.of(), false));
      assertEquals("", PrLabeler.buildLabelGuidance(null, false));
    }

    @Test
    void shouldReturnEmptyWhenEveryLabelNameIsBlank() {
      assertEquals(
          "", PrLabeler.buildLabelGuidance(java.util.Arrays.asList(label(null), label(" ")), true));
    }

    @Test
    void shouldRenderHeaderLabelListAndRestrictWhenCreateDisabled() {
      var rendered =
          PrLabeler.buildLabelGuidance(
              List.of(label("bug", "Something is broken"), label("docs")), false);
      assertTrue(rendered.contains("## Available Repository Labels"));
      assertTrue(rendered.contains("- bug: Something is broken\n"));
      assertTrue(rendered.contains("- docs\n"));
      assertTrue(
          rendered.contains("Choose only labels from the list above — do not invent new ones."));
    }

    @Test
    void shouldInviteNewLabelsWhenCreateEnabled() {
      var rendered = PrLabeler.buildLabelGuidance(List.of(label("bug")), true);
      assertTrue(rendered.contains("## Available Repository Labels"));
      assertTrue(rendered.contains("- bug\n"));
      assertTrue(rendered.contains("you may propose a short, lower-case, hyphenated new label"));
    }

    @Test
    void shouldSkipNullOrBlankNamesAndBlankDescriptions() {
      var rendered =
          PrLabeler.buildLabelGuidance(
              java.util.Arrays.asList(
                  label(null), label(" "), label("docs", "  "), label("bug", "broken")),
              false);
      // Null- and blank-named labels are skipped; blank description dropped, the real one kept.
      assertTrue(rendered.contains("- docs\n"));
      assertTrue(rendered.contains("- bug: broken\n"));
      assertFalse(rendered.contains("null"));
    }
  }
}
