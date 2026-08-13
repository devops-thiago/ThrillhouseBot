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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link AiContextWindowExceededException}'s classifier and cause-chain finder. */
class AiContextWindowExceededExceptionTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        // OpenAI: error code and message wording.
        "The request failed: context_length_exceeded",
        "This model's maximum context length is 128000 tokens, however you requested 190000",
        // Anthropic: messages API wordings.
        "invalid_request_error: prompt is too long: 250000 tokens > 200000 maximum",
        "input length and `max_tokens` exceed context limit: 210000 + 8192 > 200000",
        // Gemini: input-side rejection wording.
        "The input token count (1200000) exceeds the maximum number of tokens allowed (1048576)"
      })
  void classifyRecognizesTheProvidersContextWindowRejections(String providerMessage) {
    var failure = new RuntimeException(providerMessage);

    var classified = AiContextWindowExceededException.classify(failure);

    assertTrue(classified.isPresent(), providerMessage);
    assertSame(failure, classified.get().getCause());
    assertTrue(
        classified.get().getMessage().contains("context window"), classified.get().getMessage());
  }

  @Test
  void classifyMatchesCaseInsensitivelyAndWalksTheCauseChain() {
    var failure =
        new RuntimeException(
            "call failed", new IllegalStateException("PROMPT IS TOO LONG: 300000 tokens"));

    assertTrue(AiContextWindowExceededException.classify(failure).isPresent());
  }

  @Test
  void classifyLeavesOtherFailuresOnTheTransientPath() {
    assertFalse(
        AiContextWindowExceededException.classify(new RuntimeException("boom")).isPresent());
    assertFalse(
        AiContextWindowExceededException.classify(new RuntimeException("rate limit exceeded"))
            .isPresent());
    assertFalse(
        AiContextWindowExceededException.classify(new RuntimeException((String) null)).isPresent());
  }

  @Test
  void classifyReturnsAnAlreadyTypedRejectionWithoutRewrapping() {
    var rejection =
        new AiContextWindowExceededException("over the window", new RuntimeException("cause"));
    var wrapped = new RuntimeException("wrap", rejection);

    var classified = AiContextWindowExceededException.classify(wrapped);

    assertTrue(classified.isPresent());
    assertSame(rejection, classified.get());
  }

  @Test
  void findInWalksTheCauseChainAndReturnsTheRejection() {
    var rejection = new AiContextWindowExceededException("over the window", null);
    var wrapped = new CompletionException(new IllegalStateException("wrap", rejection));

    var found = AiContextWindowExceededException.findIn(wrapped);

    assertTrue(found.isPresent());
    assertSame(rejection, found.get());
  }

  @Test
  void findInReturnsEmptyForAnUnrelatedFailure() {
    assertFalse(AiContextWindowExceededException.findIn(new RuntimeException("boom")).isPresent());
  }

  @Test
  void classifyTerminatesOnACyclicCauseChain() {
    // Same guarantee Throwables.findCause makes: a self-caused chain must not spin the walk.
    var cyclic = new AtomicReference<RuntimeException>();
    var failure =
        new RuntimeException("boom") {
          @Override
          public synchronized Throwable getCause() {
            return cyclic.get();
          }
        };
    cyclic.set(failure);

    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> assertFalse(AiContextWindowExceededException.classify(failure).isPresent()));
  }
}
