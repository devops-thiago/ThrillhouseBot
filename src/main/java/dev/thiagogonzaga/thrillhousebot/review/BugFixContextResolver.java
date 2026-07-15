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

import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Detects that a PR declares itself a bug fix and loads the linked issues' text, so the review
 * prompt can check the fix actually changes behavior for the failure trigger it claims to fix
 * (issue #110). Detection is textual — the PR-template "Bug fix" checkbox or a {@code
 * Fixes/Closes/Resolves #N} closing reference in the body — and the linked-issue fetch is
 * best-effort enrichment whose failure must never fail the review.
 */
@ApplicationScoped
public class BugFixContextResolver {

  private static final String ACCEPT = "application/vnd.github+json";

  /** Linked issues fetched per PR; the closing references beyond this many are dropped. */
  static final int MAX_LINKED_ISSUES = 3;

  /** Per-issue body budget, so one sprawling issue cannot crowd out the diff. */
  static final int MAX_ISSUE_BODY_CHARS = 4000;

  /** GitHub's closing keywords ({@code fix/close/resolve} and inflections) referencing an issue. */
  private static final Pattern CLOSING_REFERENCE =
      Pattern.compile("(?i)\\b(?:fix(?:e[sd])?|close[sd]?|resolve[sd]?)\\b:?\\s+#(\\d{1,9})\\b");

  /** A checked PR-template checkbox whose label contains "bug fix" (emoji between them is fine). */
  private static final Pattern BUG_FIX_CHECKBOX =
      Pattern.compile("(?im)^\\s*[-*]\\s*\\[[xX]\\][^\\r\\n]*bug\\s*fix");

  private final GitHubCommentClient commentClient;

  @Inject
  public BugFixContextResolver(@RestClient GitHubCommentClient commentClient) {
    this.commentClient = commentClient;
  }

  /**
   * Whether the PR body declares this change a bug fix — the PR-template "Bug fix" checkbox is
   * checked, or the body carries a {@code Fixes/Closes/Resolves #N} closing reference.
   */
  static boolean isBugFix(String prBody) {
    if (prBody == null || prBody.isBlank()) {
      return false;
    }
    return BUG_FIX_CHECKBOX.matcher(prBody).find() || CLOSING_REFERENCE.matcher(prBody).find();
  }

  /** Issue numbers named by closing references in the PR body, in order, deduplicated, capped. */
  static List<Integer> linkedIssueNumbers(String prBody) {
    if (prBody == null || prBody.isBlank()) {
      return List.of();
    }
    var numbers = new LinkedHashSet<Integer>();
    var matcher = CLOSING_REFERENCE.matcher(prBody);
    while (matcher.find() && numbers.size() < MAX_LINKED_ISSUES) {
      numbers.add(Integer.parseInt(matcher.group(1)));
    }
    return List.copyOf(numbers);
  }

  /**
   * Title and body of each issue the PR's closing references name, rendered as prompt-ready text —
   * empty when the PR is not a bug fix, names no issues, or every fetch fails. Each fetch fails
   * soft: the efficacy check still runs on the PR description alone.
   */
  String loadLinkedIssueContext(String auth, String owner, String repo, String prBody) {
    if (!isBugFix(prBody)) {
      return "";
    }
    var sections = new ArrayList<String>();
    for (int issueNumber : linkedIssueNumbers(prBody)) {
      try {
        var issue = commentClient.getIssue(auth, ACCEPT, owner, repo, issueNumber);
        sections.add(renderIssue(issueNumber, issue));
      } catch (RuntimeException e) {
        Log.warnf(
            e,
            "Failed to fetch linked issue #%d; bug-fix efficacy check continues without it",
            issueNumber);
      }
    }
    return String.join("\n\n", sections);
  }

  private static String renderIssue(int issueNumber, GitHubCommentClient.IssueDetails issue) {
    var title = issue.title() == null ? "" : issue.title().strip();
    var body = issue.body() == null ? "" : issue.body().strip();
    if (body.length() > MAX_ISSUE_BODY_CHARS) {
      body = body.substring(0, MAX_ISSUE_BODY_CHARS) + "\n[... issue body truncated]";
    }
    var sb = new StringBuilder("### Linked issue #").append(issueNumber);
    if (!title.isEmpty()) {
      sb.append(": ").append(title);
    }
    if (!body.isEmpty()) {
      sb.append('\n').append(body);
    }
    return sb.toString();
  }
}
