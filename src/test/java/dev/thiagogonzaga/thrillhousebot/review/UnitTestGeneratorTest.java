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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver.ResolvedInstructions;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestAssistant;
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

  @Mock private GitHubPullRequestClient prClient;
  @Mock private ReviewDiffFormatter diffFormatter;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private ProjectStackResolver projectStackResolver;
  @Mock private UnitTestAssistant testAssistant;

  private UnitTestGenerator generator;

  @BeforeEach
  void setUp() {
    lenient()
        .when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(ResolvedInstructions.EMPTY);
    lenient().when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    generator =
        new UnitTestGenerator(
            prClient,
            diffFormatter,
            instructionsResolver,
            projectStackResolver,
            testAssistant,
            new UnitTestGenerationParser(new ObjectMapper()),
            new SuggestionFormatter());
  }

  private void diffReturns(String diff) {
    diffReturns(diff, 0);
  }

  private void diffReturns(String diff, int omittedFiles) {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(new FileDiff("Foo.java", "modified", 1, 0, 1, "@@ -1 +1 @@")));
    when(diffFormatter.buildDiffStringWithStats(anyList()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff(diff, omittedFiles));
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
    diffReturns("## Overview: 1 files (+1 -0)\n\ndiff");
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
    diffReturns("## Overview\ndiff");
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
    diffReturns("## Overview\ndiff");
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
    diffReturns("## Overview\ndiff");
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
    diffReturns("## Overview\ndiff");
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn("{\"tests\":[],\"notes\":\"Only formatting changed.\"}");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.NOTHING_TO_TEST), body);
    assertTrue(body.contains("Only formatting changed."), body);
  }

  @Test
  void skipsAProposalWithNoUsablePathOrCode() {
    diffReturns("## Overview\ndiff");
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
  void appendsPartialCoverageDisclosureWhenTheDiffWasTruncated() {
    diffReturns("## Overview: 75 files (+9000 -0)\n\ndiff", 48);
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    String body = generate();

    assertNotNull(body);
    assertEquals(
        ReviewResult.truncationDisclosure(48),
        body.substring(body.indexOf(UnitTestGenerator.FOOTER) + UnitTestGenerator.FOOTER.length()));
    assertTrue(body.contains("48 file(s) were omitted"), body);
    assertTrue(body.contains("partial coverage"), body);
    assertFalse(body.contains("findings and verdict"), body);
  }

  @Test
  void disclosesPartialCoverageEvenWhenNoTestsWereProposed() {
    diffReturns("## Overview: 75 files (+9000 -0)\n\ndiff", 12);
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn("{\"tests\":[]}");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(UnitTestGenerator.NOTHING_TO_TEST), body);
    assertTrue(body.contains("12 file(s) were omitted"), body);
  }

  @Test
  void appendsNoDisclosureWhenNothingWasOmitted() {
    diffReturns("## Overview\ndiff", 0);
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    String body = generate();

    assertNotNull(body);
    assertTrue(body.endsWith(UnitTestGenerator.FOOTER), body);
    assertFalse(body.contains("were omitted"), body);
  }

  @Test
  void returnsNullWhenThereIsNoDiff() {
    diffReturns("(no changes detected)");

    assertNull(generate());
    verifyNoInteractions(testAssistant);
  }

  @Test
  void returnsNullWhenTheAssistantThrows() {
    diffReturns("## Overview\ndiff");
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertNull(generate());
  }

  @Test
  void returnsNullWhenTheResponseIsNotUsableJson() {
    diffReturns("## Overview\ndiff");
    prDetails();
    when(testAssistant.generate(any(), any(), any(), any()))
        .thenReturn("Sure! Here are some tests.");

    assertNull(generate());
  }

  @Test
  void fencesTheDiffAndPassesTheProjectStackToTheAssistant() {
    diffReturns("raw diff with <<<DIFF_END>>> marker");
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
    assertTrue(diff.getValue().contains("<<<DIFF_END>>> marker"));
    assertTrue(prContext.getValue().contains("title"));
    assertEquals("pom.xml: junit", stack.getValue());
  }

  @Test
  void stillGeneratesWhenTheProjectStackCannotBeResolved() {
    diffReturns("## Overview\ndiff");
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
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("404"));
    when(testAssistant.generate(any(), any(), any(), any())).thenReturn(ONE_TEST);

    assertNotNull(generate());
    var prContext = ArgumentCaptor.forClass(String.class);
    verify(testAssistant).generate(any(), prContext.capture(), any(), any());
    assertEquals("", prContext.getValue());
  }
}
