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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient.IssueComment;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link BaseCodeCitations}: linked-issue criteria the description shows met on the base. */
class BaseCodeCitationsTest {

  private static final String REVIEW = "src/main/java/dev/thiagogonzaga/thrillhousebot/review/";
  private static final String TESTS = "src/test/java/dev/thiagogonzaga/thrillhousebot/review/";

  /** The two entries the #1141 summary listed at 12:01:59Z (summary comment 6210493250). */
  private static final String CHECK_RUN_GAP =
      "Linked issue #952: The check-run title reports the open total (new + still open), with the"
          + " new count separate — no changed file touches check-run title construction, and the"
          + " description does not claim it here.";

  private static final String IAC_GAP =
      "Linked issue #952: The IaC scan drops a model finding on the same line and same rule — the"
          + " description instead adds a suggestion-based merge (removesDetectedLine); the change"
          + " matches the description but is broader or different than this criterion as written.";

  /** #1141's body, verbatim. */
  private static String pr1141Body() throws IOException {
    return Files.readString(Path.of("src/test/resources/basecode/pr1141-body.md"));
  }

  /** A file of {@code size} lines, each {@code "// line N"}, with {@code named} lines replaced. */
  private static List<String> file(int size, Map<Integer, String> named) {
    var lines = new ArrayList<String>();
    IntStream.rangeClosed(1, size).forEach(i -> lines.add(named.getOrDefault(i, "// line " + i)));
    return lines;
  }

  /** A patch adding one line at {@code line}, a modified file's own change. */
  private static GitHubPullRequestClient.FileDiff modified(String path, int line) {
    return new GitHubPullRequestClient.FileDiff(
        path, "modified", 1, 0, 1, "@@ -" + line + ",0 +" + line + ",1 @@\n+  // new");
  }

  /** The repository at #1141's head, as far as the description cites it. */
  private static final class Repository {
    final Set<String> paths = new HashSet<>();
    final Map<String, List<String>> contents = new HashMap<>();
    final List<String> read = new ArrayList<>();
    boolean truncated;

    Repository() {
      add(
          REVIEW + "SecurityScan.java",
          file(
              1700,
              Map.of(
                  1098,
                  "  private static boolean duplicatesDetection(",
                  1614,
                  "  private static boolean nearbyIacDefect(")));
      add(
          REVIEW + "FindingDeduplicator.java",
          file(60, Map.of(30, "  static final int LINE_TOLERANCE = 3;")));
      add(
          TESTS + "SecurityScanTest.java",
          file(
              900,
              Map.of(
                  400,
                      "  void eachIacRuleCollapsesAModelFindingNamingItsDefectNearby(String rule) {",
                  410, "  void theModelsRootFindingOnTheLineBelowCollapsesIntoTheScans() {")));
      add(
          REVIEW + "VerdictBuilder.java",
          file(
              900,
              Map.of(
                  100,
                  "newFindingsSummaryX XnewFindingsSummary newFindingsSummary_ newFindingsSummary$"
                      + " newFindingsSummary",
                  372,
                  "  static String checkTitleForResult(",
                  458,
                  "  static String newFindingsSummary(")));
      add(
          TESTS + "VerdictBuilderTest.java",
          file(
              500,
              Map.of(
                  200,
                  "  void checkRunCountsLineLeadsWithTheOpenTotalWhenEarlierFindingsAreStillOpen() {")));
      paths.add(REVIEW + "SecretScanner.java");
      paths.add("Makefile");
    }

    void add(String path, List<String> lines) {
      paths.add(path);
      contents.put(path, lines);
    }

    BaseCodeCitations citations(String description, List<GitHubPullRequestClient.FileDiff> files) {
      return BaseCodeCitations.of(
          description,
          files,
          () -> Optional.of(new CitedLocationResolver.RepositoryPaths(paths, truncated)),
          path -> {
            read.add(path);
            return Optional.ofNullable(contents.get(path));
          });
    }
  }

  /** #1141's changed files that the description cites: both modified away from the cited lines. */
  private static List<GitHubPullRequestClient.FileDiff> pr1141Files() {
    return List.of(
        modified(REVIEW + "SecurityScan.java", 1200),
        modified(TESTS + "SecurityScanTest.java", 880),
        modified(REVIEW + "SecretScanner.java", 40));
  }

  /**
   * #1148, built from #1141: both entries are criteria shipped in 0.7.1. The description's AC1 and
   * AC2 bullets state them, under a paragraph saying they shipped, and cite the methods and tests
   * that meet them; every cited file and test is on the base, so both move to "met by existing
   * code" with what they cite.
   */
  @Test
  void the1141EntriesAreMetByTheBaseCodeTheDescriptionCites() throws IOException {
    var repository = new Repository();

    var split =
        repository
            .citations(pr1141Body(), pr1141Files())
            .split(List.of(CHECK_RUN_GAP, IAC_GAP, "The description says X, the code does Y."));

    assertEquals(List.of("The description says X, the code does Y."), split.unmet());
    assertEquals(2, split.met().size());
    var checkRun = split.met().get(0);
    assertEquals("#952", checkRun.issues());
    assertEquals(
        "The check-run title reports the open total (new + still open), with the new count"
            + " separate",
        checkRun.criterion());
    assertEquals(
        List.of(
            "VerdictBuilder.java:458",
            "VerdictBuilder.java:372",
            "VerdictBuilder.newFindingsSummary",
            "VerdictBuilderTest.checkRunCountsLineLeadsWithTheOpenTotalWhenEarlierFindingsAreStillOpen"),
        checkRun.citations());
    assertEquals(
        List.of(
            "SecurityScan.java:1098",
            "SecurityScan.java:1614",
            "SecurityScan.duplicatesDetection",
            "FindingDeduplicator.LINE_TOLERANCE",
            "SecurityScanTest.eachIacRuleCollapsesAModelFindingNamingItsDefectNearby"),
        split.met().get(1).citations());
    assertEquals(
        List.of(
            REVIEW + "VerdictBuilder.java",
            TESTS + "VerdictBuilderTest.java",
            REVIEW + "SecurityScan.java",
            REVIEW + "FindingDeduplicator.java",
            TESTS + "SecurityScanTest.java"),
        repository.read,
        "each cited file is read once, however often it is cited");
  }

  /**
   * A description that says a criterion is met but cites a file or test the base does not hold
   * keeps the gap: a test missing from the repository, a member named only on a line this pull
   * request adds, a cited line it adds, a cited file it adds, or a changed file with no patch.
   */
  @Test
  void aClaimCitingAMissingFileOrTestKeepsTheGap() throws IOException {
    var body = pr1141Body();
    var missingTest = new Repository();
    missingTest.paths.remove(TESTS + "VerdictBuilderTest.java");
    assertEquals(
        List.of(CHECK_RUN_GAP),
        missingTest.citations(body, pr1141Files()).split(List.of(CHECK_RUN_GAP)).unmet());

    var renamedTest = new Repository();
    renamedTest.contents.put(TESTS + "VerdictBuilderTest.java", file(500, Map.of()));
    assertEquals(
        List.of(CHECK_RUN_GAP),
        renamedTest.citations(body, pr1141Files()).split(List.of(CHECK_RUN_GAP)).unmet(),
        "the test is not in its file");

    var addedTest = new Repository();
    var files = new ArrayList<>(pr1141Files());
    files.add(modified(TESTS + "VerdictBuilderTest.java", 200));
    assertEquals(
        List.of(CHECK_RUN_GAP),
        addedTest.citations(body, files).split(List.of(CHECK_RUN_GAP)).unmet(),
        "the test is on a line this pull request adds");

    var addedLine = new ArrayList<>(pr1141Files());
    addedLine.add(modified(REVIEW + "VerdictBuilder.java", 458));
    assertEquals(
        List.of(CHECK_RUN_GAP),
        new Repository().citations(body, addedLine).split(List.of(CHECK_RUN_GAP)).unmet(),
        "the cited line is one this pull request adds");

    var addedFile = new ArrayList<>(pr1141Files());
    addedFile.add(
        new GitHubPullRequestClient.FileDiff(
            REVIEW + "VerdictBuilder.java", "added", 900, 0, 900, "@@ -0,0 +1,1 @@\n+x"));
    assertEquals(
        List.of(CHECK_RUN_GAP),
        new Repository().citations(body, addedFile).split(List.of(CHECK_RUN_GAP)).unmet(),
        "the cited file is new in this pull request");

    var patchless = new ArrayList<>(pr1141Files());
    patchless.add(
        new GitHubPullRequestClient.FileDiff(
            REVIEW + "VerdictBuilder.java", "modified", 900, 0, 900, null));
    assertEquals(
        List.of(CHECK_RUN_GAP),
        new Repository().citations(body, patchless).split(List.of(CHECK_RUN_GAP)).unmet(),
        "which lines are new is not known");
  }

  /** What cannot be read never releases a gap: no listing, a truncated one, an unreadable file. */
  @Test
  void whatCannotBeReadKeepsTheGap() throws IOException {
    var body = pr1141Body();
    var unlisted =
        BaseCodeCitations.of(body, pr1141Files(), Optional::empty, path -> Optional.empty());
    assertEquals(List.of(IAC_GAP), unlisted.split(List.of(IAC_GAP)).unmet());

    var truncated = new Repository();
    truncated.truncated = true;
    truncated.paths.remove(REVIEW + "FindingDeduplicator.java");
    assertEquals(
        List.of(IAC_GAP), truncated.citations(body, pr1141Files()).split(List.of(IAC_GAP)).unmet());

    var unreadable = new Repository();
    unreadable.contents.remove(REVIEW + "SecurityScan.java");
    assertEquals(
        List.of(IAC_GAP),
        unreadable.citations(body, pr1141Files()).split(List.of(IAC_GAP)).unmet());

    var ambiguous = new Repository();
    ambiguous.paths.add("legacy/SecurityScan.java");
    assertEquals(
        List.of(IAC_GAP),
        ambiguous.citations(body, pr1141Files()).split(List.of(IAC_GAP)).unmet(),
        "two files of the cited name");
  }

  /**
   * #1177: a cited line must be in the file. A line past its end, or a file listed but not read,
   * cites nothing on the base and keeps the gap; the last line of a file is still cited.
   */
  @Test
  void aCitedLineTheFileDoesNotHoldKeepsTheGap() {
    var gaps = List.of(CHECK_RUN_GAP);
    var oneLine = new Repository();
    oneLine.contents.put(REVIEW + "VerdictBuilder.java", List.of("// one line only"));
    assertEquals(
        gaps,
        oneLine
            .citations(
                "The check-run title already reports the open total with the new count"
                    + " separate: VerdictBuilder.java:999999999.",
                List.of())
            .split(gaps)
            .unmet(),
        "the #1177 reproduction");
    assertEquals(List.of(REVIEW + "VerdictBuilder.java"), oneLine.read);

    var repository = new Repository();
    assertEquals(
        gaps,
        repository.citations(shipped("VerdictBuilder.java:901"), List.of()).split(gaps).unmet(),
        "one line past the end of a 900-line file");
    assertEquals(
        List.of("VerdictBuilder.java:900"),
        repository
            .citations(shipped("VerdictBuilder.java:900"), List.of())
            .split(gaps)
            .met()
            .getFirst()
            .citations(),
        "the last line");

    var unreadable = new Repository();
    unreadable.contents.remove(REVIEW + "VerdictBuilder.java");
    assertEquals(
        gaps,
        unreadable.citations(shipped("VerdictBuilder.java:372"), List.of()).split(gaps).unmet(),
        "a listed file that cannot be read");
    assertEquals(
        gaps,
        unreadable
            .citations(shipped("`VerdictBuilder.newFindingsSummary`"), List.of())
            .split(gaps)
            .unmet(),
        "a member of a listed file that cannot be read");

    var changed = new Repository();
    assertEquals(
        gaps,
        changed
            .citations(
                shipped("VerdictBuilder.java:901"),
                List.of(modified(REVIEW + "VerdictBuilder.java", 40)))
            .split(gaps)
            .unmet(),
        "past the end of a file the pull request changes");
  }

  /**
   * An unmet criterion with no valid citation stays listed: a passage that says it is met but cites
   * nothing, a citation without a claim that the criterion is already met, a negated claim, a
   * description that does not state the criterion, and a criterion line pointing at a finding.
   */
  @Test
  void anUnmetCriterionWithNoCitationKeepsTheGap() {
    var repository = new Repository();
    var gaps = List.of(CHECK_RUN_GAP);

    assertEquals(
        gaps,
        repository
            .citations(
                "The check run already reports the open total with the new count separate.",
                List.of())
            .split(gaps)
            .unmet(),
        "no citation");
    assertEquals(
        gaps,
        repository
            .citations(
                "The check run reports the open total with the new count separate:"
                    + " `VerdictBuilder.newFindingsSummary` (VerdictBuilder.java:458).",
                List.of())
            .split(gaps)
            .unmet(),
        "no claim the criterion was met before this change");
    assertEquals(
        gaps,
        repository
            .citations(
                "The check run does not already report the open total with the new count"
                    + " separate: `VerdictBuilder.newFindingsSummary` (VerdictBuilder.java:458).",
                List.of())
            .split(gaps)
            .unmet(),
        "a negated claim");
    assertEquals(
        gaps,
        repository
            .citations(
                "Already shipped: `VerdictBuilder.newFindingsSummary` (VerdictBuilder.java:458).",
                List.of())
            .split(gaps)
            .unmet(),
        "the passage does not state the criterion");

    var pointing =
        "Linked issue #952: The check-run title reports the open total"
            + LinkedIssueCriteria.FINDING_SEPARATOR
            + "Counts line";
    assertEquals(
        List.of(pointing),
        repository
            .citations(
                "The check run reports the open total with the new count separate, already met"
                    + " by `VerdictBuilder.newFindingsSummary` (VerdictBuilder.java:458).",
                List.of())
            .split(List.of(pointing))
            .unmet(),
        "a line pointing at a finding");
  }

  /** A passage introduced by a heading is not led by the paragraph above the heading. */
  @Test
  void aHeadingEndsTheListAParagraphIntroduced() {
    var description =
        """
        Criteria 1 and 2 shipped in 0.7.1.

        ## Changes

        * The check run reports the open total with the new count separate:
          `VerdictBuilder.newFindingsSummary`.
        """;

    assertEquals(
        List.of(CHECK_RUN_GAP),
        new Repository().citations(description, List.of()).split(List.of(CHECK_RUN_GAP)).unmet());

    var led =
        """
        Criteria 1 and 2 shipped in 0.7.1:
        1. The check run reports the open total with the new count separate:
           `VerdictBuilder.newFindingsSummary`.
        """;
    assertEquals(
        1,
        new Repository().citations(led, List.of()).split(List.of(CHECK_RUN_GAP)).met().size(),
        "a list continued over two lines, led by its paragraph");
  }

  /**
   * The summary lists the #1141 entries under "met by existing code" with their citations, and
   * Description vs. Implementation is gone; the next round carries nothing from that section.
   */
  @Test
  void theSummaryListsTheCriteriaAsMetAndTheNextRoundCarriesNone() throws IOException {
    var rendered =
        summary(
            new Repository().citations(pr1141Body(), pr1141Files()),
            List.of(CHECK_RUN_GAP, IAC_GAP.replace("Linked issue #952:", "Linked issue:")));

    assertFalse(rendered.contains(PrSummaryGenerator.GAPS_HEADING), rendered);
    assertTrue(rendered.contains(PrSummaryGenerator.MET_HEADING), rendered);
    assertTrue(
        rendered.contains("- Linked issue #952: The check-run title reports the open total"),
        rendered);
    assertTrue(
        rendered.contains(
            "- Linked issue: The IaC scan drops a model finding on the same line and same"
                + " rule — cited: `SecurityScan.java:1098`, `SecurityScan.java:1614`,"
                + " `SecurityScan.duplicatesDetection`, `FindingDeduplicator.LINE_TOLERANCE`,"
                + " `SecurityScanTest.eachIacRuleCollapsesAModelFindingNamingItsDefectNearby`"),
        rendered);
    assertEquals(List.of(), carried(rendered));
  }

  /**
   * #1177: a criterion whose citation is past the end of its file stays under Description vs.
   * Implementation, and the next round carries it.
   */
  @Test
  void aCriterionCitingALinePastTheEndIsListedAndCarried() {
    var rendered =
        summary(
            new Repository().citations(shipped("VerdictBuilder.java:999999999"), List.of()),
            List.of(CHECK_RUN_GAP));

    assertTrue(rendered.contains(PrSummaryGenerator.GAPS_HEADING), rendered);
    assertFalse(rendered.contains(PrSummaryGenerator.MET_HEADING), rendered);
    assertEquals(List.of(CHECK_RUN_GAP), carried(rendered));
  }

  /** The summary of an empty review whose summary call listed {@code gaps}. */
  private static String summary(BaseCodeCitations citations, List<String> gaps) {
    var sources = new PrSummaryGenerator.DescriptionSources("", Set.of(), "", citations);
    var empty =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            RiskLevel.LOW,
            ReviewState.APPROVE,
            false,
            "",
            List.of(),
            List.of(),
            0);

    return new PrSummaryGenerator(false)
        .generate(
            8,
            374,
            31,
            List.of(),
            new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", "Fixes #952.", gaps),
            empty,
            sources);
  }

  /** The gaps the next round carries from a summary comment holding {@code rendered}. */
  private static List<String> carried(String rendered) {
    var conversation =
        List.of(
            new IssueComment(
                PrSummaryGenerator.SUMMARY_MARKER + "\n" + rendered,
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    return DescriptionGapCarryover.previousGaps(
        conversation, BotIdentity.of("thrillhousebot[bot]"));
  }

  /** The criterion line #1148 is about, as a passage led by a claim that it shipped. */
  private static String shipped(String citations) {
    return "Criteria 1 and 2 shipped in 0.7.1:\n- The check run reports the open total with the new"
        + " count separate: "
        + citations
        + ".";
  }

  /**
   * A cited path may be the whole repository path or end in its directories, and a member counts
   * only where it stands as a whole identifier.
   */
  @Test
  void citationsResolveByWholeOrPartialPathAndWholeIdentifier() {
    var met =
        new Repository()
            .citations(
                shipped(
                    REVIEW
                        + "VerdictBuilder.java:458, review/VerdictBuilder.java:372 and"
                        + " `VerdictBuilder.newFindingsSummary`"),
                List.of())
            .split(List.of(CHECK_RUN_GAP))
            .met();

    assertEquals(
        List.of(
            REVIEW + "VerdictBuilder.java:458",
            "review/VerdictBuilder.java:372",
            "VerdictBuilder.newFindingsSummary"),
        met.getFirst().citations());
  }

  /**
   * More is kept: a passage sharing too few of the criterion's words, a cited line 0, and a member
   * of a changed file whose patch is missing or empty.
   */
  @Test
  void aWeakMatchABadLineOrAnUnknownPatchKeepsTheGap() {
    var gaps = List.of(CHECK_RUN_GAP);
    var repository = new Repository();
    assertEquals(
        gaps,
        repository
            .citations(
                "The check run already shipped: `VerdictBuilder.newFindingsSummary`.", List.of())
            .split(gaps)
            .unmet());
    assertEquals(
        gaps,
        repository.citations(shipped("VerdictBuilder.java:0"), List.of()).split(gaps).unmet());
    assertEquals(
        gaps,
        repository
            .citations(shipped("other/VerdictBuilder.java:372"), List.of())
            .split(gaps)
            .unmet(),
        "no file under the cited directory");
    for (var patch : new String[] {null, " "}) {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  REVIEW + "VerdictBuilder.java", "modified", 1, 0, 1, patch));
      assertEquals(
          gaps,
          repository
              .citations(shipped("`VerdictBuilder.newFindingsSummary`"), files)
              .split(gaps)
              .unmet());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "VerdictBuilder.java, true",
    "src/review/VerdictBuilder.java, true",
    "0.7.1, true",
    "Makefile, false",
    ".env, false",
    "trailing., false",
    "name.toolongextension, false",
    "name.j-s, false"
  })
  void aPathTokenNamesAFileByItsExtension(String token, boolean file) {
    assertEquals(file, BaseCodeCitations.namesAFile(token));
  }

  @ParameterizedTest
  @CsvSource({
    "VerdictBuilder.java:458, VerdictBuilder.java, 458",
    "src/a.java:12:5, src/a.java, 12",
    "a.java:12., a.java, 12",
    "https://x.io/a.md, , 0",
    "a.java:, , 0",
    "a.java:1234567890, , 0",
    "a.java:12-14, , 0",
    ":12, , 0"
  })
  void aTokenIsReadAsPathAndLine(String token, String path, int line) {
    var cited = BaseCodeCitations.pathLine(token);
    assertEquals(path == null ? null : new BaseCodeCitations.PathLine(path, line), cited, token);
  }

  /** A path token that is no file name, or a line number too long to be one, cites nothing. */
  @Test
  void aTokenThatIsNoFileOrNoLineIsNotACitation() {
    var met =
        new Repository()
            .citations(
                shipped(
                    "`VerdictBuilder.newFindingsSummary` (see docs/: 4, Makefile:12,"
                        + " VerdictBuilder.java:12345678901)"),
                List.of())
            .split(List.of(CHECK_RUN_GAP))
            .met();

    assertEquals(List.of("VerdictBuilder.newFindingsSummary"), met.getFirst().citations());
  }

  /** The criterion stated with {@code claim} before its citation. */
  private static String claimed(String claim) {
    return "The check run reports the open total with the new count separate, which "
        + claim
        + ": `VerdictBuilder.newFindingsSummary` (VerdictBuilder.java:458).";
  }

  /**
   * A negation anywhere in the clause before the claim withdraws it, not only one right before the
   * claim's word; a negation after the claim, in the same sentence, does not.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "is not, as far as I can tell, already met",
        "is not yet shipped",
        "was never shipped before this PR",
        "isn't really already met",
        "no earlier change has already met",
        "cannot be said to have already shipped"
      })
  void aNegationInTheClauseWithdrawsTheClaim(String claim) {
    assertEquals(
        List.of(CHECK_RUN_GAP),
        new Repository().citations(claimed(claim), List.of()).split(List.of(CHECK_RUN_GAP)).unmet(),
        claim);
  }

  @Test
  void aNegationAfterTheClaimOrInAnEarlierSentenceLeavesIt() {
    var repository = new Repository();
    assertEquals(
        1,
        repository
            .citations(claimed("already shipped, and this PR does not change it"), List.of())
            .split(List.of(CHECK_RUN_GAP))
            .met()
            .size());
    assertEquals(
        1,
        repository
            .citations("Nothing here is new. " + claimed("already shipped"), List.of())
            .split(List.of(CHECK_RUN_GAP))
            .met()
            .size());
  }

  /**
   * Text that is not the author's own prose is never a claim: a fenced code block, a blockquote and
   * an HTML comment, whether they hold the passage itself or the paragraph that leads its list.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        ```
        The check run already reports the open total with the new count separate: \
        `VerdictBuilder.newFindingsSummary`.
        ```""",
        """
        ~~~text
        Criteria 1 and 2 shipped in 0.7.1:
        ~~~
        - The check run reports the open total with the new count separate: \
        `VerdictBuilder.newFindingsSummary`.""",
        "> The check run already reports the open total with the new count separate:"
            + " `VerdictBuilder.newFindingsSummary`.",
        """
        > Criteria 1 and 2 shipped in 0.7.1:
        - The check run reports the open total with the new count separate: \
        `VerdictBuilder.newFindingsSummary`.""",
        "<!-- The check run already reports the open total with the new count separate:"
            + " `VerdictBuilder.newFindingsSummary`. -->",
        """
        <!--
        Criteria 1 and 2 shipped in 0.7.1:
        -->
        - The check run reports the open total with the new count separate: \
        `VerdictBuilder.newFindingsSummary`.""",
        "<!-- The check run already reports the open total with the new count separate:"
            + " `VerdictBuilder.newFindingsSummary`."
      })
  void quotedFencedOrCommentedTextIsNoClaim(String description) {
    assertEquals(
        List.of(CHECK_RUN_GAP),
        new Repository().citations(description, List.of()).split(List.of(CHECK_RUN_GAP)).unmet(),
        description);
  }

  @Test
  void noDescriptionOrNoGapReadsNothing() {
    assertSame(BaseCodeCitations.NONE, BaseCodeCitations.of(null, List.of(), null, null));
    assertSame(BaseCodeCitations.NONE, BaseCodeCitations.of(" ", List.of(), null, null));
    var gaps = List.of(CHECK_RUN_GAP);
    assertSame(gaps, BaseCodeCitations.NONE.split(gaps).unmet());
    var repository = new Repository();
    assertEquals(
        List.of(), repository.citations("Already met.", List.of()).split(List.of()).unmet());
    assertEquals(List.of(), repository.read, "nothing is read for no gap");
  }
}
