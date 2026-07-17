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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver.ResolvedInstructions;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrDescribeAssistant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrDescriptionGeneratorTest {

  private static final String AUTH = "token gh-abc";

  @Mock private GitHubPullRequestClient prClient;
  @Mock private ReviewDiffFormatter diffFormatter;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private PrDescribeAssistant describeAssistant;

  @InjectMocks private PrDescriptionGenerator generator;

  @BeforeEach
  void setUp() {
    lenient()
        .when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(ResolvedInstructions.EMPTY);
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

  private String generate() {
    return generator.generate("owner", "repo", 7, "main", 12345L, AUTH);
  }

  @Test
  void wrapsTheAssistantSuggestionInAComment() {
    diffReturns("## Overview: 1 files (+1 -0)\n\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("old title", "old body", null, null));
    when(describeAssistant.describe(any(), any(), any(), any()))
        .thenReturn("### Suggested title\n`feat: do x`\n");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(PrDescriptionGenerator.HEADER));
    assertTrue(body.contains("### Suggested title"));
    assertTrue(body.endsWith(PrDescriptionGenerator.FOOTER));
  }

  @Test
  void appendsPartialCoverageDisclosureWhenTheDiffWasTruncated() {
    // The formatter dropped 48 files from the diff the suggestion is derived from, so the comment
    // must disclose that it covers only part of the PR — reusing the review path's wording.
    diffReturns("## Overview: 75 files (+9000 -0)\n\ndiff", 48);
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(describeAssistant.describe(any(), any(), any(), any()))
        .thenReturn("### Suggested title\n`x`");

    String body = generate();

    assertNotNull(body);
    assertEquals(
        ReviewResult.truncationDisclosure(48),
        body.substring(
            body.indexOf(PrDescriptionGenerator.FOOTER) + PrDescriptionGenerator.FOOTER.length()));
    assertTrue(body.contains("48 file(s) were omitted"), body);
    assertTrue(body.contains("partial coverage"), body);
    // The review-only "findings and verdict" framing must not leak onto a /describe suggestion.
    assertFalse(body.contains("findings and verdict"), body);
  }

  @Test
  void appendsNoDisclosureWhenNothingWasOmitted() {
    diffReturns("## Overview: 1 files (+1 -0)\n\ndiff", 0);
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(describeAssistant.describe(any(), any(), any(), any())).thenReturn("ok");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.endsWith(PrDescriptionGenerator.FOOTER));
    assertFalse(body.contains("were omitted"), body);
  }

  @Test
  void returnsNullWhenThereIsNoDiff() {
    diffReturns("(no changes detected)");

    assertNull(generate());
    verifyNoInteractions(describeAssistant);
  }

  @Test
  void returnsNullWhenAssistantProducesBlank() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(describeAssistant.describe(any(), any(), any(), any())).thenReturn("   ");

    assertNull(generate());
  }

  @Test
  void returnsNullWhenAssistantThrows() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(describeAssistant.describe(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertNull(generate());
  }

  @Test
  void stillDescribesWhenPrDetailsFetchFails() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("404"));
    when(describeAssistant.describe(any(), any(), any(), any()))
        .thenReturn("### Suggested title\n`x`");

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
  void fencesDiffBeforeCallingTheAssistant() {
    diffReturns("raw diff with <<<DIFF_END>>> marker");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(describeAssistant.describe(any(), any(), any(), any())).thenReturn("ok");

    generate();

    var diffArg = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant).describe(diffArg.capture(), any(), any(), any());
    // The diff is wrapped in an unguessable random fence and passed byte-exact, so PR content
    // (including the old diff markers) reaches the model verbatim and cannot forge the boundary.
    assertTrue(diffArg.getValue().contains(PromptTemplateEscaper.fencePrefix()));
    assertTrue(diffArg.getValue().contains("<<<DIFF_END>>> marker"));
  }

  @Test
  void returnsNullWhenDiffFetchFails() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("boom"));
    // SoftLoaders.files degrades the failed fetch to an empty list, which the formatter renders as
    // "(no changes detected)" — treated the same as no diff.
    when(diffFormatter.buildDiffStringWithStats(anyList()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("(no changes detected)", 0));

    // A failed diff fetch degrades to no suggestion, not a crash.
    assertNull(generate());
    verifyNoInteractions(describeAssistant);
  }

  @Test
  void returnsNullWhenDiffIsBlank() {
    diffReturns("   ");

    assertNull(generate());
    verifyNoInteractions(describeAssistant);
  }

  @Test
  void returnsNullWhenAssistantReturnsNull() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(describeAssistant.describe(any(), any(), any(), any())).thenReturn(null);

    assertNull(generate());
  }

  @Test
  void treatsNullTitleAndBodyAsEmptyContext() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails(null, null, null, null));
    when(describeAssistant.describe(any(), any(), any(), any())).thenReturn("ok");

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
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("github down"));
    when(describeAssistant.describe(any(), any(), any(), any())).thenReturn("ok");

    String body = generate();

    assertNotNull(body);
    var instructions = ArgumentCaptor.forClass(String.class);
    verify(describeAssistant).describe(any(), any(), any(), instructions.capture());
    // A failed instructions lookup degrades to no instructions, not a crash. escape("") -> "".
    assertEquals("", instructions.getValue());
  }
}
