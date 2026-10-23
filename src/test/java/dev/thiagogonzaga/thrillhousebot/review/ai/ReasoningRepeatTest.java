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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReasoningRepeatTest {

  private static final Result<String> REASONED_AWAY =
      Result.<String>builder()
          .content(null)
          .finishReason(FinishReason.LENGTH)
          .tokenUsage(new TokenUsage(2900, 8192))
          .build();

  private static final Result<String> ANSWERED =
      Result.<String>builder()
          .content("{\"contradicted\": []}")
          .finishReason(FinishReason.STOP)
          .tokenUsage(new TokenUsage(2900, 300))
          .build();

  private static ThrillhouseConfig config(
      boolean enabled, String effort, Optional<String> conciseEffort) {
    return config(enabled, effort, conciseEffort, Optional.empty());
  }

  private static ThrillhouseConfig config(
      boolean enabled,
      String effort,
      Optional<String> conciseEffort,
      Optional<String> repeatEffort) {
    var config = mock(ThrillhouseConfig.class);
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var reasoning = mock(ThrillhouseConfig.AiPricingConfig.ReasoningConfig.class);
    lenient().when(config.ai()).thenReturn(ai);
    lenient().when(ai.reasoning()).thenReturn(reasoning);
    lenient().when(reasoning.enabled()).thenReturn(enabled);
    lenient().when(reasoning.effort()).thenReturn(effort);
    lenient().when(reasoning.conciseEffort()).thenReturn(conciseEffort);
    lenient().when(reasoning.repeatEffort()).thenReturn(repeatEffort);
    return config;
  }

  /**
   * #1173: glm-5.3-flash ignores none, so with reasoning enabled the repeat goes out at low, and a
   * lane already at low or none has no lower effort to repeat at. Pinned for every lane effort.
   */
  @ParameterizedTest
  @CsvSource({
    "none, ''",
    "low, ''",
    "medium, low",
    "high, low",
    "xhigh, low",
    "max, low",
    "MAX, low"
  })
  void withReasoningEnabledTheRepeatGoesOutAtLowBelowTheLanesEffort(String lane, String repeat) {
    var expected = repeat.isEmpty() ? Optional.<String>empty() : Optional.of(repeat);
    assertEquals(
        expected,
        ReasoningRepeat.effortFor(config(true, lane, Optional.empty()), false),
        "active lane at " + lane);
    assertEquals(
        expected,
        ReasoningRepeat.effortFor(config(true, "max", Optional.of(lane)), true),
        "concise lane at " + lane);
  }

  @Test
  void aConfiguredRepeatEffortIsSentUnlessTheFirstCallAlreadySentIt() {
    assertEquals(
        Optional.of("none"),
        ReasoningRepeat.effortFor(config(true, "max", Optional.empty(), Optional.of("None")), true),
        "an operator can ask for none on a provider that honors it");
    assertEquals(
        Optional.of("medium"),
        ReasoningRepeat.effortFor(
            config(true, "max", Optional.empty(), Optional.of("medium")), false));
    assertEquals(
        Optional.of("none"),
        ReasoningRepeat.effortFor(
            config(false, "max", Optional.empty(), Optional.of("none")), true),
        "with reasoning off nothing was sent, so any configured effort is a different call");
    assertEquals(
        Optional.empty(),
        ReasoningRepeat.effortFor(config(true, "max", Optional.empty(), Optional.of("low")), true),
        "the concise lane already sent low, so the repeat would be the identical call");
  }

  /**
   * #1169: with reasoning off nothing was sent, and glm-5.3-flash at its default effort, and at
   * none, spent the concise cap reasoning; at low it answered. The repeat sends the lane's effort.
   */
  @Test
  void withReasoningOffTheRepeatSendsTheLanesEffort() {
    assertEquals(
        Optional.of("low"),
        ReasoningRepeat.effortFor(config(false, "max", Optional.empty()), true));
    assertEquals(
        Optional.of("medium"),
        ReasoningRepeat.effortFor(config(false, "max", Optional.of("Medium")), true));
    assertEquals(
        Optional.of("high"),
        ReasoningRepeat.effortFor(config(false, "High", Optional.empty()), false));
  }

  @Test
  void theFirstEffortIsWhatTheLaneSendsOrTheProviderDefault() {
    assertEquals("low", ReasoningRepeat.firstEffort(config(true, "max", Optional.empty()), true));
    assertEquals(
        "the provider default",
        ReasoningRepeat.firstEffort(config(false, "low", Optional.empty()), true));
  }

  @Test
  void anOverrideHoldsForItsScopeNestsAndANullOneSetsNothing() {
    assertNull(ReasoningRepeat.current());
    try (var _ = ReasoningRepeat.override("low")) {
      assertEquals("low", ReasoningRepeat.current());
      try (var _ = ReasoningRepeat.override("none")) {
        assertEquals("none", ReasoningRepeat.current());
      }
      assertEquals("low", ReasoningRepeat.current(), "the outer override comes back");
      try (var _ = ReasoningRepeat.override(null)) {
        assertEquals("low", ReasoningRepeat.current(), "a null override changes nothing");
      }
      assertEquals("low", ReasoningRepeat.current());
    }
    assertNull(ReasoningRepeat.current(), "nothing outlives the outermost scope");
  }

  @Test
  void aRequestIsRewrittenOnlyUnderAnOverrideAndKeepsItsOwnValues() {
    var request =
        ChatRequest.builder()
            .messages(UserMessage.from("hello"))
            .parameters(ChatRequestParameters.builder().temperature(0.5).build())
            .build();

    assertSame(request, ReasoningRepeat.applied(request));
    ChatRequest rewritten;
    try (var _ = ReasoningRepeat.override("low")) {
      rewritten = ReasoningRepeat.applied(request);
    }

    var parameters = assertInstanceOf(OpenAiChatRequestParameters.class, rewritten.parameters());
    assertEquals("low", parameters.reasoningEffort());
    assertEquals(0.5, parameters.temperature());
    assertEquals(request.messages(), rewritten.messages());
  }

  @Test
  void onlyALengthStopWithNoAnswerTextIsAStopWithNoContent() {
    assertTrue(ReasoningRepeat.stoppedWithNoContent(REASONED_AWAY));
    assertTrue(
        ReasoningRepeat.stoppedWithNoContent(
            Result.<String>builder().content("  \n").finishReason(FinishReason.LENGTH).build()));
    assertFalse(
        ReasoningRepeat.stoppedWithNoContent(
            Result.<String>builder()
                .content("{\"contra")
                .finishReason(FinishReason.LENGTH)
                .build()),
        "a cut answer is the salvage path's, never repeated");
    assertFalse(
        ReasoningRepeat.stoppedWithNoContent(
            Result.<String>builder().content(null).finishReason(FinishReason.STOP).build()));
    assertFalse(ReasoningRepeat.stoppedWithNoContent(null));
  }

  @Test
  void aBlockingCallThatReasonedItsCapAwayIsRepeatedOnceAtTheEffortAndBothAreMetered() {
    var effortPerCall = new ArrayList<String>();
    var metered = new ArrayList<Result<String>>();
    var repeats = new AtomicInteger();

    var result =
        ReasoningRepeat.blocking(
            "Summary verification",
            () -> {
              effortPerCall.add(ReasoningRepeat.current());
              return effortPerCall.size() == 1 ? REASONED_AWAY : ANSWERED;
            },
            () -> Optional.of("low"),
            () -> false,
            metered::add,
            repeats::incrementAndGet);

    assertSame(ANSWERED, result, "the repeat's answer is the call's");
    assertEquals(Arrays.asList(null, "low"), effortPerCall);
    assertEquals(List.of(REASONED_AWAY, ANSWERED), metered, "the cut call was billed too");
    assertEquals(1, repeats.get());
    assertNull(ReasoningRepeat.current(), "the override ends with the repeat");
  }

  @Test
  void aRepeatThatIsCutAgainIsReturnedForTheCallerToReport() {
    var calls = new AtomicInteger();

    var result =
        ReasoningRepeat.blocking(
            "Finding verification",
            () -> {
              calls.incrementAndGet();
              return Result.<String>builder()
                  .content(null)
                  .finishReason(FinishReason.LENGTH)
                  .build();
            },
            () -> Optional.of("none"),
            () -> false,
            r -> {},
            () -> {});

    assertEquals(2, calls.get(), "one repeat, never a second");
    assertTrue(ReasoningRepeat.stoppedWithNoContent(result));
  }

  @Test
  void noRepeatForAnAnswerACutAnswerNoEffortOrTheSpendCeiling() {
    var effortRead = new AtomicInteger();
    assertSame(ANSWERED, once(ANSWERED, () -> countRead(effortRead, "low"), false));
    assertEquals(0, effortRead.get(), "the effort is read only once a repeat is due");

    var cut = Result.<String>builder().content("{\"a").finishReason(FinishReason.LENGTH).build();
    assertSame(cut, once(cut, () -> Optional.of("low"), false));
    assertSame(REASONED_AWAY, once(REASONED_AWAY, Optional::empty, false));
    assertSame(REASONED_AWAY, once(REASONED_AWAY, () -> Optional.of("low"), true));
  }

  private static Optional<String> countRead(AtomicInteger reads, String effort) {
    reads.incrementAndGet();
    return Optional.of(effort);
  }

  /** {@link ReasoningRepeat#blocking} over a call that must be made exactly once. */
  private static Result<String> once(
      Result<String> first,
      java.util.function.Supplier<Optional<String>> effort,
      boolean ceilingReached) {
    var calls = new AtomicInteger();
    var repeats = new AtomicInteger();
    var result =
        ReasoningRepeat.blocking(
            "Response repair",
            () -> {
              calls.incrementAndGet();
              return first;
            },
            effort,
            () -> ceilingReached,
            r -> {},
            repeats::incrementAndGet);
    assertEquals(1, calls.get());
    assertEquals(0, repeats.get());
    return result;
  }
}
