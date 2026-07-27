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

import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGitDataClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.FixGenerator;
import dev.thiagogonzaga.thrillhousebot.review.ai.FixResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.FixResponseParser;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Authors the change that resolves one review finding, driven by the opt-in {@code /fix} command on
 * a finding thread: it asks the {@link FixGenerator} for verbatim search/replace edits over the
 * PR's current files, applies them, commits the result to a {@code thrillhousebot/*} branch via the
 * Git Data API, and opens a clearly attributed PR targeting the reviewed PR's branch — so merging
 * the fix PR updates the original PR, and a human stays the one who merges. The {@code fix.enabled}
 * switch, the pause check, and write-access authorization are enforced by the webhook layer before
 * this runs.
 */
@ApplicationScoped
public class FixService {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String COMMAND = "/fix";

  /** Namespace of every branch this service pushes; never a branch a human owns. */
  static final String BRANCH_PREFIX = "thrillhousebot/fix-";

  // Bounds on the file contents handed to the model. The flagged file always goes first; further
  // changed files ride along until a cap is hit, so the single-shot call stays within budget.
  private static final int MAX_CONTEXT_FILES = 8;
  private static final int MAX_FILE_CHARS = 40_000;
  private static final int MAX_TOTAL_CONTEXT_CHARS = 120_000;

  private static final int MAX_TITLE_CHARS = 70;

  static final String NO_THREAD =
      "🔧 ThrillhouseBot could not load this finding thread to draft a fix. "
          + "Please try `/fix` again.";
  static final String NO_PR_DETAILS =
      "🔧 ThrillhouseBot could not load this pull request to draft a fix. "
          + "Please try `/fix` again.";
  static final String FORK_UNSUPPORTED =
      "🔧 ThrillhouseBot cannot open fix PRs for pull requests from a fork — it can only push "
          + "branches to this repository.";
  static final String NO_FILE_CONTEXT =
      "🔧 ThrillhouseBot could not load the affected file contents to draft a fix. "
          + "Please try `/fix` again.";
  static final String GENERATION_FAILED =
      "🔧 ThrillhouseBot could not draft a fix for this finding. Please try `/fix` again.";
  static final String EDIT_MISMATCH =
      "🔧 ThrillhouseBot drafted a fix, but it no longer matches the current files — this usually "
          + "happens after a new push to the PR. Please try `/fix` again.";
  static final String PUSH_FAILED =
      "🔧 ThrillhouseBot drafted the fix but could not push the branch or open the PR. Check that "
          + "the GitHub App has the **contents: write** permission, then try `/fix` again.";

  static final String PR_DISCLAIMER =
      "> ⚠️ **AI-generated fix.** ThrillhouseBot's changes are advisory — review, test, and "
          + "adjust before merging. Close this PR to discard the proposal.";

  private final ExecutorService executor;
  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;
  private final GitHubReviewClient reviewClient;
  private final GitHubGitDataClient gitDataClient;
  private final ReviewDiffFormatter diffFormatter;
  private final InstructionsResolver instructionsResolver;
  private final ProjectStackResolver projectStackResolver;
  private final FixGenerator fixGenerator;
  private final FixResponseParser parser;
  private final ThrillhouseConfig config;

  @Inject
  public FixService(
      @ReviewExecutor ExecutorService executor,
      GitHubAuthClient authClient,
      @RestClient GitHubPullRequestClient prClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubGitDataClient gitDataClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      ProjectStackResolver projectStackResolver,
      FixGenerator fixGenerator,
      FixResponseParser parser,
      ThrillhouseConfig config) {
    this.executor = executor;
    this.authClient = authClient;
    this.prClient = prClient;
    this.reviewClient = reviewClient;
    this.gitDataClient = gitDataClient;
    this.diffFormatter = diffFormatter;
    this.instructionsResolver = instructionsResolver;
    this.projectStackResolver = projectStackResolver;
    this.fixGenerator = fixGenerator;
    this.parser = parser;
    this.config = config;
  }

  /**
   * Coordinates of one {@code /fix} request: the PR, the requesting user, and the finding thread
   * ({@code rootCommentId} anchors the reply; {@code path} is the file the thread sits on).
   */
  public record FixTask(
      String owner,
      String repo,
      int prNumber,
      String defaultBranch,
      long installationId,
      String login,
      long rootCommentId,
      String path) {}

  /** Runs the fix asynchronously off the webhook ACK path. */
  public void handle(FixTask task) {
    executor.execute(() -> execute(task));
  }

  /** Visible for tests: performs the whole fix on the calling thread. */
  @ActivateRequestContext
  void execute(FixTask task) {
    try {
      var auth = authClient.getAuthHeader(task.installationId());

      var finding = loadFindingBody(auth, task);
      if (finding == null) {
        reply(auth, task, NO_THREAD);
        return;
      }

      var pr =
          SoftLoaders.pullRequest(
              prClient, auth, task.owner(), task.repo(), task.prNumber(), COMMAND);
      if (pr == null || pr.head() == null || isBlank(pr.head().sha()) || isBlank(pr.head().ref())) {
        reply(auth, task, NO_PR_DETAILS);
        return;
      }
      if (isForkPr(task, pr)) {
        reply(auth, task, FORK_UNSUPPORTED);
        return;
      }

      var files =
          SoftLoaders.files(prClient, auth, task.owner(), task.repo(), task.prNumber(), COMMAND);
      var reviewable = diffFormatter.reviewableFiles(files);
      var contents = loadContext(auth, task, pr.head().sha(), reviewable);
      if (contents.isEmpty()) {
        reply(auth, task, NO_FILE_CONTEXT);
        return;
      }

      var response = generateOrReportFailure(auth, task, finding, files, reviewable, contents, pr);
      if (response == null) {
        return;
      }
      if (response.edits().isEmpty()) {
        reply(auth, task, declinedMessage(response));
        return;
      }
      int maxEditedFiles = config.review().fix().maxEditedFiles();
      if (response.edits().size() > maxEditedFiles) {
        reply(
            auth,
            task,
            "🔧 The drafted fix would touch "
                + response.edits().size()
                + " files, more than the configured limit of "
                + maxEditedFiles
                + " — a change that broad deserves a human. No branch was created.");
        return;
      }

      Map<String, String> changed;
      try {
        changed = applyEdits(auth, task, pr.head().sha(), contents, response.edits());
      } catch (EditApplicationException e) {
        Log.infof(
            "/fix edits did not apply on %s/%s #%d: %s",
            task.owner(), task.repo(), task.prNumber(), e.getMessage());
        reply(auth, task, e.userMessage);
        return;
      }

      openFixPr(auth, task, pr, response, changed);
    } catch (RuntimeException e) {
      Log.warnf(
          e, "Failed to handle /fix on %s/%s #%d", task.owner(), task.repo(), task.prNumber());
      tryReply(task, GENERATION_FAILED);
    }
  }

  /** The finding thread's root comment body plus its file path, or {@code null} on failure. */
  private String loadFindingBody(String auth, FixTask task) {
    try {
      var root =
          reviewClient.getPullRequestComment(
              auth, ACCEPT, task.owner(), task.repo(), task.rootCommentId());
      if (root == null || isBlank(root.body())) {
        return null;
      }
      var file = !isBlank(root.path()) ? root.path() : task.path();
      return "File: " + (file == null ? "(unknown)" : file) + "\n\n" + root.body();
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Failed to load finding thread %d for /fix on %s/%s #%d",
          task.rootCommentId(),
          task.owner(),
          task.repo(),
          task.prNumber());
      return null;
    }
  }

  /**
   * Whether the PR's head lives in another repository. The bot can only push branches to the
   * repository it is installed on, so fork PRs are declined instead of failing on the ref write.
   */
  private static boolean isForkPr(FixTask task, GitHubPullRequestClient.PullRequestDetails pr) {
    var headRepo = pr.head().repo();
    if (headRepo == null || isBlank(headRepo.fullName())) {
      return false;
    }
    return !headRepo.fullName().equalsIgnoreCase(task.owner() + "/" + task.repo());
  }

  /**
   * Full current contents of the flagged file plus further changed files, keyed by path in prompt
   * order, bounded by {@link #MAX_CONTEXT_FILES}/{@link #MAX_FILE_CHARS}/{@link
   * #MAX_TOTAL_CONTEXT_CHARS}. Only files loaded here may be edited by a replace, so the applied
   * fix can never drift from what the model saw.
   */
  private Map<String, String> loadContext(
      String auth,
      FixTask task,
      String headSha,
      List<GitHubPullRequestClient.FileDiff> reviewable) {
    var candidates = new ArrayList<String>();
    if (!isBlank(task.path())) {
      candidates.add(task.path());
    }
    for (var file : reviewable) {
      if (!"removed".equals(file.status()) && !candidates.contains(file.filename())) {
        candidates.add(file.filename());
      }
    }

    var contents = new LinkedHashMap<String, String>();
    int totalChars = 0;
    for (var path : candidates) {
      if (contents.size() >= MAX_CONTEXT_FILES || totalChars >= MAX_TOTAL_CONTEXT_CHARS) {
        break;
      }
      var text = loadFileContent(auth, task, path, headSha);
      if (text == null || text.length() > MAX_FILE_CHARS) {
        continue;
      }
      contents.put(path, text);
      totalChars += text.length();
    }
    return contents;
  }

  /** One file's decoded UTF-8 content at {@code ref}, or {@code null} when it cannot be read. */
  private String loadFileContent(String auth, FixTask task, String path, String ref) {
    try {
      var file = prClient.getFileContent(auth, ACCEPT, task.owner(), task.repo(), path, ref);
      if (file == null || file.content() == null) {
        return null;
      }
      return new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      Log.debugf(e, "Could not load %s@%s for /fix context (skipping)", path, ref);
      return null;
    }
  }

  /**
   * Builds the prompt, runs generation, and returns the parsed fix — posting the failure notice and
   * returning {@code null} when the diff build, model call, or parse throws, so the caller can bail
   * without a nested try.
   */
  private FixResponse generateOrReportFailure(
      String auth,
      FixTask task,
      String finding,
      List<GitHubPullRequestClient.FileDiff> files,
      List<GitHubPullRequestClient.FileDiff> reviewable,
      Map<String, String> contents,
      GitHubPullRequestClient.PullRequestDetails pr) {
    try {
      var diff = diffFormatter.buildDiffStringWithStats(files, reviewable).text();
      var raw =
          fixGenerator.generate(
              PromptTemplateEscaper.fence(finding),
              PromptTemplateEscaper.fence(contextBundle(contents)),
              PromptTemplateEscaper.fence(diff),
              PromptTemplateEscaper.escape(PromptSections.prContext(pr.title(), pr.body())),
              PromptTemplateEscaper.escape(
                  SoftLoaders.projectStack(
                      projectStackResolver,
                      task.owner(),
                      task.repo(),
                      task.defaultBranch(),
                      task.installationId(),
                      COMMAND)),
              buildInstructionsSection(task));
      return parser.parse(raw);
    } catch (RuntimeException e) {
      Log.warnf(
          e, "Fix generation failed for %s/%s #%d", task.owner(), task.repo(), task.prNumber());
      reply(auth, task, GENERATION_FAILED);
      return null;
    }
  }

  /** The CURRENT FILE CONTENTS prompt section: each file's full text under a FILE header. */
  private static String contextBundle(Map<String, String> contents) {
    var sb = new StringBuilder();
    contents.forEach(
        (path, text) ->
            sb.append("### FILE: ").append(path).append('\n').append(text).append('\n'));
    return sb.toString();
  }

  private static String declinedMessage(FixResponse response) {
    var message = new StringBuilder("🔧 ThrillhouseBot declined to draft a fix for this finding.");
    if (!isBlank(response.notes())) {
      message.append("\n\n").append(response.notes().strip());
    }
    return message.toString();
  }

  /** Thrown when an edit cannot be applied; {@code userMessage} is posted to the thread. */
  private static final class EditApplicationException extends RuntimeException {
    private final String userMessage;

    EditApplicationException(String logMessage, String userMessage) {
      super(logMessage);
      this.userMessage = userMessage;
    }
  }

  /**
   * Applies every edit and returns the new content per touched path. All-or-nothing on purpose: a
   * fix whose edits only partially apply is worse than no fix, so the first mismatch aborts.
   */
  private Map<String, String> applyEdits(
      String auth,
      FixTask task,
      String headSha,
      Map<String, String> contents,
      List<FixResponse.FileEdit> edits) {
    var changed = new LinkedHashMap<String, String>();
    for (var edit : edits) {
      if (!edit.isApplicable()) {
        throw new EditApplicationException("edit missing file/search/replace", EDIT_MISMATCH);
      }
      var path = edit.file().strip();
      if (edit.isCreate()) {
        validateNewPath(auth, task, headSha, contents, changed, path);
        changed.put(path, edit.replace());
        continue;
      }
      var current = changed.containsKey(path) ? changed.get(path) : contents.get(path);
      if (current == null) {
        throw new EditApplicationException("replace targets unloaded file " + path, EDIT_MISMATCH);
      }
      int first = current.indexOf(edit.search());
      if (first < 0 || current.indexOf(edit.search(), first + 1) >= 0) {
        throw new EditApplicationException("search snippet not unique in " + path, EDIT_MISMATCH);
      }
      changed.put(
          path,
          current.substring(0, first)
              + edit.replace()
              + current.substring(first + edit.search().length()));
    }
    return changed;
  }

  /** Rejects created paths that escape the repo or collide with an existing file. */
  private void validateNewPath(
      String auth,
      FixTask task,
      String headSha,
      Map<String, String> contents,
      Map<String, String> changed,
      String path) {
    if (path.startsWith("/") || path.contains("\\") || path.contains("..")) {
      throw new EditApplicationException("unsafe created path " + path, EDIT_MISMATCH);
    }
    if (contents.containsKey(path) || changed.containsKey(path)) {
      throw new EditApplicationException("created path already loaded: " + path, EDIT_MISMATCH);
    }
    if (loadFileContent(auth, task, path, headSha) != null) {
      throw new EditApplicationException("created path already exists: " + path, EDIT_MISMATCH);
    }
  }

  /** Commits the changed files to a new bot branch and opens the attributed fix PR. */
  private void openFixPr(
      String auth,
      FixTask task,
      GitHubPullRequestClient.PullRequestDetails pr,
      FixResponse response,
      Map<String, String> changed) {
    try {
      var headSha = pr.head().sha();
      var baseTree = gitDataClient.getCommit(auth, ACCEPT, task.owner(), task.repo(), headSha);
      var entries =
          changed.entrySet().stream()
              .map(e -> GitHubGitDataClient.TreeEntry.file(e.getKey(), e.getValue()))
              .toList();
      var tree =
          gitDataClient.createTree(
              auth,
              ACCEPT,
              task.owner(),
              task.repo(),
              new GitHubGitDataClient.CreateTreeRequest(baseTree.tree().sha(), entries));
      var commit =
          gitDataClient.createCommit(
              auth,
              ACCEPT,
              task.owner(),
              task.repo(),
              new GitHubGitDataClient.CreateCommitRequest(
                  commitMessage(task, response), tree.sha(), List.of(headSha)));
      var branch =
          BRANCH_PREFIX
              + task.prNumber()
              + "-"
              + task.rootCommentId()
              + "-"
              + commit.sha().substring(0, 7);
      gitDataClient.createRef(
          auth,
          ACCEPT,
          task.owner(),
          task.repo(),
          new GitHubGitDataClient.CreateRefRequest("refs/heads/" + branch, commit.sha()));

      var created =
          prClient.createPullRequest(
              auth,
              ACCEPT,
              task.owner(),
              task.repo(),
              new GitHubPullRequestClient.CreatePullRequestRequest(
                  prTitle(response),
                  prBody(task, pr, response, changed),
                  branch,
                  pr.head().ref(),
                  true));

      Log.infof(
          "/fix opened PR #%d (branch %s) for finding thread %d on %s/%s #%d",
          created.number(),
          branch,
          task.rootCommentId(),
          task.owner(),
          task.repo(),
          task.prNumber());
      reply(
          auth,
          task,
          "🔧 Opened "
              + created.htmlUrl()
              + " with a proposed fix for this finding. It targets `"
              + pr.head().ref()
              + "`, so merging it updates this PR. AI-generated — review before merging.");
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "/fix could not push the branch or open the PR on %s/%s #%d",
          task.owner(),
          task.repo(),
          task.prNumber());
      reply(auth, task, PUSH_FAILED);
    }
  }

  private static String commitMessage(FixTask task, FixResponse response) {
    return subjectLine(response)
        + "\n\nProposed by ThrillhouseBot in response to a /fix request by @"
        + task.login()
        + " on "
        + task.owner()
        + "/"
        + task.repo()
        + "#"
        + task.prNumber()
        + ". AI-generated — review before merging.";
  }

  private static String prTitle(FixResponse response) {
    return "🤖 Fix: " + subjectLine(response);
  }

  /** The model's summary squeezed onto one bounded line, with a fallback when it is blank. */
  private static String subjectLine(FixResponse response) {
    var summary = response.summary();
    if (isBlank(summary)) {
      return "proposed fix for a review finding";
    }
    var line = summary.strip().replaceAll("\\s+", " ");
    return line.length() <= MAX_TITLE_CHARS ? line : line.substring(0, MAX_TITLE_CHARS - 1) + "…";
  }

  private String prBody(
      FixTask task,
      GitHubPullRequestClient.PullRequestDetails pr,
      FixResponse response,
      Map<String, String> changed) {
    var threadUrl =
        "https://github.com/"
            + task.owner()
            + "/"
            + task.repo()
            + "/pull/"
            + task.prNumber()
            + "#discussion_r"
            + task.rootCommentId();
    var sb = new StringBuilder();
    sb.append("This PR was opened by ThrillhouseBot in response to a `/fix` request by @")
        .append(task.login())
        .append(" on a review finding in #")
        .append(task.prNumber())
        .append(" ([finding thread](")
        .append(threadUrl)
        .append(")). It targets `")
        .append(pr.head().ref())
        .append("`, so merging it updates #")
        .append(task.prNumber())
        .append(".\n\n");
    sb.append("### Proposed change\n").append(subjectLine(response)).append("\n\n");
    sb.append("**Files touched:**\n");
    changed.keySet().forEach(path -> sb.append("- `").append(path).append("`\n"));
    if (!isBlank(response.notes())) {
      sb.append("\n### Notes from the model\n").append(response.notes().strip()).append('\n');
    }
    sb.append('\n').append(PR_DISCLAIMER);
    return sb.toString();
  }

  // Command-specific guidance for the repository-instructions section.
  private static final String INSTRUCTIONS_GUIDANCE =
      "The repository maintainers have provided these guidelines; respect them when authoring"
          + " the fix.\n";

  /**
   * Pre-rendered, pre-escaped repository-instructions section, or empty when none is configured.
   */
  private String buildInstructionsSection(FixTask task) {
    var instructions =
        SoftLoaders.instructions(
            instructionsResolver,
            task.owner(),
            task.repo(),
            task.defaultBranch(),
            task.installationId(),
            COMMAND);
    return PromptSections.instructionsSection(instructions, INSTRUCTIONS_GUIDANCE);
  }

  /** Posts a reply into the finding thread; failures are logged, never propagated. */
  private void reply(String auth, FixTask task, String body) {
    try {
      reviewClient.replyToReviewComment(
          auth,
          ACCEPT,
          task.owner(),
          task.repo(),
          task.prNumber(),
          task.rootCommentId(),
          new GitHubReviewClient.ReplyToReviewCommentRequest(body));
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Failed to post /fix reply on %s/%s #%d thread %d",
          task.owner(),
          task.repo(),
          task.prNumber(),
          task.rootCommentId());
    }
  }

  /** Best-effort failure reply for the outer catch, where even the auth header may be at fault. */
  private void tryReply(FixTask task, String body) {
    try {
      reply(authClient.getAuthHeader(task.installationId()), task, body);
    } catch (RuntimeException e) {
      Log.debugf(e, "Could not post /fix failure reply on %s/%s", task.owner(), task.repo());
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
