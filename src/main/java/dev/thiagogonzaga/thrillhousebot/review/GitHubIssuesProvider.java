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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The GitHub Issues {@link IssueTrackerProvider} (#58): links a pull request to issues of its own
 * repository and reads their title and body.
 *
 * <p>Links are taken from three sources, in this precedence:
 *
 * <ol>
 *   <li>Closing keywords in the PR body ({@code Closes #57}, {@code fixes owner/repo#57}, {@code
 *       resolves https://github.com/owner/repo/issues/57}), in the order they appear. This is what
 *       the author wrote, it costs no request, and GitHub only records these links itself when the
 *       base is the default branch, so for a PR into a release branch it is the only source. Code
 *       spans, fenced blocks and HTML comments are skipped: a PR template's commented-out {@code
 *       Closes #} example is not a link.
 *   <li>GitHub's {@code closingIssuesReferences} (one GraphQL read), which adds issues linked from
 *       the Development panel. An error or a missing permission there is not fatal; the body's
 *       links stand on their own.
 *   <li>The head branch name ({@code issue-57}, {@code fix/57-short-name}), taken from the same
 *       GraphQL read, and only when the switch is on and the two sources above linked nothing: a
 *       branch name is a guess, so it never adds to an explicit link.
 * </ol>
 *
 * <p>Only issues of the pull request's own repository are read; a reference to another repository
 * is ignored, since its issue is not what this repository's reviewers maintain, and the app may not
 * be installed there. A number that names a pull request, the PR's own number, and an issue that
 * cannot be read (missing, deleted, private to the credential) are skipped. Nothing is written.
 */
@ApplicationScoped
public class GitHubIssuesProvider implements IssueTrackerProvider {

  private static final String ACCEPT = "application/vnd.github+json";

  /** Candidate numbers tried per review, so a body full of references cannot fan out requests. */
  static final int MAX_CANDIDATES = 10;

  static final String VIA_BODY = "a closing keyword in the PR body";
  static final String VIA_CLOSING_REFERENCE = "GitHub's closing references for this PR";
  static final String VIA_BRANCH = "the head branch name";

  // One page of closing references: ten is well above any issue cap the config accepts.
  static final String LINKS_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            headRefName
            closingIssuesReferences(first: 10) {
              nodes { number repository { nameWithOwner } }
            }
          }
        }
      }""";

  /**
   * GitHub's closing keywords ({@code close/fix/resolve} and inflections, optional colon) followed
   * by one issue reference: {@code #n}, {@code owner/repo#n} or an issue URL on github.com.
   */
  private static final Pattern CLOSING_REFERENCE =
      Pattern.compile(
          "(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b:?\\s+"
              + "(?:(?<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#"
              + "|https?://github\\.com/(?<urlrepo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/issues/"
              + "|#)"
              + "(?<num>\\d{1,9})\\b");

  // Markdown regions whose text GitHub does not treat as a link: HTML comments, fenced blocks and
  // inline code spans. Removed before the body is scanned.
  private static final Pattern NON_LINK_REGIONS =
      Pattern.compile("(?s)<!--.*?-->|```.*?(?:```|$)|~~~.*?(?:~~~|$)|`[^`\\n]*`");

  /**
   * An issue number in the last segment of a branch name: {@code 57}, {@code 57-short-name}, or one
   * of a few conventional prefixes before it ({@code issue-57}, {@code gh-57}, {@code fix-57}).
   */
  private static final Pattern BRANCH_ISSUE =
      Pattern.compile(
          "(?i)^(?:(?:issues?|gh|bug|fix|feat|feature|task)[-_]?)?#?(\\d{1,9})(?:[-_].*)?$");

  private final GitHubCommentClient commentClient;
  private final GitHubGraphQLClient graphQLClient;

  @Inject
  public GitHubIssuesProvider(
      @RestClient GitHubCommentClient commentClient,
      @RestClient GitHubGraphQLClient graphQLClient) {
    this.commentClient = commentClient;
    this.graphQLClient = graphQLClient;
  }

  @Override
  public String name() {
    return ThrillhouseConfig.TicketContextConfig.GITHUB_PROVIDER;
  }

  @Override
  public List<LinkedTicket> linkedTickets(TicketLookup lookup, int limit) {
    var tickets = new ArrayList<LinkedTicket>();
    for (var candidate : candidates(lookup, limit).entrySet()) {
      if (tickets.size() >= limit) {
        break;
      }
      var ticket = read(lookup, candidate.getKey(), candidate.getValue());
      if (ticket != null) {
        tickets.add(ticket);
      }
    }
    return tickets;
  }

  /** Candidate issue numbers, in precedence order, each with how it was linked. */
  private Map<Integer, String> candidates(TicketLookup lookup, int limit) {
    var candidates = new LinkedHashMap<Integer, String>();
    for (int number : bodyReferences(lookup.prBody(), lookup.owner(), lookup.repo())) {
      addCandidate(candidates, lookup, number, VIA_BODY);
    }
    if (candidates.size() >= limit) {
      return candidates;
    }
    var links = readLinks(lookup);
    for (int number : closingReferences(links, lookup.owner(), lookup.repo())) {
      addCandidate(candidates, lookup, number, VIA_CLOSING_REFERENCE);
    }
    if (candidates.isEmpty() && lookup.fromBranch()) {
      var branchNumber = branchIssueNumber(links.path("headRefName").asText(""));
      if (branchNumber > 0) {
        addCandidate(candidates, lookup, branchNumber, VIA_BRANCH);
      }
    }
    return candidates;
  }

  private static void addCandidate(
      Map<Integer, String> candidates, TicketLookup lookup, int number, String via) {
    if (number > 0
        && number != lookup.prNumber()
        && candidates.size() < MAX_CANDIDATES
        && !candidates.containsKey(number)) {
      candidates.put(number, via);
    }
  }

  /**
   * Issue numbers of {@code owner/repo} named by closing keywords in {@code prBody}, in order,
   * deduplicated. A reference to another repository is dropped; the repository comparison ignores
   * case, as GitHub does.
   */
  static List<Integer> bodyReferences(String prBody, String owner, String repo) {
    if (prBody == null || prBody.isBlank()) {
      return List.of();
    }
    var visible = NON_LINK_REGIONS.matcher(prBody).replaceAll(" ");
    var numbers = new ArrayList<Integer>();
    var matcher = CLOSING_REFERENCE.matcher(visible);
    while (matcher.find()) {
      var named = matcher.group("repo") != null ? matcher.group("repo") : matcher.group("urlrepo");
      if (named != null && !sameRepository(named, owner, repo)) {
        continue;
      }
      var number = Integer.parseInt(matcher.group("num"));
      if (!numbers.contains(number)) {
        numbers.add(number);
      }
    }
    return numbers;
  }

  /** The pull request node of the links query, or a missing node when it could not be read. */
  private JsonNode readLinks(TicketLookup lookup) {
    try {
      var variables = new LinkedHashMap<String, Object>();
      variables.put("owner", lookup.owner());
      variables.put("name", lookup.repo());
      variables.put("number", lookup.prNumber());
      var response =
          graphQLClient.execute(
              lookup.auth(), new GitHubGraphQLClient.GraphQLRequest(LINKS_QUERY, variables));
      if (response == null) {
        return MissingNode.getInstance();
      }
      if (response.has("errors")) {
        // Partial data is still usable: a field the app may not read comes back null with an error.
        Log.debugf(
            "Closing references of %s/%s#%d came back with %d error(s)",
            lookup.owner(), lookup.repo(), lookup.prNumber(), response.path("errors").size());
      }
      return response.path("data").path("repository").path("pullRequest");
    } catch (RuntimeException e) {
      Log.debugf(
          e,
          "Could not read the closing references of %s/%s#%d; using the PR body's links only",
          lookup.owner(),
          lookup.repo(),
          lookup.prNumber());
      return MissingNode.getInstance();
    }
  }

  /** Issue numbers of {@code owner/repo} among the query's closing references. */
  static List<Integer> closingReferences(JsonNode pullRequest, String owner, String repo) {
    var numbers = new ArrayList<Integer>();
    for (var node : pullRequest.path("closingIssuesReferences").path("nodes")) {
      var nameWithOwner = node.path("repository").path("nameWithOwner").asText("");
      if (node.path("number").isInt() && sameRepository(nameWithOwner, owner, repo)) {
        numbers.add(node.path("number").asInt());
      }
    }
    return numbers;
  }

  /** The issue number a branch name carries in its last segment, or {@code 0} when none. */
  static int branchIssueNumber(String branch) {
    if (branch == null || branch.isBlank()) {
      return 0;
    }
    var lastSegment = branch.substring(branch.lastIndexOf('/') + 1);
    var matcher = BRANCH_ISSUE.matcher(lastSegment);
    return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private static boolean sameRepository(String nameWithOwner, String owner, String repo) {
    return nameWithOwner
        .toLowerCase(Locale.ROOT)
        .equals((owner + "/" + repo).toLowerCase(Locale.ROOT));
  }

  /** The issue, or {@code null} when it is a pull request or could not be read. */
  private LinkedTicket read(TicketLookup lookup, int number, String via) {
    GitHubCommentClient.IssueDetails issue;
    try {
      issue = commentClient.getIssue(lookup.auth(), ACCEPT, lookup.owner(), lookup.repo(), number);
    } catch (RuntimeException e) {
      // A 404 (never existed, or private to this credential), a 410 (deleted) or an outage: the
      // review goes on without this issue.
      Log.infof(
          "Linked issue %s/%s#%d could not be read (%s); the review continues without it",
          lookup.owner(), lookup.repo(), number, e.getClass().getSimpleName());
      return null;
    }
    if (issue == null || issue.isPullRequest()) {
      Log.debugf(
          "%s/%s#%d is not an issue; not used as linked-issue context",
          lookup.owner(), lookup.repo(), number);
      return null;
    }
    return new LinkedTicket("#" + number, via, issue.title(), issue.body());
  }
}
