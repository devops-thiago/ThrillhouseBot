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
import dev.thiagogonzaga.thrillhousebot.review.ai.ChangelogAssistant;
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

  @Mock private GitHubPullRequestClient prClient;
  @Mock private ReviewDiffFormatter diffFormatter;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private ChangelogAssistant changelogAssistant;

  private ChangelogEntryGenerator generator;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    generator =
        new ChangelogEntryGenerator(
            prClient, diffFormatter, instructionsResolver, changelogAssistant);
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(ResolvedInstructions.EMPTY);
  }

  private void diffReturns(String diff) {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(new FileDiff("Foo.java", "modified", 1, 0, 1, "@@ -1 +1 @@")));
    when(diffFormatter.buildDiffString(anyList())).thenReturn(diff);
  }

  private String generate() {
    return generator.generate("owner", "repo", 7, "main", 12345L, AUTH);
  }

  @Test
  void wrapsTheAssistantEntryInAComment() {
    diffReturns("## Overview: 1 files (+1 -0)\n\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("title", "body", null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn("### Added\n- **Thing**: does x (#7)\n");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.startsWith(ChangelogEntryGenerator.HEADER));
    assertTrue(body.contains("### Added"));
    assertTrue(body.endsWith(ChangelogEntryGenerator.FOOTER));
  }

  @Test
  void passesThePrNumberToTheAssistant() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn("### Fixed\n- x (#7)");

    generate();

    var prNumber = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), prNumber.capture(), any(), any(), any());
    // The PR number is fed to the prompt so the model can stamp each bullet with `(#7)`.
    assertEquals("7", prNumber.getValue());
  }

  @Test
  void returnsNullWhenThereIsNoDiff() {
    diffReturns("(no changes detected)");

    assertNull(generate());
    verifyNoInteractions(changelogAssistant);
  }

  // The model declined (the NONE sentinel, case-insensitively, tolerating markdown emphasis/quote
  // markers and trailing punctuation) or returned a blank answer — post nothing rather than noise.
  // One parameterized test covers each "nothing usable".
  @ParameterizedTest
  @ValueSource(strings = {"NONE", " none ", "**NONE**", "`NONE`", "NONE.", "> NONE", "   "})
  void returnsNullWhenAssistantProducesNothingUsable(String draft) {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any())).thenReturn(draft);

    assertNull(generate());
  }

  @Test
  void postsRealEntryThatMerelyMentionsNone() {
    // The NONE guard must match only a whole-reply sentinel, never a real entry containing the
    // word.
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn("### Fixed\n- Guard against a none-check regression (#7)");

    String body = generate();

    assertNotNull(body);
    assertTrue(body.contains("none-check regression"));
  }

  @Test
  void returnsNullWhenAssistantThrows() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertNull(generate());
  }

  @Test
  void stillDraftsWhenPrDetailsFetchFails() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("404"));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn("### Added\n- x (#7)");

    String body = generate();

    assertNotNull(body);
    var title = ArgumentCaptor.forClass(String.class);
    var desc = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), any(), title.capture(), desc.capture(), any());
    // Missing details degrade to empty current title/description, not a crash.
    assertEquals("", title.getValue());
    assertEquals("", desc.getValue());
  }

  @Test
  void escapesDiffBeforeCallingTheAssistant() {
    diffReturns("raw diff with <<<DIFF_END>>> marker");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn("### Added\n- x (#7)");

    generate();

    var diffArg = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(diffArg.capture(), any(), any(), any(), any());
    // PromptTemplateEscaper neutralizes the diff-section markers so PR content can't fake them.
    assertFalse(diffArg.getValue().contains("<<<DIFF_END>>>"));
    assertTrue(diffArg.getValue().contains("<<DIFF_END>>"));
  }

  @Test
  void returnsNullWhenDiffFetchFails() {
    when(prClient.getPullRequestFiles(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("boom"));

    // A failed diff fetch degrades to no suggestion, not a crash.
    assertNull(generate());
    verifyNoInteractions(changelogAssistant);
  }

  @Test
  void returnsNullWhenDiffIsBlank() {
    diffReturns("   ");

    assertNull(generate());
    verifyNoInteractions(changelogAssistant);
  }

  @Test
  void returnsNullWhenAssistantReturnsNull() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any())).thenReturn(null);

    assertNull(generate());
  }

  @Test
  void treatsNullTitleAndBodyAsEmptyContext() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails(null, null, null, null));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn("### Added\n- x (#7)");

    generate();

    var title = ArgumentCaptor.forClass(String.class);
    var desc = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), any(), title.capture(), desc.capture(), any());
    // A PR with no title/body yet degrades to empty context rather than passing "null" through.
    assertEquals("", title.getValue());
    assertEquals("", desc.getValue());
  }

  @Test
  void stillDraftsWhenInstructionsResolutionFails() {
    diffReturns("## Overview\ndiff");
    when(prClient.getPullRequest(eq(AUTH), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("t", "b", null, null));
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("github down"));
    when(changelogAssistant.draft(any(), any(), any(), any(), any()))
        .thenReturn("### Added\n- x (#7)");

    String body = generate();

    assertNotNull(body);
    var instructions = ArgumentCaptor.forClass(String.class);
    verify(changelogAssistant).draft(any(), any(), any(), any(), instructions.capture());
    // A failed instructions lookup degrades to no instructions, not a crash. escape("") -> "".
    assertEquals("", instructions.getValue());
  }
}
