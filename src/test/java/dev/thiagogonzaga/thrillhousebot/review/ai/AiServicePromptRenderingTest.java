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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.TokenStream;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Drives the real quarkus-langchain4j rendering pipeline (template + escaper) for every AI service
 * and asserts the rendered user message actually carries every {@code @V} context variable.
 *
 * <p>This is the regression guard for the {@code @UserMessage}-on-parameter bug: placing
 * {@code @UserMessage} on the first parameter made quarkus-langchain4j send only that parameter's
 * raw value and never render the template, so the diff was the only thing reaching the review model
 * and the maintainer's question the only thing reaching the reply model — repo instructions,
 * previous findings, the PR diff (for the verifier), and the surrounding context were all silently
 * dropped. Each test would have failed under the old code.
 */
@QuarkusTest
class AiServicePromptRenderingTest {

  @InjectMock ChatModel chatModel;
  @InjectMock StreamingChatModel streamingChatModel;

  @Inject ReplyAssistant replyAssistant;
  @Inject FindingVerifier findingVerifier;
  @Inject PrReviewer prReviewer;
  @Inject PrDescribeAssistant describeAssistant;
  @Inject ChangelogAssistant changelogAssistant;
  @Inject DocGenerator docGenerator;

  @Test
  void describePromptIncludesEveryContextVariable() {
    String user =
        captureBlocking(
            () ->
                describeAssistant.describe(
                    PromptTemplateEscaper.escape("DIFF_SENTINEL"),
                    PromptTemplateEscaper.escape("TITLE_SENTINEL"),
                    PromptTemplateEscaper.escape("DESC_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTR_SENTINEL")));

    assertTrue(user.contains("DIFF_SENTINEL"), "diff missing");
    // Regression (#186 reintroduced): @UserMessage on the diff param dropped all of these.
    assertTrue(user.contains("TITLE_SENTINEL"), "currentTitle missing");
    assertTrue(user.contains("DESC_SENTINEL"), "currentDescription missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
    assertTrue(user.contains("## The change"), "template did not render");
  }

  @Test
  void changelogPromptIncludesEveryContextVariable() {
    ChatRequest request =
        captureBlockingRequest(
            () ->
                changelogAssistant.draft(
                    PromptTemplateEscaper.escape("DIFF_SENTINEL"),
                    "4242",
                    PromptTemplateEscaper.escape("TITLE_SENTINEL"),
                    PromptTemplateEscaper.escape("DESC_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTR_SENTINEL")));
    String user = userText(request);
    String all = allText(request);

    assertTrue(user.contains("DIFF_SENTINEL"), "diff missing");
    // Regression (#186 reintroduced): every @V was dropped. prNumber renders into the system
    // template (so each bullet can carry "(#N)"); the rest render into the user template.
    assertTrue(all.contains("4242"), "prNumber missing");
    assertTrue(user.contains("TITLE_SENTINEL"), "currentTitle missing");
    assertTrue(user.contains("DESC_SENTINEL"), "currentDescription missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
    assertTrue(user.contains("## The change"), "template did not render");
  }

  @Test
  void docGeneratorPromptIncludesEveryContextVariable() {
    String user =
        captureBlocking(
            () ->
                docGenerator.generate(
                    PromptTemplateEscaper.escape("DIFF_SENTINEL"),
                    PromptTemplateEscaper.escape("PRCONTEXT_SENTINEL"),
                    PromptTemplateEscaper.escape("STACK_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTR_SENTINEL")));

    assertTrue(user.contains("DIFF_SENTINEL"), "diff missing");
    // Regression (#186 reintroduced): @UserMessage on the diff param dropped all of these.
    assertTrue(user.contains("PRCONTEXT_SENTINEL"), "prContext missing");
    assertTrue(user.contains("STACK_SENTINEL"), "projectStack missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
  }

  @Test
  void replyPromptIncludesEveryContextVariable() {
    String user =
        captureBlocking(
            () ->
                replyAssistant.reply(
                    PromptTemplateEscaper.escape("QUESTION_SENTINEL"),
                    PromptTemplateEscaper.escape("PRCONTEXT_SENTINEL"),
                    PromptTemplateEscaper.escape("FINDING_SENTINEL"),
                    PromptTemplateEscaper.escape("CODECONTEXT_SENTINEL"),
                    PromptTemplateEscaper.escape("THREAD_SENTINEL")));

    assertTrue(user.contains("QUESTION_SENTINEL"), "question missing");
    assertTrue(user.contains("PRCONTEXT_SENTINEL"), "prContext missing");
    assertTrue(user.contains("FINDING_SENTINEL"), "finding missing");
    assertTrue(user.contains("CODECONTEXT_SENTINEL"), "codeContext (diff) missing");
    assertTrue(user.contains("THREAD_SENTINEL"), "thread missing");
    // The template's own static text must render too (proof the template ran, not the bare param).
    assertTrue(user.contains("## The maintainer's latest message"), "template did not render");
  }

  @Test
  void replyPromptOmitsBlankOptionalSections() {
    String user =
        captureBlocking(
            () ->
                replyAssistant.reply(
                    PromptTemplateEscaper.escape("QUESTION_SENTINEL"), "", "", "", ""));

    assertTrue(user.contains("QUESTION_SENTINEL"), "question missing");
    assertTrue(
        user.contains("## The maintainer's latest message"), "latest-message header missing");
    // Blank @V values must leave their {{#if}} sections out entirely.
    assertFalse(user.contains("## Pull request"), "prContext section should be omitted");
    assertFalse(
        user.contains("## Your original review finding"), "finding section should be omitted");
    assertFalse(user.contains("## Code context"), "code-context section should be omitted");
    assertFalse(user.contains("## Conversation so far"), "thread section should be omitted");
  }

  @Test
  void replyPromptFencesCodeContextAndKeepsItByteExact() {
    // codeContext (the diff/hunk) is wrapped in a per-review random fence and passed byte-exact —
    // no marker rewriting — so hostile content, including the diff markers themselves, survives
    // verbatim and uninterpreted; the unguessable fence, not content rewriting, isolates data.
    String hostile =
        "code {config:secret} {#if x}IF{/if} a|}b backslash\\n end <<<DIFF_END>>> after";
    String user =
        captureBlocking(
            () ->
                replyAssistant.reply(
                    PromptTemplateEscaper.escape("Q"),
                    "",
                    "",
                    PromptTemplateEscaper.fence(hostile),
                    ""));

    assertTrue(user.contains("{config:secret}"), "Qute expression must not be interpreted");
    assertTrue(user.contains("{#if x}IF{/if}"), "Qute section must not be interpreted");
    assertTrue(user.contains("a|}b"), "section terminator must survive verbatim");
    assertTrue(user.contains("backslash\\n"), "backslash must survive verbatim");
    // Byte-exact: the diff markers are NOT rewritten — they reach the model exactly as written.
    assertTrue(user.contains("<<<DIFF_END>>> after"), "marker must survive byte-exact");
    // The content is wrapped in the unguessable fence that isolates it instead.
    assertTrue(user.contains(PromptTemplateEscaper.fencePrefix()), "code context must be fenced");
  }

  @Test
  void verifyPromptIncludesDiffAndAllContext() {
    String user =
        captureBlocking(
            () ->
                findingVerifier.verify(
                    PromptTemplateEscaper.escape("FINDINGS_SENTINEL"),
                    PromptTemplateEscaper.escape("DIFF_SENTINEL"),
                    PromptTemplateEscaper.escape("STACK_SENTINEL"),
                    PromptTemplateEscaper.escape("PREVFINDINGS_SENTINEL")));

    assertTrue(user.contains("FINDINGS_SENTINEL"), "findings missing");
    // Regression: the verifier used to audit findings WITHOUT the diff.
    assertTrue(user.contains("DIFF_SENTINEL"), "diff missing from verifier prompt");
    assertTrue(user.contains("STACK_SENTINEL"), "projectStack missing");
    assertTrue(user.contains("PREVFINDINGS_SENTINEL"), "previousFindings missing");
  }

  @Test
  void reviewPromptIncludesInstructionsAndPreviousFindings() throws InterruptedException {
    String user =
        captureStreaming(
            () ->
                prReviewer.reviewStream(
                    PromptTemplateEscaper.escape("DIFF_SENTINEL"),
                    PromptTemplateEscaper.escape("PRCONTEXT_SENTINEL"),
                    PromptTemplateEscaper.escape("BASECMP_SENTINEL"),
                    PromptTemplateEscaper.escape("STACK_SENTINEL"),
                    PromptTemplateEscaper.escape("TESTS_SENTINEL"),
                    PromptTemplateEscaper.escape("PREVFINDINGS_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTRUCTIONS_SENTINEL")));

    assertTrue(user.contains("DIFF_SENTINEL"), "diff missing");
    assertTrue(user.contains("PRCONTEXT_SENTINEL"), "prContext missing");
    assertTrue(user.contains("BASECMP_SENTINEL"), "baseComparison missing");
    assertTrue(user.contains("STACK_SENTINEL"), "projectStack missing");
    assertTrue(user.contains("TESTS_SENTINEL"), "relatedTests missing");
    // Regression: the review prompt used to drop these entirely.
    assertTrue(user.contains("PREVFINDINGS_SENTINEL"), "previousFindings missing");
    assertTrue(user.contains("INSTRUCTIONS_SENTINEL"), "repoInstructions missing");
  }

  @Test
  void summaryPromptIncludesEveryContextVariable() throws InterruptedException {
    String user =
        captureStreaming(
            () ->
                prReviewer.summarizeStream(
                    PromptTemplateEscaper.escape("PRCONTEXT_SENTINEL"),
                    PromptTemplateEscaper.escape("FINDINGS_SENTINEL"),
                    PromptTemplateEscaper.escape("CHANGEDFILES_SENTINEL"),
                    PromptTemplateEscaper.escape("PREVFINDINGS_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTRUCTIONS_SENTINEL")));

    assertTrue(user.contains("PRCONTEXT_SENTINEL"), "prContext missing");
    assertTrue(user.contains("FINDINGS_SENTINEL"), "findings missing");
    assertTrue(user.contains("CHANGEDFILES_SENTINEL"), "changedFiles missing");
    assertTrue(user.contains("PREVFINDINGS_SENTINEL"), "previousFindings missing");
    assertTrue(user.contains("INSTRUCTIONS_SENTINEL"), "repoInstructions missing");
  }

  private ChatRequest captureBlockingRequest(Runnable call) {
    var captured = new AtomicReference<ChatRequest>();
    when(chatModel.chat(any(ChatRequest.class)))
        .thenAnswer(
            inv -> {
              captured.set(inv.getArgument(0));
              return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            });
    call.run();
    return captured.get();
  }

  private String captureBlocking(Runnable call) {
    return userText(captureBlockingRequest(call));
  }

  private String captureStreaming(Supplier<TokenStream> call) throws InterruptedException {
    var captured = new AtomicReference<ChatRequest>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0));
              StreamingChatResponseHandler handler = inv.getArgument(1);
              handler.onCompleteResponse(
                  ChatResponse.builder().aiMessage(AiMessage.from("{}")).build());
              return null;
            })
        .when(streamingChatModel)
        .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

    var done = new CountDownLatch(1);
    call.get()
        .onPartialResponse(token -> {})
        .onCompleteResponse(response -> done.countDown())
        .onError(error -> done.countDown())
        .start();
    assertTrue(done.await(10, TimeUnit.SECONDS), "review stream did not complete");
    return userText(captured.get());
  }

  private static String userText(ChatRequest request) {
    return request.messages().stream()
        .filter(UserMessage.class::isInstance)
        .map(m -> ((UserMessage) m).singleText())
        .findFirst()
        .orElseThrow(() -> new AssertionError("no user message in chat request"));
  }

  /**
   * Every message's text (system + user), so a variable is found wherever its template places it.
   */
  private static String allText(ChatRequest request) {
    var sb = new StringBuilder();
    for (var m : request.messages()) {
      if (m instanceof UserMessage u) {
        sb.append(u.singleText()).append('\n');
      } else if (m instanceof SystemMessage s) {
        sb.append(s.text()).append('\n');
      }
    }
    return sb.toString();
  }
}
