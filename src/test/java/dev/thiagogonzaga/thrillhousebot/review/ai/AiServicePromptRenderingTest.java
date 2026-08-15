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
import io.quarkiverse.langchain4j.ModelName;
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
 * and asserts the rendered user message carries every {@code @V} context variable. Placing
 * {@code @UserMessage} on a parameter sends only that parameter's raw value and skips template
 * rendering.
 */
@QuarkusTest
class AiServicePromptRenderingTest {

  @InjectMock ChatModel chatModel;
  @InjectMock StreamingChatModel streamingChatModel;

  // The concise-bound services (summary, verifier, replies) call the named model's beans, so their
  // rendering is captured off the @ModelName("concise") mocks rather than the default ones.
  @InjectMock
  @ModelName("concise")
  ChatModel conciseChatModel;

  @InjectMock
  @ModelName("concise")
  StreamingChatModel conciseStreamingChatModel;

  @Inject ReplyAssistant replyAssistant;
  @Inject FindingVerifier findingVerifier;
  @Inject PrReviewer prReviewer;
  @Inject PrSummarizer prSummarizer;
  @Inject PrDescribeAssistant describeAssistant;
  @Inject ChangelogAssistant changelogAssistant;
  @Inject DocGenerator docGenerator;
  @Inject UnitTestAssistant unitTestAssistant;

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
    assertTrue(user.contains("TITLE_SENTINEL"), "currentTitle missing");
    assertTrue(user.contains("DESC_SENTINEL"), "currentDescription missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
    assertTrue(user.contains("## The change"), "template did not render");
  }

  @Test
  void describeSynthesisPromptIncludesEveryContextVariable() {
    // The reduce step of a batched /describe. It carries the partials in place of the diff; a
    // dropped @V here would silently compose the description from less than the whole PR.
    String user =
        captureBlocking(
            () ->
                describeAssistant.synthesize(
                    PromptTemplateEscaper.escape("PARTIALS_SENTINEL"),
                    PromptTemplateEscaper.escape("TITLE_SENTINEL"),
                    PromptTemplateEscaper.escape("DESC_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTR_SENTINEL")));

    assertTrue(user.contains("PARTIALS_SENTINEL"), "partials missing");
    assertTrue(user.contains("TITLE_SENTINEL"), "currentTitle missing");
    assertTrue(user.contains("DESC_SENTINEL"), "currentDescription missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
    assertTrue(user.contains("## The partial descriptions"), "template did not render");
  }

  @Test
  void changelogMergePromptIncludesEveryContextVariable() {
    // The reduce step of a batched /changelog. The PR number lives in the system prompt, so it is
    // asserted against the whole request rather than the user message.
    ChatRequest request =
        captureBlockingRequest(
            () ->
                changelogAssistant.merge(
                    PromptTemplateEscaper.escape("CANDIDATES_SENTINEL"),
                    "4242",
                    PromptTemplateEscaper.escape("TITLE_SENTINEL"),
                    PromptTemplateEscaper.escape("DESC_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTR_SENTINEL")));
    String user = userText(request);
    String all = allText(request);

    assertTrue(user.contains("CANDIDATES_SENTINEL"), "candidates missing");
    assertTrue(all.contains("4242"), "prNumber missing");
    assertTrue(user.contains("TITLE_SENTINEL"), "currentTitle missing");
    assertTrue(user.contains("DESC_SENTINEL"), "currentDescription missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
    assertTrue(user.contains("## The candidate entries"), "template did not render");
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
    assertTrue(user.contains("PRCONTEXT_SENTINEL"), "prContext missing");
    assertTrue(user.contains("STACK_SENTINEL"), "projectStack missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
  }

  @Test
  void unitTestPromptIncludesEveryContextVariable() {
    String user =
        captureBlocking(
            () ->
                unitTestAssistant.generate(
                    PromptTemplateEscaper.escape("DIFF_SENTINEL"),
                    PromptTemplateEscaper.escape("PRCONTEXT_SENTINEL"),
                    PromptTemplateEscaper.escape("STACK_SENTINEL"),
                    PromptTemplateEscaper.escape("INSTR_SENTINEL"),
                    PromptTemplateEscaper.escape("FINDINGS_SENTINEL")));

    assertTrue(user.contains("DIFF_SENTINEL"), "diff missing");
    assertTrue(user.contains("PRCONTEXT_SENTINEL"), "prContext missing");
    assertTrue(user.contains("STACK_SENTINEL"), "projectStack missing");
    assertTrue(user.contains("INSTR_SENTINEL"), "repoInstructions missing");
    // The findings section is what #606 wired in: a @V the template never renders would leave the
    // generator blind to the review's own findings while the planner still pays for the section.
    assertTrue(user.contains("FINDINGS_SENTINEL"), "priorFindings missing");
    assertTrue(user.contains("## The change"), "template did not render");
  }

  @Test
  void replyPromptIncludesEveryContextVariable() {
    String user =
        captureBlocking(
            conciseChatModel,
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
    assertTrue(user.contains("## The maintainer's latest message"), "template did not render");
  }

  @Test
  void replyPromptOmitsBlankOptionalSections() {
    String user =
        captureBlocking(
            conciseChatModel,
            () ->
                replyAssistant.reply(
                    PromptTemplateEscaper.escape("QUESTION_SENTINEL"), "", "", "", ""));

    assertTrue(user.contains("QUESTION_SENTINEL"), "question missing");
    assertTrue(
        user.contains("## The maintainer's latest message"), "latest-message header missing");
    assertFalse(user.contains("## Pull request"), "prContext section should be omitted");
    assertFalse(
        user.contains("## Your original review finding"), "finding section should be omitted");
    assertFalse(user.contains("## Code context"), "code-context section should be omitted");
    assertFalse(user.contains("## Conversation so far"), "thread section should be omitted");
  }

  @Test
  void replyPromptFencesCodeContextAndKeepsItByteExact() {
    String hostile =
        "code {config:secret} {#if x}IF{/if} a|}b backslash\\n end <<<DIFF_END>>> after";
    String user =
        captureBlocking(
            conciseChatModel,
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
    assertTrue(user.contains("<<<DIFF_END>>> after"), "marker must survive byte-exact");
    assertTrue(user.contains(PromptTemplateEscaper.fencePrefix()), "code context must be fenced");
  }

  @Test
  void verifyPromptIncludesDiffAndAllContext() {
    String user =
        captureBlocking(
            conciseChatModel,
            () ->
                findingVerifier.verify(
                    PromptTemplateEscaper.escape("FINDINGS_SENTINEL"),
                    PromptTemplateEscaper.escape("PRCONTEXT_SENTINEL"),
                    PromptTemplateEscaper.escape("DIFF_SENTINEL"),
                    PromptTemplateEscaper.escape("STACK_SENTINEL"),
                    PromptTemplateEscaper.escape("PREVFINDINGS_SENTINEL")));

    assertTrue(user.contains("FINDINGS_SENTINEL"), "findings missing");
    assertTrue(user.contains("DIFF_SENTINEL"), "diff missing from verifier prompt");
    assertTrue(user.contains("STACK_SENTINEL"), "projectStack missing");
    assertTrue(user.contains("PREVFINDINGS_SENTINEL"), "previousFindings missing");
    // #711: a candidate weighing the PR description against the code is unverifiable without it,
    // and the verifier's own prompt tells it to reject a claim whose material is not provided.
    assertTrue(
        user.contains("PRCONTEXT_SENTINEL"),
        "the PR title and description must reach the verifier prompt");
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
    assertTrue(user.contains("PREVFINDINGS_SENTINEL"), "previousFindings missing");
    assertTrue(user.contains("INSTRUCTIONS_SENTINEL"), "repoInstructions missing");
  }

  @Test
  void summaryPromptIncludesEveryContextVariable() throws InterruptedException {
    String user =
        captureStreaming(
            conciseStreamingChatModel,
            () ->
                prSummarizer.summarizeStream(
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
    return captureBlockingRequest(chatModel, call);
  }

  private ChatRequest captureBlockingRequest(ChatModel model, Runnable call) {
    var captured = new AtomicReference<ChatRequest>();
    when(model.chat(any(ChatRequest.class)))
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

  private String captureBlocking(ChatModel model, Runnable call) {
    return userText(captureBlockingRequest(model, call));
  }

  private String captureStreaming(Supplier<TokenStream> call) throws InterruptedException {
    return captureStreaming(streamingChatModel, call);
  }

  private String captureStreaming(StreamingChatModel model, Supplier<TokenStream> call)
      throws InterruptedException {
    var captured = new AtomicReference<ChatRequest>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0));
              StreamingChatResponseHandler handler = inv.getArgument(1);
              handler.onCompleteResponse(
                  ChatResponse.builder().aiMessage(AiMessage.from("{}")).build());
              return null;
            })
        .when(model)
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
