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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import io.quarkiverse.githubapp.GitHubClientProvider;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerifiedWritersResolverTest {

  private static final String BOT = "thrillhousebot[bot]";

  @Mock private GitHubClientProvider clientProvider;
  @Mock private GitHub gitHub;
  @Mock private GHRepository repository;

  private VerifiedWritersResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    lenient().doReturn(gitHub).when(clientProvider).getInstallationClient(anyLong());
    lenient().doReturn(repository).when(gitHub).getRepository(anyString());
    resolver = new VerifiedWritersResolver(clientProvider, BotIdentity.of(BOT));
  }

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  private static GitHubReviewClient.PullRequestComment reply(String login, String association) {
    return new GitHubReviewClient.PullRequestComment(
        2L, 1L, "src/A.java", "won't fix", user(login), association);
  }

  private static GitHubCommentClient.IssueComment conversation(String login, String association) {
    return new GitHubCommentClient.IssueComment(3L, "a comment", user(login), association);
  }

  private static GitHubReviewClient.ReviewResponse.User user(String login) {
    return login == null ? null : new GitHubReviewClient.ReviewResponse.User(login);
  }

  @Test
  void aMemberWithWriteAccessIsConfirmedAndAMemberWithReadAccessIsNot() throws Exception {
    doReturn(GHPermissionType.WRITE).when(repository).getPermission("keeper");
    doReturn(GHPermissionType.READ).when(repository).getPermission("sorter");
    doReturn(GHPermissionType.ADMIN).when(repository).getPermission("admin");

    var writers =
        resolver.resolve(
            7L,
            "o",
            "r",
            List.of(reply("keeper", "MEMBER"), reply("sorter", "MEMBER")),
            List.of(conversation("admin", "COLLABORATOR")));

    assertTrue(writers.isWriter("keeper", "MEMBER"));
    assertTrue(writers.isWriter("admin", "COLLABORATOR"));
    assertFalse(writers.isWriter("sorter", "MEMBER"), "read access closes nothing");
    verify(clientProvider).getInstallationClient(7L);
    verify(gitHub).getRepository("o/r");
  }

  @Test
  void eachDistinctLoginIsAskedAboutOnce() throws Exception {
    doReturn(GHPermissionType.WRITE).when(repository).getPermission("Keeper");

    var writers =
        resolver.resolve(
            7L,
            "o",
            "r",
            List.of(reply("Keeper", "MEMBER"), reply("keeper", "MEMBER")),
            List.of(conversation("KEEPER", "OWNER"), conversation("Keeper", "MEMBER")));

    assertTrue(writers.isWriter("keeper", "OWNER"));
    verify(repository, times(1)).getPermission(anyString());
    verify(gitHub, times(1)).getRepository(anyString());
  }

  @Test
  void nobodyIsAskedAboutWhenNoAuthorCouldHoldWriteAccess() {
    var writers =
        resolver.resolve(
            7L,
            "o",
            "r",
            List.of(
                reply("fork-author", "CONTRIBUTOR"),
                reply("stranger", "NONE"),
                reply("unknown", null),
                reply(null, "MEMBER"),
                new GitHubReviewClient.PullRequestComment(
                    4L,
                    1L,
                    "src/A.java",
                    "a reply whose author has no login",
                    new GitHubReviewClient.ReviewResponse.User(null),
                    "MEMBER"),
                reply("  ", "MEMBER"),
                reply(BOT, "MEMBER")),
            List.of(
                conversation("drive-by", "FIRST_TIME_CONTRIBUTOR"), conversation(null, "OWNER")));

    assertSame(VerifiedWriters.none(), writers);
    verifyNoInteractions(clientProvider);
  }

  @Test
  void aPermissionCallThatFailsLeavesThatAuthorOut() throws Exception {
    doThrow(new IOException("502 from GitHub")).when(repository).getPermission("keeper");
    doReturn(GHPermissionType.WRITE).when(repository).getPermission("other");

    var writers =
        resolver.resolve(
            7L, "o", "r", List.of(reply("keeper", "MEMBER"), reply("other", "MEMBER")), List.of());

    assertFalse(writers.isWriter("keeper", "MEMBER"), "an unanswered lookup confirms nobody");
    assertTrue(writers.isWriter("other", "MEMBER"));
  }

  @Test
  void aRepositoryThatCannotBeReadConfirmsNobody() throws Exception {
    doThrow(new IOException("installation suspended")).when(gitHub).getRepository("o/r");

    var writers = resolver.resolve(7L, "o", "r", List.of(reply("keeper", "OWNER")), List.of());

    assertSame(VerifiedWriters.none(), writers);
    verify(repository, never()).getPermission(anyString());
  }

  @Test
  void aReviewWithNoInstallationConfirmsNobody() {
    var writers = resolver.resolve(0L, "o", "r", List.of(reply("keeper", "OWNER")), List.of());

    assertSame(VerifiedWriters.none(), writers);
    verifyNoInteractions(clientProvider);
  }

  @Test
  void anInterruptedReviewStopsAskingAndConfirmsNobodyElse() throws Exception {
    Thread.currentThread().interrupt();

    var writers = resolver.resolve(7L, "o", "r", List.of(reply("keeper", "OWNER")), List.of());

    assertSame(VerifiedWriters.none(), writers);
    verify(repository, never()).getPermission(anyString());
  }
}
