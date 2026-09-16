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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
   * The phrases a finding uses when it names the coverage report as its source — the wording the
   * review prompt itself asks for. Every one of them names the report; none of them merely
   * describes the outcome. "Never executed" was here and is not: it is also how a finding grounded
   * in the diff alone describes unreachable code ("the guard returns first, so it is never
   * executed"), and contradicting that finding's measurement would contradict a claim it never made
   * (#475 review). The scan is deliberately literal and narrow, and it gates only the contradicting
   * notes: a phrasing it misses attaches nothing, which is the behaviour the verifier had before
   * this class existed.
   */
  private static final List<String> COVERAGE_ATTRIBUTIONS =
      List.of(
          "coverage report",
          "patch coverage",
          "coverage section",
          "coverage measurement",
          "coverage data");

  /**
   * The rendered coverage section's per-file line prefix, as {@code PatchCoverageResolver} emits
   * it.
   */
  private static final String FILE_LINE_PREFIX = "- ";

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

    /** Uncovered added lines, as the section renders them, by the path it lists them under. */
    private final Map<String, String> uncoveredByPath;

    /** Whether a coverage section was supplied at all, which an empty map does not say. */
    private final boolean coverageSupplied;

    private final List<PathScopedInstructions.AppliedScope> scopes;

    /** Every file any of this review's scopes governs, for the citation lookup below. */
    private final Set<String> governedFiles;

    private final EvidenceBudget budget;

    /** Whether this round speaks at all; see {@link ContextEvidenceResolver#disabled()}. */
    private final boolean silent;

    private Round(
        String patchCoverage,
        PathScopedInstructions pathInstructions,
        EvidenceBudget budget,
        boolean silent) {
      this.coverageSupplied = patchCoverage != null && !patchCoverage.isBlank();
      this.uncoveredByPath = coverageSupplied ? parseUncovered(patchCoverage) : Map.of();
      this.scopes = pathInstructions == null ? List.of() : pathInstructions.scopes();
      this.governedFiles =
          scopes.stream()
              .flatMap(scope -> scope.files().stream())
              .collect(Collectors.toUnmodifiableSet());
      this.budget = budget;
      this.silent = silent;
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
      return note == null ? null : budget.attach(note);
    }

    /** What this review's coverage section says about the line the finding cites. */
    private String coverageNote(ReviewResponse.Finding finding) {
      var cited = finding.file().strip();
      var path = coveragePathFor(cited);
      var ranges = path == null ? null : uncoveredByPath.get(path);
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
      if (!attributesToCoverage(finding)) {
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
        return contradiction(
            finding,
            "The patch-coverage section this review supplied lists no uncovered added line in `"
                + cited
                + "`. A file the report does not measure is absent from that section too, so this"
                + " does not establish that the file's lines are covered either.");
      }
      return contradiction(
          finding,
          "The patch-coverage section this review supplied lists these added lines of `"
              + path
              + "` as never executed: "
              + ranges
              + ". The cited line "
              + finding.line()
              + " is not among them.");
    }

    /**
     * Records that a finding attributed to coverage material the review's section does not hold.
     */
    private static String contradiction(ReviewResponse.Finding finding, String note) {
      Log.infof(
          "Finding '%s' (%s:%d) attributes a claim to patch coverage that this review's section"
              + " does not carry; the verifier is told what the section actually lists",
          LogSafe.oneLine(finding.title()), LogSafe.oneLine(finding.file()), finding.line());
      return note;
    }

    /** The path the coverage section lists for a cited path, or {@code null} when it lists none. */
    private String coveragePathFor(String cited) {
      if (uncoveredByPath.containsKey(cited)) {
        return cited;
      }
      String found = null;
      for (var path : uncoveredByPath.keySet()) {
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
      for (var scope : scopes) {
        if (scope.files().contains(governed)) {
          sb.append(sb.isEmpty() ? preamble : "\n")
              .append("The maintainers scoped review rules to files matching `")
              .append(scope.glob())
              .append("`, and `")
              .append(governed)
              .append("` is one of the files in this pull request they govern. Their text for that")
              .append(" glob, verbatim:\n")
              .append(quotedRules(scope.instructions()));
        }
      }
      // A governed path came from the scopes' own file lists, so at least one scope holds it.
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
      if (governedFiles.contains(cited)) {
        return cited;
      }
      String found = null;
      for (var file : governedFiles) {
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
   * Whether the finding credits a coverage report for something. Only the finding's own prose is
   * scanned — the text a maintainer would read as the claim — and only to decide whether a
   * contradicting note is worth attaching.
   */
  static boolean attributesToCoverage(ReviewResponse.Finding finding) {
    var text =
        ((finding.title() == null ? "" : finding.title())
                + ' '
                + (finding.description() == null ? "" : finding.description()))
            .toLowerCase(Locale.ROOT);
    return COVERAGE_ATTRIBUTIONS.stream().anyMatch(text::contains);
  }

  /**
   * The rendered coverage section read back into the paths and line ranges it lists. Its per-file
   * lines are {@code - <path>: <ranges>}; the heading, the prose, the roll-up count and a line
   * carrying no ranges at all do not parse and are skipped.
   *
   * <p>A line the section's size cap cut mid-range would parse, and its last range would be a line
   * number the report never measured. Nothing here could tell that token from a real one, so the
   * cap is applied on a line boundary in {@code PatchCoverageResolver.render} instead: the section
   * this reads never holds a partial entry.
   */
  static Map<String, String> parseUncovered(String section) {
    Map<String, String> byPath = new LinkedHashMap<>();
    for (var raw : section.split("\n")) {
      var line = raw.strip();
      if (!line.startsWith(FILE_LINE_PREFIX)) {
        continue;
      }
      var entry = line.substring(FILE_LINE_PREFIX.length());
      var colon = entry.indexOf(": ");
      if (colon > 0) {
        byPath.put(entry.substring(0, colon), entry.substring(colon + 2).strip());
      }
    }
    return Map.copyOf(byPath);
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
