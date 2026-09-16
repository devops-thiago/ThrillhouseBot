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
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Attaches to each candidate finding the review-context material that finding rests on, so the
 * verifier judges the material instead of guessing at it (#475).
 *
 * <p>The reviewer is handed context sections the verifier is not: the patch-coverage measurement
 * for this commit (#115) and the maintainers' path-scoped rules (#33). A finding raised from one of
 * them therefore reaches the verifier as a claim about an artifact nobody showed it, which is
 * precisely the ground the verifier's own prompt tells it to reject on. Every context dimension
 * added to the review pass widens that gap.
 *
 * <p>The finding's grounding is built into the finding instead. The generator prompt asks for the
 * measurement or the rule to be quoted in the description; this class then matches the finding
 * against the sections the review actually supplied and attaches what they really say, the way
 * {@link CitedLocationResolver} attaches what the cited file really holds. Both ride on the
 * candidate JSON rather than in a verifier slot of their own, so the verifier's inputs stay as they
 * are while this material grows, and both charge the one {@link EvidenceBudget} the review holds.
 *
 * <p>Evidence is read from the rendered sections rather than from the resolvers that produced them,
 * because what the verifier has to be told is what the REVIEWER was shown. The coverage section is
 * capped at a file count, a range count per file and a total size, so a file the render dropped is
 * a file the reviewer never saw — and a coverage claim about it rests on nothing, whatever the
 * report held.
 *
 * <p>Both directions are attached, and they are not symmetric.
 *
 * <ul>
 *   <li>Supporting material is attached whenever it exists: a finding anchored to a line the
 *       coverage section lists carries that measurement, and a finding on a file a scope governs
 *       carries that scope's rules verbatim.
 *   <li>Contradicting material is attached only where a claim could otherwise be invented out of
 *       nothing. A reviewer that attributes a measurement to a coverage report is told, in the
 *       field, when this review's section does not carry it — including when there was no section
 *       at all. Without that, "the coverage report shows this line is never executed" would be a
 *       sentence any finding could write to buy itself a rejection ground it is immune to. A
 *       fabricated path-scoped rule needs no such note: the scope's real text is attached beside
 *       the finding, so a rule the maintainers never wrote is visible as absent from it.
 * </ul>
 *
 * <p>Nothing here is a verdict. Each note states what the review's own material says and leaves the
 * judgement to the verifier, exactly as {@link CitedLocationResolver} does.
 */
public final class ContextEvidenceResolver {

  /** Character cap on one quoted scope's rules, so scoped prose cannot fill a finding's note. */
  static final int MAX_QUOTED_RULE_CHARS = 500;

  /**
   * Marker of a rule block the cap cut, so the verifier never reads a cut rule as the whole one.
   */
  private static final String RULES_TRUNCATED = "\n… (rules truncated)";

  /**
   * The word a finding uses when it credits coverage material for something. One stem, not a list
   * of the phrasings the review prompt happens to coach: "per the coverage analysis", "the coverage
   * results show", "the CI's coverage run confirms" all credit a measurement, and a scan that
   * missed them would let a paraphrased fabrication reach the verifier uncontradicted, which is the
   * direction this class exists to close (#475 review).
   *
   * <p>It reads as a mention rather than as a claim, deliberately. A finding can name coverage to
   * disclaim it, or raise a defect about a coverage configuration, and either then travels with a
   * note stating what the section holds. That note costs such a finding nothing — the verifier's
   * rule for it is conditioned on an attribution the finding actually made — while the miss it
   * replaces costs the guard the case it was built for. The stem stops short of the outcome
   * wording: "never executed" is also how a finding grounded in the diff alone describes
   * unreachable code, and contradicting that would contradict a claim it never made.
   */
  private static final String COVERAGE_MENTION = "coverage";

  /**
   * The rendered coverage section's per-file line prefix, as {@code PatchCoverageResolver} emits
   * it.
   */
  private static final String FILE_LINE_PREFIX = "- ";

  /**
   * What the render's roll-up line says after its count, as {@code PatchCoverageResolver} emits it.
   */
  private static final String ROLL_UP_SUFFIX = " more changed file(s)";

  /** What a file's rendered ranges end with when the render named only some of them. */
  private static final String RANGE_ROLL_UP = " more range(s)";

  /** What the render leaves behind when the section's total size cap cut it. */
  private static final String SECTION_CUT = "(patch coverage truncated)";

  private ContextEvidenceResolver() {}

  /**
   * Opens a round over the context sections one review supplied. The round is used from the batch
   * threads of a multi-call review concurrently; its parsed sections are immutable and its budget
   * is the review's own.
   *
   * @param patchCoverage the rendered uncovered-changed-lines section, or blank when none was read
   * @param pathInstructions the scopes that matched a file in this review
   * @param budget the review's evidence budget, shared with {@link CitedLocationResolver}
   */
  public static Round forReview(
      String patchCoverage, PathScopedInstructions pathInstructions, EvidenceBudget budget) {
    return new Round(patchCoverage, pathInstructions, budget, false);
  }

  /**
   * A round that attaches nothing at all, for a caller that loaded no review context. It is silent
   * rather than merely empty: a caller that never looked knows nothing about what the review
   * measured, and "this review supplied no patch-coverage section" would be a statement about the
   * caller rather than about the review (#475 review).
   */
  public static Round disabled() {
    return new Round(null, null, null, true);
  }

  /** One review's parsed context sections and the budget their notes are charged to. */
  public static final class Round {

    /** The section's listed files with their rendered ranges, and the count it left unnamed. */
    private final Coverage coverage;

    /** Whether a coverage section was supplied at all, which an empty map does not say. */
    private final boolean coverageSupplied;

    /** The scopes governing each file they matched, in declaration order, built once per review. */
    private final Map<String, List<PathScopedInstructions.AppliedScope>> scopesByFile;

    /**
     * Governed files by their last path segment. A suffix match implies an equal last segment —
     * {@link CitedLocationResolver#sharesPathSuffix} asks one path to end in {@code "/" + } the
     * other — so a citation that lost a leading directory is looked up here instead of walking
     * every governed file once per finding.
     */
    private final Map<String, List<String>> governedByName;

    private final EvidenceBudget budget;

    /** Whether this round speaks at all; see {@link ContextEvidenceResolver#disabled()}. */
    private final boolean silent;

    private Round(
        String patchCoverage,
        PathScopedInstructions pathInstructions,
        EvidenceBudget budget,
        boolean silent) {
      this.coverageSupplied = patchCoverage != null && !patchCoverage.isBlank();
      this.coverage = coverageSupplied ? parseSection(patchCoverage) : Coverage.NONE;
      this.scopesByFile = new LinkedHashMap<>();
      this.governedByName = new LinkedHashMap<>();
      index(pathInstructions);
      this.budget = budget;
      this.silent = silent;
    }

    /** The scope and name indexes one review's governed files are looked up through. */
    private void index(PathScopedInstructions pathInstructions) {
      if (pathInstructions == null) {
        return;
      }
      for (var scope : pathInstructions.scopes()) {
        for (var file : scope.files()) {
          scopesByFile.computeIfAbsent(file, f -> new ArrayList<>()).add(scope);
          governedByName.computeIfAbsent(lastSegment(file), n -> new ArrayList<>()).add(file);
        }
      }
    }

    /**
     * Resolves every finding in the list, returning the per-finding evidence the verifier is
     * handed. Findings are keyed by the location and title the model wrote, so the lookup still
     * finds its entry after the later stages of the pipeline rebuild a finding.
     */
    public FindingVerificationService.ContextEvidence locate(
        List<ReviewResponse.Finding> findings) {
      if (silent || findings == null || findings.isEmpty()) {
        return FindingVerificationService.ContextEvidence.NONE;
      }
      Map<FindingKey, String> notes = new HashMap<>();
      for (var finding : findings) {
        var key = FindingKey.of(finding);
        if (key != null && !notes.containsKey(key)) {
          var note = noteFor(finding);
          if (note != null) {
            notes.put(key, note);
          }
        }
      }
      if (notes.isEmpty()) {
        return FindingVerificationService.ContextEvidence.NONE;
      }
      return finding -> {
        var key = FindingKey.of(finding);
        return key == null ? null : notes.get(key);
      };
    }

    /** The sentences attached to one finding, or {@code null} when nothing could be said. */
    private String noteFor(ReviewResponse.Finding finding) {
      var note = join(coverageNote(finding), pathRuleNote(finding));
      if (note == null) {
        return null;
      }
      var attached = budget.attach(note);
      if (attached == null) {
        Log.debugf(
            "Context evidence for %s:%d dropped at the review's character budget",
            LogSafe.oneLine(finding.file()), finding.line());
      }
      return attached;
    }

    /** What this review's coverage section says about the line the finding cites. */
    private String coverageNote(ReviewResponse.Finding finding) {
      var cited = finding.file().strip();
      var path = coveragePathFor(cited);
      var ranges = path == null ? null : coverage.byPath().get(path);
      if (ranges != null && rangesContain(ranges, finding.line())) {
        return "The patch-coverage section this review supplied lists line "
            + finding.line()
            + " of `"
            + path
            + "` among the added lines the report for this commit records as executable and never"
            + " executed. The lines it lists for that file: "
            + ranges
            + ".";
      }
      if (!namesCoverage(finding)) {
        return null;
      }
      if (!coverageSupplied) {
        return contradiction(
            finding,
            "This review supplied no patch-coverage section: no coverage report was read for this"
                + " commit, so nothing in this review's material measures which lines a test"
                + " executed.");
      }
      if (ranges == null) {
        return contradiction(finding, unlistedFileNote(cited));
      }
      return contradiction(finding, listedFileNote(path, ranges, finding.line()));
    }

    /**
     * What the section says about a line it does not name in a file it does list. The per-file
     * ranges are capped too, and the render discloses that with its own roll-up, so a line past the
     * named ones may still have been measured: the note says what the section names rather than
     * denying the measurement (#475 review).
     */
    private static String listedFileNote(String path, String ranges, int citedLine) {
      var listed =
          "The patch-coverage section this review supplied lists these added lines of `"
              + path
              + "` as never executed: "
              + ranges
              + ".";
      return ranges.contains(RANGE_ROLL_UP)
          ? listed
              + " The cited line "
              + citedLine
              + " is not among the ones it names, and the section says it names only part of that"
              + " file's uncovered lines, so whether the report measured this line is not settled"
              + " here."
          : listed + " The cited line " + citedLine + " is not among them.";
    }

    /**
     * Records that a finding named the coverage report while citing a line the review's section
     * does not carry. The line says the finding NAMES the report rather than that it claims
     * anything of it: the scan reads prose, and a finding can name the report to disclaim it ("no
     * coverage data was read, but the guard above returns first"), which is a mention and not an
     * attribution (#475 review). It also stops short of saying the verifier was told: the note is
     * built here and charged to the review's budget afterwards, which can still drop it, and {@link
     * #noteFor} logs that separately.
     */
    private static String contradiction(ReviewResponse.Finding finding, String note) {
      Log.infof(
          "Finding '%s' (%s:%d) names patch coverage while citing a line this review's section does"
              + " not carry",
          LogSafe.oneLine(finding.title()), LogSafe.oneLine(finding.file()), finding.line());
      return note;
    }

    /**
     * What the section says about a file it does not list. Not being listed is not being measured
     * and covered: the render names only as many files as its cap allows and then discloses how
     * many it left out, so a finding's file may be one of those and its measurement may be real
     * (#475 review). The note says so rather than reading the absence as a refutation.
     */
    private String unlistedFileNote(String cited) {
      var base =
          "The patch-coverage section this review supplied lists no uncovered added line in `"
              + cited
              + "`. A file the report does not measure is absent from that section too, so this"
              + " does not establish that the file's lines are covered either.";
      if (coverage.unnamedFiles() > 0) {
        return base
            + " The section also says "
            + coverage.unnamedFiles()
            + " further changed file(s) have uncovered added lines without naming them, so this"
            + " file may be one of them.";
      }
      // The size cap takes whole lines, and the roll-up is the last of them, so a cut section can
      // name fewer files than the report measured with no count left to say so.
      return coverage.truncated()
          ? base
              + " The section was cut at its size cap, so it may not name every file with uncovered"
              + " added lines and this file may be one it left out."
          : base;
    }

    /** The path the coverage section lists for a cited path, or {@code null} when it lists none. */
    private String coveragePathFor(String cited) {
      if (coverage.byPath().containsKey(cited)) {
        return cited;
      }
      String found = null;
      for (var path : coverage.byPath().keySet()) {
        if (CitedLocationResolver.sharesPathSuffix(path, cited)) {
          if (found != null) {
            // Two listed files match the citation, so which one it means is not settled; saying
            // nothing beats naming another file's measurement as this finding's.
            return null;
          }
          found = path;
        }
      }
      return found;
    }

    /** The maintainers' scoped rules for the file the finding cites, quoted as they wrote them. */
    private String pathRuleNote(ReviewResponse.Finding finding) {
      var cited = finding.file().strip();
      var governed = governedPathFor(cited);
      if (governed == null) {
        return null;
      }
      var preamble =
          governed.equals(cited)
              ? ""
              : "The cited path `"
                  + cited
                  + "` matches `"
                  + governed
                  + "`, the only file under a maintainer-scoped glob it matches. ";
      var sb = new StringBuilder();
      for (var scope : scopesByFile.get(governed)) {
        sb.append(sb.isEmpty() ? preamble : "\n")
            .append("The maintainers scoped review rules to files matching `")
            .append(scope.glob())
            .append("`, and `")
            .append(governed)
            .append("` is one of the files in this pull request they govern. Their text for that")
            .append(" glob, verbatim:\n")
            .append(quotedRules(scope.instructions()));
      }
      // A governed path came from the index, so it has at least one scope.
      return sb.toString();
    }

    /**
     * The governed file a citation names, or {@code null} when none does or more than one does.
     * Exact first, then the single governed file a citation that lost a leading directory matches —
     * the same discipline {@link #coveragePathFor} applies, and for the same reason: a scope's
     * rules attached to a finding about another directory's file assert a governance fact that is
     * not true, which is the direction this class exists to close (#475 review).
     */
    private String governedPathFor(String cited) {
      if (scopesByFile.containsKey(cited)) {
        return cited;
      }
      String found = null;
      for (var file : governedByName.getOrDefault(lastSegment(cited), List.of())) {
        if (CitedLocationResolver.sharesPathSuffix(file, cited)) {
          if (found != null) {
            return null;
          }
          found = file;
        }
      }
      return found;
    }
  }

  /** The file name a path ends in, which a suffix match always shares with the path it matches. */
  static String lastSegment(String path) {
    var slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  /** A scope's rules, bounded so one verbose scope cannot crowd out every other note. */
  static String quotedRules(String instructions) {
    var rules = instructions == null ? "" : instructions.strip();
    return rules.length() <= MAX_QUOTED_RULE_CHARS
        ? rules
        : ConfigKeyContextResolver.truncate(rules, MAX_QUOTED_RULE_CHARS) + RULES_TRUNCATED;
  }

  /** The two notes as one, dropping either when it has nothing to say. */
  static String join(String coverage, String pathRules) {
    if (coverage == null) {
      return pathRules;
    }
    return pathRules == null ? coverage : coverage + "\n" + pathRules;
  }

  /**
   * Whether the finding names coverage material at all. Only the finding's own prose is scanned —
   * the text a maintainer would read as the claim — and only to decide whether a contradicting note
   * is worth attaching.
   */
  static boolean namesCoverage(ReviewResponse.Finding finding) {
    var text =
        ((finding.title() == null ? "" : finding.title())
                + ' '
                + (finding.description() == null ? "" : finding.description()))
            .toLowerCase(Locale.ROOT);
    return text.contains(COVERAGE_MENTION);
  }

  /**
   * What one rendered coverage section carries: the files it lists with their rendered ranges, and
   * the number of further changed files it says have uncovered added lines without naming them.
   *
   * @param byPath the listed files and their ranges, exactly as the section renders them
   * @param unnamedFiles the render's own roll-up count, 0 when it listed everything
   * @param truncated whether the render's total size cap cut the section, which can take the
   *     roll-up line with it and leave no count behind
   */
  record Coverage(Map<String, String> byPath, int unnamedFiles, boolean truncated) {

    /** No section was supplied, which is not the same as a section that lists nothing. */
    static final Coverage NONE = new Coverage(Map.of(), 0, false);
  }

  /**
   * The rendered coverage section read back into what it says. Its per-file lines are {@code -
   * <path>: <ranges>} and its roll-up is {@code - (N more changed file(s) …)}; the heading, the
   * prose and a line carrying no ranges at all do not parse and are skipped.
   *
   * <p>A line the section's size cap cut mid-range would parse, and its last range would be a line
   * number the report never measured. Nothing here could tell that token from a real one, so the
   * cap is applied on a line boundary in {@code PatchCoverageResolver.render} instead: the section
   * this reads never holds a partial entry.
   */
  static Coverage parseSection(String section) {
    Map<String, String> byPath = new LinkedHashMap<>();
    var unnamed = 0;
    for (var raw : section.split("\n")) {
      var line = raw.strip();
      if (!line.startsWith(FILE_LINE_PREFIX)) {
        continue;
      }
      var entry = line.substring(FILE_LINE_PREFIX.length());
      var colon = entry.indexOf(": ");
      if (colon > 0) {
        byPath.put(entry.substring(0, colon), entry.substring(colon + 2).strip());
      } else {
        unnamed += rolledUpFiles(entry);
      }
    }
    return new Coverage(Map.copyOf(byPath), unnamed, section.contains(SECTION_CUT));
  }

  /** The count a roll-up line discloses, or 0 when the line is not one. */
  private static int rolledUpFiles(String entry) {
    if (!entry.startsWith("(") || !entry.contains(ROLL_UP_SUFFIX)) {
      return 0;
    }
    var count = parseLine(entry.substring(1, entry.indexOf(ROLL_UP_SUFFIX)));
    return count == null ? 0 : count;
  }

  /**
   * Whether the rendered ranges of one file cover {@code line}. The text is the section's own —
   * {@code 12-18, 24, and 3 more range(s)} — so a token that is neither a number nor a range is the
   * roll-up and simply does not match.
   */
  static boolean rangesContain(String ranges, int line) {
    for (var token : ranges.split(",")) {
      if (rangeContains(token.strip(), line)) {
        return true;
      }
    }
    return false;
  }

  private static boolean rangeContains(String token, int line) {
    var dash = token.indexOf('-');
    var from = parseLine(dash <= 0 ? token : token.substring(0, dash));
    var to = dash <= 0 ? from : parseLine(token.substring(dash + 1));
    return from != null && to != null && line >= from && line <= to;
  }

  /** One rendered bound as a number, or {@code null} when the token is not one. */
  private static Integer parseLine(String token) {
    try {
      return Integer.valueOf(token.strip());
    } catch (NumberFormatException _) {
      return null;
    }
  }
}
