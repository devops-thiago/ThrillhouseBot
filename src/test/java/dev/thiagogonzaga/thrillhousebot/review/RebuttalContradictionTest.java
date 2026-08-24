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
        // The count wrapped onto a following added line: its leading '+' is a diff marker, not
        // code, and must not hide the count from the exclusion (#761).
        "+    var pool = Executors.newFixedThreadPool(\n+        1);",
        "+    var pool = Executors.newFixedThreadPool(\n+        1, factory);",
        // The same wrap on context lines was already excluded and must stay so.
        "     var pool = Executors.newFixedThreadPool(\n         1);",
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
        // A genuinely concurrent count wrapped onto a following added line still registers.
        "+    var pool = Executors.newFixedThreadPool(\n+        8);",
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
        // An unclosed opener is rescanned as text, so the later closed literal is still stepped
        // over whole rather than the line being cut at its quoted slashes.
        "+    let f = &'a ctx; var s = \"//cdn.example.com\"; executor.submit(() -> run(ctx));",
      })
  void shouldKeepDispatchEvidenceThatFollowsAQuotedDoubleSlash(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "the // sits inside a string literal, so the dispatch after it is live code: " + codeLine);
  }

  /**
   * Dispatch text quoted inside a string literal is prose, not live code. Matching it overruled a
   * maintainer whose "it runs serially" decline was correct — the over-fire direction, which is the
   * more expensive one because it argues with a maintainer who is right.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // A log or documentation string naming the construct.
        "+    var m = \"hand work to .submit( here\";",
        "+    log.info(\"call executor.execute( to enqueue\");",
        // A single-quoted and a backtick (Go raw) literal.
        "+    msg := 'use pool.submit( for async work'",
        "+    doc := `each event calls handler.execute(ctx)`",
        // Every other construct the matcher knows, quoted rather than run.
        "+    var s = \"Executors.newCachedThreadPool() would be wrong here\";",
        "+    var s = \"Executors.newFixedThreadPool(8) was removed\";",
        "+    var s = \"CompletableFuture.runAsync is not used\";",
        "+    var s = \"do not add new Thread( calls\";",
        "+    var s = \"items.parallelStream() is banned in this module\";",
        // An escaped quote does not close the literal early and expose the dispatch text.
        "+    var s = \"a\\\".submit(\";",
        // Two single-quoted strings on one line: the first's closer sits more than a char
        // literal's width from the next opener, so both stay literals and both are blanked.
        "+    var s = 'quote .submit( here' + 'more prose text here'",
        // The same shape with the openers far enough apart that even an escape-length span
        // cannot be mistaken for a char literal.
        "+    var s = 'quote .submit( here' and afterwards 'more prose text here'",
      })
  void shouldNotTreatDispatchTextInsideAStringLiteralAsEvidence(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine + "\n").isEmpty(),
        "the dispatch text sits inside a string literal, so it is not live code: " + codeLine);
  }

  /** The other direction: a real dispatch next to an unrelated literal still registers. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    log.info(\"enqueue\"); executor.submit(() -> run(ctx));",
        "+    executor.submit(() -> run(ctx)); log.info(\"enqueued one task\");",
        // The literal itself quotes dispatch text, and the real dispatch follows it.
        "+    log.info(\"about to .submit( work\"); executor.submit(() -> run(ctx));",
        // An unclosed opener is ordinary text, so the dispatch after it stays visible.
        "+    let f = &'a ctx; executor.submit(() -> run(ctx));",
        // A lifetime's apostrophe pairing with a later char literal's opener must not blank the
        // live code between them: the ; inside the would-be span marks it as code, not a literal.
        "+    let f = &'a ctx; executor.submit(() -> run(ctx)); let nl = '\\n';",
        "+    let f = &'a ctx; executor.submit(() -> run(ctx)); let sep = ',';",
        // A turbofish lifetime pairing with a later char literal: no ; sits inside the misread
        // span, so the lifetime position (after <) and the char-literal-shaped closer are the
        // tells that keep the dispatch between the apostrophes live.
        "+    foo::<'a>(executor.submit(|| ()), '\\n');",
        // Two lifetimes with the dispatch between them and no char literal at all.
        "+    fn go<'a>(e: &'a Exec) { e.submit(run); }",
        // A span holding a ; is code shape even with the apostrophe in column zero, where there
        // is no prefix character to consult.
        "+'a; b' executor.submit(() -> run(ctx))",
        // A stray apostrophe in an identifier pairing with a char literal's opener: neither a
        // lifetime prefix nor a ; exists, so the char-literal-shaped closer is the tell.
        "+    log(don't panic, executor.submit(() -> run(ctx)), '\\n')",
        // A trait-bound lifetime (not after & or <) pairing with a long char-literal escape: the
        // escape-shaped closer is the tell that keeps the dispatch live.
        "+    fn f<T: 'a>(e: &mut Exec) { e.submit(run) } let c = '\\u{1F600}';",
        "+    fn f<T: 'a>(e: &mut Exec) { e.submit(run) } let c = '\\u0041';",
      })
  void shouldKeepRealDispatchNextToAStringLiteral(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine + "\n").isPresent(),
        "the dispatch sits outside the literal, so it is live code: " + codeLine);
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
        // An opener with no closer is not a literal: a Rust lifetime, and an apostrophe in prose.
        "+    let f = handler(&'a ctx); // executor.submit(() -> run(ctx));",
        "+    var n = count; // don't let this executor.submit(task) come back",
        // A closed literal earlier on the line must not consume the resumed scan.
        "+    var a = \"//x\"; let f = &'a ctx; // executor.submit(() -> run(ctx));",
        // A closed literal after the unclosed opener is stepped over, and the real comment
        // beyond it is still stripped.
        "+    let f = &'a ctx; var s = \"//x\"; // executor.submit(() -> run(ctx));",
        // A file URL keeps its three slashes rather than reading as a comment.
        "+    var u = \"file:///etc/hosts\"; // executor.submit(() -> run(ctx));",
        // The scheme carve-out still applies after the unclosed opener, so the resumed scan lands
        // on the real comment rather than on the URL's slashes.
        "+    let u = &'a; see http://example.com // executor.submit(() -> run(ctx));",
        // A second // after the same unclosed opener must not move the cut past the first.
        "+    let f = &'a ctx; // note // executor.submit(() -> run(ctx));",
      })
  void shouldNotReadACommentAsDispatchEvidenceAfterABackslash(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isEmpty(),
        "a dispatch named only in a comment is not live code, so the decline stands: " + codeLine);
  }

  /** The resumed scan after an unclosed opener must cut at the comment, not before the code. */
  @Test
  void shouldKeepDispatchBeforeACommentOnALineWithAnUnclosedQuote() {
    var codeLine = "+    let f = &'a ctx; executor.submit(() -> run(ctx)); // one per request\n";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "the dispatch is live code before the comment, so it still refutes the decline");

    var loneSlash = "+    let f = &'a ctx; executor.submit(() -> run(ctx)); /\n";
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", loneSlash).isPresent(),
        "a single trailing slash opens no comment, so there is nothing to strip");
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
  void shouldIgnoreClaimsThatAppearOnlyInsideASingleBacktickSpan() {
    var rebuttal =
        "Declining — the bot's own text says `it never runs concurrently`, but that quote is not"
            + " our position and the finding is accepted risk for this release.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a claim quoted inside a single-backtick span is not the maintainer's own assertion");
  }

  @Test
  void shouldIgnoreClaimsThatAppearOnlyInsideADoubleBacktickSpan() {
    var rebuttal =
        "Declining — the constant is named ``RUNS_SERIALLY`` for historical reasons only, and the"
            + " finding is accepted risk for this release.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a claim quoted inside a double-backtick span is not the maintainer's own assertion");
  }

  @Test
  void shouldIgnoreClaimsThatAppearOnlyInsideATripleBacktickSpanOnOneLine() {
    var rebuttal =
        "Declining — the docstring literally reads ```runs serially per key``` and we are keeping"
            + " that wording; no code change in this PR.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "an inline triple-backtick pair is consumed as a fenced block, never as an assertion");
  }

  @Test
  void shouldNotLetASpanInsideABlockquoteLineSplitItsTailIntoAnAssertion() {
    var rebuttal =
        """
        > The bot's finding names the pool `serial`; the executor runs the single-threaded pool
        Accepted risk, we are not changing this in the current release.
        """;

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a blockquoted line must be dropped whole even when it contains a code span");
  }

  @Test
  void shouldStripADoubleBacktickSpanThatQuotesALoneBacktick() {
    var rebuttal =
        "Declining — the config literally reads ``x`single-threaded pool`` and we accept the risk"
            + " for this release.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a double-backtick span quoting a lone backtick must be stripped whole, not half-stripped"
            + " at the first inner backtick pair");
  }

  @Test
  void shouldStripASingleBacktickSpanWhoseBodyCarriesALongerBacktickRun() {
    var rebuttal =
        "Declining — the config literally reads `the pool is a``single-threaded pool` and we"
            + " accept the risk for this release.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a single-backtick span closes at the next run of exactly one backtick, so a longer"
            + " interior run is body text and the span must be stripped whole");
  }

  @Test
  void shouldStripASpanContainingOneLineEnding() {
    var rebuttal =
        "Declining — the doc quotes `the handler is\nsingle-threaded by design` but that is the"
            + " bot's wording, and the finding is accepted risk for this release.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a span crossing one line ending is a valid CommonMark span and must be stripped whole");
  }

  @Test
  void shouldTreatABacktickRunSpanningTwoLineEndingsAsLiteralText() {
    var rebuttal =
        """
        See the note` about pooling.

        Separately, the handler is single-threaded so there is no race here, per the runbook`.""";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isPresent(),
        "a backtick pair spanning more than one line ending is not stripped, so the assertion"
            + " after the paragraph break must still be re-checked");
  }

  @Test
  void shouldTreatAnUnclosedBacktickRunAsLiteralText() {
    var rebuttal =
        "Declining — see the `runbook section on pooling; the handler is single-threaded and"
            + " there is no race here.";

    var contradiction = RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE);

    assertTrue(
        contradiction.isPresent(),
        "an unclosed backtick run is literal text and must not swallow the assertion after it");
    assertTrue(
        contradiction.get().claim().contains("single-threaded"),
        "the note must quote the assertion, was: " + contradiction.get().claim());
  }

  @Test
  void shouldTreatABacktickRunAtTheEndOfTheReplyAsLiteralText() {
    var rebuttal = "The handler is single-threaded, so there is no race here — see `";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isPresent(),
        "a backtick run that ends the reply never closes and must stay literal");
  }

  @Test
  void shouldStripASpanWhoseBodyIsExactlyTheLengthBound() {
    // 984 filler characters plus " single-threaded" make the body exactly 1000 characters, the
    // same maximum the regex passes this scanner replaced would strip.
    var rebuttal =
        "Declining — the doc quotes `"
            + "x".repeat(984)
            + " single-threaded` and the finding is accepted risk for this release.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "a span whose body sits exactly at the length bound must still be stripped whole");
  }

  @Test
  void shouldNotLetADistantCloserBeyondTheBoundSwallowTheReply() {
    var rebuttal =
        "Opening quote `starts here. "
            + "x".repeat(1200)
            + " The handler is single-threaded, there is no race.` end of quote.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isPresent(),
        "a closer beyond the length bound must not let one backtick swallow the reply; the"
            + " assertion inside the unswallowed text must still be re-checked");
  }

  @Test
  void shouldNotBridgeAClaimPhraseAcrossAStrippedSpan() {
    var rebuttal =
        "Accepted risk — per the runbook the deliveries drain one at a`beat`time only in the"
            + " diagram, and we are not changing the code in this PR.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isEmpty(),
        "stripping a span must not join its neighbours into a claim phrase the reply never made");
  }

  @Test
  void shouldMatchTheSameSentenceWhenTheClaimPhraseIsRealRatherThanBridged() {
    var rebuttal =
        "Accepted risk — per the runbook the deliveries drain one at a time only in the"
            + " diagram, and we are not changing the code in this PR.";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE).isPresent(),
        "the bridging probe is only meaningful if the unspanned phrase in the same sentence"
            + " does match");
  }

  @Test
  void shouldStillContradictWhenTheAssertionSitsOutsideTheBacktickedQuotation() {
    var rebuttal =
        "Declining, `executor.submit` here is on the single-threaded pool, so there is no race.";

    var contradiction = RebuttalContradiction.find(RACE_FINDING, rebuttal, DISPATCHING_CODE);

    assertTrue(
        contradiction.isPresent(),
        "the single-threaded assertion outside the code span must still be re-checked");
    assertTrue(
        contradiction.get().claim().contains("single-threaded pool"),
        "the note must quote the assertion, was: " + contradiction.get().claim());
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

  /**
   * A multi-character delimiter whose content legally holds an interior quote. The single-character
   * quote toggle the scanner used to run closed on that quote, so a {@code //} still inside the
   * literal truncated the line and the dispatch after it never reached the matcher — the
   * false-negative direction, which lets a decline that was wrong stand (#651).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // A Java text block quoting HTML: idiomatic in a Java 25 codebase, not exotic.
        "+    var html = \"\"\"<a href=\"//cdn.example.com\">x</a>\"\"\";"
            + " executor.submit(() -> run(ctx));",
        // A Python triple-quoted string holding an apostrophe.
        "+    s = '''a'b//c'''; executor.submit(lambda: run(ctx))",
        // C++ raw strings: bare, with a named delimiter, and with an encoding prefix.
        "+    auto path = R\"(a\"b//c)\"; executor.submit([]{ run(ctx); });",
        "+    auto q = R\"sql(a\"b//c)sql\"; executor.submit([]{ run(ctx); });",
        "+    auto p = u8R\"(a\"b//c)\"; executor.submit([]{ run(ctx); });",
        // An escape inside a text block does not close it early either.
        "+    var t = \"\"\"a\\\"b//c\"\"\"; executor.submit(() -> run(ctx));",
        // A raw string in the first column: the prefix scan must stop at the start of the line.
        "+R\"(a\"b//c)\"; executor.submit(() -> run(ctx));",
      })
  void shouldKeepDispatchAfterALiteralWhoseInteriorQuoteDoesNotCloseIt(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "the interior quote does not close the literal, so the dispatch after it is live code: "
            + codeLine);
  }

  /** A literal that stays open at the end of a line resumes on the next line of the same hunk. */
  @Test
  void shouldKeepDispatchAfterATextBlockWhoseEscapeEndsTheLine() {
    var code = "+    var t = \"\"\"a\\\n+        b\"\"\"; executor.submit(() -> run(ctx));\n";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", code).isPresent(),
        "the text block closes on the second line, so the dispatch after it is live code");
  }

  /**
   * Dispatch text inside a block comment is not live code. The scan stripped line comments and
   * nothing else, so a construct sitting in a block comment was quoted back as evidence and
   * overruled a maintainer whose decline was right — the over-fire direction (#651).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    var n = count; /* executor.submit(() -> run(ctx)); */\n+    run(ctx);\n",
        "+    /* one dispatch we removed:\n+     * executor.submit(() -> run(ctx));\n"
            + "+     */\n+    run(ctx);\n",
      })
  void shouldNotTreatDispatchTextInsideABlockCommentAsEvidence(String code) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", code).isEmpty(),
        "a dispatch named only in a block comment is not live code and keeps the decline: " + code);
  }

  /** The mirror: a closed block comment must not swallow the live code that follows it. */
  @Test
  void shouldKeepDispatchAfterAClosedBlockComment() {
    var codeLine = "+    /* deferred */ executor.submit(() -> run(ctx));\n";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "the dispatch sits after the block comment, so it is live code");
  }

  /**
   * A literal that spans lines — a text block, a Go raw string, a C++ raw string — carries its
   * state to the next line of the same hunk, so the dispatch text quoted inside it is blanked
   * rather than matched as live code (#651).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    var doc = \"\"\"\n+        hand work to executor.submit(task) here\n"
            + "+        \"\"\";\n",
        "+    doc := `\n+      each event calls handler.submit(ctx)\n+    `\n",
        "+    auto sql = R\"sql(\n+      hand work to executor.submit(task)\n+    )sql\";\n",
      })
  void shouldNotTreatDispatchTextInsideALiteralThatSpansLinesAsEvidence(String code) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", code).isEmpty(),
        "the dispatch text is quoted inside a multi-line literal, so it is not live code: " + code);
  }

  /**
   * A JavaScript template literal's {@code ${…}} interpolation is live code, not quoted text.
   * Blanking the backtick literal whole erased a real dispatch sitting inside one — the
   * false-negative direction, opposite to the quoted-text over-fire the blanking exists to close.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    const m = `job ${executor.submit(task)} queued`;",
        // A template nested inside an interpolation of another template.
        "+    const m = `a ${ `b ${executor.submit(t)}` } c`;",
        // Braces inside the interpolation must not close it early.
        "+    const m = `${ fn({k: v}) } ${executor.submit(t)}`;",
      })
  void shouldKeepDispatchInsideATemplateLiteralInterpolation(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "an interpolation is live code, so the dispatch inside it refutes the decline: "
            + codeLine);
  }

  /** The mirror: a string literal inside an interpolation is still quoted text. */
  @Test
  void shouldNotTreatQuotedTextInsideATemplateInterpolationAsEvidence() {
    var codeLine = "+    const m = `${map[\"hand work to .submit( here\"]}`;\n";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isEmpty(),
        "the dispatch text sits in a string inside the interpolation, so it is not live code");
  }

  /**
   * A statement label ends in a colon too, so testing only the character before the slashes read
   * {@code case 1://} as a URL scheme and kept the real comment after it as live code — the
   * over-fire direction, where a dispatch named only in a comment overrules a correct decline.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    case 1:// executor.submit(() -> run(ctx));",
        "+    default:// note: executor.submit(() -> run(ctx));",
        "+  retry:// executor.submit(() -> run(ctx));",
      })
  void shouldNotTreatALabelColonAsAUrlScheme(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isEmpty(),
        "the colon belongs to a label, so what follows is a comment: " + codeLine);
  }

  /**
   * The scheme carve-out used to skip exactly one slash pair, so a URL whose own path holds a
   * doubled slash was cut at the second pair and the dispatch after it was lost. A recognised
   * scheme now consumes the whole URL token.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "+    var url = http://example.com//v1; executor.submit(() -> run(ctx));",
        "+    var url = https://example.com//v1//v2; executor.submit(() -> run(ctx));",
        "+    var f = file:///etc//hosts; executor.submit(() -> run(ctx));",
        // A URL in the first column: the scheme scan must stop at the start of the line.
        "+http://example.com//v1; executor.submit(() -> run(ctx));",
        // A URL that runs to the end of the line, with the dispatch before it.
        "+    executor.submit(() -> run(ctx)); var u = http://example.com//v1",
      })
  void shouldKeepDispatchAfterAUrlWhosePathHoldsADoubledSlash(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "the doubled slash belongs to the URL, so the dispatch after it is live code: " + codeLine);
  }

  /**
   * {@code R"} only opens a raw string when a well-formed delimiter and its {@code (} follow, and
   * when it is not the tail of an identifier. Everything else falls back to a plain string literal,
   * which is what the scan did before raw strings were modelled.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // No ( at all: an ordinary string with an R in front of it (a Python raw string).
        "+    var s = R\"nodelim\"; executor.submit(() -> run(ctx));",
        // Whitespace cannot appear in a C++ raw-string delimiter.
        "+    var s = R\"a b(c)\"; executor.submit(() -> run(ctx));",
        // A delimiter longer than any real one.
        "+    var s = R\"abcdefghijklmnopqr(x)\"; executor.submit(() -> run(ctx));",
        // The R is the last character of an identifier, not a raw-string prefix.
        "+    var s = fooR\"(a)\"; executor.submit(() -> run(ctx));",
        // A capital R that opens no literal at all.
        "+    Runnable r = () -> run(ctx); executor.submit(r);",
      })
  void shouldFallBackToAPlainStringWhenARawStringOpenerIsNotWellFormed(String codeLine) {
    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", codeLine).isPresent(),
        "the literal closes at its quote, so the dispatch after it is live code: " + codeLine);
  }

  /**
   * Carried state is bounded to one hunk. An unclosed block comment blanks the rest of the hunk it
   * opens in — the safe direction, since blanking only ever keeps a decline — and the next hunk
   * header starts from live code again, so one stray delimiter cannot silence a whole patch.
   */
  @Test
  void shouldBoundCarriedLexicalStateToTheHunkThatOpensIt() {
    var withinHunk =
        """
        diff --git a/Worker.java
        @@ -1,2 +1,4 @@
        +    var n = count; /* deferred:
        +    executor.submit(() -> run(ctx));
        """;

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", withinHunk).isEmpty(),
        "an unclosed block comment blanks the rest of its hunk, which keeps the decline");

    var nextHunk =
        """
        diff --git a/Worker.java
        @@ -1,2 +1,3 @@
        +    var n = count; /* deferred:
        @@ -20,2 +20,3 @@
        +    executor.submit(() -> run(ctx));
        """;

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", nextHunk).isPresent(),
        "the hunk header ends the carried state, so the next hunk's dispatch is live code");
  }

  /**
   * A patch whose hunk starts inside a Java text block shows the closing delimiter without its
   * opener — the shape a diff of this repository's own history produces constantly, since a text
   * block constant sits just above the code most hunks touch. Reading that closer as an opener
   * inverts every literal below it and blanks the live code in between, which is the false-negative
   * this scan exists to avoid; a delimiter with a statement terminator after it ends a literal, it
   * does not start one (JLS 3.10.6).
   */
  @Test
  void shouldNotReadATextBlockCloserAtTheTopOfAHunkAsAnOpener() {
    var midLiteral =
        "diff --git a/ReviewResult.java\n"
            + "@@ -340,6 +385,10 @@ public record ReviewResult(\n"
            + " \n"
            + "       \"\"\";\n"
            + " \n"
            + "+    executor.submit(() -> run(ctx));\n";

    var contradiction = RebuttalContradiction.find(RACE_FINDING, "It runs serially.", midLiteral);

    assertTrue(
        contradiction.isPresent(),
        "the hunk opens inside a text block, so its first delimiter closes one and the dispatch"
            + " below it is live code");
    assertEquals("executor.submit(() -> run(ctx));", contradiction.get().evidence());

    // The other spelling of the same closer: a text block concatenated with what follows it, where
    // the terminator sits a space away from the delimiter.
    var concatenated =
        "diff --git a/ReviewResult.java\n"
            + "@@ -340,6 +385,10 @@ public record ReviewResult(\n"
            + " \n"
            + "       \"\"\" + SUFFIX;\n"
            + "+    executor.submit(() -> run(ctx));\n";

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", concatenated).isPresent(),
        "a closer whose terminator is a space away still ends a literal rather than starting one");
  }

  /**
   * The reviewed code arrives as {@code ReviewDiffFormatter} builds it: file sections whose patch
   * sits inside a ```` ```diff ```` fence. A fence line is not a diff body line, so its backticks
   * must not open a template literal that blanks the hunk under it.
   */
  @Test
  void shouldNotLetAMarkdownFenceInTheFormattedDiffBlankTheHunk() {
    var formatted =
        """
        ### src/main/java/Worker.java (modified, +1 -0)
        ```diff
        @@ -1,2 +1,3 @@
        +    executor.submit(() -> run(ctx));
        ```
        """;

    assertTrue(
        RebuttalContradiction.find(RACE_FINDING, "It runs serially.", formatted).isPresent(),
        "the ```diff fence is a section delimiter, not a literal opener");
  }
}
