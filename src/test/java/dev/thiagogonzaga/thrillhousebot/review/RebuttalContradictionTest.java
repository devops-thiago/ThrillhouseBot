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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

  /** One phrasing per "concurrency is impossible" argument the matcher recognises. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "The handler is single-threaded.",
        "There is a single thread doing this.",
        "They run serially.",
        "The commands are executed serially.",
        "Access is serialized by the caller.",
        "These are processed sequentially.",
        "They happen one at a time.",
        "It cannot run concurrently.",
        "Two of them can't be concurrent.",
        "This never executes concurrently.",
        "There is no concurrency on this path.",
        "The path is not concurrent.",
        "There is no race here.",
        "That isn't a race.",
        "That is not a race, really.",
        "pause() is only ever called from the command path.",
        "It is only invoked from the webhook path.",
        "The command path is the only caller.",
      })
  void shouldRecogniseEverySerializationArgument(String rebuttal) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isPresent(),
        "this is a 'concurrency is impossible' claim and the code refutes it: " + rebuttal);
  }

  /** One snippet per concurrent-dispatch construct the matcher accepts as refuting evidence. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    var pool = Executors.newVirtualThreadPerTaskExecutor();",
        "+    var pool = Executors.newCachedThreadPool();",
        "+    var pool = Executors.newScheduledThreadPool(2);",
        "+    var pool = Executors.newWorkStealingPool();",
        "+    var pool = Executors.newFixedThreadPool(8);",
        "+    executor.submit(() -> run(ctx));",
        "+    executor.execute(() -> run(ctx));",
        "+    CompletableFuture.runAsync(() -> run(ctx));",
        "+    CompletableFuture.supplyAsync(() -> load(ctx));",
        "+    new Thread(() -> run(ctx)).start();",
        "+  @Async",
        "+    items.parallelStream().forEach(this::handle);",
      })
  void shouldRecogniseEveryConcurrentDispatchConstruct(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "this construct dispatches concurrently: " + codeLine);
  }

  /**
   * A one-thread pool genuinely serializes, so it refutes nothing — and this is the direction that
   * matters most, since a match here overrules a maintainer whose decline was correct. The thread
   * count is what decides it, not how the call is spelled around it.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    var pool = Executors.newFixedThreadPool(1);",
        // The two-argument overload is the standard way to name a single worker.
        "+    var pool = Executors.newFixedThreadPool(1, new WorkerThreadFactory());",
        // Spacing inside the call must not let the count slip past the guard.
        "+    var pool = Executors.newFixedThreadPool( 1 );",
        "+    var pool = Executors.newFixedThreadPool( 1 , factory);",
        "+    var pool = Executors.newFixedThreadPool(\t1\t);",
        // A block comment naming the count is the natural place to explain a pool of one.
        "+    var pool = Executors.newFixedThreadPool(1 /* one worker */);",
        "+    var pool = Executors.newFixedThreadPool(/* only one */ 1);",
        "+    var pool = Executors.newFixedThreadPool(1 /* worker */, factory);",
        // The evidence text is rejoined lines, so an explanation long enough to wrap is one the
        // scan really sees across the break.
        "+    var pool = Executors.newFixedThreadPool(1 /* one worker: the downstream\n"
            + "+        store is not safe for parallel writes */);",
        "+    var pool = Executors.newFixedThreadPool(1 /* exactly one worker, because the queue"
            + " must stay ordered for the replay to be deterministic */);",
        // A justification that cites its source: one URL is enough to run a comment past 256
        // characters, which is where the previous bound sat.
        "+    var pool = Executors.newFixedThreadPool(1 /* one worker per"
            + " https://github.com/org/repo/issues/12345#issuecomment-1234567890 and the four"
            + " incidents linked from it, all of which came from parallel writes to the same"
            + " ledger row while the nightly replay was running; the ordering guarantee the"
            + " downstream reconciler depends on is only worth having if exactly one thread"
            + " appends here, so please do not widen this pool without reading that thread"
            + " first */);",
      })
  void shouldNotTreatASingleThreadedFixedPoolAsConcurrentDispatch(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine + "\n").isEmpty(),
        "a one-thread pool genuinely serializes, so it does not refute the decline: " + codeLine);
  }

  /** The counterpart: any count above one does dispatch concurrently, however it is spelled. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    var pool = Executors.newFixedThreadPool(10);",
        // 11 starts with the same digit as 1 and must not be excluded with it.
        "+    var pool = Executors.newFixedThreadPool(11);",
        "+    var pool = Executors.newFixedThreadPool( 10 );",
        "+    var pool = Executors.newFixedThreadPool(8, factory);",
        "+    var pool = Executors.newFixedThreadPool(workers);",
        // A comment does not make a count knowable, and the class cannot evaluate an expression.
        "+    var pool = Executors.newFixedThreadPool(2 /* two */);",
        "+    var pool = Executors.newFixedThreadPool(1 + 0);",
        "+    var pool = Executors.newFixedThreadPool(2 /* two\n+        threads */);",
      })
  void shouldTreatAMultiThreadedFixedPoolAsConcurrentDispatch(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine + "\n").isPresent(),
        "a pool of more than one thread dispatches concurrently: " + codeLine);
  }

  /**
   * A {@code //} inside a string literal is not a comment start. Cutting the line there threw away
   * the dispatch that followed it, so the decline stood over code that does dispatch concurrently.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // A protocol-relative URL in a Java/JS string literal.
        "+    var base = \"//cdn.example.com\"; executor.submit(() -> run(ctx));",
        // A Go raw string literal.
        "+    raw := `a//b`; executor.submit(func() { run(ctx) })",
        // A C++ raw string literal, whose R\"( opener the scan must not read as a comment either.
        "+    auto path = R\"(prefix//rest)\"; executor.submit([]{ run(ctx); });",
        // A single-quoted literal.
        "+    var sep = '//'; executor.submit(() -> run(ctx));",
        // An escaped backslash closes its literal, so the escape still applies where it exists.
        "+    var dir = \"C:\\\\\"; executor.submit(() -> run(ctx));",
      })
  void shouldKeepDispatchEvidenceThatFollowsAQuotedDoubleSlash(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "the // sits inside a string literal, so the dispatch after it is live code: " + codeLine);
  }

  /**
   * The mirror of the case above: a real trailing comment must stay stripped. Honouring a backslash
   * escape inside a Go raw string — where a backslash is literal — stepped over the closing
   * backtick, left the quote state open, and let the comment's own text pose as live code.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // A Windows path in a Go raw string, then a real comment naming a dispatch.
        "+    path := `C:\\`; // executor.submit(() -> run(ctx));",
        // The same shape with no literal involved at all.
        "+    var n = count; // executor.submit(() -> run(ctx));",
      })
  void shouldNotReadACommentAsDispatchEvidenceAfterABackslash(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isEmpty(),
        "a dispatch named only in a comment is not live code, so the decline stands: " + codeLine);
  }

  static Stream<Arguments> commentScanEdgeCases() {
    return Stream.of(
        arguments(
            "an escaped quote does not close the literal, so the // after it is still quoted",
            "+    var s = \"a\\\"//b\"; executor.submit(() -> run(ctx));",
            true),
        arguments(
            "a URL scheme's :// is not a comment start",
            "+    var u = http://example.com; executor.submit(() -> run(ctx));",
            true),
        arguments(
            "a line opening with // is comment from its very first character",
            "// executor.submit(() -> run(ctx));",
            false),
        arguments(
            "a lone / is division, and a trailing / ends the line without opening a comment",
            "+    var half = total / 2; executor.submit(() -> run(ctx)); /",
            true));
  }

  /** Edge shapes of the comment scan, each turning on one decision the scan makes alone. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("commentScanEdgeCases")
  void shouldFindTheCommentStartOutsideStringLiterals(String name, String code, boolean refuted) {
    assertEquals(
        refuted,
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", code).isPresent(),
        name);
  }

  @Test
  void shouldStillStripARealTrailingLineCommentAfterAStringLiteral() {
    var commented = "+    var base = \"//cdn.example.com\"; // executor.submit(() -> run(ctx));\n";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", commented).isEmpty(),
        "a dispatch that only appears in a line comment is not live code");
  }

  @Test
  void shouldSkipARebuttalTooLargeToAnalyze() {
    var huge = "It is single-threaded. ".repeat(2000);

    assertTrue(huge.length() > 20_000, "the fixture must exceed the analysis cap");
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, huge, DISPATCHING_CODE).isEmpty(),
        "an unread reply keeps its decline — skipping is the conservative outcome");
  }

  @Test
  void shouldQuoteTheEarliestClaimWhicheverArgumentItBelongsTo() {
    // Two claims in two different sentences. "only ever called from" is declared LAST but appears
    // FIRST; "no race" is declared earlier but appears later. The quote must follow position in the
    // reply, not the order the patterns happen to be declared in — otherwise splitting the original
    // single alternation would have silently changed which sentence gets quoted back at the
    // maintainer.
    var rebuttal = "It is only ever called from the command path. Anyway there is no race.";

    var contradiction = RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE);

    assertTrue(contradiction.isPresent());
    assertEquals(
        "It is only ever called from the command path.",
        contradiction.get().claim(),
        "the earliest claim in the reply wins, regardless of which pattern matched it");

    // The mirror image: now the FIRST-declared argument is also the one appearing first, so the
    // later match must be rejected rather than overwrite it. Both directions together pin the
    // quote to position alone.
    var mirrored =
        RebuttalContradiction.find(
            RACE_FINDING, "It is single-threaded. Anyway there is no race.", DISPATCHING_CODE);

    assertTrue(mirrored.isPresent());
    assertEquals("It is single-threaded.", mirrored.get().claim());
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
  void shouldNotQuoteAConcurrentDispatchThatOnlyAppearsOnARemovedLine() {
    // The maintainer deleted the dispatch in this revision, so the finding's premise is no longer
    // refuted by live code. A removed (-) line must not be quoted as evidence, and clip() would
    // otherwise strip the leading '-' and present the deleted line as if it still ran (F3).
    var removedDispatch =
        "diff --git a/Worker.java\n@@ -1,3 +1,2 @@\n-    pool.submit(task);\n+    run(task);\n";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", removedDispatch).isEmpty(),
        "a dispatch that survives only on a removed line is not live code and keeps the decline");
  }

  @Test
  void shouldNotQuoteAConcurrentDispatchThatOnlyAppearsInALineComment() {
    // The dispatch is mentioned only in a // comment on an added line — commented-out or
    // explanatory text, not live code — so it must not refute the decline (F3).
    var commentedDispatch =
        """
        diff --git a/Worker.java
        @@ -1,2 +1,3 @@
        +    // executor.submit(() -> run(ctx)); removed, now synchronous
        +    run(ctx);
        """;

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", commentedDispatch).isEmpty(),
        "a dispatch mentioned only in a line comment is not live code and keeps the decline");
  }

  @Test
  void shouldNotTreatAUrlSchemeSlashesAsALineComment() {
    // A "://" URL scheme must not be mistaken for a // line-comment start when stripping comments
    // from right-side code, so a dispatch on a later line is still seen as live evidence (F3).
    var codeWithUrl =
        """
        diff --git a/Worker.java
        @@ -1,2 +1,3 @@
        +    var docs = "http://example.com/submit";
        +    executor.submit(() -> run(ctx));
        """;

    var contradiction = RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeWithUrl);

    assertTrue(
        contradiction.isPresent(),
        "the URL's // is a scheme, not a comment, so the dispatch below it stays live evidence");
    assertEquals("executor.submit(() -> run(ctx));", contradiction.get().evidence());
  }

  @Test
  void shouldStripALeadingListDashFromTheQuotedClaim() {
    // A maintainer's reply written as a markdown bullet ("- ...") is still their assertion; the
    // leading "- " must be trimmed from the quoted claim.
    var contradiction =
        RebuttalContradiction.find(RACE_FINDING, "- It is single-threaded.", DISPATCHING_CODE);

    assertTrue(contradiction.isPresent());
    assertEquals(
        "It is single-threaded.",
        contradiction.get().claim(),
        "the leading list dash must be stripped from the quote");
  }

  @Test
  void shouldQuoteAConcurrentDispatchThatSurvivesOnAContextLine() {
    // The dispatch is on an unchanged context line (space-prefixed): still live code, so it refutes
    // the decline and the marker is stripped from the quote.
    var contextDispatch =
        "diff --git a/Worker.java\n@@ -1,3 +1,3 @@\n     pool.submit(task);\n+    log(task);\n";

    var contradiction =
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", contextDispatch);

    assertTrue(contradiction.isPresent());
    assertEquals("pool.submit(task);", contradiction.get().evidence());
  }
}
