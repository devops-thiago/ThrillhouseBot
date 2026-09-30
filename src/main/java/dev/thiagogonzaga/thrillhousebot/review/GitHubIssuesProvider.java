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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
   * GitHub's closing keywords ({@code close/fix/resolve} and inflections, optional colon), then the
   * reference that follows, which {@link #ISSUE_REFERENCE} reads.
   */
  private static final Pattern CLOSING_KEYWORD =
      Pattern.compile("(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b:?\\s+(\\S+)");

  /**
   * One issue reference at the start of a token: {@code #n}, {@code owner/repo#n} or an issue URL
   * on github.com. Group 1 is the qualified repository, group 2 the URL's repository, group 3 the
   * number.
   */
  private static final Pattern ISSUE_REFERENCE =
      Pattern.compile(
          "(?:([\\w.-]+/[\\w.-]+)#|https?://github\\.com/([\\w.-]+/[\\w.-]+)/issues/|#)"
              + "(\\d{1,9})(?!\\d)");

  /**
   * An issue number in the last segment of a branch name: {@code 57}, {@code 57-short-name}, or a
   * word before it ({@code issue-57}, {@code gh-57}) that must be one of {@link #BRANCH_PREFIXES}.
   */
  private static final Pattern BRANCH_ISSUE =
      Pattern.compile("^([A-Za-z]*)[-_]?#?(\\d{1,9})(?:[-_].*)?$");

  /** The words a branch name may put before its issue number. */
  private static final Set<String> BRANCH_PREFIXES =
      Set.of("", "issue", "issues", "gh", "bug", "fix", "feat", "feature", "task");

  private static final String NUMBER = "number";

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
    if (number > 0 && number != lookup.prNumber() && candidates.size() < MAX_CANDIDATES) {
      candidates.putIfAbsent(number, via);
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
    var visible = withoutNonLinkRegions(prBody);
    var numbers = new LinkedHashSet<Integer>();
    var keywords = CLOSING_KEYWORD.matcher(visible);
    while (keywords.find()) {
      var reference = ISSUE_REFERENCE.matcher(keywords.group(1));
      if (reference.lookingAt()) {
        var named = reference.group(reference.group(1) != null ? 1 : 2);
        var number = Integer.parseInt(reference.group(3));
        if (named == null || sameRepository(named, owner, repo)) {
          numbers.add(number);
        }
      }
    }
    return List.copyOf(numbers);
  }

  /**
   * {@code body} with the Markdown regions GitHub does not read links from replaced by a space:
   * HTML comments (an unclosed one runs to the end, as in HTML), fenced blocks of three or more
   * backticks or tildes (an unclosed one runs to the end), and code spans of any backtick count,
   * which may wrap a line but not cross a blank one. A backtick run with no matching closer is
   * literal text. Linear in the body's length for any input a PR body can hold.
   */
  static String withoutNonLinkRegions(String body) {
    return new NonLinkStripper(body).strip();
  }

  /**
   * One pass over a PR body for {@link #withoutNonLinkRegions}. It caches the next blank line, and
   * a closer search that fails leaves no run of that width in the paragraph to open another, so the
   * whole body is scanned a bounded number of times.
   */
  private static final class NonLinkStripper {
    private final String body;
    private int literalEnd; // end of a marker run that opened no region
    private int nextBlank =
        -2; // position of the next "\n\n" at or after the last query; -2 unknown

    NonLinkStripper(String body) {
      this.body = body;
    }

    String strip() {
      var out = new StringBuilder(body.length());
      var i = 0;
      while (i < body.length()) {
        var end = regionEnd(i);
        if (end > i) {
          out.append(' ');
          i = end;
        } else {
          // Not a region: copy the character, or the whole marker run that opened nothing, so a
          // run's tail is never read as a narrower marker of its own.
          var next = Math.max(i + 1, literalEnd);
          out.append(body, i, next);
          i = next;
        }
      }
      return out.toString();
    }

    /**
     * The end of the non-link region starting at {@code i}, or {@code i} when none starts there.
     */
    private int regionEnd(int i) {
      if (body.startsWith("<!--", i)) {
        var close = body.indexOf("-->", i + 4);
        return close < 0 ? body.length() : close + 3;
      }
      var c = body.charAt(i);
      if (c != '`' && c != '~') {
        return i;
      }
      var run = i;
      while (run < body.length() && body.charAt(run) == c) {
        run++;
      }
      var width = run - i;
      if (width >= 3) {
        var close = body.indexOf(body.substring(i, run), run);
        return close < 0 ? body.length() : close + width;
      }
      var close = c == '~' ? -1 : closingRun(run, width); // one or two tildes are text
      if (close < 0) {
        literalEnd = run;
        return i;
      }
      return close + width;
    }

    /**
     * Where the run of exactly {@code width} backticks closing a code span starts, from {@code
     * from}, or {@code -1} when a blank line or the end of the body comes first.
     */
    private int closingRun(int from, int width) {
      var limit = paragraphEnd(from);
      var j = from;
      while (j < limit) {
        var start = body.indexOf('`', j);
        if (start < 0 || start >= limit) {
          break;
        }
        j = start;
        while (j < body.length() && body.charAt(j) == '`') {
          j++;
        }
        if (j - start == width) {
          return start;
        }
      }
      return -1;
    }

    private int paragraphEnd(int from) {
      if (nextBlank == -2 || (nextBlank >= 0 && nextBlank < from)) {
        nextBlank = body.indexOf("\n\n", from);
      }
      return nextBlank < 0 ? body.length() : nextBlank;
    }
  }

  /** The pull request node of the links query, or a missing node when it could not be read. */
  private JsonNode readLinks(TicketLookup lookup) {
    try {
      var variables = new LinkedHashMap<String, Object>();
      variables.put("owner", lookup.owner());
      variables.put("name", lookup.repo());
      variables.put(NUMBER, lookup.prNumber());
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
      if (node.path(NUMBER).isInt() && sameRepository(nameWithOwner, owner, repo)) {
        numbers.add(node.path(NUMBER).asInt());
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
    if (!matcher.matches()
        || !BRANCH_PREFIXES.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
      return 0;
    }
    return Integer.parseInt(matcher.group(2));
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
