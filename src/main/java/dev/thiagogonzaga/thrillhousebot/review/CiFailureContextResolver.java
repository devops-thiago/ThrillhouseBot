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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.ArtifactZipFetcher;
import dev.thiagogonzaga.thrillhousebot.github.GitHubActionsClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the opt-in CI-failure review context (#59): a bounded, plain-text summary of the checks
 * that had already completed without passing on the head commit when the review started, so a
 * finding can be tied to the failing test or build step it explains.
 *
 * <p>The failing checks are not fetched here. They come from the {@link
 * CiStatusEvaluator.CiEvaluation} the CI gate reads for the same head, which carries each failing
 * check's name, conclusion and output panel ({@link CiStatusEvaluator.FailedCheck}); this class
 * only adds what that list cannot hold — one page of annotations per detailed check, and, behind a
 * separate switch, the tail of up to {@link #MAX_LOG_FETCHES} failing GitHub Actions job logs.
 *
 * <p>Every string here is produced by code the pull request can change: a test can print anything,
 * including text shaped like an instruction or a prompt heading. So each field is stripped of
 * control and formatting characters (ANSI colour codes, carriage returns, bidi overrides), clipped,
 * and the whole section is capped at {@code max-chars}. The caller fences the result like every
 * other untrusted slot; nothing here is ever phrased as, or allowed to become, an instruction.
 *
 * <p>Pending checks contribute one line, and only alongside a failure: a review that starts while
 * CI is still running — the usual case for an automatic review — gets no section at all rather than
 * a paragraph saying nothing is known yet. Best-effort throughout: nothing here can fail a review.
 */
@ApplicationScoped
public class CiFailureContextResolver {

  private static final String ACCEPT = "application/vnd.github+json";

  /** The app slug of a GitHub Actions check run, whose id is also its job id. */
  static final String GITHUB_ACTIONS_APP = "github-actions";

  /** Failing checks rendered in full; the rest are named on one line. */
  static final int MAX_CHECKS_DETAILED = 5;

  /** Annotations requested per detailed check — one page, one call. */
  static final int ANNOTATIONS_PAGE_SIZE = 20;

  /** Annotations rendered per check, failure-level first. */
  static final int MAX_ANNOTATIONS_PER_CHECK = 8;

  /** Job logs downloaded per review when logs are enabled. */
  static final int MAX_LOG_FETCHES = 2;

  /** Bytes kept from the end of a job log. */
  static final int LOG_TAIL_BYTES = 16 * 1024;

  /** Log lines rendered per job. */
  static final int LOG_TAIL_LINES = 30;

  /** Characters of log tail rendered per job. */
  static final int MAX_LOG_CHARS = 1_500;

  /** Characters of a check's output summary rendered. */
  static final int MAX_SUMMARY_CHARS = 600;

  /** Characters of any single line — a title, an annotation, a log line. */
  static final int MAX_LINE_CHARS = 300;

  /** Appended when the whole section had to be cut to {@code max-chars}. */
  static final String TRUNCATION_NOTE = "[CI context truncated to its character budget]";

  /** Conclusions whose job log is worth reading: the job ran and stopped on an error. */
  private static final Set<String> LOG_WORTHY_CONCLUSIONS = Set.of("failure", "timed_out");

  // ANSI CSI sequences (colour, cursor movement) that CI runners emit into logs and summaries.
  private static final Pattern ANSI_CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

  // The ISO-8601 timestamp GitHub Actions prefixes to every log line: tokens, not information.
  private static final Pattern LOG_TIMESTAMP =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z ?");

  private final GitHubCheckRunClient checkRunClient;
  private final GitHubActionsClient actionsClient;
  private final ArtifactZipFetcher fetcher;
  private final boolean enabled;
  private final boolean includeLogs;
  private final int maxChars;

  @Inject
  public CiFailureContextResolver(
      @RestClient GitHubCheckRunClient checkRunClient,
      @RestClient GitHubActionsClient actionsClient,
      ArtifactZipFetcher fetcher,
      ThrillhouseConfig config) {
    this(
        checkRunClient,
        actionsClient,
        fetcher,
        config.review().ciContext().enabled(),
        config.review().ciContext().includeLogs(),
        config.review().ciContext().maxChars());
  }

  /** Visible for tests: the switches and the cap are passed directly. */
  CiFailureContextResolver(
      GitHubCheckRunClient checkRunClient,
      GitHubActionsClient actionsClient,
      ArtifactZipFetcher fetcher,
      boolean enabled,
      boolean includeLogs,
      int maxChars) {
    this.checkRunClient = checkRunClient;
    this.actionsClient = actionsClient;
    this.fetcher = fetcher;
    this.enabled = enabled;
    this.includeLogs = includeLogs;
    this.maxChars = maxChars;
  }

  /** Whether the CI-failure context is switched on for this deployment. */
  boolean enabled() {
    return enabled;
  }

  /**
   * The CI-failure section for the review prompt, unfenced, or {@code ""} when the feature is off
   * or no check had completed without passing when {@code evaluation} was read.
   */
  String resolve(
      String auth, String owner, String repo, CiStatusEvaluator.CiEvaluation evaluation) {
    if (!enabled || evaluation == null || evaluation.failures().failed().isEmpty()) {
      return "";
    }
    try {
      return cap(render(auth, owner, repo, evaluation.failures()), maxChars);
    } catch (RuntimeException e) {
      Log.warn("CI-failure context could not be built, continuing without it", e);
      return "";
    }
  }

  private String render(
      String auth, String owner, String repo, CiStatusEvaluator.CiFailures failures) {
    var failed = failures.failed();
    var sb = new StringBuilder();
    sb.append("Checks on this commit that had completed without passing when this review started: ")
        .append(failed.size())
        .append('\n');
    if (failures.pending() > 0) {
      sb.append(failures.pending())
          .append(" other check(s) had not finished; their outcome is unknown to this review.\n");
    }
    var logsLeft = includeLogs ? MAX_LOG_FETCHES : 0;
    var detailed = Math.min(failed.size(), MAX_CHECKS_DETAILED);
    for (var i = 0; i < detailed; i++) {
      var check = failed.get(i);
      sb.append('\n');
      appendCheck(sb, check);
      appendAnnotations(sb, auth, owner, repo, check);
      if (logsLeft > 0 && logWorthy(check)) {
        logsLeft--;
        appendLogTail(sb, auth, owner, repo, check.checkRunId());
      }
    }
    if (failed.size() > detailed) {
      var rest = failed.subList(detailed, failed.size()).stream().map(c -> oneLine(c.name()));
      sb.append("\nAlso failing, not detailed: ")
          .append(String.join(", ", rest.toList()))
          .append('\n');
    }
    return sb.toString();
  }

  private static void appendCheck(StringBuilder sb, CiStatusEvaluator.FailedCheck check) {
    sb.append("### ")
        .append(oneLine(check.name()))
        .append(" (conclusion: ")
        .append(oneLine(check.conclusion() == null ? "unknown" : check.conclusion()))
        .append(")\n");
    var title = oneLine(check.title());
    if (!title.isEmpty()) {
      sb.append("Title: ").append(title).append('\n');
    }
    var summary = clip(clean(check.summary()).strip(), MAX_SUMMARY_CHARS);
    if (!summary.isEmpty()) {
      sb.append("Summary:\n").append(summary).append('\n');
    }
  }

  private void appendAnnotations(
      StringBuilder sb,
      String auth,
      String owner,
      String repo,
      CiStatusEvaluator.FailedCheck check) {
    if (check.checkRunId() <= 0 || check.annotationsCount() <= 0) {
      return;
    }
    List<GitHubCheckRunClient.Annotation> page;
    try {
      page =
          checkRunClient.listAnnotations(
              auth, ACCEPT, owner, repo, check.checkRunId(), ANNOTATIONS_PAGE_SIZE);
    } catch (RuntimeException e) {
      Log.debugf(e, "Could not read annotations of check run %d", check.checkRunId());
      return;
    }
    if (page == null || page.isEmpty()) {
      return;
    }
    var ordered = new ArrayList<>(page);
    // Failure-level annotations first: a warning on the same page rarely explains the failure.
    ordered.sort(Comparator.comparingInt(CiFailureContextResolver::levelRank));
    var shown = Math.min(ordered.size(), MAX_ANNOTATIONS_PER_CHECK);
    sb.append("Annotations (")
        .append(shown)
        .append(" of ")
        .append(check.annotationsCount())
        .append("):\n");
    for (var annotation : ordered.subList(0, shown)) {
      sb.append("- ").append(formatAnnotation(annotation)).append('\n');
    }
  }

  private static int levelRank(GitHubCheckRunClient.Annotation annotation) {
    return "failure".equalsIgnoreCase(annotation.annotationLevel()) ? 0 : 1;
  }

  private static String formatAnnotation(GitHubCheckRunClient.Annotation annotation) {
    var location = oneLine(annotation.path());
    if (annotation.startLine() != null) {
      location = location + ":" + annotation.startLine();
    }
    var level = oneLine(annotation.annotationLevel());
    var title = oneLine(annotation.title());
    var message = oneLine(annotation.message());
    var sb = new StringBuilder(location);
    if (!level.isEmpty()) {
      sb.append(" [").append(level).append(']');
    }
    if (!title.isEmpty()) {
      sb.append(' ').append(title).append(':');
    }
    return clip(sb.append(' ').append(message).toString().strip(), MAX_LINE_CHARS);
  }

  private static boolean logWorthy(CiStatusEvaluator.FailedCheck check) {
    return check.checkRunId() > 0
        && GITHUB_ACTIONS_APP.equals(check.appSlug())
        && check.conclusion() != null
        && LOG_WORTHY_CONCLUSIONS.contains(check.conclusion().toLowerCase(Locale.ROOT));
  }

  private void appendLogTail(StringBuilder sb, String auth, String owner, String repo, long jobId) {
    var tail = logTail(auth, owner, repo, jobId);
    if (!tail.isEmpty()) {
      sb.append("Job log tail:\n").append(tail).append('\n');
    }
  }

  /** The rendered tail of one job's log, or {@code ""} when it could not be read. */
  private String logTail(String auth, String owner, String repo, long jobId) {
    byte[] bytes;
    try (var response = actionsClient.downloadJobLogs(auth, ACCEPT, owner, repo, jobId)) {
      var location = response == null ? null : response.getLocation();
      if (location == null) {
        Log.debugf("Job log download for %d returned no redirect location", jobId);
        return "";
      }
      bytes = fetcher.fetchTail(location, LOG_TAIL_BYTES);
    } catch (RuntimeException e) {
      Log.debugf(e, "Could not read the log of job %d", jobId);
      return "";
    }
    return renderLogTail(bytes);
  }

  /**
   * The last {@link #LOG_TAIL_LINES} non-blank lines of a log tail, timestamps and control
   * characters removed, within {@link #MAX_LOG_CHARS}. When the bytes fill the whole tail window
   * the first line is a fragment cut mid-line, so it is dropped.
   */
  static String renderLogTail(byte[] bytes) {
    if (bytes.length == 0) {
      return "";
    }
    var lines = clean(new String(bytes, StandardCharsets.UTF_8)).split("\n", -1);
    var start = bytes.length >= LOG_TAIL_BYTES ? 1 : 0;
    var kept = new ArrayDeque<String>();
    var chars = 0;
    var full = false;
    for (var i = lines.length - 1; i >= start && kept.size() < LOG_TAIL_LINES && !full; i--) {
      var line = clip(LOG_TIMESTAMP.matcher(lines[i]).replaceFirst("").strip(), MAX_LINE_CHARS);
      if (!line.isEmpty()) {
        full = chars + line.length() + 1 > MAX_LOG_CHARS;
        if (!full) {
          kept.addFirst(line);
          chars += line.length() + 1;
        }
      }
    }
    return String.join("\n", kept);
  }

  /**
   * {@code text} with every character that could restyle, reorder or hide what the model reads
   * removed: ANSI escape sequences, carriage returns (a {@code \r} lets a log line overwrite itself
   * in a terminal, hiding what precedes it), tabs as spaces, and every other control or format
   * character — the latter covers the Unicode bidi overrides that can make text read differently
   * from its byte order. Newlines are kept.
   */
  static String clean(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    var normalized =
        ANSI_CSI.matcher(text).replaceAll("").replace("\r\n", "\n").replace('\r', '\n');
    var sb = new StringBuilder(normalized.length());
    normalized
        .codePoints()
        .forEach(
            cp -> {
              if (cp == '\n') {
                sb.append('\n');
              } else if (cp == '\t') {
                sb.append(' ');
              } else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) {
                sb.appendCodePoint(cp);
              }
            });
    return sb.toString();
  }

  /** {@link #clean} folded onto one line and clipped to {@link #MAX_LINE_CHARS}. */
  static String oneLine(String text) {
    return clip(clean(text).replace('\n', ' ').strip(), MAX_LINE_CHARS);
  }

  /** {@code text} cut to at most {@code max} characters, never inside a surrogate pair. */
  static String clip(String text, int max) {
    if (text.length() <= max) {
      return text;
    }
    var end = max - 1;
    if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end) + "…";
  }

  /**
   * {@code section} within {@code maxChars}, cut at a line boundary with {@link #TRUNCATION_NOTE}
   * appended. The header lines come first, so a cut only ever drops check detail.
   */
  static String cap(String section, int maxChars) {
    if (section.length() <= maxChars) {
      return section;
    }
    var room = Math.max(0, maxChars - TRUNCATION_NOTE.length() - 1);
    var cut = section.lastIndexOf('\n', room);
    var kept = cut > 0 ? section.substring(0, cut) : clip(section, room);
    return kept + "\n" + TRUNCATION_NOTE;
  }
}
