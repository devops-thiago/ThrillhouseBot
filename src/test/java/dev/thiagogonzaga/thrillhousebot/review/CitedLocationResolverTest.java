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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CitedLocationResolver} — resolving a finding's cited {@code path:line}
 * against the file it names at the pull request's head commit, so the verifier rules on the code
 * that is actually there rather than on the window it happened to be given (#650).
 */
class CitedLocationResolverTest {

  private static final String PATH = "src/main/java/app/Renderer.java";

  /**
   * The #636 shape: the quoted line is real and in the file, nine lines below where the finding
   * cites it, and outside the batch's hunks.
   */
  private static final String RENDERER =
      """
      package app;

      class Renderer {

        String header(String title) {
          return "<h1>" + MarkdownSafe.of(title) + "</h1>";
        }

        String body(String purpose) {
          return "<p>" + purpose + "</p>";
        }
      }
      """;

  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final CitedLocationResolver resolver = new CitedLocationResolver(prClient);

  private CitedLocationResolver.Round round(FileDiff... files) {
    return resolver.forReview("token", "o", "r", "headsha", List.of(files));
  }

  private static FileDiff changed(String path) {
    return new FileDiff(path, "modified", 1, 0, 1, "@@ -1 +1 @@\n+x");
  }

  private void givenFile(String path, String content) {
    when(prClient.getFileContent(any(), any(), eq("o"), eq("r"), eq(path), eq("headsha")))
        .thenReturn(
            new GitHubPullRequestClient.FileContent(
                path,
                path,
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
                "base64",
                content.length()));
  }

  private static ReviewResponse.Finding finding(String file, int line, String quote) {
    return new ReviewResponse.Finding(
        "high", "high", file, line, "Unescaped splice", "purpose reaches the sink raw", quote, "x");
  }

  private String resolve(CitedLocationResolver.Round round, ReviewResponse.Finding finding) {
    return round.locate(List.of(finding)).forFinding(finding);
  }

  @Test
  void findsTheQuotedCodeWhereItReallyIsWhenTheCitedLineIsOff() {
    givenFile(PATH, RENDERER);
    var finding = finding(PATH, 6, "return \"<p>\" + purpose + \"</p>\";");

    var note = resolve(round(changed(PATH)), finding);

    assertNotNull(note, "a citation a few lines off must still resolve");
    assertTrue(note.contains("is at line 10 of"), note);
    assertTrue(note.contains("not at the cited line 6"), note);
    assertTrue(note.contains("> 10 |"), note);
    assertTrue(note.contains("+ purpose +"), note);
  }

  @Test
  void confirmsTheQuotedCodeSittingAtTheCitedLine() {
    givenFile(PATH, RENDERER);
    var finding = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.contains("at the cited line 10"), note);
    assertTrue(note.contains("> 10 |"), note);
  }

  @Test
  void matchesAMultiLineQuoteOnlyAsAContiguousRun() {
    givenFile(PATH, RENDERER);
    var contiguous =
        finding(PATH, 2, "String body(String purpose) {\nreturn \"<p>\" + purpose + \"</p>\";");
    var scattered =
        finding(PATH, 2, "String header(String title) {\nreturn \"<p>\" + purpose + \"</p>\";");

    assertTrue(resolve(round(changed(PATH)), contiguous).contains("is at line 9 of"), "contiguous");
    assertTrue(
        resolve(round(changed(PATH)), scattered)
            .contains("The code the finding quotes appears nowhere in"),
        "a recombination of real but scattered lines is not a match");
  }

  @Test
  void showsTheCitedLineWhenTheQuoteIsNowhereInTheFile() {
    givenFile(PATH, RENDERER);
    var finding = finding(PATH, 6, "return sanitize(purpose);");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.startsWith("The code the finding quotes appears nowhere in"), note);
    assertTrue(note.contains("> 6 |"), note);
    assertTrue(note.contains("MarkdownSafe.of(title)"), note);
  }

  @Test
  void reportsACitedLinePastTheEndOfTheFile() {
    givenFile(PATH, RENDERER);
    var finding = finding(PATH, 400, "return sanitize(purpose);");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.contains("has 12 lines at the pull request's head commit"), note);
    assertTrue(note.contains("the cited line 400 does not exist"), note);
  }

  @Test
  void reportsAPathNoChangedFileHas() {
    var finding = finding("src/main/java/app/Absent.java", 3, "return x;");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.startsWith("No file changed by this pull request has this path"), note);
    verifyNoInteractions(prClient);
  }

  @Test
  void readsARenamedFileFromItsNewPath() {
    givenFile(PATH, RENDERER);
    var renamed =
        new FileDiff(PATH, "renamed", 1, 0, 1, "@@ -1 +1 @@\n+x", "src/main/java/app/Old.java");
    var finding = finding("src/main/java/app/Old.java", 10, "return \"<p>\" + purpose + \"</p>\";");

    var note = resolve(round(renamed), finding);

    assertTrue(note.startsWith("This pull request renames "), note);
    assertTrue(note.contains("to `" + PATH + "`"), note);
    assertTrue(note.contains("at the cited line 10"), note);
  }

  @Test
  void resolvesACitationThatDiffersOnlyInCase() {
    givenFile(PATH, RENDERER);
    var finding =
        finding("SRC/main/java/app/renderer.java", 10, "return \"<p>\" + purpose + \"</p>\";");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.startsWith("No file in the pull request has the cited path exactly"), note);
    assertTrue(note.contains("`" + PATH + "` is the only changed file it matches"), note);
    assertTrue(note.contains("at the cited line 10"), note);
  }

  @Test
  void resolvesACitationMissingALeadingDirectory() {
    givenFile(PATH, RENDERER);
    var finding = finding("app/Renderer.java", 10, "return \"<p>\" + purpose + \"</p>\";");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.contains("`" + PATH + "` is the only changed file it matches"), note);
    assertTrue(note.contains("at the cited line 10"), note);
  }

  @Test
  void refusesToGuessBetweenTwoFilesWithTheSameSuffix() {
    var finding = finding("app/Renderer.java", 10, "return x;");

    var note =
        resolve(
            round(changed("main/app/Renderer.java"), changed("other/app/Renderer.java")), finding);

    assertTrue(note.startsWith("No file changed by this pull request has this path"), note);
    verifyNoInteractions(prClient);
  }

  @Test
  void reportsAFileThePullRequestDeletes() {
    var removed = new FileDiff(PATH, "removed", 0, 12, 12, "@@ -1,12 +0,0 @@\n-x");
    var finding = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");

    var note = resolve(round(removed), finding);

    assertTrue(note.contains("deletes `" + PATH + "`"), note);
    assertTrue(note.contains("no content at the head commit"), note);
    verifyNoInteractions(prClient);
  }

  @Test
  void resolvesNothingForAFindingThatCitesNoFile() {
    var finding = finding("  ", 10, "return x;");

    assertNull(resolve(round(changed(PATH)), finding));
    verifyNoInteractions(prClient);
  }

  @Test
  void resolvesNothingForAFileLevelFindingThatQuotesNothing() {
    givenFile(PATH, RENDERER);
    var finding = finding(PATH, 0, null);

    assertNull(resolve(round(changed(PATH)), finding));
  }

  @Test
  void failsOpenWhenTheFileCannotBeRead() {
    when(prClient.getFileContent(any(), any(), any(), any(), any(), any()))
        .thenThrow(new WebApplicationException(404));
    var finding = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");

    assertNull(resolve(round(changed(PATH)), finding));
  }

  @Test
  void skipsAFileTooLargeToBeSource() {
    when(prClient.getFileContent(any(), any(), eq("o"), eq("r"), eq(PATH), eq("headsha")))
        .thenReturn(
            new GitHubPullRequestClient.FileContent(
                PATH,
                PATH,
                Base64.getEncoder().encodeToString(RENDERER.getBytes(StandardCharsets.UTF_8)),
                "base64",
                CitedLocationResolver.MAX_FILE_BYTES + 1));
    var finding = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");

    assertNull(resolve(round(changed(PATH)), finding));
  }

  @Test
  void readsEachFileOnceForTheWholeRound() {
    givenFile(PATH, RENDERER);
    var round = round(changed(PATH));
    var first = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");
    var second = finding(PATH, 6, "return \"<h1>\" + MarkdownSafe.of(title) + \"</h1>\";");

    assertNotNull(round.locate(List.of(first)).forFinding(first));
    assertNotNull(round.locate(List.of(second)).forFinding(second));

    verify(prClient, times(1)).getFileContent(any(), any(), any(), any(), eq(PATH), any());
  }

  @Test
  void stopsReadingFilesAtTheRoundsFetchBudget() {
    var files = new FileDiff[CitedLocationResolver.MAX_FILES_FETCHED + 1];
    var findings = new ReviewResponse.Finding[files.length];
    for (var i = 0; i < files.length; i++) {
      var path = "src/main/java/app/File" + i + ".java";
      files[i] = changed(path);
      givenFile(path, RENDERER);
      findings[i] = finding(path, 10, "return \"<p>\" + purpose + \"</p>\";");
    }
    var round = round(files);

    var located = round.locate(List.of(findings));

    assertNotNull(located.forFinding(findings[0]));
    assertNull(
        located.forFinding(findings[files.length - 1]),
        "the finding past the round's fetch budget resolves to nothing");
    verify(prClient, times(CitedLocationResolver.MAX_FILES_FETCHED))
        .getFileContent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void aDisabledRoundResolvesNothing() {
    var finding = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");

    assertNull(CitedLocationResolver.disabled().locate(List.of(finding)).forFinding(finding));
  }

  @Test
  void resolvesNothingForAnEmptyCandidateList() {
    assertSame(
        FindingVerificationService.CitedLocations.NONE, round(changed(PATH)).locate(List.of()));
  }

  @Test
  void resolvesOneNotePerDistinctFindingAndSkipsRepeats() {
    givenFile(PATH, RENDERER);
    var finding = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");

    var located = round(changed(PATH)).locate(List.of(finding, finding));

    assertNotNull(located.forFinding(finding));
    assertNull(located.forFinding(null));
  }

  @Test
  void stopsAttachingAtTheRoundsCharacterBudget() {
    var files = new FileDiff[CitedLocationResolver.MAX_FILES_FETCHED];
    var findings = new ReviewResponse.Finding[files.length];
    var wide = "x".repeat(CitedLocationResolver.MAX_LINE_CHARS * 3);
    var content = (wide + "\n").repeat(20);
    for (var i = 0; i < files.length; i++) {
      var path = "src/main/java/app/Wide" + i + ".java";
      files[i] = changed(path);
      givenFile(path, content);
      findings[i] = finding(path, 10, null);
    }

    var located = round(files).locate(List.of(findings));

    assertNotNull(located.forFinding(findings[0]));
    assertTrue(
        located.forFinding(findings[0]).length() <= CitedLocationResolver.MAX_NOTE_CHARS,
        "one note is capped");
    assertNull(
        located.forFinding(findings[files.length - 1]),
        "notes past the round's character budget are dropped");
  }

  @Test
  void refusesToSettleAQuoteTheFileHoldsMoreThanOnce() {
    var repeated =
        """
        int x = compute();
        int y = 0;
        int x = compute();
        """;
    givenFile(PATH, repeated);
    var finding = finding(PATH, 3, "int x = compute();");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.startsWith("The code the finding quotes appears in 2 places in"), note);
    assertTrue(note.contains("is not settled here"), note);
    assertTrue(note.contains("nearest to the cited line 3 is line 3"), note);
    assertTrue(note.contains("> 3 |"), "the window shown is the cited line's: " + note);
  }

  @Test
  void showsTheNearestOccurrenceWhenAnAmbiguousQuoteHasNoUsableCitedLine() {
    givenFile(PATH, "int x = compute();\nint y = 0;\nint x = compute();\n");
    var finding = finding(PATH, 40, "int x = compute();");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(note.contains("The cited line is not a line of this file."), note);
    assertTrue(note.contains("nearest to the cited line 40 is line 3"), note);
    assertTrue(note.contains("> 3 |"), note);
  }

  @Test
  void namesAFileLevelCitationThatHasNoLine() {
    givenFile(PATH, "int x = compute();\nint y = 0;\nint x = compute();\n");
    var finding = finding(PATH, 0, "int x = compute();");

    var note = resolve(round(changed(PATH)), finding);

    assertTrue(
        note.contains("nearest to the cited location, which names no line, is line 1"), note);
  }

  @Test
  void countsLinesWithoutTheTrailingNewlinesEmptyTail() {
    givenFile(PATH, "one\ntwo\n");
    var finding = finding(PATH, 9, "nothing here");

    assertTrue(resolve(round(changed(PATH)), finding).contains("has 2 lines"), "line count");
  }

  @Test
  void toleratesAReviewWithNoFileListAndFindingsWithNoUsableEntry() {
    var round = resolver.forReview("token", "o", "r", "headsha", null);
    var usable = finding(PATH, 10, "return x;");

    assertSame(
        FindingVerificationService.CitedLocations.NONE,
        round.locate(null),
        "a round with nothing to resolve resolves nothing");
    var located = round.locate(Arrays.asList(null, finding(null, 3, "x"), usable));

    assertTrue(
        located.forFinding(usable).startsWith("No file changed by this pull request has this path"),
        "a review with no file list matches nothing");
    verifyNoInteractions(prClient);
  }

  @Test
  void ignoresAChangedFileWithNoPath() {
    givenFile(PATH, RENDERER);
    var nameless = new FileDiff(null, "modified", 1, 0, 1, "");
    var blankName = new FileDiff("  ", "modified", 1, 0, 1, "");
    var blankRename = new FileDiff(PATH, "modified", 1, 0, 1, "", "  ");
    var finding = finding(PATH, 10, "return \"<p>\" + purpose + \"</p>\";");
    var withGaps =
        resolver.forReview(
            "token", "o", "r", "headsha", Arrays.asList(null, nameless, blankName, blankRename));

    assertNotNull(withGaps.locate(List.of(finding)).forFinding(finding));
  }

  @Test
  void refusesToGuessBetweenTwoFilesDifferingOnlyInCase() {
    var finding = finding("app/FILE.java", 3, "return x;");

    var note = resolve(round(changed("app/File.java"), changed("app/file.java")), finding);

    assertTrue(note.startsWith("No file changed by this pull request has this path"), note);
    verifyNoInteractions(prClient);
  }

  @Test
  void reportsAQuoteMissingFromAFileTheFindingCitesWithoutALine() {
    givenFile(PATH, RENDERER);
    var finding = finding(PATH, 0, "return sanitize(purpose);");

    var note = resolve(round(changed(PATH)), finding);

    assertEquals(
        "The code the finding quotes appears nowhere in `"
            + PATH
            + "` at the pull request's head commit.",
        note);
  }

  @Test
  void failsOpenOnAnAbsentOrEmptyResponseBody() {
    var finding = finding(PATH, 10, "return x;");
    when(prClient.getFileContent(any(), any(), any(), any(), eq(PATH), any())).thenReturn(null);
    assertNull(resolve(round(changed(PATH)), finding), "no body");

    var contentless = new GitHubPullRequestClient.FileContent(PATH, PATH, null, "base64", 10);
    when(prClient.getFileContent(any(), any(), any(), any(), eq(PATH), any()))
        .thenReturn(contentless);
    assertNull(resolve(round(changed(PATH)), finding), "no content");

    givenFile(PATH, "");
    assertNull(resolve(round(changed(PATH)), finding), "empty file");
  }

  @Test
  void countsEveryLineOfAFileThatDoesNotEndInANewline() {
    givenFile(PATH, "one\ntwo");
    var finding = finding(PATH, 9, "nothing here");

    assertTrue(resolve(round(changed(PATH)), finding).contains("has 2 lines"), "line count");
  }

  @Test
  void countsTheOneLineOfASingleLineFile() {
    givenFile(PATH, "one");
    var finding = finding(PATH, 9, "nothing here");

    assertTrue(resolve(round(changed(PATH)), finding).contains("has 1 lines"), "line count");
  }

  @Test
  void resolvesACitationCarryingAnExtraLeadingDirectory() {
    givenFile("app/Renderer.java", RENDERER);
    var finding = finding("src/main/app/Renderer.java", 10, "return \"<p>\" + purpose + \"</p>\";");

    var note = resolve(round(changed("app/Renderer.java")), finding);

    assertTrue(note.contains("`app/Renderer.java` is the only changed file it matches"), note);
  }

  @Test
  void keepsTheFirstOccurrenceWhenALaterOneIsFartherFromTheCitedLine() {
    givenFile(PATH, "int x = compute();\nint y = 0;\nint x = compute();\n");
    var finding = finding(PATH, 1, "int x = compute();");

    assertTrue(
        resolve(round(changed(PATH)), finding).contains("nearest to the cited line 1 is line 1"),
        "the nearer of two occurrences is the one named");
  }

  @Test
  void matchesAQuoteAcrossAShortBlankGapOnly() {
    assertEquals(
        1,
        CitedLocationResolver.locateQuote(List.of("a", "b"), List.of("a", "", "b"), 1).line(),
        "a blank line the quote dropped does not break the run");
    assertEquals(
        0,
        CitedLocationResolver.locateQuote(List.of("a", "b"), List.of("a", "", "", "", "b"), 1)
            .line(),
        "lines merely appearing in that order far apart are not a contiguous run");
    assertEquals(
        0,
        CitedLocationResolver.locateQuote(List.of("a", "b"), List.of("a"), 1).line(),
        "a quote running past the end of the file is not a match");
    assertEquals(
        CitedLocationResolver.QuoteMatch.NONE,
        CitedLocationResolver.locateQuote(List.of(), List.of("a"), 1),
        "no quote");
  }

  @Test
  void normalizesAQuoteToItsNonBlankStrippedLines() {
    assertEquals(List.of(), CitedLocationResolver.normalizedLines("   "));
    assertEquals(List.of("a", "b"), CitedLocationResolver.normalizedLines("  a  \n\n b \n"));
  }
}
