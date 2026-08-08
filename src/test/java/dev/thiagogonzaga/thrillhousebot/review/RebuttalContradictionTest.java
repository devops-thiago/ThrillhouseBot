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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RebuttalContradictionTest {

  /** The dogfood finding: PrPauseService.pause() check-then-insert under concurrent webhooks. */
  private static final ReviewResponse.Finding RACE_FINDING =
      new ReviewResponse.Finding(
          "medium",
          "low",
          "src/main/java/dev/thiagogonzaga/thrillhousebot/webhook/PrPauseService.java",
          60,
          "Race condition in pause() can cause a UniqueConstraint violation under concurrent"
              + " webhooks",
          "pause() checks for an existing PausedPr and then inserts one. Two deliveries can both"
              + " pass the check before either inserts — low confidence, verify before acting.",
          null,
          null);

  /** The command path from the same PR: each command is handed to the shared review executor. */
  private static final String DISPATCHING_CODE =
      """
      diff --git a/src/main/java/dev/thiagogonzaga/thrillhousebot/webhook/CommentCommandService.java
      @@ -130,7 +130,9 @@ public class CommentCommandService {
      +  private void dispatch(CommandContext ctx) {
      +    executor.execute(() -> execute(ctx));
      +  }
      """;

  private static final String SERIAL_CODE =
      """
      diff --git a/src/main/java/dev/thiagogonzaga/thrillhousebot/webhook/CommentCommandService.java
      @@ -130,7 +130,9 @@ public class CommentCommandService {
      +  private void dispatch(CommandContext ctx) {
      +    execute(ctx);
      +  }
      """;

  @Test
  void shouldContradictAsyncAfterAckRebuttalWhenTheCodeDispatchesConcurrently() {
    var rebuttal =
        "Not changed — pause() is only ever called from the /pause command path, which runs"
            + " asynchronously on the review executor after the webhook has returned 200.";

    var contradiction = RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE);

    assertTrue(
        contradiction.isPresent(),
        "the async-after-ack rebuttal is refuted by executor.execute(...) in the reviewed code");
    assertTrue(
        contradiction.get().claim().contains("only ever called from"),
        "the note must quote the maintainer's claim, was: " + contradiction.get().claim());
    assertEquals("executor.execute(() -> execute(ctx));", contradiction.get().evidence());
    assertTrue(contradiction.get().note().contains("executor.execute(() -> execute(ctx));"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Intentional — this is the house style for command handlers and we're keeping it.",
        "We accept this risk; the window is tiny and a retry fixes it.",
        "Not worth it right now, deferring to the v0.7 cleanup.",
        "Won't fix — that's what the product wants here.",
      })
  void shouldRespectRebuttalsThatAreNotRefutableFromCode(String rebuttal) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "style / intent / accepted-risk rebuttals must keep the decline");
  }

  @Test
  void shouldRespectDeclineWhenTheCodeShowsNoConcurrentDispatch() {
    var rebuttal = "pause() is only ever called from the /pause command path — single-threaded.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, SERIAL_CODE).isEmpty(),
        "without concurrent-dispatch evidence in the reviewed code the maintainer is trusted");
  }

  @Test
  void shouldRespectDeclineWhenTheFindingIsNotAboutConcurrency() {
    var styleFinding =
        new ReviewResponse.Finding(
            "low", "high", "src/A.java", 3, "Method name is misleading", "rename it", null, null);

    assertTrue(
        RebuttalContradiction.find(
                styleFinding, "It only ever runs single-threaded anyway.", DISPATCHING_CODE)
            .isEmpty(),
        "the concurrency family must not fire on a finding that is not about concurrency");
  }

  @Test
  void shouldIgnoreClaimsThatAppearOnlyInQuotedMarkdown() {
    var rebuttal =
        """
        > Race condition — two deliveries can both pass the check. It is not single-threaded.

        ```java
        // only ever called from the command path
        ```

        Yes, agreed in principle, but we are not changing it in this PR.
        """;

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a blockquote or fenced block is quoted material, not the maintainer's own assertion");
  }

  @Test
  void shouldRespectDeclineWhenThereIsNoCodeToCheckAgainst() {
    var rebuttal = "It is single-threaded, so there is no race.";

    assertTrue(RebuttalContradiction.find(RACE_FINDING, rebuttal, "").isEmpty());
    assertTrue(RebuttalContradiction.find(RACE_FINDING, rebuttal, null).isEmpty());
    assertTrue(RebuttalContradiction.find(RACE_FINDING, null, DISPATCHING_CODE).isEmpty());
    assertTrue(RebuttalContradiction.find(null, rebuttal, DISPATCHING_CODE).isEmpty());
  }

  @Test
  void shouldReadTheConcurrencySignalFromTheDescriptionWhenTheFindingHasNoTitle() {
    var untitled =
        new ReviewResponse.Finding(
            "medium",
            "low",
            "src/A.java",
            7,
            null,
            "Two deliveries can interleave between the check and the insert — a data race.",
            null,
            null);

    assertTrue(
        RebuttalContradiction.find(untitled, "It runs serially.", DISPATCHING_CODE).isPresent(),
        "a null title must not hide the concurrency signal carried by the description");
  }

  @Test
  void shouldReadTheConcurrencySignalFromTheTitleWhenTheFindingHasNoDescription() {
    var undescribed =
        new ReviewResponse.Finding(
            "medium",
            "low",
            "src/A.java",
            7,
            "Race condition on the paused-PR insert",
            null,
            null,
            null);

    assertTrue(
        RebuttalContradiction.find(undescribed, "It runs serially.", DISPATCHING_CODE).isPresent(),
        "a null description must not hide the concurrency signal carried by the title");
  }

  @Test
  void shouldQuoteOnlyTheClaimSentenceOfALongerReply() {
    var rebuttal =
        "Thanks for the flag, I dug into this one. The command path is single-threaded."
            + " Closing it out.";

    var contradiction = RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE);

    assertTrue(contradiction.isPresent());
    assertEquals(
        "The command path is single-threaded.",
        contradiction.get().claim(),
        "the quote must start after the preceding sentence, not at the top of the reply");
  }

  @Test
  void shouldQuoteAWholeUnterminatedReplyAsTheClaim() {
    // The claim opens the reply and the reply never ends a sentence, so the quote runs from the
    // first character to the last with no terminator on either side.
    var contradiction =
        RebuttalContradiction.find(RACE_FINDING, "single-threaded here", DISPATCHING_CODE);

    assertTrue(contradiction.isPresent());
    assertEquals("single-threaded here", contradiction.get().claim());
  }

  @ParameterizedTest
  @ValueSource(strings = {"!", "?", ";", ".", "\n"})
  void shouldStopTheQuotedClaimAtEverySentenceTerminator(String terminator) {
    var rebuttal = "Nope, it is single-threaded" + terminator + " Moving on to the next thing";

    var contradiction = RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE);

    assertTrue(contradiction.isPresent());
    assertFalse(
        contradiction.get().claim().contains("Moving on"),
        "the quote must stop at the terminator, was: " + contradiction.get().claim());
  }

  @Test
  void shouldQuoteEvidenceOnTheLastLineWhenTheCodeHasNoTrailingNewline() {
    var codeEndingOnTheDispatch =
        "diff --git a/Worker.java\n@@ -1,2 +1,3 @@\n+    pool.submit(task);";

    var contradiction =
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeEndingOnTheDispatch);

    assertTrue(contradiction.isPresent());
    assertEquals("pool.submit(task);", contradiction.get().evidence());
  }

  @Test
  void shouldStripTheDiffMarkerFromAnEvidenceLineOnEitherSide() {
    var removedDispatch =
        "diff --git a/Worker.java\n@@ -1,3 +1,2 @@\n-    pool.submit(task);\n+    run(task);\n";

    var contradiction =
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", removedDispatch);

    assertTrue(contradiction.isPresent());
    assertEquals(
        "pool.submit(task);",
        contradiction.get().evidence(),
        "a leading -/+ diff marker is noise in the quote");
  }
}
