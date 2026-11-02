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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

class RunningReviewCoverageTest {

  private static final String ACCEPT = "application/vnd.github+json";

  private final GitHubAuthClient authClient = mock(GitHubAuthClient.class);
  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);
  private final RunningReviewCoverage coverage =
      new RunningReviewCoverage(authClient, prClient, commentClient);
  private final ReviewOrchestrator.ReviewRequest req =
      new ReviewOrchestrator.ReviewRequest(
          "owner",
          "repo",
          7,
          "",
          "(manual review)",
          "",
          "",
          "main",
          3L,
          TestScopes.REPOSITORY_ID,
          true);

  @BeforeEach
  void setUp() {
    when(authClient.getAuthHeader(3L)).thenReturn("Bearer t");
  }

  @Test
  void shouldReadTheCurrentHead() {
    when(prClient.getPullRequest("Bearer t", ACCEPT, "owner", "repo", 7))
        .thenReturn(details(new GitHubPullRequestClient.Ref("abc1234def")));

    assertEquals(Optional.of("abc1234def"), coverage.currentHead(req));
  }

  @Test
  void shouldFallBackAndLogWhenTheHeadReadFails() {
    when(prClient.getPullRequest(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenThrow(new IllegalStateException("502"));

    var logged = captureLog(() -> assertEquals(Optional.empty(), coverage.currentHead(req)));

    assertWarned(logged, "Could not read the head of owner/repo #7 — queueing the /review");
  }

  @Test
  void shouldFallBackAndLogWhenTheTokenCannotBeMinted() {
    when(authClient.getAuthHeader(3L)).thenThrow(new IllegalStateException("no key"));

    var logged = captureLog(() -> assertEquals(Optional.empty(), coverage.currentHead(req)));

    assertWarned(logged, "Could not read the head of owner/repo #7 — queueing the /review");
  }

  @Test
  void shouldFallBackAndLogWhenGitHubNamesNoHead() {
    when(prClient.getPullRequest("Bearer t", ACCEPT, "owner", "repo", 7))
        .thenReturn(details(null))
        .thenReturn(details(new GitHubPullRequestClient.Ref(" ")))
        .thenReturn(null);

    var logged =
        captureLog(
            () -> {
              assertEquals(Optional.empty(), coverage.currentHead(req));
              assertEquals(Optional.empty(), coverage.currentHead(req));
              assertEquals(Optional.empty(), coverage.currentHead(req));
            });

    assertEquals(3, logged.size());
    assertWarned(logged, "GitHub returned no head for owner/repo #7 — queueing the /review");
  }

  @Test
  void shouldReplyThatTheRunningReviewCoversTheHead() {
    coverage.notifyCovered(req, "abc1234def");

    verify(commentClient)
        .createComment(
            "Bearer t",
            ACCEPT,
            "owner",
            "repo",
            7,
            new GitHubCommentClient.CreateCommentRequest(
                RunningReviewCoverage.coveredNotice("abc1234def")));
    assertTrue(RunningReviewCoverage.coveredNotice("abc1234def").contains("`abc1234`"));
    assertTrue(RunningReviewCoverage.coveredNotice("abc").contains("`abc`"));
  }

  @Test
  void shouldLogAndSwallowAFailedReply() {
    when(commentClient.createComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenThrow(new IllegalStateException("403"));

    var logged = captureLog(() -> coverage.notifyCovered(req, "abc1234def"));

    assertWarned(logged, "Failed to reply to /review on owner/repo #7");
    verify(commentClient)
        .createComment(eq("Bearer t"), eq(ACCEPT), eq("owner"), eq("repo"), eq(7), any());
  }

  private static GitHubPullRequestClient.PullRequestDetails details(
      GitHubPullRequestClient.Ref head) {
    return new GitHubPullRequestClient.PullRequestDetails("title", "", head, null);
  }

  private static void assertWarned(List<LogRecord> logged, String message) {
    assertTrue(
        logged.stream()
            .anyMatch(
                entry ->
                    entry.getLevel().intValue() == Level.WARNING.intValue()
                        && MessageFormatter.arrayFormat(entry.getMessage(), entry.getParameters())
                            .getMessage()
                            .equals(message)),
        () -> "expected warning: " + message);
  }

  private static List<LogRecord> captureLog(Runnable action) {
    var julLogger = Logger.getLogger(RunningReviewCoverage.class.getName());
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var capture =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            logged.add(entry);
          }

          @Override
          public void flush() {
            // Nothing is buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    julLogger.addHandler(capture);
    try {
      action.run();
    } finally {
      julLogger.removeHandler(capture);
    }
    return logged;
  }
}
