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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.CommentVisibilityService;
import dev.thiagogonzaga.thrillhousebot.github.CommentVisibilityService.Entry;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutdatedOutputHiderTest {

  private static final String BOT = "thrillhousebot[bot]";
  private static final Instant START = Instant.parse("2026-01-02T00:00:00Z");

  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class, RETURNS_DEEP_STUBS);
  private final CommentVisibilityService visibility = mock(CommentVisibilityService.class);
  private final OutdatedOutputHider hider =
      new OutdatedOutputHider(config, visibility, BotIdentity.of(BOT));
  private final List<Entry> comments = new ArrayList<>();
  private final List<Entry> reviews = new ArrayList<>();

  @BeforeEach
  void on() {
    when(config.review().hideOutdated().enabled()).thenReturn(true);
    when(config.github().writeRetryBudget()).thenReturn(Duration.ZERO);
    when(visibility.comments("auth", "owner", "repo", 7)).thenReturn(comments);
    when(visibility.reviews("auth", "owner", "repo", 7)).thenReturn(reviews);
  }

  @Test
  void doesNothingWhenOff() {
    when(config.review().hideOutdated().enabled()).thenReturn(false);

    hide();

    verifyNoInteractions(visibility);
  }

  @Test
  void readsAMissingSettingAsOff() {
    var partial = mock(ThrillhouseConfig.class);
    var unset = new OutdatedOutputHider(partial, visibility, BotIdentity.of(BOT));

    unset.hide("auth", request(), START, List.of("ROOT1"));
    when(partial.review()).thenReturn(mock(ThrillhouseConfig.ReviewConfig.class));
    unset.hide("auth", request(), START, List.of("ROOT1"));

    verifyNoInteractions(visibility);
  }

  @Test
  void aCommentThatOnlyOpensLikeACoveredNoteIsNotOne() {
    assertFalse(OutdatedOutputHider.isNotice("A review of `main` shows a race."));
  }

  @Test
  void keepsTheNewestSummaryAndTheNewestReview() {
    comments.add(comment("S1", PrSummaryGenerator.SUMMARY_MARKER + "\nold", BOT, 1));
    comments.add(comment("S2", PrSummaryGenerator.SUMMARY_MARKER + "\nnew", BOT, 1));
    reviews.add(review("R1", "first", "COMMENTED", 1));
    reviews.add(review("R2", "second", "COMMENTED", 3));
    reviews.add(review("REPLY", "", "COMMENTED", 3));

    assertEquals(List.of("S1", "R1"), hidden());
  }

  @Test
  void leavesAStandingRequestForChangesInView() {
    reviews.add(review("R1", "blocks", "CHANGES_REQUESTED", 1));
    reviews.add(review("R2", "later note", "COMMENTED", 3));

    assertEquals(List.of(), hidden());
  }

  @Test
  void hidesARequestForChangesALaterApprovalReplaced() {
    reviews.add(review("R1", "blocks", "CHANGES_REQUESTED", 1));
    reviews.add(review("R2", "", "APPROVED", 3));
    reviews.add(review("PENDING", "draft", "PENDING", 3));

    assertEquals(List.of("R1"), hidden());
  }

  @Test
  void hidesOnlyTheBotsOwnEarlierNotices() {
    var failure = "⚠️ **ThrillhouseBot review could not be completed.**\n\nretry";
    comments.add(comment("OLD", failure, BOT, 1));
    comments.add(comment("DURING", failure, BOT, 3));
    comments.add(comment("HUMAN", failure, "octocat", 1));
    comments.add(comment("ANSWER", "## 🤖 ThrillhouseBot commands", BOT, 1));
    comments.add(new Entry("DONE", failure, BOT, at(1), true, ""));

    assertEquals(List.of("OLD"), hidden());
  }

  @Test
  void hidesARefusedReviewBodyOnceSomethingNewerReplacesIt() {
    var refused = ReviewPublisher.REVIEW_REFUSED_NOTE + "\n\nbody";
    comments.add(comment("C1", refused, BOT, 1));
    comments.add(comment("C2", refused, BOT, 3));

    assertEquals(List.of("C1"), hidden());
  }

  @Test
  void hidesTheRootOfAThreadGitHubWouldNotResolve() {
    hider.hide("auth", request(), START, List.of("ROOT1"));

    verify(visibility).minimize("auth", "ROOT1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Resource not accessible by integration", "Could not resolve to a node"})
  void aRefusalStopsThePassOnlyWhenTheAppMayNotMinimize(String message) {
    var errors = JsonNodeFactory.instance.arrayNode();
    errors.addObject().put("message", message);
    doThrow(GitHubGraphQLException.from(errors))
        .when(visibility)
        .minimize(anyString(), anyString());

    assertDoesNotThrow(() -> hider.hide("auth", request(), START, List.of("A", "B")));

    var attempts = message.startsWith("Resource") ? 1 : 2;
    verify(visibility, times(attempts)).minimize(anyString(), any());
  }

  @Test
  void recognizesTheReviewPathsNotices() {
    var identity = BotIdentity.of(BOT);
    for (var notice :
        List.of(
            RunningReviewCoverage.coveredNotice("abcdef1234"),
            MaintainerReplyService.CLEAR_DIRECTIVE_ACK,
            MaintainerReplyService.DECLINE_DIRECTIVE_ACK,
            MaintainerReplyService.CLEAR_DIRECTIVE_AMBIGUOUS_RANGE_ACK,
            MaintainerReplyService.DECLINE_DIRECTIVE_AMBIGUOUS_RANGE_ACK,
            MaintainerReplyService.clearDirectiveNoLocatorAck(identity),
            MaintainerReplyService.declineDirectiveNoLocatorAck(identity),
            MaintainerReplyService.clearDirectiveUnauthorizedAck(identity),
            MaintainerReplyService.declineDirectiveUnauthorizedAck(identity))) {
      assertTrue(OutdatedOutputHider.isNotice(notice), notice);
    }
  }

  private void hide() {
    hider.hide("auth", request(), START, List.of());
  }

  private List<String> hidden() {
    var ids = new ArrayList<String>();
    doAnswer(
            call -> {
              ids.add(call.getArgument(1));
              return null;
            })
        .when(visibility)
        .minimize(anyString(), anyString());
    hide();
    return ids;
  }

  private static ReviewOrchestrator.ReviewRequest request() {
    return new ReviewOrchestrator.ReviewRequest(
        "owner",
        "repo",
        7,
        "abcdefgh",
        "Test PR",
        "",
        "base1234567",
        "main",
        123L,
        1L,
        false,
        "main");
  }

  private static Instant at(int day) {
    return Instant.parse("2026-01-0" + day + "T00:00:00Z");
  }

  private static Entry comment(String id, String body, String login, int day) {
    return new Entry(id, body, login, at(day), false, "");
  }

  private static Entry review(String id, String body, String state, int day) {
    return new Entry(id, body, BOT, at(day), false, state);
  }
}
