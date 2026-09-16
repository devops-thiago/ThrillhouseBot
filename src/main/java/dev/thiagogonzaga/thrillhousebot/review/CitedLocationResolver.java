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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Resolves each candidate finding's cited {@code path:line} against the file it names, at the pull
 * request's head commit, and hands the verifier what is actually there (#650).
 *
 * <p>The verifier is one model call over fixed material and cannot look anything up. Its material
 * is a diff — changed hunks with a few context lines — so a finding whose evidence sits in the same
 * file a dozen lines outside the nearest hunk reads to it as a claim about code nobody showed it,
 * and its own prompt tells it to reject exactly that. #636 lost a real security-invariant violation
 * that way: the quoted line existed, nine lines from where the finding cited it, and simply was not
 * in the batch's hunks. The reverse failure is just as expensive — a finding judged against a line
 * number the model got slightly wrong is judged against the wrong code.
 *
 * <p>So resolution is quote-first and line-second: the finding's own {@code suggestion_old} is
 * searched for in the whole file, and only when it is nowhere does the cited line number decide
 * what is shown. A citation that is off by a few lines therefore still resolves, which is the case
 * that motivated the ticket.
 *
 * <p>Every outcome is reported as fact, never as a verdict — "the quoted code is at line 118, not
 * the cited line 109", "the pull request changes no file matching this path" — and the finding's
 * own {@code file} and {@code line} are left exactly as the model wrote them, so a citation the
 * model got wrong still reaches the maintainer as the model wrote it. Each correction is logged at
 * INFO for the same reason.
 *
 * <p>Only files the pull request itself changes are read. That is the whole cost story: the paths
 * fetched come from the PR's own file list rather than from model output, so no model-chosen path
 * ever reaches the contents API, the ignore globs already applied to that list apply here for free,
 * and the fetch count is bounded per review round and cached across batches. A path the PR does not
 * change is answered from the file list alone, with no request at all.
 *
 * <p>Failure is silent and total: an unreadable file, an oversized file, or an exhausted budget
 * attaches nothing and the verifier sees exactly what it sees today.
 */
@ApplicationScoped
public class CitedLocationResolver {

  private static final String ACCEPT = "application/vnd.github+json";

  /** Distinct files read per review round; findings citing further files resolve to nothing. */
  static final int MAX_FILES_FETCHED = 8;

  /** A source file is never megabytes; anything larger is skipped rather than decoded. */
  static final long MAX_FILE_BYTES = 512L * 1024;

  /** Lines shown either side of the resolved line. */
  static final int CONTEXT_LINES = 3;

  /** Character cap on one rendered source line, so a minified file cannot fill the prompt. */
  static final int MAX_LINE_CHARS = 200;

  /** Character cap on one finding's note. */
  static final int MAX_NOTE_CHARS = 1_200;

  /** Character cap on everything one round attaches, so this evidence never rivals the diff. */
  static final int MAX_TOTAL_CHARS = 6_000;

  private final GitHubPullRequestClient prClient;

  @Inject
  public CitedLocationResolver(@RestClient GitHubPullRequestClient prClient) {
    this.prClient = prClient;
  }

  /**
   * Opens a resolution round over one review's changed files. The returned object carries the
   * round's fetch budget and file cache, so the batches of a multi-call review share both; it is
   * used from those batch threads concurrently.
   *
   * @param ref the revision files are read at, normally the PR head SHA
   * @param files the review's reviewable files, already ignore-filtered
   */
  public Round forReview(
      String auth,
      String owner,
      String repo,
      String ref,
      List<GitHubPullRequestClient.FileDiff> files) {
    return new Round(prClient, auth, owner, repo, ref, files);
  }

  /** A round that resolves nothing, for a caller with no repository access. */
  public static Round disabled() {
    return new Round(null, null, null, null, null, List.of());
  }

  /** One review round's budget, cache and path index. */
  public static final class Round {

    private final GitHubPullRequestClient prClient;
    private final String auth;
    private final String owner;
    private final String repo;
    private final String ref;

    /** Changed files by exact path, plus the lowercase and rename-source indexes. */
    private final Map<String, GitHubPullRequestClient.FileDiff> byPath;

    private final Map<String, List<String>> pathsByLowerCase;
    private final Map<String, String> pathByRenameSource;

    /** Decoded file lines, empty when the file could not be read; shared across batches. */
    private final Map<String, Optional<List<String>>> contents = new ConcurrentHashMap<>();

    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicInteger charsAttached = new AtomicInteger();

    private Round(
        GitHubPullRequestClient prClient,
        String auth,
        String owner,
        String repo,
        String ref,
        List<GitHubPullRequestClient.FileDiff> files) {
      this.prClient = prClient;
      this.auth = auth;
      this.owner = owner;
      this.repo = repo;
      this.ref = ref;
      this.byPath = new LinkedHashMap<>();
      this.pathsByLowerCase = new LinkedHashMap<>();
      this.pathByRenameSource = new LinkedHashMap<>();
      index(files);
    }

    private void index(List<GitHubPullRequestClient.FileDiff> files) {
      if (files == null) {
        return;
      }
      for (var file : files) {
        if (file == null || file.filename() == null || file.filename().isBlank()) {
          continue;
        }
        byPath.put(file.filename(), file);
        pathsByLowerCase
            .computeIfAbsent(file.filename().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
            .add(file.filename());
        if (file.previousFilename() != null && !file.previousFilename().isBlank()) {
          pathByRenameSource.put(file.previousFilename(), file.filename());
        }
      }
    }

    /**
     * Resolves every finding in the list, returning the per-finding evidence the verifier is
     * handed. Findings are keyed by the location and title the model wrote, so the lookup still
     * finds its entry after the later stages of the pipeline rebuild a finding.
     *
     * <p>Call this with the findings <em>as the model raised them</em>, before {@link
     * FindingQuoteValidator} runs: that guard nulls {@code suggestion_old} on precisely the finding
     * whose quote is outside the review window, which is the finding this class exists to resolve.
     */
    public FindingVerificationService.CitedLocations locate(List<ReviewResponse.Finding> findings) {
      if (findings == null || findings.isEmpty() || prClient == null) {
        return FindingVerificationService.CitedLocations.NONE;
      }
      Map<Key, String> notes = new HashMap<>();
      for (var finding : findings) {
        if (finding == null || finding.file() == null || finding.file().isBlank()) {
          continue;
        }
        var key = Key.of(finding);
        if (notes.containsKey(key)) {
          continue;
        }
        var note = noteFor(finding);
        if (note != null) {
          notes.put(key, note);
        }
      }
      if (notes.isEmpty()) {
        return FindingVerificationService.CitedLocations.NONE;
      }
      return finding -> finding == null ? null : notes.get(Key.of(finding));
    }

    /** The sentence(s) attached to one finding, or {@code null} when nothing could be said. */
    private String noteFor(ReviewResponse.Finding finding) {
      var cited = finding.file().strip();
      var match = match(cited);
      if (match == null) {
        Log.infof(
            "Finding '%s' cites %s, a path no file in this pull request has; the verifier is told"
                + " the citation could not be resolved",
            LogSafe.oneLine(finding.title()), LogSafe.oneLine(finding.file()));
        return attach(
            finding,
            "No file changed by this pull request has this path (compared exactly, ignoring case,"
                + " by rename source, and by path suffix), so the cited location could not be"
                + " resolved against the pull request's files.");
      }
      var preamble = preambleFor(cited, match);
      if ("removed".equals(match.status())) {
        return attach(
            finding,
            preamble
                + "This pull request deletes `"
                + match.filename()
                + "`, so it has no content at the head commit.");
      }
      var lines = linesOf(match.filename());
      if (lines == null) {
        return null;
      }
      var body = resolveWithin(finding, match.filename(), lines);
      return body == null ? null : attach(finding, preamble + body);
    }

    /**
     * What the file at {@code path} actually holds for this finding: the quoted code wherever it
     * really is, falling back to the cited line when the quote is nowhere in the file.
     */
    private String resolveWithin(ReviewResponse.Finding finding, String path, List<String> lines) {
      var quoted = normalizedLines(finding.suggestionOld());
      var quoteLine = locateQuote(quoted, lines, finding.line());
      return quoteLine > 0
          ? quoteNote(finding, path, lines, quoteLine)
          : unquotedNote(finding, path, lines, quoted.isEmpty());
    }

    /** The note for a finding whose quote the file does not hold, or that quotes nothing. */
    private String unquotedNote(
        ReviewResponse.Finding finding, String path, List<String> lines, boolean quotesNothing) {
      var citedLine = finding.line();
      var missing =
          quotesNothing
              ? ""
              : "The code the finding quotes appears nowhere in `"
                  + path
                  + "` at the pull request's head commit. ";
      if (citedLine > lines.size()) {
        Log.infof(
            "Finding '%s' cites %s:%d, past the end of a file that has %d lines at the head"
                + " commit; the verifier is told the cited line does not exist",
            LogSafe.oneLine(finding.title()),
            LogSafe.oneLine(finding.file()),
            finding.line(),
            lines.size());
        return missing
            + "`"
            + path
            + "` has "
            + lines.size()
            + " lines at the pull request's head commit, so the cited line "
            + citedLine
            + " does not exist.";
      }
      if (citedLine <= 0) {
        // A file-level finding cites no line, so there is no window to show; only the absence of
        // a quote it did carry is worth saying.
        return missing.isBlank() ? null : missing.strip();
      }
      return missing + snippet(path, lines, citedLine);
    }

    /** The note for a quote that was found, naming the line it is really on. */
    private String quoteNote(
        ReviewResponse.Finding finding, String path, List<String> lines, int quoteLine) {
      var citedLine = finding.line();
      if (citedLine == quoteLine) {
        return "The code the finding quotes is at the cited line "
            + citedLine
            + " of `"
            + path
            + "`. "
            + snippet(path, lines, quoteLine);
      }
      Log.infof(
          "Finding '%s' cites %s:%d, but the code it quotes is at line %d of that file at the"
              + " head commit; the verifier is told where it really is and the finding keeps the"
              + " location the review raised it with",
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          finding.line(),
          quoteLine);
      return "The code the finding quotes is at line "
          + quoteLine
          + " of `"
          + path
          + "`, not at the cited line "
          + citedLine
          + ". "
          + snippet(path, lines, quoteLine);
    }

    /** What is said before the content when the cited path is not the path that was read. */
    private String preambleFor(String cited, GitHubPullRequestClient.FileDiff match) {
      if (cited.equals(match.filename())) {
        return "";
      }
      if (cited.equals(match.previousFilename())) {
        return "This pull request renames `"
            + cited
            + "` to `"
            + match.filename()
            + "`; the content below is read from the new path. ";
      }
      Log.infof(
          "A finding cites %s, which no file in the pull request has; the only changed file"
              + " matching it is %s, and the verifier is told so",
          LogSafe.oneLine(cited), match.filename());
      return "No file in the pull request has the cited path exactly; `"
          + match.filename()
          + "` is the only changed file it matches, and the content below is read from there. ";
    }

    /**
     * The changed file a cited path names, or {@code null} when none does. Exact first, then the
     * rename source, then a unique case-insensitive match, then a unique path-suffix match — the
     * shape of a citation that lost or gained a leading directory. Ambiguity resolves to nothing:
     * pointing the verifier at the wrong file is worse than pointing it at none.
     */
    private GitHubPullRequestClient.FileDiff match(String cited) {
      var exact = byPath.get(cited);
      if (exact != null) {
        return exact;
      }
      var renamed = pathByRenameSource.get(cited);
      if (renamed != null) {
        return byPath.get(renamed);
      }
      var sameCase = pathsByLowerCase.get(cited.toLowerCase(Locale.ROOT));
      if (sameCase != null && sameCase.size() == 1) {
        return byPath.get(sameCase.get(0));
      }
      return uniqueSuffixMatch(cited);
    }

    /** The one changed file whose path shares a trailing path segment run with {@code cited}. */
    private GitHubPullRequestClient.FileDiff uniqueSuffixMatch(String cited) {
      GitHubPullRequestClient.FileDiff found = null;
      for (var entry : byPath.entrySet()) {
        if (sharesPathSuffix(entry.getKey(), cited)) {
          if (found != null) {
            return null;
          }
          found = entry.getValue();
        }
      }
      return found;
    }

    /** The file's lines at the round's ref, fetched once per round, or {@code null}. */
    private List<String> linesOf(String path) {
      var cached =
          contents.computeIfAbsent(
              path,
              p ->
                  fetches.incrementAndGet() > MAX_FILES_FETCHED
                      ? Optional.empty()
                      : Optional.ofNullable(fetch(p)));
      return cached.orElse(null);
    }

    private List<String> fetch(String path) {
      try {
        var file = prClient.getFileContent(auth, ACCEPT, owner, repo, path, ref);
        if (file == null || file.content() == null || file.size() > MAX_FILE_BYTES) {
          return null;
        }
        // GitHub wraps base64 content in newlines — only the MIME decoder tolerates them.
        var text =
            new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);
        if (text.isEmpty()) {
          return null;
        }
        // A file ending in a newline has no extra last line; keeping the split's trailing empty
        // element would overstate the line count the "line N does not exist" note reports.
        var lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
          lines.remove(lines.size() - 1);
        }
        return List.copyOf(lines);
      } catch (RuntimeException e) {
        Log.debugf(
            e, "Could not read %s from %s/%s to resolve a cited location", path, owner, repo);
        return null;
      }
    }

    /** Charges the round's character budget, dropping a note that no longer fits. */
    private String attach(ReviewResponse.Finding finding, String note) {
      var bounded = note.length() > MAX_NOTE_CHARS ? note.substring(0, MAX_NOTE_CHARS) : note;
      if (charsAttached.addAndGet(bounded.length()) > MAX_TOTAL_CHARS) {
        Log.debugf(
            "Cited-location evidence for %s:%d dropped at the round's character budget",
            LogSafe.oneLine(finding.file()), finding.line());
        return null;
      }
      return bounded;
    }
  }

  /**
   * How a finding is looked up after the pipeline has rebuilt it. Location and title survive every
   * stage between resolution and verification; {@code suggestion_old} does not.
   */
  record Key(String file, int line, String title) {
    static Key of(ReviewResponse.Finding finding) {
      return new Key(finding.file(), finding.line(), finding.title());
    }
  }

  /**
   * The line the quoted code is on, or 0 when the file does not hold it. A multi-line quote must
   * appear as a contiguous run; a single-line quote is that run's degenerate case. When the code
   * appears more than once the occurrence nearest the cited line wins, so a near-miss citation
   * resolves to the location it was nearly right about rather than to the file's first match.
   */
  static int locateQuote(List<String> quoted, List<String> lines, int citedLine) {
    if (quoted.isEmpty()) {
      return 0;
    }
    var best = 0;
    for (var i = 0; i < lines.size(); i++) {
      if (!lines.get(i).strip().equals(quoted.get(0))) {
        continue;
      }
      if (!runMatches(quoted, lines, i)) {
        continue;
      }
      var lineNumber = i + 1;
      if (best == 0 || Math.abs(lineNumber - citedLine) < Math.abs(best - citedLine)) {
        best = lineNumber;
      }
    }
    return best;
  }

  /** Whether the quote's remaining lines follow, skipping blank lines the model dropped. */
  private static boolean runMatches(List<String> quoted, List<String> lines, int start) {
    var at = start;
    for (var q = 1; q < quoted.size(); q++) {
      at++;
      while (at < lines.size() && lines.get(at).isBlank()) {
        at++;
      }
      if (at >= lines.size() || !lines.get(at).strip().equals(quoted.get(q))) {
        return false;
      }
    }
    return true;
  }

  /** The quote's non-blank lines, stripped, in order — the form {@link #locateQuote} matches. */
  static List<String> normalizedLines(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    var normalized = new ArrayList<String>();
    for (var raw : text.split("\n", -1)) {
      var line = raw.strip();
      if (!line.isEmpty()) {
        normalized.add(line);
      }
    }
    return List.copyOf(normalized);
  }

  /**
   * Whether one path ends in the other on a path-segment boundary. Equal paths are not considered:
   * the only caller reaches this after an exact lookup has already missed.
   */
  static boolean sharesPathSuffix(String a, String b) {
    return a.endsWith("/" + b) || b.endsWith("/" + a);
  }

  /** The numbered window around {@code focus}, with the focused line marked. */
  static String snippet(String path, List<String> lines, int focus) {
    var from = Math.max(1, focus - CONTEXT_LINES);
    var to = Math.min(lines.size(), focus + CONTEXT_LINES);
    var sb =
        new StringBuilder("Lines ")
            .append(from)
            .append('-')
            .append(to)
            .append(" of `")
            .append(path)
            .append("` at the pull request's head commit:\n");
    for (var n = from; n <= to; n++) {
      var text = lines.get(n - 1);
      sb.append(n == focus ? "> " : "  ")
          .append(n)
          .append(" | ")
          .append(text.length() > MAX_LINE_CHARS ? text.substring(0, MAX_LINE_CHARS) + "…" : text)
          .append('\n');
    }
    return sb.toString().stripTrailing();
  }
}
