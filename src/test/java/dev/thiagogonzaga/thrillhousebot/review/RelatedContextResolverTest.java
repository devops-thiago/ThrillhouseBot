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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService.Outcome;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService.Reason;
import dev.thiagogonzaga.thrillhousebot.review.ai.RelatedContextSelectionService.Selected;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RelatedContextResolverTest {

  private static final String HANDLER = "src/app/web/Handler.java";
  private static final String SANITIZER = "src/app/util/InputSanitizer.java";
  private static final String LIMITS = "src/app/util/Limits.java";

  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final RelatedContextSelectionService selection =
      mock(RelatedContextSelectionService.class);
  private final DiffBudgetPlanner planner = mock(DiffBudgetPlanner.class);
  private final ReviewSession session = mock(ReviewSession.class);

  private final FileDiff handler =
      new FileDiff(
          HANDLER,
          "modified",
          3,
          0,
          3,
          "@@ -1,2 +1,5 @@ class Handler\n"
              + "+import app.util.InputSanitizer;\n"
              + "+    var id = sanitizer.clean(raw);\n"
              + "+    if (id.length() > Limits.MAX_ID) return;");

  @BeforeEach
  void setUp() {
    when(planner.perCallInputBudget()).thenReturn(100_000);
    when(planner.relatedContextTokenBudget()).thenReturn(15_000);
    when(prClient.getTree(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new GitHubPullRequestClient.TreeResponse(
                "sha",
                List.of(
                    new GitHubPullRequestClient.TreeEntry(HANDLER, "blob", 1),
                    new GitHubPullRequestClient.TreeEntry(SANITIZER, "blob", 1),
                    new GitHubPullRequestClient.TreeEntry(LIMITS, "blob", 1)),
                false));
  }

  private RelatedContextResolver resolver(boolean enabled, int maxAiCalls) {
    return new RelatedContextResolver(
        enabled, 4, maxAiCalls, selection, planner, new TokenCounter(), path -> false);
  }

  private CitedLocationResolver.Round round() {
    return new CitedLocationResolver(prClient)
        .forReview("auth", "o", "r", "headsha", List.of(handler), new EvidenceBudget());
  }

  private ReviewContextLoader.ReviewContext context(List<FileDiff> files) {
    return new ReviewContextLoader.ReviewContext(
        files,
        "diff",
        "base",
        0,
        List.of(),
        List.of(),
        List.of(),
        true,
        false,
        null,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "stack",
        "",
        "",
        "",
        files,
        () -> new DiffLineResolver(Map.of()),
        new ReviewContextLoader.PrTotals(1, 1, 1));
  }

  private void fileHolds(String path, String text) {
    when(prClient.getFileContent(any(), any(), eq("o"), eq("r"), eq(path), eq("headsha")))
        .thenReturn(
            new GitHubPullRequestClient.FileContent(
                path.substring(path.lastIndexOf('/') + 1),
                path,
                Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)),
                "base64",
                text.length()));
  }

  private static String sanitizerSource() {
    var lines = new ArrayList<String>();
    lines.add("package app.util;");
    for (var i = 0; i < 30; i++) {
      lines.add("// filler " + i);
    }
    lines.add("public final class InputSanitizer {");
    for (var i = 0; i < 30; i++) {
      lines.add("  // more filler " + i);
    }
    lines.add("  String clean(String raw) {");
    lines.add("    return raw.strip();");
    lines.add("  }");
    lines.add("}");
    return String.join("\n", lines) + "\n";
  }

  @Test
  void readsThePickedFilesClippedToWhatTheChangedCodeUses() {
    when(selection.select(anyLong(), any()))
        .thenReturn(
            new Outcome(List.of(new Selected(SANITIZER, Reason.SANITIZER_OR_VALIDATOR)), true));
    fileHolds(SANITIZER, sanitizerSource());

    var related = resolver(true, 6).resolve(session, context(List.of(handler)), round());

    assertEquals(1, related.selectionCalls());
    assertEquals(1, related.files().size());
    var file = related.files().get(0);
    assertEquals(SANITIZER, file.path());
    assertEquals("sanitizer-or-validator", file.reason());
    assertEquals(List.of(HANDLER), file.referencedBy());
    assertEquals(66, file.totalLines());
    var section = related.section();
    assertTrue(section.contains("32 | public final class InputSanitizer {"), section);
    assertTrue(section.contains("63 |   String clean(String raw) {"), section);
    assertFalse(section.contains("1 | package app.util;"), section);

    var request = ArgumentCaptor.forClass(RelatedContextSelectionService.Request.class);
    verify(selection).select(anyLong(), request.capture());
    assertEquals(
        List.of(SANITIZER, LIMITS),
        request.getValue().candidates().stream()
            .map(RelatedContextSelectionService.Candidate::path)
            .toList());
    assertEquals("imported", request.getValue().candidates().get(0).how());
    assertEquals("named in added lines", request.getValue().candidates().get(1).how());
    assertEquals(4, request.getValue().maxFiles());
    assertEquals(
        List.of("- " + HANDLER + " (modified, +3/-0)\n    @@ -1,2 +1,5 @@ class Handler"),
        request.getValue().changedFiles());
    verify(prClient, never()).getFileContent(any(), any(), any(), any(), eq(LIMITS), any());
  }

  @Test
  void offOrBelowTheCallAllowanceOrWithNoFilesNothingIsReadOrCalled() {
    var ctx = context(List.of(handler));

    assertSame(RelatedContext.NONE, resolver(false, 6).resolve(session, ctx, round()));
    assertSame(RelatedContext.NONE, resolver(true, 2).resolve(session, ctx, round()));
    assertSame(
        RelatedContext.NONE, resolver(true, 6).resolve(session, context(List.of()), round()));
    assertSame(
        RelatedContext.NONE,
        RelatedContextResolver.disabled().resolve(session, ctx, CitedLocationResolver.disabled()));
    assertFalse(RelatedContextResolver.disabled().enabled());
    verifyNoInteractions(selection, prClient);
  }

  @Test
  void noListingNoCandidateAnUnreadableFileOrAFailureLeaveTheReviewWithoutIt() {
    var ctx = context(List.of(handler));
    assertSame(
        RelatedContext.NONE,
        resolver(true, 6).resolve(session, ctx, CitedLocationResolver.disabled()));
    var plain = new FileDiff("src/app/web/Plain.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+int x;");
    assertSame(
        RelatedContext.NONE, resolver(true, 6).resolve(session, context(List.of(plain)), round()));

    when(selection.select(anyLong(), any()))
        .thenReturn(
            new Outcome(
                List.of(
                    new Selected(SANITIZER, Reason.INTERFACE),
                    new Selected("src/not/a/Candidate.java", Reason.CALLER)),
                true));
    var unreadable = resolver(true, 6).resolve(session, ctx, round());
    assertTrue(unreadable.files().isEmpty());
    assertEquals(1, unreadable.selectionCalls());
    assertEquals("", unreadable.section());

    // The selection fails open on its own, so a failure here comes after the call was made and
    // counted.
    fileHolds(SANITIZER, sanitizerSource());
    when(planner.relatedContextTokenBudget()).thenThrow(new IllegalStateException("boom"));
    var failed = resolver(true, 6).resolve(session, ctx, round());
    assertTrue(failed.files().isEmpty());
    assertEquals(1, failed.selectionCalls());
  }

  @Test
  void aFileThatDoesNotFitTheBudgetShareIsCutBackOrLeftOut() {
    when(selection.select(anyLong(), any()))
        .thenReturn(
            new Outcome(
                List.of(
                    new Selected(SANITIZER, Reason.INTERFACE),
                    new Selected(LIMITS, Reason.RELATED)),
                true));
    fileHolds(SANITIZER, sanitizerSource());
    fileHolds(LIMITS, "final class Limits {\n  static final int MAX_ID = 64;\n}\n");
    var whole = resolver(true, 6).resolve(session, context(List.of(handler)), round());
    assertEquals(2, whole.files().size());
    assertEquals(2, whole.files().get(0).regions().size());

    var tokens = new TokenCounter();
    var first = whole.files().get(0);
    var firstRegionOnly = first.withRegions(first.regions().subList(0, 1));
    when(planner.relatedContextTokenBudget())
        .thenReturn(tokens.estimateTokens(firstRegionOnly.render()) + 3);
    var cut = resolver(true, 6).resolve(session, context(List.of(handler)), round());
    assertEquals(List.of(SANITIZER), cut.files().stream().map(f -> f.path()).toList());
    assertEquals(1, cut.files().get(0).regions().size());

    when(planner.relatedContextTokenBudget()).thenReturn(1);
    assertTrue(
        resolver(true, 6).resolve(session, context(List.of(handler)), round()).files().isEmpty());
  }

  @Test
  void aFileWithNothingTheChangeNamesShowsItsTopAndLongLinesAndFilesAreClipped() {
    var none =
        new RelatedReferences.Candidate(
            "p", RelatedReferences.Via.IMPORT, List.of(), List.of(), List.of());
    var lines = new ArrayList<String>();
    for (var i = 0; i < 100; i++) {
      lines.add("line " + i);
    }
    var top = RelatedContextResolver.regions(lines, none);
    assertEquals(1, top.size());
    assertEquals(1, top.get(0).firstLine());
    assertEquals(RelatedContextResolver.HEAD_LINES, top.get(0).lines().size());

    var wide = new ArrayList<String>();
    for (var i = 0; i < 60; i++) {
      wide.add("x".repeat(RelatedContextResolver.MAX_LINE_CHARS + 50));
    }
    var clipped = RelatedContextResolver.regions(wide, none);
    assertTrue(clipped.get(0).lines().get(0).endsWith("…"));
    assertTrue(
        clipped.get(0).lines().size()
            <= RelatedContextResolver.MAX_FILE_CHARS / RelatedContextResolver.MAX_LINE_CHARS);

    var named =
        new RelatedReferences.Candidate(
            "p", RelatedReferences.Via.SYMBOL, List.of(), List.of("Limits"), List.of("check"));
    var source =
        List.of("class Limits {", "  void check() {}", "  void other() { this.check(); }", "}");
    var regions = RelatedContextResolver.regions(source, named);
    assertEquals(1, regions.size());
    assertEquals(4, regions.get(0).lines().size());
  }

  @Test
  void theChangedFileListIsCappedAndCloseReleasesTheLedgerEntry() {
    var many = new ArrayList<FileDiff>();
    for (var i = 0; i < RelatedContextResolver.MAX_LISTED_FILES + 2; i++) {
      many.add(new FileDiff("f" + i + ".java", "added", 1, 0, 1, null));
    }
    var listed = RelatedContextResolver.changedFileList(many);
    assertEquals(RelatedContextResolver.MAX_LISTED_FILES + 1, listed.size());
    assertEquals("(2 more changed file(s) not listed)", listed.get(listed.size() - 1));

    resolver(true, 6).close(session);
    verify(selection).release(anyLong());
    RelatedContextResolver.disabled().close(session);
  }

  @Test
  void theInjectedResolverReadsItsSwitchesFromTheConfiguration() {
    var config = mock(ThrillhouseConfig.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    when(config.review().relatedContext().enabled()).thenReturn(true);
    when(config.review().relatedContext().maxFiles()).thenReturn(4);
    when(config.review().maxAiCalls()).thenReturn(6);
    var injected =
        new RelatedContextResolver(
            config,
            selection,
            planner,
            new TokenCounter(),
            new ReviewDiffFormatter(List.of("**/util/**"), 5000));
    when(selection.select(anyLong(), any())).thenReturn(new Outcome(List.of(), false));

    assertTrue(injected.enabled());
    var related = injected.resolve(session, context(List.of(handler)), round());

    assertSame(RelatedContext.NONE, related, "every candidate is under an ignored path");
    verifyNoInteractions(selection);
  }

  @Test
  void aSelectionThatMadeNoCallCountsNoneAndARenameSourceIsNotACandidate() {
    when(selection.select(anyLong(), any())).thenReturn(new Outcome(List.of(), false));
    var renamed = new FileDiff("src/app/util/Caps.java", "renamed", 0, 0, 0, null, LIMITS);

    var related = resolver(true, 6).resolve(session, context(List.of(handler, renamed)), round());

    assertEquals(0, related.selectionCalls());
    var request = ArgumentCaptor.forClass(RelatedContextSelectionService.Request.class);
    verify(selection).select(anyLong(), request.capture());
    assertEquals(1, request.getValue().candidates().size());
  }

  @Test
  void hitsAreCappedLongLinesSkippedAndARepeatedNameCountsOnce() {
    var named =
        new RelatedReferences.Candidate(
            "p", RelatedReferences.Via.SYMBOL, List.of(), List.of("Limits"), List.of("check"));
    var lines = new ArrayList<String>();
    lines.add("void check() {} // " + "x".repeat(RelatedReferences.MAX_LINE_CHARS));
    lines.add("class Limits {");
    for (var i = 0; i < 400; i++) {
      lines.add(i % 30 == 0 ? "  void check() { Limits.other(); }" : "  // filler");
    }

    var regions = RelatedContextResolver.regions(lines, named);

    var onlyLong =
        RelatedContextResolver.regions(
            List.of("void check() {} // " + "x".repeat(RelatedReferences.MAX_LINE_CHARS)), named);
    assertEquals(1, onlyLong.size(), "a line too long to scan is no hit; the top is shown");
    assertTrue(regions.size() <= RelatedContextResolver.MAX_HITS, regions.toString());
    assertEquals(List.of(), RelatedContextResolver.regions(List.of(), named));
    var byName =
        new RelatedReferences.Candidate(
            "p", RelatedReferences.Via.SYMBOL, List.of(), List.of("Limits"), List.of());
    var twice = new ArrayList<String>();
    twice.add("class Limits {");
    for (var i = 0; i < 60; i++) {
      twice.add("  // filler");
    }
    twice.add("  Limits copy() { return this; }");
    assertEquals(1, RelatedContextResolver.regions(twice, byName).size(), "the first use only");
  }
}
