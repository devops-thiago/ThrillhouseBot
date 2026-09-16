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
 * that motivated the ticket. A quote the file holds in more than one place settles nothing, and is
 * reported as unsettled rather than resolved to the nearest one: a generic one-liner that also
 * appears in another method would otherwise point the verifier at code the finding never meant, and
 * the prompt reads this material as fact.
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

  /**
   * Blank lines tolerated between two consecutive lines of a quote. The quote arrives with its own
   * blank lines dropped, so a run has to step over the source's; tolerating any number instead
   * would let a two-line quote match lines that merely appear in that order anywhere below each
   * other, which is not the contiguous run this match claims to be.
   */
  static final int MAX_BLANK_GAP = 2;

  /** Character cap on one rendered source line, so a minified file cannot fill the prompt. */
  static final int MAX_LINE_CHARS = 200;

  /** The phrase joining a line number to the path it belongs to, used in every rendered note. */
  private static final String OF_PATH = " of `";

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
        if (file != null && file.filename() != null && !file.filename().isBlank()) {
          byPath.put(file.filename(), file);
          pathsByLowerCase
              .computeIfAbsent(file.filename().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
              .add(file.filename());
          if (file.previousFilename() != null && !file.previousFilename().isBlank()) {
            pathByRenameSource.put(file.previousFilename(), file.filename());
          }
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
        var key = keyOf(finding);
        if (key != null && !notes.containsKey(key)) {
          var note = noteFor(finding);
          if (note != null) {
            notes.put(key, note);
          }
        }
      }
      if (notes.isEmpty()) {
        return FindingVerificationService.CitedLocations.NONE;
      }
      return finding -> {
        var key = keyOf(finding);
        return key == null ? null : notes.get(key);
      };
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
      if (lines.isEmpty()) {
        return null;
      }
      var body = resolveWithin(finding, match.filename(), lines.get());
      return body == null ? null : attach(finding, preamble + body);
    }

    /**
     * What the file at {@code path} actually holds for this finding: the quoted code wherever it
     * really is, falling back to the cited line when the quote is nowhere in the file.
     */
    private String resolveWithin(ReviewResponse.Finding finding, String path, List<String> lines) {
      var quoted = normalizedLines(finding.suggestionOld());
      var match = locateQuote(quoted, lines, finding.line());
      if (!match.found()) {
        return unquotedNote(finding, path, lines, quoted.isEmpty());
      }
      return match.occurrences() > 1
          ? ambiguousQuoteNote(finding, path, lines, match)
          : quoteNote(finding, path, lines, match.line());
    }

    /**
     * The note for a quote the file holds in more than one place. Which occurrence the finding
     * means is not settled by the quote, so the nearest one is named rather than asserted: a
     * generic one-liner ({@code return null;}, a lone brace) that also appears in another method
     * would otherwise redirect the verifier to code the finding never pointed at, and the prompt
     * reads this field as settled fact. The window shown is the cited line's whenever that line
     * exists, since that is where the finding actually points.
     */
    private String ambiguousQuoteNote(
        ReviewResponse.Finding finding, String path, List<String> lines, QuoteMatch match) {
      var citedLine = finding.line();
      var inRange = citedLine >= 1 && citedLine <= lines.size();
      Log.infof(
          "Finding '%s' cites %s:%d and quotes code that appears in %d places in that file at the"
              + " head commit; the verifier is told the occurrence is not settled",
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          finding.line(),
          match.occurrences());
      return "The code the finding quotes appears in "
          + match.occurrences()
          + " places in `"
          + path
          + "` at the pull request's head commit, so which one it means is not settled here; the"
          + " nearest to the cited "
          + citedDescription(citedLine)
          + " is line "
          + match.line()
          + ". "
          + (inRange ? "" : "The cited line is not a line of this file. ")
          + snippet(path, lines, inRange ? citedLine : match.line());
    }

    /** How a citation's line is named in prose, including the file-level finding that has none. */
    private static String citedDescription(int citedLine) {
      return citedLine <= 0 ? "location, which names no line," : "line " + citedLine;
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
            + OF_PATH
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
          + OF_PATH
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

    /** The file's lines at the round's ref, fetched once per round; empty when unreadable. */
    private Optional<List<String>> linesOf(String path) {
      return contents.computeIfAbsent(
          path, p -> fetches.incrementAndGet() > MAX_FILES_FETCHED ? Optional.empty() : fetch(p));
    }

    private Optional<List<String>> fetch(String path) {
      try {
        var file = prClient.getFileContent(auth, ACCEPT, owner, repo, path, ref);
        if (file == null || file.content() == null || file.size() > MAX_FILE_BYTES) {
          return Optional.empty();
        }
        // GitHub wraps base64 content in newlines — only the MIME decoder tolerates them.
        var text =
            new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);
        if (text.isEmpty()) {
          return Optional.empty();
        }
        // A file ending in a newline has no extra last line; keeping the split's trailing empty
        // element would overstate the line count the "line N does not exist" note reports.
        var lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
          lines.remove(lines.size() - 1);
        }
        return Optional.of(List.copyOf(lines));
      } catch (RuntimeException e) {
        Log.debugf(
            e, "Could not read %s from %s/%s to resolve a cited location", path, owner, repo);
        return Optional.empty();
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
  record Key(String file, int line, String title) {}

  /** The lookup key for a finding worth resolving, or {@code null} when it cites no file. */
  private static Key keyOf(ReviewResponse.Finding finding) {
    if (finding == null || finding.file() == null || finding.file().isBlank()) {
      return null;
    }
    return new Key(finding.file(), finding.line(), finding.title());
  }

  /**
   * Where the quoted code sits in the file: the occurrence nearest the cited line, and how many
   * places hold it. A near-miss citation therefore resolves to the location it was nearly right
   * about rather than to the file's first match, while a quote the file holds more than once is
   * reported as unsettled rather than silently resolved to one of them.
   */
  record QuoteMatch(int line, int occurrences) {

    static final QuoteMatch NONE = new QuoteMatch(0, 0);

    boolean found() {
      return line > 0;
    }
  }

  /**
   * The {@link QuoteMatch} for this quote. A multi-line quote must appear as a run in order, with
   * at most {@link #MAX_BLANK_GAP} blank lines between consecutive quoted lines — the quote itself
   * carries no blank lines, so some tolerance is needed, but an unbounded one would match lines
   * scattered anywhere below each other. A single-line quote is that run's degenerate case.
   */
  static QuoteMatch locateQuote(List<String> quoted, List<String> lines, int citedLine) {
    if (quoted.isEmpty()) {
      return QuoteMatch.NONE;
    }
    var best = 0;
    var occurrences = 0;
    for (var i = 0; i < lines.size(); i++) {
      if (lines.get(i).strip().equals(quoted.get(0)) && runMatches(quoted, lines, i)) {
        occurrences++;
        var lineNumber = i + 1;
        if (best == 0 || Math.abs(lineNumber - citedLine) < Math.abs(best - citedLine)) {
          best = lineNumber;
        }
      }
    }
    return new QuoteMatch(best, occurrences);
  }

  /** Whether the quote's remaining lines follow, over the blank lines the quote itself dropped. */
  private static boolean runMatches(List<String> quoted, List<String> lines, int start) {
    var at = start;
    for (var q = 1; q < quoted.size(); q++) {
      at++;
      var skipped = 0;
      while (at < lines.size() && lines.get(at).isBlank() && skipped < MAX_BLANK_GAP) {
        at++;
        skipped++;
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
            .append(OF_PATH)
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
