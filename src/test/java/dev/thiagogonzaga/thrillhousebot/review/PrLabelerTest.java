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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PrLabelerTest {

  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;
  @Mock private ThrillhouseConfig.LabelsConfig labelsConfig;
  @Mock private GitHubLabelClient labelClient;
  @Mock private GitHubCommentClient commentClient;

  private PrLabeler labeler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.labels()).thenReturn(labelsConfig);
    when(labelsConfig.enabled()).thenReturn(true);
    when(labelsConfig.apply()).thenReturn(true);
    when(labelsConfig.allowCreate()).thenReturn(false);
    when(labelsConfig.maxLabels()).thenReturn(3);
    labeler = new PrLabeler(config, labelClient, commentClient);
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
      labeler.applyOrSuggest(request(false, List.of("bug", "area/api"), List.of(label("bug"))));

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
  }

  @Nested
  class FormatAvailableLabels {

    @Test
    void shouldReturnEmptyForNoLabels() {
      assertEquals("", PrLabeler.formatAvailableLabels(List.of()));
      assertEquals("", PrLabeler.formatAvailableLabels(null));
    }

    @Test
    void shouldRenderNameAndDescription() {
      var rendered =
          PrLabeler.formatAvailableLabels(
              List.of(label("bug", "Something is broken"), label("docs")));
      assertEquals("- bug: Something is broken\n- docs\n", rendered);
    }
  }
}
