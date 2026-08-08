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

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver.ResolvedInstructions;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrDescribeAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrDescribeAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrSuggestionPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PrDescriptionGeneratorTest {

  private static final String AUTH = "token gh-abc";

  private static final String PATCH =
      """
      @@ -0,0 +1,3 @@
      +var in = Files.newInputStream(path);
      +int total = 0;
      +total += items.size();""";

  /** A second file, so the line cap has something to drop and the planner something to batch. */
  private static final String OTHER_PATCH =
      """
      @@ -0,0 +1,3 @@
      +int retries = 0;
      +while (retries < 3) { call(); }
      +log.info("done");""";

  @Mock private GitHubPullRequestClient prClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private RepoSettingsResolver repoSettingsResolver;
  @Mock private ActiveModelSettings activeModel;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;
  @Mock private PrDescribeAssistant describeAssistant;

  private final ReviewDiffFormatter diffFormatter = new ReviewDiffFormatter(List.of(), 5000);

  private PrDescriptionGenerator generator;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    lenient().when(config.review()).thenReturn(reviewConfig);
    lenient().when(reviewConfig.maxAiCalls()).thenReturn(6);
    // Default: budgeting on with ample room, so a normal PR is a single batch and needs no
    // synthesis call — the same shape the command had before batching.
    lenient().when(activeModel.maxInputTokens()).thenReturn(1_000_000);
    lenient().when(activeModel.tokenSafetyMargin()).thenReturn(1.0);
    lenient().when(activeModel.outputBufferTokens()).thenReturn(0);
    lenient()
        .when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(ResolvedInstructions.EMPTY);
    lenient()
        .when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(RepoSettings.EMPTY);
    generator = generatorWith(diffFormatter);
  }

  private PrDescriptionGenerator generatorWith(ReviewDiffFormatter formatter) {
    return new PrDescriptionGenerator(
        prClient,
        formatter,
        instructionsResolver,
        repoSettingsResolver,
        new DiffBudgetPlanner(formatter, new TokenCounter(), config, activeModel),
        activeModel,
        config,
        describeAssistant);
  }

  private static FileDiff foo() {
    return new FileDiff("src/Foo.java", "modified", 3, 0, 3, PATCH);
  }

  private static FileDiff otherFile() {
    return new FileDiff("src/Other.java", "modified", 3, 0, 3, OTHER_PATCH);
  }

  /** A PR whose files are the given ones, with the usual title/body. */
  private void prWithFiles(FileDiff... files) {
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", null, null));
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(files));
  }

  /** A PR with one file, whose details come from the given stub. */
  private void prWithFilesAndDetails(PullRequestDetails details) {
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(details);
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(foo()));
  }

  private void describeReturns(String partial) {
    when(describeAssistant.describe(any(), any(), any(), any())).thenReturn(partial);
  }

  private String generate() {
    return generator.generate("owner", "repo", 7, "main", 12345L, AUTH);
  }

  /** The diff text each batch call actually received, in call order. */
  private List<String> diffsSentToAssistant() {
    var diff = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant, atLeastOnce()).describe(diff.capture(), any(), any(), any());
    return diff.getAllValues();
  }

  /**
   * A per-call input budget of exactly the shared prompt overhead plus {@code diffTokens}. Derived
   * from {@code /describe}'s own prompts rather than hardcoded, so editing a prompt cannot silently
   * turn these tests into no-ops by making every file overflow — and so a regression that sized the
   * overhead from another command's prompts would show up here.
   */
  private static int budgetFor(int diffTokens) {
    var overhead =
        new TokenCounter()
            .estimateTokens(
                PrDescribeAssistantPrompts.system()
                    + PrSuggestionPrompts.user()
                    + PromptTemplateEscaper.fence(" ")
                    + "Title"
                    + "Body"
                    + "");
    return overhead + diffTokens;
  }

  /** Budgeting on, with {@code diffTokens} of room per call for diff text. */
  private void budgetWithDiffRoom(int diffTokens) {
    when(activeModel.maxInputTokens()).thenReturn(budgetFor(diffTokens));
  }

  @Test
  void wrapsTheAssistantSuggestionInAComment() {
    prWithFiles(foo());
    describeReturns("### Suggested title\n`feat: do x`\n");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(PrDescriptionGenerator.HEADER));
    assertTrue(body.contains("### Suggested title"));
    assertTrue(body.endsWith(PrDescriptionGenerator.FOOTER));
  }

  @Test
  void appendsNoDisclosureWhenTheBudgetCoveredEveryFile() {
    prWithFiles(foo(), otherFile());
    describeReturns("### Suggested title\n`x`");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.endsWith(PrDescriptionGenerator.FOOTER));
    assertFalse(body.contains("were omitted"), body);
  }

  @Test
  void returnsNullWhenThereIsNoDiff() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of());

    assertNull(generate());
    verifyNoInteractions(describeAssistant);
  }

  @Test
  void returnsNullWhenAssistantProducesBlank() {
    prWithFiles(foo());
    describeReturns("   ");

    assertNull(generate());
  }

  @Test
  void returnsNullWhenAssistantThrows() {
    prWithFiles(foo());
    when(describeAssistant.describe(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertNull(generate());
  }

  @Test
  void stillDescribesWhenPrDetailsFetchFails() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(foo()));
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("404"));
    describeReturns("### Suggested title\n`x`");

    String body = generate();

    assertNotNull(body);
    var title = ArgumentCaptor.forClass(String.class);
    var desc = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant).describe(any(), title.capture(), desc.capture(), any());
    // Missing details degrade to empty current title/description, not a crash.
    assertEquals("", title.getValue());
    assertEquals("", desc.getValue());
  }

  @Test
  void fencesTheBatchDiffBeforeCallingTheAssistant() {
    prWithFiles(
        new FileDiff("src/Foo.java", "modified", 1, 0, 1, "@@ -0,0 +1 @@\n+<<<DIFF_END>>>"));
    describeReturns("ok");

    generate();

    var diffArg = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant).describe(diffArg.capture(), any(), any(), any());
    // The batch is wrapped in an unguessable random fence and passed byte-exact, so PR content
    // (including the old diff markers) reaches the model verbatim and cannot forge the boundary.
    assertTrue(diffArg.getValue().contains(PromptTemplateEscaper.fencePrefix()));
    assertTrue(diffArg.getValue().contains("<<<DIFF_END>>>"));
  }

  @Test
  void returnsNullWhenDiffFetchFails() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("boom"));

    // A failed diff fetch degrades to no suggestion, not a crash.
    assertNull(generate());
    verifyNoInteractions(describeAssistant);
  }

  @Test
  void returnsNullWhenAssistantReturnsNull() {
    prWithFiles(foo());
    describeReturns(null);

    assertNull(generate());
  }

  @Test
  void treatsNullTitleAndBodyAsEmptyContext() {
    prWithFilesAndDetails(new PullRequestDetails(null, null, null, null));
    describeReturns("ok");

    generate();

    var title = ArgumentCaptor.forClass(String.class);
    var desc = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant).describe(any(), title.capture(), desc.capture(), any());
    // A PR with no title/body yet degrades to empty context rather than passing "null" through.
    assertEquals("", title.getValue());
    assertEquals("", desc.getValue());
  }

  @Test
  void stillDescribesWhenInstructionsResolutionFails() {
    prWithFiles(foo());
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("github down"));
    describeReturns("ok");

    String body = generate();

    assertNotNull(body);
    var instructions = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant).describe(any(), any(), any(), instructions.capture());
    // A failed instructions lookup degrades to no instructions, not a crash. escape("") -> "".
    assertEquals("", instructions.getValue());
  }

  // ---------------------------------------------------------------------------------------------
  // Token-budgeted batching (#457): /describe plans batches over the reviewable FILE LIST, so a
  // diff longer than max-diff-lines no longer costs whole files their coverage.
  // ---------------------------------------------------------------------------------------------

  @Test
  void describesFilesThatTheLineCapWouldHaveDroppedEntirely() {
    // The whole point of the change. With a line cap this small the rendered diff string keeps only
    // the first file, so the pre-batching implementation wrote the PR description from a diff that
    // never mentioned src/Other.java. Batching sizes by tokens over the file list instead.
    var lineCapped = new ReviewDiffFormatter(List.of(), 4);
    budgetWithDiffRoom(4000);
    prWithFiles(foo(), otherFile());
    describeReturns("### Suggested title\n`x`");

    String body = generatorWith(lineCapped).generate("owner", "repo", 7, "main", 12345L, AUTH);

    assertNotNull(body);
    var sent = String.join("\n", diffsSentToAssistant());
    assertTrue(sent.contains("src/Other.java"), sent);
    assertTrue(sent.contains("while (retries < 3) { call(); }"), sent);
  }

  @Test
  void synthesizesOneDescriptionFromThePerBatchPartials() {
    // Two batches produce two partial descriptions. Concatenating them would repeat the overview
    // and read as two pull requests, so the reduce step is a real synthesis call and its answer is
    // what gets posted.
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    when(describeAssistant.describe(any(), any(), any(), any()))
        .thenAnswer(
            call ->
                call.<String>getArgument(0).contains("src/Other.java")
                    ? "### Suggested title\n`retry work`"
                    : "### Suggested title\n`stream work`");
    when(describeAssistant.synthesize(any(), any(), any(), any()))
        .thenReturn("### Suggested title\n`feat: stream and retry`");

    String body = generate();

    assertNotNull(body);
    assertTrue(diffsSentToAssistant().size() > 1, "expected more than one batch call");
    assertTrue(body.contains("feat: stream and retry"), body);
    // The raw partials must not be pasted into the comment alongside the synthesis.
    assertFalse(body.contains("`retry work`"), body);
    assertFalse(body.contains("`stream work`"), body);
    // Both partials reached the synthesis call, fenced as untrusted data.
    var partials = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant).synthesize(partials.capture(), any(), any(), any());
    assertTrue(partials.getValue().contains(PromptTemplateEscaper.fencePrefix()));
    assertTrue(partials.getValue().contains("`retry work`"), partials.getValue());
    assertTrue(partials.getValue().contains("`stream work`"), partials.getValue());
  }

  @Test
  void spendsNoSynthesisCallOnASingleBatch() {
    prWithFiles(foo());
    describeReturns("### Suggested title\n`x`");

    String body = generate();

    assertNotNull(body);
    // One partial already is the description of the whole PR; the reserved call stays unspent.
    verify(describeAssistant, never()).synthesize(any(), any(), any(), any());
  }

  @Test
  void reservesOneOfTheAiCallsForTheSynthesis() {
    // max-ai-calls bounds the whole run, synthesis included: with an allowance of 3 the command may
    // spend at most 2 batch calls, or the reduce step would push the run over the operator's cap.
    when(reviewConfig.maxAiCalls()).thenReturn(3);
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile(), thirdFile());
    describeReturns("### Suggested title\n`x`");
    when(describeAssistant.synthesize(any(), any(), any(), any())).thenReturn("### merged");

    generate();

    verify(describeAssistant, times(2)).describe(any(), any(), any(), any());
    verify(describeAssistant, times(1)).synthesize(any(), any(), any(), any());
  }

  @Test
  void namesTheFilesLeftUncoveredWhenTheBatchBudgetRunsOut() {
    // With batching, max-ai-calls — not max-diff-lines — is what bounds coverage on a huge PR.
    // Describing only the first N batches and saying nothing would be the same class of defect as
    // the line cap this replaced, just relocated, so the files that never got a batch are named.
    when(reviewConfig.maxAiCalls()).thenReturn(2);
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    describeReturns("### Suggested title\n`x`");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("partial coverage"), body);
    assertTrue(body.contains("src/Other.java"), body);
    assertTrue(body.contains("omitted entirely"), body);
  }

  @Test
  void sendsOneUncappedBatchWhenTokenBudgetingIsDisabled() {
    // max-input-tokens=0 turns budgeting off; that must mean one uncapped call over every file,
    // not a regression to the line-capped diff string.
    when(activeModel.maxInputTokens()).thenReturn(0);
    prWithFiles(foo(), otherFile());
    describeReturns("### Suggested title\n`x`");

    String body = generate();

    assertNotNull(body);
    var sent = diffsSentToAssistant();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).contains("src/Foo.java"), sent.get(0));
    assertTrue(sent.get(0).contains("src/Other.java"), sent.get(0));
    assertFalse(body.contains("partial coverage"), body);
  }

  @Test
  void leavesFilesTheRepositoryAskedTheBotToIgnoreOutOfScope() {
    // #449: per-repo ignore patterns are additive on top of the global set, and the filtered list
    // is what the batches are planned from — so an ignored file is never described.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(List.of("src/Other.java"), List.of(), ".github/thrillhousebot.yml"));
    prWithFiles(foo(), otherFile());
    describeReturns("### Suggested title\n`x`");

    generate();

    var sent = String.join("\n", diffsSentToAssistant());
    assertFalse(sent.contains("src/Other.java"), sent);
    assertTrue(sent.contains("src/Foo.java"), sent);
  }

  @Test
  void keepsThePartialsFromTheBatchesThatSucceededWhenOneBatchFails() {
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    when(describeAssistant.describe(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              if (call.<String>getArgument(0).contains("src/Other.java")) {
                throw new RuntimeException("model down");
              }
              return "### Suggested title\n`stream work`";
            });

    String body = generate();

    assertNotNull(body);
    // One partial survived, so it is the description — and the shortfall is disclosed rather than
    // presented as a complete pass.
    assertTrue(body.contains("stream work"), body);
    assertTrue(body.contains("Partial pass"), body);
    assertTrue(body.contains("could not be analyzed"), body);
    verify(describeAssistant, never()).synthesize(any(), any(), any(), any());
  }

  @Test
  void returnsNullWhenTheSynthesisCallFails() {
    // A description stitched from concatenated partials would read badly, so a failed reduce posts
    // nothing rather than falling back to one.
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    describeReturns("### Suggested title\n`x`");
    when(describeAssistant.synthesize(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertNull(generate());
  }

  @Test
  void namesTheFilesWhenTheBudgetCouldNotCoverASingleOne() {
    // A budget too small for even a one-line clip of any file. The old line cap would still have
    // handed the model a stub and posted a description "of the PR"; going quiet instead would hide
    // a misconfigured budget. Neither: say nothing was covered, and name what was not.
    // A budget the shared prompt overhead alone exhausts: the planner floors the diff budget at
    // one token, and no clip of any file fits that.
    when(activeModel.maxInputTokens()).thenReturn(10);
    prWithFiles(foo(), otherFile());

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(PrDescriptionGenerator.NOT_COVERED), body);
    assertTrue(body.contains("src/Foo.java"), body);
    assertTrue(body.contains("src/Other.java"), body);
    assertTrue(body.contains("partial coverage"), body);
    verifyNoInteractions(describeAssistant);
  }

  @Test
  void staysSilentWhenEveryChangedFileIsOutOfScope() {
    // The other half of the empty-plan branch: nothing was covered *and* nothing was omitted,
    // because the repository ignores every changed file. That is genuinely nothing to describe, so
    // the command posts nothing — announcing an uncoverable budget here would be a false alarm
    // about a budget that was never the problem.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(
                List.of("src/Foo.java", "src/Other.java"),
                List.of(),
                ".github/thrillhousebot.yml"));
    prWithFiles(foo(), otherFile());

    assertNull(generate());
    verifyNoInteractions(describeAssistant);
  }

  /** A third file, so a max-ai-calls of 3 can be shown to buy only two batches. */
  private static FileDiff thirdFile() {
    return new FileDiff(
        "src/Third.java",
        "modified",
        3,
        0,
        3,
        """
        @@ -0,0 +1,3 @@
        +var cache = new HashMap<String, String>();
        +cache.put("k", "v");
        +return cache;""");
  }
}
