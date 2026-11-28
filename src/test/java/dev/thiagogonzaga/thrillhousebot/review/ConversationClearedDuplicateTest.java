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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A finding cleared from the PR conversation and raised again in other words (#1430). A thread-less
 * "Things to double-check" item can only be cleared there.
 */
class ConversationClearedDuplicateTest {

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final String FILE = "compose.yaml";

  private final ReviewResponse.Finding prior =
      finding(
          "The database port is published to every network interface",
          "The ports mapping has no host address, so the database listens on every interface.");

  private final ReviewResponse.Finding reRaised =
      finding(
          "The database port is published on every network interface",
          "Without a host address in the ports mapping the database accepts outside connections.");

  /** The newest round raised nothing; the older one raised the finding. */
  private final List<ReviewResponse> priorRounds =
      List.of(
          new ReviewResponse(List.of(), List.of(), null),
          new ReviewResponse(List.of(prior), List.of(), null));

  /** Newest first, like the rounds: the directive below is written between the two. */
  private final List<Instant> roundStarts =
      List.of(Instant.parse("2026-01-03T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

  private final List<GitHubCommentClient.IssueComment> cleared =
      List.of(says("@thrillhousebot resolved `compose.yaml:45` — " + prior.title(), "MEMBER"));

  @Test
  void theReRaiseIsTheSameDefectByTheFollowUpMatching() {
    assertTrue(
        FollowUpAnalyzer.isSameFinding(reRaised, prior),
        "the pair must read as a re-raise, or this suite tests nothing");
  }

  @Test
  void aClearedItemRaisedAgainOnAnUnchangedFileIsDropped() {
    var kept = screen(reRaised, cleared, unchangedSinceTheOlderRound());

    assertTrue(kept.findings().isEmpty());
  }

  @Test
  void aClearedItemRaisedAgainOnAChangedFileIsKept() {
    var kept = screen(reRaised, cleared, (round, path) -> DiffLineResolver.FileChange.CHANGED);

    assertEquals(List.of(reRaised), kept.findings());
  }

  @Test
  void aFileWhoseChangeCannotBeToldKeepsTheFinding() {
    var kept = screen(reRaised, cleared, DiffLineResolver.RoundChanges.NONE);

    assertEquals(List.of(reRaised), kept.findings());
  }

  @Test
  void aChangeToAnotherFileTheFindingNamesKeepsIt() {
    var naming = finding(prior.title(), prior.description() + " The deploy.env file sets none.");
    var response = new ReviewResponse(List.of(reRaised), List.of(), null);

    var kept =
        FollowUpAnalyzer.withoutConversationCleared(
            response,
            List.of(new ReviewResponse(List.of(naming), List.of(), null)),
            List.of(roundStarts.get(1)),
            cleared,
            new DiffLineResolver(
                Map.of(),
                List.of(FILE, "deploy.env"),
                DiffLineResolver.HeadFiles.NONE,
                (round, path) ->
                    FILE.equals(path)
                        ? DiffLineResolver.FileChange.UNCHANGED
                        : DiffLineResolver.FileChange.CHANGED),
            BOT,
            KnownWriters.ALL);

    assertEquals(List.of(reRaised), kept.findings());
  }

  @Test
  void aDirectiveDoesNotCoverARoundThatStartedAfterIt() {
    var response = new ReviewResponse(List.of(reRaised), List.of(), null);
    var raisedAgainAfterTheClear = List.of(new ReviewResponse(List.of(prior), List.of(), null));

    var kept =
        FollowUpAnalyzer.withoutConversationCleared(
            response,
            raisedAgainAfterTheClear,
            List.of(roundStarts.get(0)),
            cleared,
            resolver((round, path) -> DiffLineResolver.FileChange.UNCHANGED),
            BOT,
            KnownWriters.ALL);

    assertEquals(List.of(reRaised), kept.findings());
  }

  @Test
  void aMissingOrUnreadableTimeClearsNothing() {
    var directive = cleared.get(0).body();
    var untimed =
        List.of(says(directive, "MEMBER", null), says(directive, "MEMBER", "not-a-timestamp"));

    var keptForTheComments = screen(reRaised, untimed, unchangedSinceTheOlderRound());
    var keptForTheRounds =
        FollowUpAnalyzer.withoutConversationCleared(
            new ReviewResponse(List.of(reRaised), List.of(), null),
            priorRounds,
            Arrays.asList(null, null),
            cleared,
            resolver(unchangedSinceTheOlderRound()),
            BOT,
            KnownWriters.ALL);

    assertEquals(List.of(reRaised), keptForTheComments.findings());
    assertEquals(List.of(reRaised), keptForTheRounds.findings());
  }

  @Test
  void aDifferentDefectInTheSameFileIsKept() {
    var other =
        finding(
            "Container image tag floats between releases",
            "Pulling latest makes each deployment run whatever was pushed most recently.");

    var kept = screen(other, cleared, unchangedSinceTheOlderRound());

    assertEquals(List.of(other), kept.findings());
  }

  @Test
  void aCommentFromSomeoneWithoutWriteAccessClearsNothing() {
    var drive = List.of(says(cleared.get(0).body(), "CONTRIBUTOR"));

    var kept = screen(reRaised, drive, unchangedSinceTheOlderRound());

    assertEquals(List.of(reRaised), kept.findings());
  }

  @Test
  void anItemNobodyClearedIsKeptWithoutComparingTheFile() {
    var unrelated = List.of(says("Looks good to me.", "MEMBER"));

    var kept = screen(reRaised, unrelated, (round, path) -> fail("no comparison was needed"));

    assertEquals(List.of(reRaised), kept.findings());
  }

  @Test
  void aConversationWithNoCommentLeavesTheResponseAsItWas() {
    var response = new ReviewResponse(List.of(reRaised), List.of(), null);

    assertSame(
        response,
        FollowUpAnalyzer.withoutConversationCleared(
            response,
            priorRounds,
            roundStarts,
            List.of(),
            resolver(DiffLineResolver.RoundChanges.NONE),
            BOT,
            KnownWriters.ALL));
  }

  /** The file holds what the older round saw, and differs from what the newest one saw. */
  private static DiffLineResolver.RoundChanges unchangedSinceTheOlderRound() {
    return (round, path) ->
        round == 1 ? DiffLineResolver.FileChange.UNCHANGED : DiffLineResolver.FileChange.CHANGED;
  }

  private ReviewResponse screen(
      ReviewResponse.Finding finding,
      List<GitHubCommentClient.IssueComment> comments,
      DiffLineResolver.RoundChanges changes) {
    return FollowUpAnalyzer.withoutConversationCleared(
        new ReviewResponse(List.of(finding), List.of(), null),
        priorRounds,
        roundStarts,
        comments,
        resolver(changes),
        BOT,
        KnownWriters.ALL);
  }

  private static DiffLineResolver resolver(DiffLineResolver.RoundChanges changes) {
    return new DiffLineResolver(Map.of(), List.of(), DiffLineResolver.HeadFiles.NONE, changes);
  }

  private static GitHubCommentClient.IssueComment says(String body, String association) {
    return says(body, association, "2026-01-02T00:00:00Z");
  }

  private static GitHubCommentClient.IssueComment says(
      String body, String association, String createdAt) {
    return new GitHubCommentClient.IssueComment(
        901L,
        body,
        new GitHubReviewClient.ReviewResponse.User("maintainer"),
        association,
        createdAt);
  }

  private static ReviewResponse.Finding finding(String title, String description) {
    return new ReviewResponse.Finding("medium", "low", FILE, 45, title, description, null, null);
  }
}
