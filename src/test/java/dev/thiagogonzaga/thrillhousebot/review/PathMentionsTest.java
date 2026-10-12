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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        "It globs docs/{a,b}/index.md.",
        "It imports @angular/core/testing helpers.",
        "POST /triage/run starts a run, GET /applications/:id/attachments serves files.",
        "It handles read/write and and/or paths in CI/CD.",
        "It runs on Node.js and Vue.js.",
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
}
