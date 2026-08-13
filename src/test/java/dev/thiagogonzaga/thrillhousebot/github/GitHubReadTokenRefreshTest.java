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
package dev.thiagogonzaga.thrillhousebot.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #626: a read issued late in a long review still carries the token resolved at the review's start,
 * so once that token expires the read draws {@code 401 Bad credentials} exactly as the writes did
 * before #624. Every installation-token read now routes through {@link GitHubTokenRefresh} the way
 * the writes do: one repeat with a freshly minted token, nothing more.
 */
class GitHubReadTokenRefreshTest {

  private static final String DEAD = "Bearer expired-token";
  private static final String FRESH = "Bearer minted-token";

  @BeforeEach
  void bindFresh() {
    GitHubTokenRefresh.SHARED.bind(_ -> Optional.of(FRESH));
  }

  @AfterEach
  void unbind() {
    GitHubTokenRefresh.SHARED.bind(null);
  }

  private static WebApplicationException badCredentials() {
    return new WebApplicationException(
        Response.status(401).entity("{\"message\":\"Bad credentials\"}").build());
  }

  @Test
  void aPrReadRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubPullRequestClient.class);
    doCallRealMethod()
        .when(client)
        .getPullRequest(anyString(), anyString(), anyString(), anyString(), anyInt());
    var details =
        new GitHubPullRequestClient.PullRequestDetails(
            "t", "b", new GitHubPullRequestClient.Ref("s"), new GitHubPullRequestClient.Ref("m"));
    when(client.getPullRequestOnce(DEAD, "json", "o", "r", 7)).thenThrow(badCredentials());
    when(client.getPullRequestOnce(FRESH, "json", "o", "r", 7)).thenReturn(details);

    assertSame(details, client.getPullRequest(DEAD, "json", "o", "r", 7));
  }

  @Test
  void aFreshTokenReadIsASingleCallAndNoMint() {
    var client = mock(GitHubPullRequestClient.class);
    doCallRealMethod()
        .when(client)
        .getPullRequest(anyString(), anyString(), anyString(), anyString(), anyInt());
    var details =
        new GitHubPullRequestClient.PullRequestDetails(
            "t", "b", new GitHubPullRequestClient.Ref("s"), new GitHubPullRequestClient.Ref("m"));
    when(client.getPullRequestOnce(FRESH, "json", "o", "r", 7)).thenReturn(details);

    assertSame(details, client.getPullRequest(FRESH, "json", "o", "r", 7));
    verify(client, times(1))
        .getPullRequestOnce(anyString(), anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void aRejectionNoFreshTokenCanFixPropagatesAfterOneAttempt() {
    GitHubTokenRefresh.SHARED.bind(null);
    var client = mock(GitHubPullRequestClient.class);
    doCallRealMethod()
        .when(client)
        .getPullRequest(anyString(), anyString(), anyString(), anyString(), anyInt());
    var rejection = badCredentials();
    when(client.getPullRequestOnce(DEAD, "json", "o", "r", 7)).thenThrow(rejection);

    var thrown =
        assertThrows(
            WebApplicationException.class, () -> client.getPullRequest(DEAD, "json", "o", "r", 7));

    assertSame(rejection, thrown);
    verify(client, times(1))
        .getPullRequestOnce(anyString(), anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void aFilesPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubPullRequestClient.class);
    doCallRealMethod()
        .when(client)
        .getPullRequestFilesPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    var files = List.of(new GitHubPullRequestClient.FileDiff("f", "added", 1, 0, 1, "@@"));
    when(client.getPullRequestFilesPageOnce(DEAD, "json", "o", "r", 7, 100, 2))
        .thenThrow(badCredentials());
    when(client.getPullRequestFilesPageOnce(FRESH, "json", "o", "r", 7, 100, 2)).thenReturn(files);

    assertSame(files, client.getPullRequestFilesPage(DEAD, "json", "o", "r", 7, 100, 2));
  }

  @Test
  void aComparisonRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubPullRequestClient.class);
    doCallRealMethod()
        .when(client)
        .compareCommits(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    var compare = new GitHubPullRequestClient.CompareResponse(1, List.of());
    when(client.compareCommitsOnce(DEAD, "json", "o", "r", "a", "b")).thenThrow(badCredentials());
    when(client.compareCommitsOnce(FRESH, "json", "o", "r", "a", "b")).thenReturn(compare);

    assertSame(compare, client.compareCommits(DEAD, "json", "o", "r", "a", "b"));
  }

  @Test
  void aFileContentReadRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubPullRequestClient.class);
    doCallRealMethod()
        .when(client)
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    var content = new GitHubPullRequestClient.FileContent("f", "p/f", "Zm9v", "base64", 3);
    when(client.getFileContentOnce(DEAD, "json", "o", "r", "p/f", "sha"))
        .thenThrow(badCredentials());
    when(client.getFileContentOnce(FRESH, "json", "o", "r", "p/f", "sha")).thenReturn(content);

    assertSame(content, client.getFileContent(DEAD, "json", "o", "r", "p/f", "sha"));
  }

  @Test
  void aTreeReadRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubPullRequestClient.class);
    doCallRealMethod()
        .when(client)
        .getTree(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    var tree = new GitHubPullRequestClient.TreeResponse("sha", List.of(), false);
    when(client.getTreeOnce(DEAD, "json", "o", "r", "sha", "1")).thenThrow(badCredentials());
    when(client.getTreeOnce(FRESH, "json", "o", "r", "sha", "1")).thenReturn(tree);

    assertSame(tree, client.getTree(DEAD, "json", "o", "r", "sha", "1"));
  }

  @Test
  void requiredStatusChecksRejectedForTheirCredentialAreRepeatedWithAFreshOne() {
    var client = mock(GitHubCheckRunClient.class);
    doCallRealMethod()
        .when(client)
        .getRequiredStatusChecks(anyString(), anyString(), anyString(), anyString(), anyString());
    var checks = new GitHubCheckRunClient.RequiredStatusChecks(List.of("ci"), List.of());
    when(client.getRequiredStatusChecksOnce(DEAD, "json", "o", "r", "main"))
        .thenThrow(badCredentials());
    when(client.getRequiredStatusChecksOnce(FRESH, "json", "o", "r", "main")).thenReturn(checks);

    assertSame(checks, client.getRequiredStatusChecks(DEAD, "json", "o", "r", "main"));
  }

  @Test
  void branchRulesRejectedForTheirCredentialAreRepeatedWithAFreshOne() {
    var client = mock(GitHubCheckRunClient.class);
    doCallRealMethod()
        .when(client)
        .getBranchRules(anyString(), anyString(), anyString(), anyString(), anyString());
    var rules = List.of(new GitHubCheckRunClient.BranchRule("required_status_checks", null));
    when(client.getBranchRulesOnce(DEAD, "json", "o", "r", "main")).thenThrow(badCredentials());
    when(client.getBranchRulesOnce(FRESH, "json", "o", "r", "main")).thenReturn(rules);

    assertSame(rules, client.getBranchRules(DEAD, "json", "o", "r", "main"));
  }

  @Test
  void aCheckRunsPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubCheckRunClient.class);
    doCallRealMethod()
        .when(client)
        .getCheckRuns(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
    var runs = new GitHubCheckRunClient.CheckRunsResponse(0, List.of());
    when(client.getCheckRunsOnce(DEAD, "json", "o", "r", "sha", 100, 1))
        .thenThrow(badCredentials());
    when(client.getCheckRunsOnce(FRESH, "json", "o", "r", "sha", 100, 1)).thenReturn(runs);

    assertSame(runs, client.getCheckRuns(DEAD, "json", "o", "r", "sha", 100, 1));
  }

  @Test
  void aCombinedStatusPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubCheckRunClient.class);
    doCallRealMethod()
        .when(client)
        .getCombinedStatus(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
    var status = new GitHubCheckRunClient.CombinedStatus("success", 0, List.of());
    when(client.getCombinedStatusOnce(DEAD, "json", "o", "r", "sha", 100, 1))
        .thenThrow(badCredentials());
    when(client.getCombinedStatusOnce(FRESH, "json", "o", "r", "sha", 100, 1)).thenReturn(status);

    assertSame(status, client.getCombinedStatus(DEAD, "json", "o", "r", "sha", 100, 1));
  }

  @Test
  void aGraphQlOperationRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubGraphQLClient.class);
    doCallRealMethod().when(client).execute(anyString(), any());
    var request = new GitHubGraphQLClient.GraphQLRequest("query {}", Map.of());
    var result = JsonNodeFactory.instance.objectNode();
    when(client.executeOnce(DEAD, request)).thenThrow(badCredentials());
    when(client.executeOnce(FRESH, request)).thenReturn(result);

    assertSame(result, client.execute(DEAD, request));
  }

  @Test
  void aCommentsPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubCommentClient.class);
    doCallRealMethod()
        .when(client)
        .listCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    var comments = List.of(new GitHubCommentClient.IssueComment(1L, "hi", null, null));
    when(client.listCommentsPageOnce(DEAD, "json", "o", "r", 7, 100, 1))
        .thenThrow(badCredentials());
    when(client.listCommentsPageOnce(FRESH, "json", "o", "r", 7, 100, 1)).thenReturn(comments);

    assertSame(comments, client.listCommentsPage(DEAD, "json", "o", "r", 7, 100, 1));
  }

  @Test
  void anIssueReadRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubCommentClient.class);
    doCallRealMethod()
        .when(client)
        .getIssue(anyString(), anyString(), anyString(), anyString(), anyInt());
    var issue = new GitHubCommentClient.IssueDetails(7, "t", "b");
    when(client.getIssueOnce(DEAD, "json", "o", "r", 7)).thenThrow(badCredentials());
    when(client.getIssueOnce(FRESH, "json", "o", "r", 7)).thenReturn(issue);

    assertSame(issue, client.getIssue(DEAD, "json", "o", "r", 7));
  }

  @Test
  void aReviewsPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubReviewClient.class);
    doCallRealMethod()
        .when(client)
        .listReviewsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    var reviews = List.of(new GitHubReviewClient.ReviewResponse(1L, null, null, null, null));
    when(client.listReviewsPageOnce(DEAD, "json", "o", "r", 7, 100, 1)).thenThrow(badCredentials());
    when(client.listReviewsPageOnce(FRESH, "json", "o", "r", 7, 100, 1)).thenReturn(reviews);

    assertSame(reviews, client.listReviewsPage(DEAD, "json", "o", "r", 7, 100, 1));
  }

  @Test
  void anInlineCommentsPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubReviewClient.class);
    doCallRealMethod()
        .when(client)
        .listPullRequestCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    List<GitHubReviewClient.PullRequestComment> comments = List.of();
    when(client.listPullRequestCommentsPageOnce(DEAD, "json", "o", "r", 7, 100, 1))
        .thenThrow(badCredentials());
    when(client.listPullRequestCommentsPageOnce(FRESH, "json", "o", "r", 7, 100, 1))
        .thenReturn(comments);

    assertSame(comments, client.listPullRequestCommentsPage(DEAD, "json", "o", "r", 7, 100, 1));
  }

  @Test
  void anInlineCommentReadRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubReviewClient.class);
    doCallRealMethod()
        .when(client)
        .getPullRequestComment(anyString(), anyString(), anyString(), anyString(), anyLong());
    when(client.getPullRequestCommentOnce(DEAD, "json", "o", "r", 5L)).thenThrow(badCredentials());
    var comment = mock(GitHubReviewClient.PullRequestComment.class);
    when(client.getPullRequestCommentOnce(FRESH, "json", "o", "r", 5L)).thenReturn(comment);

    assertSame(comment, client.getPullRequestComment(DEAD, "json", "o", "r", 5L));
  }

  @Test
  void aLabelsPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubLabelClient.class);
    doCallRealMethod()
        .when(client)
        .listLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
    var labels = List.of(new GitHubLabelClient.Label("bug", "d", "f00"));
    when(client.listLabelsOnce(DEAD, "json", "o", "r", 100, 1)).thenThrow(badCredentials());
    when(client.listLabelsOnce(FRESH, "json", "o", "r", 100, 1)).thenReturn(labels);

    assertSame(labels, client.listLabels(DEAD, "json", "o", "r", 100, 1));
  }

  @Test
  void anIssueLabelsPageRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var client = mock(GitHubLabelClient.class);
    doCallRealMethod()
        .when(client)
        .listIssueLabels(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    var labels = List.of(new GitHubLabelClient.Label("bug", "d", "f00"));
    when(client.listIssueLabelsOnce(DEAD, "json", "o", "r", 7, 100, 1)).thenThrow(badCredentials());
    when(client.listIssueLabelsOnce(FRESH, "json", "o", "r", 7, 100, 1)).thenReturn(labels);

    assertSame(labels, client.listIssueLabels(DEAD, "json", "o", "r", 7, 100, 1));
  }

  @Test
  void inlineReactionsRejectedForTheirCredentialAreRepeatedWithAFreshOne() {
    var client = mock(GitHubReactionClient.class);
    doCallRealMethod()
        .when(client)
        .listReviewCommentReactions(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString(),
            anyInt(),
            anyInt());
    var reactions = List.of(new GitHubReactionClient.Reaction(1L, "eyes", null, null));
    when(client.listReviewCommentReactionsOnce(DEAD, "json", "o", "r", 5L, "eyes", 100, 1))
        .thenThrow(badCredentials());
    when(client.listReviewCommentReactionsOnce(FRESH, "json", "o", "r", 5L, "eyes", 100, 1))
        .thenReturn(reactions);

    assertSame(
        reactions, client.listReviewCommentReactions(DEAD, "json", "o", "r", 5L, "eyes", 100, 1));
  }

  @Test
  void issueCommentReactionsRejectedForTheirCredentialAreRepeatedWithAFreshOne() {
    var client = mock(GitHubReactionClient.class);
    doCallRealMethod()
        .when(client)
        .listIssueCommentReactions(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString(),
            anyInt(),
            anyInt());
    var reactions = List.of(new GitHubReactionClient.Reaction(1L, "eyes", null, null));
    when(client.listIssueCommentReactionsOnce(DEAD, "json", "o", "r", 5L, "eyes", 100, 1))
        .thenThrow(badCredentials());
    when(client.listIssueCommentReactionsOnce(FRESH, "json", "o", "r", 5L, "eyes", 100, 1))
        .thenReturn(reactions);

    assertSame(
        reactions, client.listIssueCommentReactions(DEAD, "json", "o", "r", 5L, "eyes", 100, 1));
  }

  @Test
  void workflowRunsRejectedForTheirCredentialAreRepeatedWithAFreshOne() {
    var client = mock(GitHubActionsClient.class);
    doCallRealMethod()
        .when(client)
        .listWorkflowRuns(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
    var runs = new GitHubActionsClient.WorkflowRuns(0, List.of());
    when(client.listWorkflowRunsOnce(DEAD, "json", "o", "r", "sha", "completed", 100))
        .thenThrow(badCredentials());
    when(client.listWorkflowRunsOnce(FRESH, "json", "o", "r", "sha", "completed", 100))
        .thenReturn(runs);

    assertSame(runs, client.listWorkflowRuns(DEAD, "json", "o", "r", "sha", "completed", 100));
  }

  @Test
  void runArtifactsRejectedForTheirCredentialAreRepeatedWithAFreshOne() {
    var client = mock(GitHubActionsClient.class);
    doCallRealMethod()
        .when(client)
        .listRunArtifacts(anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
    var artifacts = new GitHubActionsClient.RunArtifacts(0, List.of());
    when(client.listRunArtifactsOnce(DEAD, "json", "o", "r", 9L, 100)).thenThrow(badCredentials());
    when(client.listRunArtifactsOnce(FRESH, "json", "o", "r", 9L, 100)).thenReturn(artifacts);

    assertSame(artifacts, client.listRunArtifacts(DEAD, "json", "o", "r", 9L, 100));
  }

  @Test
  void anArtifactDownloadAnswered401IsRepeatedWithAFreshTokenAndTheDeadResponseClosed() {
    var client = mock(GitHubActionsClient.class);
    doCallRealMethod()
        .when(client)
        .downloadArtifact(anyString(), anyString(), anyString(), anyString(), anyLong());
    var rejected = Response.status(401).entity("{\"message\":\"Bad credentials\"}").build();
    var redirect = Response.status(302).header("Location", "https://blob.example/z").build();
    when(client.downloadArtifactOnce(DEAD, "json", "o", "r", 9L)).thenReturn(rejected);
    when(client.downloadArtifactOnce(FRESH, "json", "o", "r", 9L)).thenReturn(redirect);

    assertSame(redirect, client.downloadArtifact(DEAD, "json", "o", "r", 9L));
  }

  @Test
  void anArtifactDownload401NoFreshTokenCanFixIsReturnedAsIs() {
    GitHubTokenRefresh.SHARED.bind(null);
    var client = mock(GitHubActionsClient.class);
    doCallRealMethod()
        .when(client)
        .downloadArtifact(anyString(), anyString(), anyString(), anyString(), anyLong());
    var rejected = Response.status(401).entity("{\"message\":\"Bad credentials\"}").build();
    when(client.downloadArtifactOnce(DEAD, "json", "o", "r", 9L)).thenReturn(rejected);

    assertSame(rejected, client.downloadArtifact(DEAD, "json", "o", "r", 9L));
    verify(client, times(1))
        .downloadArtifactOnce(anyString(), anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  void anArtifactDownloadRedirectPassesThroughUntouched() {
    var client = mock(GitHubActionsClient.class);
    doCallRealMethod()
        .when(client)
        .downloadArtifact(anyString(), anyString(), anyString(), anyString(), anyLong());
    var redirect = Response.status(302).header("Location", "https://blob.example/z").build();
    when(client.downloadArtifactOnce(FRESH, "json", "o", "r", 9L)).thenReturn(redirect);

    assertSame(redirect, client.downloadArtifact(FRESH, "json", "o", "r", 9L));
    verify(client, times(1))
        .downloadArtifactOnce(anyString(), anyString(), anyString(), anyString(), anyLong());
  }
}
