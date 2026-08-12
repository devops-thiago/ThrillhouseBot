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

import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiOk;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiTruncated;
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
import dev.thiagogonzaga.thrillhousebot.review.ai.ChangelogAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.ChangelogAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrSuggestionPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ChangelogEntryGeneratorTest {

  private static final String AUTH = "token gh-abc";

  /**
   * Filler lines appended to each fixture patch, on top of its three meaningful ones.
   *
   * <p>The batching tests below assert a <em>plan shape</em> — one file per batch, nothing clipped
   * — that the planner decides by comparing each file section's token estimate against the diff
   * budget. That budget is only approximate, and not because of the estimator: {@link
   * PromptTemplateEscaper#fence} mints a fresh CSPRNG token per call, so the shared-prompt overhead
   * {@link #budgetFor} sizes from one draw and the overhead the planner subtracts from another draw
   * differ by however much the two random tokens differ in BPE width — around 30 tokens, either
   * way. The effective diff budget therefore lands in a window that wide around the requested one.
   *
   * <p>So every margin here has to be wider than that window. With the original three-line patches
   * a section was ~50 tokens against a 40-token request, leaving a ~10-token margin the swing
   * cleared often enough to flip the plan about once in 1500 runs — #586, seen once in CI and never
   * locally. Padding the sections to a few hundred tokens (and {@link #ROOM_FOR_ONE_FILE} with
   * them) makes the fence swing a rounding error rather than the deciding term. {@link
   * #batchingFixturesLeaveMarginWiderThanTheFenceJitter} pins the margins so shrinking either one
   * fails loudly instead of flaking.
   */
  private static final int PAD_LINES = 30;

  /**
   * Upper bound on how far two {@link PromptTemplateEscaper#fence} draws can differ in BPE width. A
   * fence wraps its content in two identical lines whose only variable part is a 32-character hex
   * token, so 64 characters vary across the pair — and no BPE token covers fewer than one
   * character, so no two draws can differ by more than 64 tokens. Every batching margin below is
   * required to exceed this.
   */
  private static final int FENCE_JITTER_TOKENS = 64;

  /** Added lines per fixture file: its three meaningful ones plus {@link #PAD_LINES} of filler. */
  private static final int ADDED_LINES = 3 + PAD_LINES;

  /**
   * Diff room requested for the tests that need each fixture file in a batch of its own: above any
   * one section (so nothing is clipped) and below two of them (so they cannot share a bin), with
   * margins far wider than the fence swing described on {@link #PAD_LINES}.
   */
  private static final int ROOM_FOR_ONE_FILE = 400;

  /** Diff room that comfortably fits every fixture file in a single batch. */
  private static final int ROOM_FOR_EVERY_FILE = 4000;

  private static final String PATCH =
      patchOf("var in = Files.newInputStream(path);", "int total = 0;", "total += items.size();");

  /** A second file, so the line cap has something to drop and the planner something to batch. */
  private static final String OTHER_PATCH =
      patchOf("int retries = 0;", "while (retries < 3) { call(); }", "log.info(\"done\");");

  /** A unified-diff patch adding {@code lines}, padded out to {@link #ADDED_LINES} added lines. */
  private static String patchOf(String... lines) {
    var patch = new StringBuilder("@@ -0,0 +1,").append(ADDED_LINES).append(" @@");
    for (var line : lines) {
      patch.append("\n+").append(line);
    }
    for (var i = 0; i < PAD_LINES; i++) {
      patch.append("\n+int filler").append(i).append(" = ").append(i).append(";");
    }
    return patch.toString();
  }

  @Mock private GitHubPullRequestClient prClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private RepoSettingsResolver repoSettingsResolver;
  @Mock private ActiveModelSettings activeModel;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;
  @Mock private ChangelogAssistant changelogAssistant;

  private final ReviewDiffFormatter diffFormatter = new ReviewDiffFormatter(List.of(), 5000);

  private ChangelogEntryGenerator generator;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    lenient().when(config.review()).thenReturn(reviewConfig);
    lenient().when(reviewConfig.maxAiCalls()).thenReturn(6);
    // Default: budgeting on with ample room, so a normal PR is a single batch and needs no merge
    // call — the same shape the command had before batching.
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

  private ChangelogEntryGenerator generatorWith(ReviewDiffFormatter formatter) {
    return new ChangelogEntryGenerator(
        prClient,
        formatter,
        instructionsResolver,
        repoSettingsResolver,
        new DiffBudgetPlanner(formatter, new TokenCounter(), config, activeModel),
        activeModel,
        config,
        changelogAssistant);
  }

  private static FileDiff foo() {
    return new FileDiff("src/Foo.java", "modified", ADDED_LINES, 0, ADDED_LINES, PATCH);
  }

  private static FileDiff otherFile() {
    return new FileDiff("src/Other.java", "modified", ADDED_LINES, 0, ADDED_LINES, OTHER_PATCH);
  }

  private void prWithFiles(FileDiff... files) {
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", null, null));
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(files));
  }

  private void prWithFilesAndDetails(PullRequestDetails details) {
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(details);
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(foo()));
  }

  private void draftReturns(String entry) {
    when(changelogAssistant.draft(any(), any(), any(), any(), any())).thenReturn(aiOk(entry));
  }

  private String generate() {
    return generator.generate("owner", "repo", 7, "main", 12345L, AUTH);
  }

  /** The diff text each batch call actually received, in call order. */
  private List<String> diffsSentToAssistant() {
    var diff = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant, atLeastOnce()).draft(diff.capture(), any(), any(), any(), any());
    return diff.getAllValues();
  }

  /**
   * A per-call input budget of about the shared prompt overhead plus {@code diffTokens}. Derived
   * from {@code /changelog}'s own prompts rather than hardcoded, so editing a prompt cannot
   * silently turn these tests into no-ops by making every file overflow — and so a regression that
   * sized the overhead from another command's prompts would show up here.
   *
   * <p><em>About</em>, not exactly: the fence line this counts is drawn from a CSPRNG here and
   * again inside the planner, and the two draws differ in BPE width, so the diff room the planner
   * actually ends up with is {@code diffTokens} give or take ~30 tokens. Callers must leave margins
   * wider than that — see {@link #PAD_LINES}.
   */
  private static int budgetFor(int diffTokens) {
    var overhead =
        new TokenCounter()
            .estimateTokens(
                ChangelogAssistantPrompts.systemPrompt()
                    + PrSuggestionPrompts.userPrompt()
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
  void wrapsTheAssistantEntryInAComment() {
    prWithFiles(foo());
    draftReturns("### Added\n- **Thing**: does x (#7)\n");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(ChangelogEntryGenerator.HEADER));
    assertTrue(body.contains("### Added"));
    assertTrue(body.endsWith(ChangelogEntryGenerator.FOOTER));
  }

  @Test
  void appendsNoDisclosureWhenTheBudgetCoveredEveryFile() {
    prWithFiles(foo(), otherFile());
    draftReturns("### Added\n- x (#7)");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.endsWith(ChangelogEntryGenerator.FOOTER));
    assertFalse(body.contains("were omitted"), body);
  }

  @Test
  void passesThePrNumberToTheAssistant() {
    prWithFiles(foo());
    draftReturns("### Fixed\n- x (#7)");

    generate();

    var prNumber = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), prNumber.capture(), any(), any(), any());
    assertEquals("7", prNumber.getValue());
  }

  @Test
  void returnsNullWhenThereIsNoDiff() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of());

    assertNull(generate());
    verifyNoInteractions(changelogAssistant);
  }

  @ParameterizedTest
  @ValueSource(strings = {"NONE", " none ", "**NONE**", "`NONE`", "NONE.", "> NONE", "   "})
  void returnsNullWhenAssistantProducesNothingUsable(String draft) {
    prWithFiles(foo());
    draftReturns(draft);

    assertNull(generate());
  }

  @Test
  void postsRealEntryThatMerelyMentionsNone() {
    prWithFiles(foo());
    draftReturns("### Fixed\n- Guard against a none-check regression (#7)");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("none-check regression"));
  }

  @Test
  void postsDecorationOnlyReplyThatStripsToAnEmptyCore() {
    prWithFiles(foo());
    draftReturns("---");

    assertEquals(
        ChangelogEntryGenerator.HEADER + "---" + ChangelogEntryGenerator.FOOTER, generate());
  }

  @Test
  void returnsNullWhenAssistantThrows() {
    prWithFiles(foo());
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertNull(generate());
  }

  @Test
  void postsNothingWhenTheAssistantResponseIsCutShortAtTheLengthCap() {
    // #497: a length stop used to reach the parser and be reported as malformed JSON. It is now a
    // named failure, so the command declines to post rather than posting a half-built entry.
    prWithFiles(foo());
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn(aiTruncated("### Added\n- Streams are clo"));

    assertNull(generate());
  }

  @Test
  void stillDraftsWhenPrDetailsFetchFails() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(foo()));
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("404"));
    draftReturns("### Added\n- x (#7)");

    String body = generate();

    assertNotNull(body);
    var title = ArgumentCaptor.forClass(String.class);
    var desc = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), any(), title.capture(), desc.capture(), any());
    assertEquals("", title.getValue());
    assertEquals("", desc.getValue());
  }

  @Test
  void fencesTheBatchDiffBeforeCallingTheAssistant() {
    prWithFiles(
        new FileDiff("src/Foo.java", "modified", 1, 0, 1, "@@ -0,0 +1 @@\n+<<<DIFF_END>>>"));
    draftReturns("### Added\n- x (#7)");

    generate();

    var diffArg = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(diffArg.capture(), any(), any(), any(), any());
    assertTrue(diffArg.getValue().contains(PromptTemplateEscaper.fencePrefix()));
    assertTrue(diffArg.getValue().contains("<<<DIFF_END>>>"));
  }

  @Test
  void returnsNullWhenDiffFetchFails() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("boom"));

    assertNull(generate());
    verifyNoInteractions(changelogAssistant);
  }

  @Test
  void returnsNullWhenAssistantReturnsNull() {
    prWithFiles(foo());
    draftReturns(null);

    assertNull(generate());
  }

  @Test
  void treatsNullTitleAndBodyAsEmptyContext() {
    prWithFilesAndDetails(new PullRequestDetails(null, null, null, null));
    draftReturns("### Added\n- x (#7)");

    generate();

    var title = ArgumentCaptor.forClass(String.class);
    var desc = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), any(), title.capture(), desc.capture(), any());
    assertEquals("", title.getValue());
    assertEquals("", desc.getValue());
  }

  @Test
  void stillDraftsWhenInstructionsResolutionFails() {
    prWithFiles(foo());
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("github down"));
    draftReturns("### Added\n- x (#7)");

    String body = generate();

    assertNotNull(body);
    var instructions = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), any(), any(), any(), instructions.capture());
    assertEquals("", instructions.getValue());
  }

  // ---------------------------------------------------------------------------------------------
  // Token-budgeted batching (#457): /changelog plans batches over the reviewable FILE LIST, so a
  // diff longer than max-diff-lines no longer costs whole files their coverage.
  // ---------------------------------------------------------------------------------------------

  @Test
  void batchingFixturesLeaveMarginWiderThanTheFenceJitter() {
    // #586: every batching test below asserts a plan shape the planner decides by comparing a file
    // section's tokens against the diff room, and that room is only accurate to a fence draw (see
    // PAD_LINES). Pin the three margins that shape rests on here, so shrinking a fixture or a room
    // constant fails on this one test rather than as a rare flake spread across the whole class.
    var tokenCounter = new TokenCounter();
    var names = ReviewDiffFormatter.namesOf(List.of(foo(), otherFile()));
    var biggestSection = 0;
    var bothSections = 0;
    for (var file : List.of(foo(), otherFile())) {
      var tokens = tokenCounter.estimateTokens(diffFormatter.formatFileSection(file, names));
      biggestSection = Math.max(biggestSection, tokens);
      bothSections += tokens;
    }

    // Room for one file: above the larger section, so neither file is ever clipped…
    assertTrue(
        ROOM_FOR_ONE_FILE - biggestSection > FENCE_JITTER_TOKENS,
        "biggest section " + biggestSection + " too close to room " + ROOM_FOR_ONE_FILE);
    // …and below both together, so the two files never share a batch.
    assertTrue(
        bothSections - ROOM_FOR_ONE_FILE > FENCE_JITTER_TOKENS,
        "both sections " + bothSections + " too close to room " + ROOM_FOR_ONE_FILE);
    // Room for every file: above both together, so one batch always covers the whole PR.
    assertTrue(
        ROOM_FOR_EVERY_FILE - bothSections > FENCE_JITTER_TOKENS,
        "both sections " + bothSections + " too close to room " + ROOM_FOR_EVERY_FILE);
  }

  @Test
  void draftsFromFilesThatTheLineCapWouldHaveDroppedEntirely() {
    // The whole point of the change. With a line cap this small the rendered diff string keeps only
    // the first file, so the pre-batching implementation drafted the entry from a diff that never
    // mentioned src/Other.java. Batching sizes by tokens over the file list instead.
    var lineCapped = new ReviewDiffFormatter(List.of(), 4);
    budgetWithDiffRoom(ROOM_FOR_EVERY_FILE);
    prWithFiles(foo(), otherFile());
    draftReturns("### Added\n- x (#7)");

    String body = generatorWith(lineCapped).generate("owner", "repo", 7, "main", 12345L, AUTH);

    assertNotNull(body);
    var sent = String.join("\n", diffsSentToAssistant());
    assertTrue(sent.contains("src/Other.java"), sent);
    assertTrue(sent.contains("while (retries < 3) { call(); }"), sent);
  }

  @Test
  void mergesThePerBatchCandidatesIntoOneEntry() {
    // Two batches produce two candidate entries. The command must post exactly one entry, and two
    // candidates that describe the same change in different words can only be collapsed by the
    // merge call — so its answer is what gets posted, not the concatenation.
    budgetWithDiffRoom(ROOM_FOR_ONE_FILE);
    prWithFiles(foo(), otherFile());
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenAnswer(
            call ->
                aiOk(
                    call.<String>getArgument(0).contains("src/Other.java")
                        ? "### Added\n- Retries are bounded (#7)"
                        : "### Added\n- Streams are closed (#7)"));
    when(changelogAssistant.merge(any(), any(), any(), any(), any()))
        .thenReturn(aiOk("### Added\n- **Reliability**: bounded retries and closed streams (#7)"));

    String body = generate();

    assertNotNull(body);
    assertTrue(diffsSentToAssistant().size() > 1, "expected more than one batch call");
    assertTrue(body.contains("bounded retries and closed streams"), body);
    // Exactly one entry: the raw candidates must not be pasted in alongside the merge.
    assertFalse(body.contains("- Retries are bounded (#7)"), body);
    assertFalse(body.contains("- Streams are closed (#7)"), body);
    assertEquals(1, body.split("### Added", -1).length - 1, body);
    // Both candidates reached the merge call, fenced as untrusted data, with the PR number.
    var candidates = ArgumentCaptor.forClass(String.class);
    var prNumber = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).merge(candidates.capture(), prNumber.capture(), any(), any(), any());
    assertEquals("7", prNumber.getValue());
    assertTrue(candidates.getValue().contains(PromptTemplateEscaper.fencePrefix()));
    assertTrue(candidates.getValue().contains("Retries are bounded"), candidates.getValue());
    assertTrue(candidates.getValue().contains("Streams are closed"), candidates.getValue());
  }

  @Test
  void spendsNoMergeCallOnASingleCandidate() {
    prWithFiles(foo());
    draftReturns("### Added\n- x (#7)");

    assertNotNull(generate());
    // One candidate already is the entry; the reserved call stays unspent.
    verify(changelogAssistant, never()).merge(any(), any(), any(), any(), any());
  }

  @Test
  void spendsNoMergeCallWhenOnlyOneBatchFoundAnythingWorthReporting() {
    // A batch that declines with NONE contributes no candidate, so a PR whose changelog-worthy
    // change sits in one batch still posts that batch's entry unmerged.
    budgetWithDiffRoom(ROOM_FOR_ONE_FILE);
    prWithFiles(foo(), otherFile());
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenAnswer(
            call ->
                aiOk(
                    call.<String>getArgument(0).contains("src/Other.java")
                        ? "NONE"
                        : "### Added\n- Streams are closed (#7)"));

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("Streams are closed"), body);
    verify(changelogAssistant, never()).merge(any(), any(), any(), any(), any());
  }

  @Test
  void returnsNullWhenEveryBatchDeclines() {
    budgetWithDiffRoom(ROOM_FOR_ONE_FILE);
    prWithFiles(foo(), otherFile());
    draftReturns("NONE");

    assertNull(generate());
    verify(changelogAssistant, never()).merge(any(), any(), any(), any(), any());
  }

  @Test
  void returnsNullWhenTheMergeDeclines() {
    // The merge may still conclude that nothing is worth an entry; a literal NONE must never be
    // posted as if it were the changelog text.
    budgetWithDiffRoom(ROOM_FOR_ONE_FILE);
    prWithFiles(foo(), otherFile());
    draftReturns("### Added\n- x (#7)");
    when(changelogAssistant.merge(any(), any(), any(), any(), any())).thenReturn(aiOk("**NONE**"));

    assertNull(generate());
  }

  @Test
  void reservesOneOfTheAiCallsForTheMerge() {
    // max-ai-calls bounds the whole run, merge included: with an allowance of 3 the command may
    // spend at most 2 batch calls, or the reduce step would push the run over the operator's cap.
    when(reviewConfig.maxAiCalls()).thenReturn(3);
    budgetWithDiffRoom(ROOM_FOR_ONE_FILE);
    prWithFiles(foo(), otherFile(), thirdFile());
    draftReturns("### Added\n- x (#7)");
    when(changelogAssistant.merge(any(), any(), any(), any(), any()))
        .thenReturn(aiOk("### Added\n- merged (#7)"));

    generate();

    verify(changelogAssistant, times(2)).draft(any(), any(), any(), any(), any());
    verify(changelogAssistant, times(1)).merge(any(), any(), any(), any(), any());
  }

  @Test
  void namesTheFilesLeftUncoveredWhenTheBatchBudgetRunsOut() {
    // With batching, max-ai-calls — not max-diff-lines — is what bounds coverage on a huge PR.
    // Drafting from only the first N batches and saying nothing would be the same class of defect
    // as the line cap this replaced, just relocated, so the uncovered files are named.
    when(reviewConfig.maxAiCalls()).thenReturn(2);
    budgetWithDiffRoom(ROOM_FOR_ONE_FILE);
    prWithFiles(foo(), otherFile());
    draftReturns("### Added\n- x (#7)");

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
    draftReturns("### Added\n- x (#7)");

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
    // is what the batches are planned from — so an ignored file never reaches the entry.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(List.of("src/Other.java"), List.of(), ".github/thrillhousebot.yml"));
    prWithFiles(foo(), otherFile());
    draftReturns("### Added\n- x (#7)");

    generate();

    var sent = String.join("\n", diffsSentToAssistant());
    assertFalse(sent.contains("src/Other.java"), sent);
    assertTrue(sent.contains("src/Foo.java"), sent);
  }

  @Test
  void discloseTheShortfallWhenOneBatchFails() {
    budgetWithDiffRoom(ROOM_FOR_ONE_FILE);
    prWithFiles(foo(), otherFile());
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              if (call.<String>getArgument(0).contains("src/Other.java")) {
                throw new RuntimeException("model down");
              }
              return aiOk("### Added\n- Streams are closed (#7)");
            });

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("Streams are closed"), body);
    assertTrue(body.contains("Partial pass"), body);
    assertTrue(body.contains("could not be analyzed"), body);
  }

  @Test
  void namesTheFilesWhenTheBudgetCouldNotCoverASingleOne() {
    // A budget too small for even a one-line clip of any file. The old line cap would still have
    // handed the model a stub and posted an entry "for the PR"; going quiet instead would hide a
    // misconfigured budget. Neither: say nothing was covered, and name what was not.
    // A budget the shared prompt overhead alone exhausts: the planner floors the diff budget at
    // one token, and no clip of any file fits that.
    when(activeModel.maxInputTokens()).thenReturn(10);
    prWithFiles(foo(), otherFile());

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(ChangelogEntryGenerator.NOT_COVERED), body);
    assertTrue(body.contains("src/Foo.java"), body);
    assertTrue(body.contains("src/Other.java"), body);
    assertTrue(body.contains("partial coverage"), body);
    verifyNoInteractions(changelogAssistant);
  }

  @Test
  void staysSilentWhenEveryChangedFileIsOutOfScope() {
    // The other half of the empty-plan branch: nothing was covered *and* nothing was omitted,
    // because the repository ignores every changed file. That is genuinely nothing to write an
    // entry about, so the command posts nothing — announcing an uncoverable budget here would be a
    // false alarm about a budget that was never the problem.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(
                List.of("src/Foo.java", "src/Other.java"),
                List.of(),
                ".github/thrillhousebot.yml"));
    prWithFiles(foo(), otherFile());

    assertNull(generate());
    verifyNoInteractions(changelogAssistant);
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
