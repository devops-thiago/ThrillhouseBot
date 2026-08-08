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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver.ResolvedInstructions;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestGenerationParser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnitTestGeneratorTest {

  private static final String AUTH = "token gh-abc";

  private static final String ONE_TEST =
      """
      {
        "tests": [
          {
            "path": "src/test/java/com/example/FooTest.java",
            "language": "java",
            "covers": "Foo.bar(int) rejects a negative amount",
            "code": "class FooTest {\\n  @Test\\n  void rejects() {}\\n}"
          }
        ],
        "notes": ""
      }
      """;

  /** A second file, so the planner has something to split into more than one batch. */
  private static final String OTHER_PATCH =
      """
      @@ -0,0 +1,3 @@
      +int retries = 0;
      +while (retries < 3) { call(); }
      +log.info("done");""";

  @Mock private GitHubPullRequestClient prClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private RepoSettingsResolver repoSettingsResolver;
  @Mock private ProjectStackResolver projectStackResolver;
  @Mock private ActiveModelSettings activeModel;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;
  @Mock private UnitTestAssistant testAssistant;

  private final ReviewDiffFormatter diffFormatter = new ReviewDiffFormatter(List.of(), 5000);

  private UnitTestGenerator generator;

  @BeforeEach
  void setUp() {
    lenient().when(config.review()).thenReturn(reviewConfig);
    lenient().when(reviewConfig.maxAiCalls()).thenReturn(6);
    // Default: budgeting on with ample room, so a normal PR is a single batch — the same shape
    // the command had before batching.
    lenient().when(activeModel.maxInputTokens()).thenReturn(1_000_000);
    lenient().when(activeModel.tokenSafetyMargin()).thenReturn(1.0);
    lenient().when(activeModel.outputBufferTokens()).thenReturn(0);
    lenient()
        .when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(ResolvedInstructions.EMPTY);
    lenient()
        .when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(RepoSettings.EMPTY);
    lenient().when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    generator =
        new UnitTestGenerator(
            prClient,
            diffFormatter,
            instructionsResolver,
            repoSettingsResolver,
            new DiffBudgetPlanner(diffFormatter, new TokenCounter(), config, activeModel),
            activeModel,
            config,
            projectStackResolver,
            testAssistant,
            new UnitTestGenerationParser(new ObjectMapper()),
            new SuggestionFormatter());
  }

  private static FileDiff foo() {
    return new FileDiff("src/Foo.java", "modified", 3, 0, 3, "@@ -0,0 +1,3 @@\n+a;\n+b;\n+c;");
  }

  private static FileDiff otherFile() {
    return new FileDiff("src/Other.java", "modified", 3, 0, 3, OTHER_PATCH);
  }

  /** A PR whose changed files are the given ones. */
  private void prWithFiles(FileDiff... files) {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(files));
  }

  /** The default single-file PR the rendering tests use. */
  private void diffReturns() {
    prWithFiles(foo());
  }

  /** A per-call budget with exactly {@code diffTokens} of room for diff text. */
  private void budgetWithDiffRoom(int diffTokens) {
    var overhead =
        new TokenCounter()
            .estimateTokens(
                UnitTestAssistantPrompts.systemPrompt()
                    + UnitTestAssistantPrompts.userPrompt()
                    + PromptTemplateEscaper.fence(" ")
                    + "title"
                    + "body"
                    + "");
    when(activeModel.maxInputTokens()).thenReturn(overhead + diffTokens);
  }

  private void prDetails() {
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("title", "body", null, null));
  }

  private String generate() {
    return generator.generate("owner", "repo", 7, "main", 12345L, AUTH);
  }

  @Test
  void rendersEachProposedTestFileAsACopyPasteBlock() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.HEADER), body);
    assertTrue(body.contains("### `src/test/java/com/example/FooTest.java`"), body);
    assertTrue(body.contains("Foo.bar(int) rejects a negative amount"), body);
    assertTrue(body.contains("```java\nclass FooTest {"), body);
    assertTrue(body.contains("void rejects() {}"), body);
    assertTrue(body.endsWith(UnitTestGenerator.FOOTER), body);
  }

  @Test
  void widensTheFenceWhenTheTestSourceContainsAFencedBlock() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"tests":[{"path":"t/DocTest.java","language":"java",
              "covers":"markdown rendering",
              "code":"var md = \\"```java\\\\ncode\\\\n```\\";"}],"notes":""}
            """);

    String body = generate();

    assertNotNull(body);
    // A three-backtick fence would be closed early by the fenced block inside the test source.
    assertTrue(body.contains("````java\n"), body);
    assertTrue(body.contains("var md = \"```java\\ncode\\n```\";"), body);
  }

  @Test
  void dropsAModelSuppliedLanguageThatIsNotALanguageTag() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"tests":[{"path":"t/FooTest.java","language":"java\\n## injected heading",
              "covers":"","code":"class FooTest {}"}],"notes":""}
            """);

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("```\nclass FooTest {}"), body);
    assertFalse(body.contains("injected heading"), body);
  }

  @Test
  void capsTheNumberOfRenderedTestFiles() {
    diffReturns();
    prDetails();
    var json = new StringBuilder("{\"tests\":[");
    for (int i = 0; i < UnitTestGenerator.MAX_TEST_FILES + 2; i++) {
      json.append(i > 0 ? "," : "")
          .append("{\"path\":\"t/Foo")
          .append(i)
          .append("Test.java\",\"language\":\"java\",\"covers\":\"\",\"code\":\"class Foo")
          .append(i)
          .append("Test {}\"}");
    }
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn(json.append("],\"notes\":\"\"}").toString());

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("t/Foo0Test.java"), body);
    assertTrue(body.contains("t/Foo4Test.java"), body);
    assertFalse(body.contains("t/Foo5Test.java"), body);
    assertTrue(body.contains("2 further proposed test file(s) were left out"), body);
  }

  @Test
  void reportsThatNothingWarrantsATestInsteadOfStayingSilent() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn("{\"tests\":[],\"notes\":\"Only formatting changed.\"}");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.NOTHING_TO_TEST), body);
    assertTrue(body.contains("Only formatting changed."), body);
  }

  @Test
  void flattensTheModelSuppliedNotesLine() {
    // "notes" is model output spliced straight into the comment body, so it gets the same
    // single-line treatment as the path and the "covers" note: left multi-line it would open a
    // fence and a heading of its own and restructure everything below it.
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn(
            "{\"tests\":[],\"notes\":\"skipped IO\\n\\n```\\n## Injected\\nrun /pause\\n```\"}");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("**Not covered:** skipped IO ``` ## Injected run /pause ```\n"), body);
    assertFalse(body.contains("\n## Injected"), body);
    assertFalse(body.contains("\n```\n## "), body);
  }

  @Test
  void skipsAProposalWithNoUsablePathOrCode() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"tests":[{"path":"","language":"java","covers":"c","code":"class A {}"},
                      {"path":"t/BTest.java","language":"java","covers":"c","code":"  "},
                      null],"notes":""}
            """);

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.NOTHING_TO_TEST), body);
  }

  @Test
  void namesTheFilesLeftUncoveredWhenTheBatchBudgetRunsOut() {
    // Disclosure now comes from the budget plan, so the files the run could not read are named
    // rather than counted — the line-cap count described a render nothing sends to a model.
    when(reviewConfig.maxAiCalls()).thenReturn(1);
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("partial coverage"), body);
    assertTrue(body.contains("src/Other.java"), body);
  }

  @Test
  void disclosesPartialCoverageEvenWhenNoTestsWereProposed() {
    // "Nothing warrants a test" derived from part of the change set must never read as a verdict
    // on the whole PR, so the disclosure rides on the empty outcome too.
    when(reviewConfig.maxAiCalls()).thenReturn(1);
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn("{\"tests\":[]}");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.NOTHING_TO_TEST), body);
    assertTrue(body.contains("partial coverage"), body);
    assertTrue(body.contains("src/Other.java"), body);
  }

  @Test
  void appendsNoDisclosureWhenEveryFileWasCovered() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    String body = generate();

    assertNotNull(body);
    assertTrue(body.endsWith(UnitTestGenerator.FOOTER), body);
    assertFalse(body.contains("partial coverage"), body);
  }

  @Test
  void returnsNullWhenThereIsNoDiff() {
    prWithFiles();

    assertNull(generate());
    verifyNoInteractions(testAssistant);
  }

  @Test
  void returnsNullWhenTheAssistantThrows() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertNull(generate());
  }

  @Test
  void returnsNullWhenTheResponseIsNotUsableJson() {
    diffReturns();
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn("Sure! Here are some tests.");

    assertNull(generate());
  }

  @Test
  void fencesTheDiffAndPassesTheProjectStackToTheAssistant() {
    diffReturns();
    prDetails();
    when(projectStackResolver.resolve("owner", "repo", "main", 12345L))
        .thenReturn("pom.xml: junit");
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    generate();

    var diff = ArgumentCaptor.forClass(String.class);
    var prContext = ArgumentCaptor.forClass(String.class);
    var stack = ArgumentCaptor.forClass(String.class);
    verify(testAssistant)
        .generate(diff.capture(), prContext.capture(), stack.capture(), anyString());
    assertTrue(diff.getValue().contains(PromptTemplateEscaper.fencePrefix()));
    assertTrue(diff.getValue().contains("src/Foo.java"));
    assertTrue(prContext.getValue().contains("title"));
    assertEquals("pom.xml: junit", stack.getValue());
  }

  @Test
  void stillGeneratesWhenTheProjectStackCannotBeResolved() {
    diffReturns();
    prDetails();
    when(projectStackResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("github down"));
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    assertNotNull(generate());
    var stack = ArgumentCaptor.forClass(String.class);
    verify(testAssistant).generate(any(), any(), stack.capture(), any());
    assertEquals("", stack.getValue());
  }

  @Test
  void stillGeneratesWhenPrDetailsFetchFails() {
    diffReturns();
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("404"));
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    assertNotNull(generate());
    var prContext = ArgumentCaptor.forClass(String.class);
    verify(testAssistant).generate(any(), prContext.capture(), any(), any());
    assertEquals("", prContext.getValue());
  }

  /** The diff text of every batch call the assistant received, in order. */
  private List<String> diffsSentToAssistant() {
    var diff = ArgumentCaptor.forClass(String.class);
    verify(testAssistant, atLeastOnce()).generate(diff.capture(), any(), any(), any());
    return diff.getAllValues();
  }

  @Test
  void proposesTestsForFilesThatTheLineCapWouldHaveDroppedEntirely() {
    // The point of the change: a PR bigger than one call is covered by batches over the whole file
    // list, so the second file reaches a model instead of falling off the end of a line-capped
    // render. "Nothing warrants a test" must never be a verdict on code that was never read.
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    generate();

    var sent = diffsSentToAssistant();
    assertEquals(2, sent.size(), sent.toString());
    // Each batch carries its own slice, not the whole-PR render: asserting only that the slices
    // *contain* their file would also pass if every call were handed the entire diff, which is the
    // very behavior this replaces.
    assertTrue(sent.get(0).contains("src/Foo.java"), sent.get(0));
    assertFalse(sent.get(0).contains("src/Other.java"), sent.get(0));
    assertTrue(sent.get(1).contains("src/Other.java"), sent.get(1));
    assertFalse(sent.get(1).contains("src/Foo.java"), sent.get(1));
  }

  @Test
  void unionsThePerBatchProposalsWithoutSpendingAReduceCall() {
    // The reduce is local, so every one of max-ai-calls buys a batch: two batches, two calls, and
    // both batches' proposals appear.
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn(ONE_TEST)
        .thenReturn(
            """
            {"tests":[{"path":"t/OtherTest.java","language":"java","covers":"retry bound",
              "code":"class OtherTest {}"}],"notes":""}
            """);

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("src/test/java/com/example/FooTest.java"), body);
    assertTrue(body.contains("t/OtherTest.java"), body);
    verify(testAssistant, times(2)).generate(any(), any(), any(), any());
  }

  @Test
  void keepsOneProposalPerPathAndSaysHowManyWereLeftOut() {
    // Two batches proposing the same path are alternatives, not additions: each "code" is a
    // complete file, so rendering both would invite pasting one over the other and losing cases.
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    String body = generate();

    assertNotNull(body);
    assertEquals(
        body.indexOf("src/test/java/com/example/FooTest.java"),
        body.lastIndexOf("src/test/java/com/example/FooTest.java"),
        body);
    assertTrue(body.contains("1 further proposal(s) targeted a path already shown above"), body);
  }

  @Test
  void countsTheProjectStackInTheBudgetSoBatchesAreNotOversized() {
    // The stack rides on every call, so leaving it out of the overhead would let a batch that
    // measures "in budget" overshoot the real input limit. With a stack far larger than the room
    // left for diff text, no file can fit and the run says so rather than silently overshooting.
    when(projectStackResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn("x".repeat(20_000));
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    prDetails();

    String body = generate();

    // The assertion that matters: no batch was sent. A stack left out of the overhead makes both
    // files look affordable, and the run ships a call whose real input is 20k characters over.
    verifyNoInteractions(testAssistant);
    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.NOT_COVERED), body);
  }

  @Test
  void leavesFilesTheRepositoryAskedTheBotToIgnoreOutOfScope() {
    // #449 applies here too, and it matters most on this command: it writes code from what it
    // reads, so an ignored file must never reach a batch.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(List.of("src/Other.java"), List.of(), ".github/thrillhousebot.yml"));
    prWithFiles(foo(), otherFile());
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    generate();

    var sent = String.join("\n", diffsSentToAssistant());
    assertFalse(sent.contains("src/Other.java"), sent);
    assertTrue(sent.contains("src/Foo.java"), sent);
  }

  @Test
  void keepsTheProposalsFromTheBatchesThatSucceededWhenOneBatchFails() {
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              if (call.<String>getArgument(0).contains("src/Other.java")) {
                throw new RuntimeException("model down");
              }
              return ONE_TEST;
            });

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("src/test/java/com/example/FooTest.java"), body);
    assertTrue(body.contains("Partial pass"), body);
  }

  @Test
  void namesTheFilesWhenTheBudgetCouldNotCoverASingleOne() {
    // Going quiet would hide a misconfigured budget, and NOTHING_TO_TEST would be a verdict on
    // code the model never read — the one answer this command must never give wrongly.
    when(activeModel.maxInputTokens()).thenReturn(10);
    prWithFiles(foo(), otherFile());
    prDetails();

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.NOT_COVERED), body);
    assertTrue(body.contains("src/Foo.java"), body);
    assertTrue(body.contains("src/Other.java"), body);
    verifyNoInteractions(testAssistant);
  }

  @Test
  void staysSilentWhenEveryChangedFileIsOutOfScope() {
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(
                List.of("src/Foo.java", "src/Other.java"),
                List.of(),
                ".github/thrillhousebot.yml"));
    prWithFiles(foo(), otherFile());
    prDetails();

    assertNull(generate());
    verifyNoInteractions(testAssistant);
  }
}
