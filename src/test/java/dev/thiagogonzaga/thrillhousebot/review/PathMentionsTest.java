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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link PathMentions}: paths the model names must name files that exist (#1076). */
class PathMentionsTest {

  /** The react pull request (ThrillhouseBot-test#249): a dashboard and the README moved. */
  private static final List<FileDiff> REACT =
      List.of(
          new FileDiff("react/src/loans/paging.ts", "added", 20, 0, 20, "+export function pageOf"),
          new FileDiff("docs/REACT-V17.md", "renamed", 0, 0, 0, null, "README.md"));

  private static final Set<String> REACT_TREE =
      Set.of("react/src/loans/paging.ts", "docs/REACT-V17.md", "react/package.json");

  /** The angular pull request (#251): the docs live under angular/docs. */
  private static final List<FileDiff> ANGULAR =
      List.of(
          new FileDiff("angular/docs/CONFIG-ANGULAR.md", "added", 9, 0, 9, "+# Configuration"),
          new FileDiff("angular/src/app/app-config.ts", "added", 30, 0, 30, "+export const x"));

  /** The zig pull request (#250). */
  private static final List<FileDiff> ZIG =
      List.of(
          new FileDiff("docs/CONFIG-ZIG.md", "added", 9, 0, 9, "+# Configuration"),
          new FileDiff("docs/ZIG-V17.md", "renamed", 0, 0, 0, null, "README.md"),
          new FileDiff("zig/src/barcode.zig", "added", 30, 0, 30, "+pub fn isValid"),
          new FileDiff("zig/src/manifest.zig", "added", 30, 0, 30, "+pub fn write"),
          new FileDiff("zig/src/lims.zig", "added", 30, 0, 30, "+pub fn pendingOrders"));

  private static PathMentions mentions(List<FileDiff> files, String known, Set<String> tree) {
    return PathMentions.of(
        files, known, () -> Optional.of(new CitedLocationResolver.RepositoryPaths(tree, false)));
  }

  private static ReviewResponse.Finding finding(String description) {
    return new ReviewResponse.Finding(
        "low",
        "medium",
        "angular/src/app/app-config.ts",
        28,
        "Setting is never read",
        description,
        null,
        null);
  }

  private static ReviewResponse.Summary summary(
      String purpose, List<String> gaps, List<ReviewResponse.FileSummary> files) {
    return new ReviewResponse.Summary(
        0, 0, 0, 0, 0, "Comment.", purpose, gaps, List.of(), files, null, List.of());
  }

  /** react #249: the purpose sentence naming a README path that does not exist is cut. */
  @Test
  void aSentenceNamingAnInventedPathIsCut() {
    var purpose =
        "Adds a React dashboard under react/ showing open loans. It also moves the repository"
            + " README to docs/REACT-VIV.md as a pure rename withheld from review. The change spans"
            + " the dashboard front end.";

    var screened = mentions(REACT, "", REACT_TREE).screen(summary(purpose, List.of(), List.of()));

    assertEquals(
        "Adds a React dashboard under react/ showing open loans. The change spans the dashboard"
            + " front end.",
        screened.prPurpose());
  }

  /** A path in markdown emphasis is judged like a bare one. */
  @Test
  void anEmphasizedInventedPathIsCut() {
    var mentions = mentions(REACT, "", REACT_TREE);

    assertEquals(
        "Adds a dashboard.",
        mentions.withoutUnresolvedSentences(
            "Adds a dashboard. It moves the README to *docs/REACT-VIV.md*. It renames"
                + " **docs/REACT-VIV.md** for clarity."));
  }

  /** The real path, and the rename's source, are kept. */
  @Test
  void theRenamedPathAndItsSourceAreKept() {
    var purpose = "It moves README.md to docs/REACT-V17.md, withheld from review.";
    var mentions = mentions(REACT, "", REACT_TREE);

    assertSame(purpose, mentions.withoutUnresolvedSentences(purpose));
    var otherCase = "It moves readme.md to Docs/react-v17.md and edits REACT/SRC/LOANS/.";
    assertSame(otherCase, mentions.withoutUnresolvedSentences(otherCase));
  }

  /**
   * angular #251: a finding citing angular/src/docs/CONFIG-ANGULAR.md is mapped onto the changed
   * angular/docs/CONFIG-ANGULAR.md, with its line kept; the finding and its other fields stay.
   */
  @Test
  void aNearMissIsMappedOntoTheOneChangedFileItMeans() {
    var raised =
        finding(
            "The new config parses the key but nothing reads it."
                + " angular/src/docs/CONFIG-ANGULAR.md documents the setting, and"
                + " angular/src/docs/CONFIG-ANGULAR.md:9 names its default.");
    var response = new ReviewResponse(List.of(raised), List.of(), null);

    var screened = mentions(ANGULAR, "", Set.of()).screen(response);

    var description = screened.findings().getFirst().description();
    assertEquals(
        "The new config parses the key but nothing reads it. angular/docs/CONFIG-ANGULAR.md"
            + " documents the setting, and angular/docs/CONFIG-ANGULAR.md:9 names its default.",
        description);
    assertEquals(raised.title(), screened.findings().getFirst().title());
    assertEquals(raised.file(), screened.findings().getFirst().file());
  }

  /**
   * zig #250: the bare file names and the docs path all resolve, so the purpose stays as written.
   * "a settings page" names no path; no path rule reaches it.
   */
  @Test
  void aSummaryNamingRealFilesStays() {
    var purpose =
        "Barcodes are validated in barcode.zig, and /export writes a CSV manifest via"
            + " manifest.zig. It also adds a LIMS fetcher (lims.zig) and two docs files"
            + " (CONFIG-ZIG.md and a settings page), with the README renamed to docs/ZIG-V17.md.";
    var tree = Set.of("docs/CONFIG-ZIG.md", "docs/ZIG-V17.md", "zig/src/barcode.zig");
    var input = summary(purpose, List.of(), List.of());

    assertSame(input, mentions(ZIG, "", tree).screen(input));
  }

  /** What is not a path is never judged, even against a repository that holds none of it. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "See https://github.com/org/repo/blob/main/docs/GUIDE.md for details.",
        "It visits www.example.com/docs/index.html first.",
        "The class com.example.billing.Invoice is renamed.",
        "The image node:24-alpine is used.",
        "It pulls ghcr.io/org/app:1.2 at build time.",
        "It clones github.com/org/tools/scripts on start.",
        "Every src/**/*.ts file is linted.",
        "It reads every *config.yml and src/*.ts and *.md file.",
        "It keeps the *docs/NOPE.md*s list and x*docs/NOPE.md* too.",
        "It globs src/*docs/NOPE.md* paths.",
        "It globs docs/{a,b}/index.md.",
        "It imports @angular/core/testing helpers.",
        "POST /triage/run starts a run, GET /applications/:id/attachments serves files.",
        "It handles read/write and and/or paths in CI/CD.",
        "It runs on Node.js and Vue.js.",
        "It renders with React.js.",
        "Version 1.2 ships in v3.4.",
      })
  void nonPathsAreLeftAlone(String text) {
    var mentions = mentions(REACT, "", Set.of());

    assertEquals(text, mentions.withoutUnresolvedSentences(text));
    assertEquals(text, mentions.withoutUnresolvedMentions(text));
  }

  @Test
  void theShapeOfAPath() {
    assertTrue(PathMentions.pathShaped("docs/REACT-VIV.md"));
    assertTrue(PathMentions.pathShaped("barcode.zig"));
    assertTrue(PathMentions.pathShaped("react/src/loans"));
    assertTrue(PathMentions.pathShaped("node/"));
    assertTrue(PathMentions.pathShaped(".github/workflows/ci.yml"));
    assertTrue(PathMentions.pathShaped("server.js"));
    assertFalse(PathMentions.pathShaped("Node.js"));
    assertFalse(PathMentions.pathShaped("and/or"));
    assertFalse(PathMentions.pathShaped("com.example.x"));
    assertFalse(PathMentions.pathShaped("github.com/org/repo"));
    assertFalse(PathMentions.pathShaped("1.md"));
    assertFalse(PathMentions.pathShaped(".md"));
  }

  /**
   * A path missing on purpose is quoted from the review's own material: a description promising a
   * file no change adds, and a build step naming a source the build cannot find.
   */
  @Test
  void aPathQuotedFromThePullRequestOrAPatchIsKept() {
    var files =
        List.of(
            new FileDiff(
                "angular/Dockerfile",
                "added",
                3,
                0,
                3,
                "+COPY nginx/nginx.conf /etc/nginx/nginx.conf"));
    var mentions = mentions(files, "The description: adds docs/FAQ.md.", Set.of());
    var gap = "The description promises docs/FAQ.md, which no changed file adds.";
    var copy = finding("COPY names nginx/nginx.conf, which is not in the build context.");

    var screened = mentions.screen(summary("Adds a container.", List.of(gap), List.of()));
    var response = mentions.screen(new ReviewResponse(List.of(copy), List.of(), null));

    assertEquals(List.of(gap), screened.descriptionGaps());
    assertSame(copy, response.findings().getFirst());
  }

  /** A path the repository holds, though the change does not touch it, is kept. */
  @Test
  void aRepositoryFileIsKept() {
    var mentions = mentions(REACT, "", Set.of("react/config/defaults.yml", "react/src/api/a.ts"));
    var text =
        "It reads react/config/defaults.yml and defaults.yml, and calls into react/src/api/.";

    assertSame(text, mentions.withoutUnresolvedSentences(text));
    assertEquals(
        "Kept.", mentions.withoutUnresolvedSentences("Kept. It reads settings-page.md first."));
    assertEquals("Kept.", mentions.withoutUnresolvedSentences("Kept. It reads react/src/nope/."));
  }

  /** A repository that could not be listed, or was listed in part, leaves every mention alone. */
  @Test
  void anUnknownOrTruncatedRepositoryLeavesTheTextAlone() {
    var text = "It moves the README to docs/REACT-VIV.md.";
    var truncated =
        PathMentions.of(
            REACT,
            "",
            () -> Optional.of(new CitedLocationResolver.RepositoryPaths(Set.of(), true)));

    assertSame(text, PathMentions.of(REACT, "", Optional::empty).withoutUnresolvedSentences(text));
    assertSame(text, truncated.withoutUnresolvedSentences(text));
    assertSame(text, PathMentions.NONE.withoutUnresolvedSentences(text));
  }

  /** The repository is listed only when a mention gets past the cheaper checks, and once. */
  @Test
  void theRepositoryIsListedOnlyWhenNeeded() {
    var listings = new AtomicInteger();
    var mentions =
        PathMentions.of(
            REACT,
            "",
            () -> {
              listings.incrementAndGet();
              return Optional.of(new CitedLocationResolver.RepositoryPaths(REACT_TREE, false));
            });

    mentions.withoutUnresolvedSentences("It edits react/src/loans/paging.ts.");
    assertEquals(0, listings.get());
    mentions.withoutUnresolvedSentences("It edits docs/A.md. It edits docs/B.md.");
    assertEquals(1, listings.get());
  }

  /**
   * In a finding, an invented path leaves its description: with the parenthetical holding only it,
   * or with its code span. The finding itself stays, title and location unchanged.
   */
  @Test
  void anInventedPathLeavesAFindingsDescriptionButNotTheFinding() {
    var mentions = mentions(ANGULAR, "", Set.of("angular/docs/CONFIG-ANGULAR.md"));
    var findings =
        List.of(
            finding("The key is documented (`angular/docs/HOLD.md:4`) as a hold timer."),
            finding("The key is documented in `angular/docs/HOLD.md`, and nothing reads it."),
            finding("See angular/docs/HOLD.md for the claim."),
            finding("angular/docs/HOLD.md"));

    var screened = mentions.screen(new ReviewResponse(findings, List.of(), null));

    assertEquals(4, screened.findings().size());
    assertEquals(
        List.of(
            "The key is documented as a hold timer.",
            "The key is documented in, and nothing reads it.",
            "See for the claim.",
            "angular/docs/HOLD.md"),
        screened.findings().stream().map(ReviewResponse.Finding::description).toList());
    assertEquals(findings.getFirst().title(), screened.findings().getFirst().title());
    var untouched = new ReviewResponse(List.of(finding("Nothing to check.")), List.of(), null);
    assertSame(untouched, mentions.screen(untouched));
  }

  /** A fenced block is code: a path in it is left as written. */
  @Test
  void aFencedBlockIsLeftAlone() {
    var mentions = mentions(ANGULAR, "", Set.of());
    var text = "The build fails:\n```\nCOPY build/missing.conf /etc/\n```\nunfenced build/x.conf";

    assertEquals(
        "The build fails:\n```\nCOPY build/missing.conf /etc/\n```\nunfenced",
        mentions.withoutUnresolvedMentions(text));
    var unclosed = "Run:\n```\ncat build/missing.conf";
    assertEquals(unclosed, mentions.withoutUnresolvedMentions(unclosed));
    var summary = "It fails. See:\n```\nls. cat build/missing.conf\n```\nAlso build/x.conf. Done.";
    assertEquals(
        "It fails. See:\n```\nls. cat build/missing.conf\n```\nAlso. Done.",
        mentions.withoutUnresolvedSentences(summary),
        "a summary text holding a fence loses mentions, not sentences that split the fence");
  }

  /**
   * Every summary field is screened: a file summary loses the sentence, a description gap left
   * empty goes, and an untouched summary is returned as it was.
   */
  @Test
  void everySummaryFieldIsScreened() {
    var mentions = mentions(REACT, "", REACT_TREE);
    var input =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            "Comment. See docs/NOPE.md.",
            null,
            List.of("The description names docs/NOPE.md.", "The issue asks for paging."),
            List.of(),
            List.of(
                new ReviewResponse.FileSummary(
                    "react/src/loans/paging.ts", "Pages rows. Mirrors src/old/pager.ts."),
                new ReviewResponse.FileSummary("docs/REACT-V17.md", "The moved README.")),
            null,
            List.of());

    var screened = mentions.screen(input);

    assertEquals("Comment.", screened.overallAssessment());
    assertNull(screened.prPurpose());
    assertEquals(List.of("The issue asks for paging."), screened.descriptionGaps());
    assertEquals("Pages rows.", screened.fileSummaries().get(0).summary());
    assertSame(input.fileSummaries().get(1), screened.fileSummaries().get(1));
    assertNull(mentions.screen((ReviewResponse.Summary) null));
    var clean = summary("Adds paging.", List.of(), List.of());
    assertSame(clean, mentions.screen(clean));
  }

  /** A colon that ends a clause after a path does not hide it; a blank text is returned as is. */
  @Test
  void aClauseColonAfterAPathStillLeavesItJudged() {
    var mentions = mentions(REACT, "", REACT_TREE);
    var lines = new ArrayList<String>();
    lines.add(mentions.withoutUnresolvedSentences("Kept. It edits docs/NOPE.md: badly."));
    lines.add(mentions.withoutUnresolvedSentences("  "));
    lines.add(mentions.withoutUnresolvedMentions(null));

    assertEquals("Kept.", lines.get(0));
    assertEquals("  ", lines.get(1));
    assertNull(lines.get(2));
  }

  /**
   * A mention is stripped wherever it stands in a description: first, last, or inside a span or a
   * parenthetical that it does not close; a path and line keep their line.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          docs/NOPE.md is cited as the source. | is cited as the source.
          The source is cited: docs/NOPE.md | The source is cited:
          The source is `docs/NOPE.md and more` here. | The source is `and more` here.
          The source (docs/NOPE.md and more) is cited. | The source (and more) is cited.
          The source is docs/NOPE.md:12. | The source is.
          The source is docs/NOPE.md: | The source is:
          The source (docs/NOPE.md | The source (
          The source (docs/NOPE.md; see the issue) is cited. | The source (; see the issue) is cited.
          See *docs/NOPE.md* now. | See now.
          See **docs/NOPE.md** now. | See now.
          *docs/NOPE.md* is cited. | is cited.
          It is cited: *docs/NOPE.md* | It is cited:
          """)
  void aMentionIsStrippedWhereverItStands(String text, String stripped) {
    assertEquals(stripped, mentions(REACT, null, REACT_TREE).withoutUnresolvedMentions(text));
  }

  /** An image tag after a path-shaped name is not a path, and a blank description stays blank. */
  @Test
  void anImageTagAndABlankDescriptionAreLeftAlone() {
    var mentions = mentions(REACT, "", Set.of());

    assertEquals(
        "It pulls org/tools/app:latest.",
        mentions.withoutUnresolvedMentions("It pulls org/tools/app:latest."));
    assertEquals("  ", mentions.withoutUnresolvedMentions("  "));
    assertFalse(PathMentions.pathShaped("a/b.xyz"));
    assertFalse(PathMentions.pathShaped("v1.2/docs/guide.md"));
  }

  /** Each summary field is judged on its own: one that changes alone is still written back. */
  @Test
  void aSummaryFieldThatChangesAloneIsWrittenBack() {
    var mentions = mentions(REACT, "", REACT_TREE);
    var gapOnly = summary("Adds paging.", List.of("It names docs/NOPE.md."), List.of());
    var fileOnly =
        summary(
            "Adds paging.",
            List.of(),
            List.of(
                new ReviewResponse.FileSummary("react/src/loans/paging.ts", "See docs/NOPE.md.")));

    assertEquals(List.of(), mentions.screen(gapOnly).descriptionGaps());
    assertEquals("", mentions.screen(fileOnly).fileSummaries().getFirst().summary());
  }

  /**
   * A one-slash directory under a changed file's top-level directory is judged ("react/src" stays,
   * "react/nope" goes); one-slash prose is not ("and/or" stays).
   */
  @Test
  void aOneSlashDirectoryUnderAChangedDirectoryIsJudged() {
    var mentions = mentions(REACT, "", REACT_TREE);

    assertEquals(
        "It edits react/src. It handles and/or.",
        mentions.withoutUnresolvedSentences(
            "It edits react/src. It moves react/nope. It handles and/or."));
  }

  /**
   * The walkthrough diagram loses an invented path and gets a near miss mapped, with its syntax
   * otherwise as written; a blank or clean diagram is returned as it was.
   */
  @Test
  void theDiagramLosesAnInventedPathAndKeepsItsSyntax() {
    var mentions = mentions(REACT, "", REACT_TREE);
    var diagram =
        "flowchart TD\n  A[\"README to docs/REACT-VIV.md\"] --> B[\"react/paging.ts rows\"]";
    var input =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            "ok",
            "Adds paging.",
            List.of(),
            List.of(),
            List.of(),
            diagram,
            List.of());

    assertEquals(
        "flowchart TD\n  A[\"README to \"] --> B[\"react/src/loans/paging.ts rows\"]",
        mentions.screen(input).walkthroughDiagram());
    assertEquals("  ", mentions.withoutUnresolvedPaths("  "));
    var clean = "flowchart TD\n  A --> B";
    assertSame(clean, mentions.withoutUnresolvedPaths(clean));
  }

  /** A dotless file name such as Dockerfile is judged, bare or under a directory. */
  @Test
  void aDotlessFileNameIsJudged() {
    var files = List.of(new FileDiff("zig/Dockerfile", "added", 3, 0, 3, "+FROM alpine"));
    var mentions = mentions(files, "", Set.of("zig/Dockerfile"));

    assertTrue(PathMentions.pathShaped("Dockerfile"));
    assertTrue(PathMentions.pathShaped("src/Makefile"));
    assertEquals(
        "It edits the Dockerfile and zig/Dockerfile.",
        mentions.withoutUnresolvedSentences(
            "It edits the Dockerfile and zig/Dockerfile. It adds a Makefile."));
  }

  /** The one-slash directory gate reads any letter case, as every other step does. */
  @Test
  void theOneSlashDirectoryGateReadsAnyCase() {
    var mentions = mentions(REACT, "", REACT_TREE);

    assertEquals(
        "It edits REACT/src.",
        mentions.withoutUnresolvedSentences("It edits REACT/src. It moves React/nope."));
  }

  /** Stripping a mention outside a fence leaves the fence's blanks and punctuation as written. */
  @Test
  void theTidyLeavesAFenceAsWritten() {
    var mentions = mentions(ANGULAR, "", Set.of());
    var text = "The build fails:\n```\ncat  build/x.conf .\n```\nSee  docs/NOPE.md .";

    assertEquals(
        "The build fails:\n```\ncat  build/x.conf .\n```\nSee.",
        mentions.withoutUnresolvedMentions(text));
  }

  @ParameterizedTest
  @ValueSource(strings = {"~~~", "```"})
  void eitherFenceStyleProtectsPathsAndWhitespace(String fence) {
    var mentions = mentions(ANGULAR, "", Set.of());
    var block = fence + "sh\ncat  build/x.conf .\n" + fence;
    assertEquals(block, mentions.withoutUnresolvedMentions(block));
    assertEquals(block, mentions.withoutUnresolvedSentences(block));
    assertEquals(
        block + "\noutside", mentions.withoutUnresolvedMentions(block + "\noutside build/x.conf"));
    assertEquals(
        block + "\noutside", mentions.withoutUnresolvedSentences(block + "\noutside build/x.conf"));
    var unclosed = fence + "sh\ncat  build/x.conf .";
    assertEquals(unclosed, mentions.withoutUnresolvedMentions(unclosed));
  }

  @Test
  void aDifferentFenceMarkerInsideCodeDoesNotEndTheBlock() {
    var mentions = mentions(ANGULAR, "", Set.of());
    var block = "~~~sh\n```\ncat  build/x.conf .\n~~~";
    assertEquals(
        block + "\noutside", mentions.withoutUnresolvedMentions(block + "\noutside build/x.conf"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"````", "~~~~"})
  void shorterMarkersInsideLongerFencesStayByteExact(String fence) {
    var mentions = mentions(REACT, "", REACT_TREE);
    var shorter = fence.substring(0, 3);
    var block = fence + "\n" + shorter + "\ncat  build/x.conf .\n" + fence;
    assertEquals(block, mentions.withoutUnresolvedSentences(block));
    assertEquals(
        block + "\nOutside.",
        mentions.withoutUnresolvedMentions(block + "\nOutside build/missing.conf."));
    var unclosed = fence + "\n" + shorter + "\ncat  build/x.conf .";
    assertEquals(unclosed, mentions.withoutUnresolvedSentences(unclosed));
  }

  @ParameterizedTest
  @ValueSource(strings = {"```", "~~~"})
  void markersInCodeLiteralsDoNotExposePaths(String fence) {
    var mentions = mentions(ANGULAR, "", Set.of());
    var block =
        fence
            + "sh\necho \""
            + fence
            + "\"\n"
            + fence
            + "still-code\ncat  build/x.conf .\n "
            + fence
            + "\t\r\n";
    assertEquals(
        block + "Outside.",
        mentions.withoutUnresolvedMentions(block + "Outside build/missing.conf."));
    assertEquals(block, mentions.withoutUnresolvedSentences(block));
  }

  @ParameterizedTest
  @ValueSource(strings = {"````", "~~~~"})
  void listFencesIgnoreInnerQuotedShorterAndMixedMarkers(String fence) {
    var mentions = mentions(ANGULAR, "", Set.of());
    for (var opener : List.of("- ", "* ", "+ ", "12. ", "1) ", "> - ")) {
      var quote = opener.startsWith(">") ? "> " : "";
      var block =
          opener
              + fence
              + "sh\n"
              + quote
              + "  "
              + fence.substring(0, 3)
              + "\n"
              + quote
              + "  "
              + (fence.startsWith("~") ? "````" : "~~~~")
              + "\n"
              + quote
              + "  > "
              + fence
              + "\n"
              + quote
              + "  cat  build/x.conf .\n"
              + quote
              + "  "
              + fence;
      assertEquals(
          block + "\nOutside.",
          mentions.withoutUnresolvedMentions(block + "\nOutside build/missing.conf."));
      assertEquals(block, mentions.withoutUnresolvedSentences(block));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"```a``` ", "```a``` prose ", "```a```\n"})
  void manyInlineTripleBacktickSpansKeepTheFenceScanBounded(String span) {
    var text = span.repeat(160_000);
    assertTimeout(
        Duration.ofSeconds(2), () -> assertEquals(160_000, MarkdownFences.spans(text).size()));
  }

  private static ReviewResponse.Finding findingOn(
      String file, String description, String suggestionOld, String suggestionNew) {
    return new ReviewResponse.Finding(
        "high",
        "medium",
        file,
        14,
        "Image never builds",
        description,
        suggestionOld,
        suggestionNew);
  }

  /** The c pull request (ThrillhouseBot-test#277): its README links the docs relatively. */
  private static final List<FileDiff> C =
      List.of(
          new FileDiff("c/docs/CONFIG-C.md", "added", 9, 0, 9, "+# Configuration"),
          new FileDiff("c/src/config.c", "added", 40, 0, 40, "+int parse_sites(void)"),
          new FileDiff("c/README.md", "modified", 1, 0, 1, "+See docs/CONFIG-C.md for settings."));

  /**
   * #1094, c #277 and node #278: a path missing its language folder is mapped onto the one changed
   * file that ends with it, even though a patch quotes the short form.
   */
  @Test
  void aSuffixOnlyPathIsMappedOntoTheOneChangedFileEndingWithIt() {
    var node =
        List.of(
            new FileDiff("node/src/config.ts", "added", 30, 0, 30, "+export function load()"),
            new FileDiff(
                "node/README.md", "modified", 1, 0, 1, "+Settings live in src/config.ts."));
    var c =
        findingOn(
            "c/src/config.c",
            "docs/CONFIG-C.md documents the key only as a comma-separated list.",
            null,
            null);
    var n =
        findingOn(
            "node/src/config.ts", "The binding in src/config.ts splits on commas.", null, null);

    var screenedC =
        mentions(C, "", Set.of()).screen(new ReviewResponse(List.of(c), List.of(), null));
    var screenedNode =
        mentions(node, "", Set.of()).screen(new ReviewResponse(List.of(n), List.of(), null));

    assertEquals(
        "c/docs/CONFIG-C.md documents the key only as a comma-separated list.",
        screenedC.findings().getFirst().description());
    assertEquals(
        "The binding in node/src/config.ts splits on commas.",
        screenedNode.findings().getFirst().description());
  }

  /** A suffix two changed files end with names neither, so it is judged as before. */
  @Test
  void aSuffixOfSeveralChangedFilesIsNotMapped() {
    var both =
        List.of(
            new FileDiff("c/docs/CONFIG.md", "added", 1, 0, 1, "+# C"),
            new FileDiff("go/docs/CONFIG.md", "added", 1, 0, 1, "+# Go"),
            new FileDiff("README.md", "modified", 1, 0, 1, "+See docs/CONFIG.md."));
    var text = "docs/CONFIG.md lists the key.";

    assertSame(text, mentions(both, "", Set.of()).withoutUnresolvedMentions(text));
  }

  /** The zig pull request (#274): the Dockerfile copies the artifact under its own name. */
  private static final List<FileDiff> ZIG_IMAGE =
      List.of(
          new FileDiff(
              "zig/Dockerfile",
              "added",
              3,
              0,
              3,
              """
              @@ -0,0 +1,3 @@
              +FROM alpine:3.20
              +COPY --from=build /src/zig-out/bin/gcompact-server /usr/local/bin/gcompact-server
              +ENTRYPOINT ["/usr/local/bin/gcompact-server"]
              \\ No newline at end of file"""));

  /**
   * #1094, zig #274: a quoted Dockerfile line with a destination the line does not hold gets the
   * one the line holds; the finding and its suggestion stay.
   */
  @Test
  void aQuotedLineWithAPathItDoesNotHoldGetsTheLinesPath() {
    var raised =
        findingOn(
            "zig/Dockerfile",
            "`COPY --from=build /src/zig-out/bin/gcompact-server"
                + " /usr/local/bin/gcompressed-server` therefore fails at build time.",
            "COPY --from=build /src/zig-out/bin/gcompact-server /usr/local/bin/gcompact-server",
            "COPY --from=build /src/zig-out/bin/gcompact /usr/local/bin/gcompact");

    var screened =
        mentions(ZIG_IMAGE, "", Set.of())
            .screen(new ReviewResponse(List.of(raised), List.of(), null));

    var kept = screened.findings().getFirst();
    assertEquals(
        "`COPY --from=build /src/zig-out/bin/gcompact-server /usr/local/bin/gcompact-server`"
            + " therefore fails at build time.",
        kept.description());
    assertEquals(raised.suggestionNew(), kept.suggestionNew(), "a new destination is not checked");
  }

  /** Spans that are not quotes of the file, or quote it exactly, are left as written. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "The binary lands in `/usr/local/bin/gcompressed-server` instead.",
        "`COPY --from=build /src/zig-out/bin/gcompact-server /usr/local/bin/gcompact-server` fails.",
        "`ADD /opt/app/server /srv/server` is not in the file.",
        "No span at all names /usr/local/bin/gcompressed-server.",
        "```\nCOPY --from=build /src/zig-out/bin/gcompact-server /usr/local/bin/x\n```"
      })
  void aSpanThatIsNotAMisquoteIsLeftAlone(String description) {
    var mentions = mentions(ZIG_IMAGE, "", Set.of());

    assertSame(description, mentions.withQuotedPathsCorrected(description, "zig/Dockerfile"));
    assertSame(description, mentions.withQuotedPathsCorrected(description, "zig/build.zig"));
    assertNull(mentions.withQuotedPathsCorrected(null, "zig/Dockerfile"));
  }

  /** A misquote that two paths of the file would fit is left as written: neither is known. */
  @Test
  void aMisquoteTwoPathsFitIsLeftAlone() {
    var twice =
        List.of(
            new FileDiff(
                "Dockerfile",
                "added",
                2,
                0,
                2,
                "+RUN install /opt/a/bin/tool\n+RUN install /opt/b/bin/tool"));
    var description = "`RUN install /opt/c/bin/tool` fails.";

    assertSame(
        description,
        mentions(twice, "", Set.of()).withQuotedPathsCorrected(description, "Dockerfile"));
  }

  /** The kotlin pull request (#276): the image holds one jar under /app. */
  private static final List<FileDiff> KOTLIN_IMAGE =
      List.of(
          new FileDiff(
              "kotlin/Dockerfile",
              "added",
              2,
              0,
              2,
              """
              +COPY --from=build /app/target/desk-invoicer-1.0.0.jar /app/invoicer.jar
              +ENTRYPOINT ["java", "-jar", "/app/invoicer.jar"]"""));

  /**
   * #1094, kotlin #276: a suggestion putting /app/lib/* on the classpath, which nothing creates,
   * loses its suggestion; the finding stays with its anchor quote.
   */
  @Test
  void aSuggestionGlobbingADirectoryNothingNamesIsDropped() {
    var raised =
        findingOn(
            "kotlin/Dockerfile",
            "The jar bundles no dependencies, so the container fails at start.",
            "ENTRYPOINT [\"java\", \"-jar\", \"/app/invoicer.jar\"]",
            "ENTRYPOINT [\"java\", \"-cp\", \"/app/invoicer.jar:/app/lib/*\","
                + " \"cowork.invoicing.MainKt\"]");

    var screened =
        mentions(KOTLIN_IMAGE, "", Set.of())
            .screen(new ReviewResponse(List.of(raised), List.of(), null));

    assertEquals(1, screened.findings().size());
    var kept = screened.findings().getFirst();
    assertNull(kept.suggestionNew());
    assertEquals(raised.suggestionOld(), kept.suggestionOld());
    assertEquals(raised.description(), kept.description());
    assertEquals(raised.title(), kept.title());
  }

  /** A glob over a directory the patch or the replaced lines name keeps its suggestion. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ENTRYPOINT [\"java\", \"-cp\", \"/app/*\", \"cowork.invoicing.MainKt\"]",
        "ENTRYPOINT [\"java\", \"-cp\", \"/app/target/*\", \"cowork.invoicing.MainKt\"]",
        "ENTRYPOINT [\"java\", \"-jar\", \"/app/invoicer.jar\", \"--trace\"]",
        "ENTRYPOINT [\"java\", \"-jar\", \"/app/invoicer.jar\"] # see https://x.example/a/b.md",
        ""
      })
  void aSuggestionNamingWhatExistsIsKept(String suggestion) {
    var raised =
        findingOn(
            "kotlin/Dockerfile",
            "The jar bundles no dependencies.",
            "ENTRYPOINT [\"java\", \"-jar\", \"/app/invoicer.jar\"]",
            suggestion);
    var response = new ReviewResponse(List.of(raised), List.of(), null);

    assertSame(response, mentions(KOTLIN_IMAGE, "", Set.of()).screen(response));
  }

  /**
   * A suggestion naming a repository file nothing resolves loses its suggestion; one naming a file
   * of the repository, or the file it replaces, keeps it.
   */
  @Test
  void aSuggestionNamingAFileNothingResolvesIsDropped() {
    var mentions = mentions(ANGULAR, "", Set.of("angular/nginx.conf"));
    var invented =
        findingOn("angular/Dockerfile", "No config is copied.", null, "COPY ops/site.conf /etc/x/");
    var existing =
        findingOn("angular/Dockerfile", "No config is copied.", null, "COPY nginx.conf /etc/x/");
    var replaced =
        findingOn(
            "angular/Dockerfile",
            "Wrong destination.",
            "COPY ops/site.conf /etc/y/",
            "COPY ops/site.conf /etc/x/");

    var screened =
        mentions.screen(new ReviewResponse(List.of(invented, existing, replaced), List.of(), null));

    assertEquals(3, screened.findings().size());
    assertNull(screened.findings().get(0).suggestionNew());
    assertEquals("COPY nginx.conf /etc/x/", screened.findings().get(1).suggestionNew());
    assertEquals("COPY ops/site.conf /etc/x/", screened.findings().get(2).suggestionNew());
  }

  /**
   * What a suggestion creates or does not name as a file is not judged: a COPY or ADD destination,
   * relative or absolute, a directory, a volume mapping. A dotless file name it relies on is.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          COPY nginx.conf conf/site.conf | true
          COPY ["nginx.conf", "conf/site.conf"] | true
          add nginx.conf conf/site.conf | true
          COPY --from=build /app/target/service.war /usr/local/tomcat/webapps/ROOT.war | true
          COPY src/ /app/src/ | true
          volumes: [conf/app.yml:/etc/app.yml] | true
          RUN cp nginx.conf conf/app.conf | true
          RUN mkdir -p conf && cp nginx.conf conf/app.conf && echo ok | true
          RUN sudo install -m 644 nginx.conf "conf/app.conf"; true | true
          echo listen > conf/app.conf | true
          RUN ln -s conf/site.conf | true
          RUN java -jar lib/a/b/run.war --serve bin/x/y/run | true
          RUN docker build -f ops/Dockerfile . | false
          COPY conf/site.conf nginx.conf | false
          RUN cp conf/site.conf /etc/x | false
          RUN cat conf/site.conf && cp nginx.conf conf/app.conf | false
          """)
  void aSuggestionIsJudgedOnlyByWhatItReliesOn(String suggestion, boolean kept) {
    var raised = findingOn("angular/Dockerfile", "No config is copied.", null, suggestion);

    var screened =
        mentions(ANGULAR, "", Set.of("angular/nginx.conf"))
            .screen(new ReviewResponse(List.of(raised), List.of(), null));

    assertEquals(kept ? suggestion : null, screened.findings().getFirst().suggestionNew());
  }

  /** A misquote is corrected whichever side of the path the quoted code is on. */
  @Test
  void aMisquoteIsCorrectedWithTheCodeAfterThePath() {
    var image =
        List.of(
            new FileDiff(
                "Dockerfile",
                "added",
                2,
                0,
                2,
                // A blank line, as a hand-edited patch can carry, is no code line.
                "+FROM alpine:3.20\n\n+RUN /usr/local/bin/gcompact-server --check"));

    assertEquals(
        "`/usr/local/bin/gcompact-server --check` fails.",
        mentions(image, "", Set.of())
            .withQuotedPathsCorrected("`/usr/local/bin/gcompressed --check` fails.", "Dockerfile"));
  }

  /** A span inside a fenced block is the block's code, not a quote to correct. */
  @Test
  void aSpanInAFenceIsLeftAlone() {
    var description =
        "```\n`COPY --from=build /src/zig-out/bin/gcompact-server /usr/local/bin/x`\n```";

    assertSame(
        description,
        mentions(ZIG_IMAGE, "", Set.of()).withQuotedPathsCorrected(description, "zig/Dockerfile"));
  }

  /**
   * A suggestion over several lines is judged line by line, and a glob over a directory its own new
   * lines make keeps the suggestion.
   */
  @Test
  void aSuggestionOverSeveralLinesIsJudgedLineByLine() {
    var made =
        findingOn(
            "kotlin/Dockerfile",
            "The jar bundles no dependencies.",
            "ENTRYPOINT [\"java\", \"-jar\", \"/app/invoicer.jar\"]",
            "RUN mkdir -p /app/lib\nENTRYPOINT [\"java\", \"-cp\", \"/app/lib/*\", \"Main\"]");
    var copied =
        findingOn(
            "kotlin/Dockerfile",
            "No config is copied.",
            null,
            "COPY nginx.conf conf/site.conf\nRUN cat conf/missing.conf\nUSER app");
    var response = new ReviewResponse(List.of(made, copied), List.of(), null);

    var screened = mentions(KOTLIN_IMAGE, "", Set.of("kotlin/nginx.conf")).screen(response);

    assertEquals(made.suggestionNew(), screened.findings().get(0).suggestionNew());
    assertNull(screened.findings().get(1).suggestionNew());
  }

  /** A destination on a continuation line belongs to the instruction the lines above open. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "RUN cp nginx.conf \\\n    conf/app.conf",
        "COPY nginx.conf \\\n  conf/site.conf",
        "RUN apt-get update && \\\n  mkdir -p conf && \\\n  cp nginx.conf conf/app.conf"
      })
  void aDestinationOnAContinuationLineKeepsTheSuggestion(String suggestion) {
    var raised = findingOn("angular/Dockerfile", "No config is copied.", null, suggestion);

    var screened =
        mentions(ANGULAR, "", Set.of("angular/nginx.conf"))
            .screen(new ReviewResponse(List.of(raised), List.of(), null));

    assertEquals(suggestion, screened.findings().getFirst().suggestionNew());
  }

  /**
   * A glob over a directory only the suggestion names is kept when the suggestion writes the
   * directory, not when it only reads it or repeats the glob.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          RUN mkdir -p /app/lib && cp /app/*.jar /app/lib/ && java -cp '/app/lib/*' Main | true
          COPY lib/ /app/lib/ | true
          RUN ls /app/lib/* ; mkdir /app/lib | true
          RUN ls /app/lib && cp /app/lib/* /out/ | false
          RUN cp /app/lib/* /out/; cp /app/lib/* /out2/ | false
          RUN mkdir -p /srv/app/lib && cp /app/lib/* /out/ | false
          """)
  void aGlobKeepsItsSuggestionOnlyWhenTheSuggestionWritesItsDirectory(
      String suggestion, boolean kept) {
    var raised =
        findingOn("kotlin/Dockerfile", "The jar bundles no dependencies.", null, suggestion);

    var screened =
        mentions(KOTLIN_IMAGE, "", Set.of())
            .screen(new ReviewResponse(List.of(raised), List.of(), null));

    assertEquals(kept ? suggestion : null, screened.findings().getFirst().suggestionNew());
  }

  /** A finding that names a renamed file by its earlier path is checked against its patch. */
  @Test
  void aQuoteOnARenamedFilesEarlierPathIsCorrected() {
    var renamed =
        List.of(
            new FileDiff(
                "ops/server.Dockerfile",
                "renamed",
                1,
                0,
                1,
                "+COPY --from=build /out/app /usr/local/bin/app-server",
                "Dockerfile"));

    assertEquals(
        "`COPY --from=build /out/app /usr/local/bin/app-server` fails.",
        mentions(renamed, "", Set.of())
            .withQuotedPathsCorrected(
                "`COPY --from=build /out/app /usr/local/bin/app-srv` fails.", "Dockerfile"));
  }
}
