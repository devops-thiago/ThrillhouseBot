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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link TestAssertions}: the assertion lines of a failing test's body in the changed files. */
class TestAssertionsTest {

  private static GitHubPullRequestClient.FileDiff file(String path, String patch) {
    return new GitHubPullRequestClient.FileDiff(path, "modified", 1, 0, 1, patch);
  }

  @Test
  void noFileOrNoPatchReadsNothing() {
    assertSame(TestAssertions.NONE, TestAssertions.of(null));
    assertSame(TestAssertions.NONE, TestAssertions.of(List.of()));
    assertSame(TestAssertions.NONE, TestAssertions.of(List.of(file("a.bin", null))));
    assertEquals(List.of(), TestAssertions.NONE.assertionsOf("anything_at_all"));
  }

  /**
   * A Python body ends at the first line indented no deeper than its {@code def}; blank lines
   * inside it do not end it, and a test whose name only begins the identifier is not declared.
   */
  @Test
  void anIndentedBodyEndsAtItsDeclarationsIndentation() {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    "tests/test_waitlist.py",
                    """
                    @@ -10,3 +10,11 @@ class WaitlistTest(unittest.TestCase):
                         def test_join(self):
                    +        position = self.waitlist.join("Ana")
                    +
                    +        self.assertEqual(position, 1)
                    +    def test_join_twice(self):
                    +        self.assertEqual(self.waitlist.join("Bo"), 2)
                    +
                    +    def test_leave(self):
                    +        self.assertTrue(self.waitlist.leave("Ana"))
                    """)));

    assertEquals(List.of("self.assertequal(position, 1)"), assertions.assertionsOf("test_join"));
  }

  /** A body stops at its hunk's end: the next hunk's lines are another part of the file. */
  @Test
  void aBodyEndsWithItsHunk() {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    "store_test.go",
                    """
                    @@ -1,2 +1,3 @@
                     func TestStoreKeepsOrder(t *testing.T) {
                    +\tif got := s.First(); got != "a" { t.Errorf("got %q", got) }
                    @@ -40,1 +41,2 @@
                    +\tt.Fatalf("unreachable in this test: %v", err)
                    """)));

    assertEquals(
        List.of("if got := s.first(); got != \"a\" { t.errorf(\"got %q\", got) }"),
        assertions.assertionsOf("TestStoreKeepsOrder"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts"})
  void commonJsImportsAreSetupNotAssertions(String extension) {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    "client.test." + extension,
                    """
        @@ -0,0 +1,7 @@
        +  test("loads the client", () => {
        +    const api = require('../api');
        +    const dynamic = require(moduleName);
        +    const resolved = require.resolve('../api');
        +    module.require('../api');
        +    expect(api.ready()).toBe(true);
        +  });
        """)));

    assertEquals(
        List.of("expect(api.ready()).tobe(true);"), assertions.assertionsOf("loads the client"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Checks.kt", "Checks.kts", "Checks.scala"})
  @NullSource
  void nonJavaScriptRequireChecksRemainAssertions(String path) {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    path,
                    """
        @@ -0,0 +1,4 @@
        +  fun checksApi() {
        +    require(api.isReady())
        +    require.NoError(t, err)
        +  }
        """)));

    assertEquals(
        List.of("require(api.isready())", "require.noerror(t, err)"),
        assertions.assertionsOf("checksApi"));
  }

  @Test
  void moduleImportsInOneFileDoNotDisableRequireChecksInAnother() {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    "client.test.js",
                    """
            @@ -0,0 +1,4 @@
            +  function checksApi() {
            +    const api = require('../api');
            +    expect(api.ready()).toBe(true);
            +  }
            """),
                file(
                    "Checks.kt",
                    """
            @@ -0,0 +1,3 @@
            +  fun checksApi() {
            +    require(api.isReady())
            +  }
            """)));

    assertEquals(
        List.of("expect(api.ready()).tobe(true);", "require(api.isready())"),
        assertions.assertionsOf("checksApi"));
  }

  /** A name of several words is declared where it is written, as a Jest or Vitest title is. */
  @Test
  void aPhraseNamedTestIsDeclaredByItsTitle() {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    "src/pool.test.ts",
                    """
                    @@ -1,0 +1,3 @@
                    +  it("returns exactly the slots requested", () => {
                    +    expect(pool.take(2)).toHaveLength(2);
                    +  });
                    """)));

    assertEquals(
        List.of("expect(pool.take(2)).tohavelength(2);"),
        assertions.assertionsOf("returns exactly the slots requested"));
  }

  /**
   * An identifier test is declared only where its name stands alone, at the line's start, end or
   * between other characters; a body may run to the end of the patch.
   */
  @Test
  void anIdentifierIsDeclaredOnlyAsAWholeIdentifier() {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    "tests/batch.rs",
                    """
                    @@ -0,0 +1,5 @@
                    +fn reparses_batch() { assert!(reparsed()); }
                    +fn parses_batch_lines() { assert!(parsed_lines()); }
                    +// calls parses_batch
                    +parses_batch();
                    +    assert_eq!(parses_batch_result(), 1);
                    """)));

    assertEquals(
        List.of("assert_eq!(parses_batch_result(), 1);"), assertions.assertionsOf("parses_batch"));
  }

  /**
   * Only an assertion call is an assertion line: at the start of the line, after a qualifier, or
   * after {@code let}/{@code =}. A setup call whose name merely starts with a keyword and a comment
   * using one are not.
   */
  @Test
  void onlyAnAssertionCallIsAnAssertionLine() {
    var assertions =
        TestAssertions.of(
                List.of(
                    file(
                        "src/test/BrokerTest.java",
                        """
                        @@ -0,0 +1,20 @@
                        +  void retriesWhenTheBrokerFails() {
                        +    var expected = expectedFor(name);
                        +    String required = requirements.first();
                        +    // should retry when the broker returns 503
                        +    /* expect one retry */
                        +     * verify the backoff
                        +    # assert later
                        +    -- refute nothing
                        +    assertEquals(expected, broker.send(name));
                        +    self.assertTrue(broker.retried)
                        +    expect(broker.calls()).toHaveLength(2);
                        +    assert broker.calls == 2
                        +    assert_eq!(calls, 2);
                        +    let _ = verify(mock).send(name);
                        +    if got != want { t.Errorf("got %d", got) }
                        +    t.Fatalf("broker: %v", err)
                        +    Assertions.assertThat(calls).isEqualTo(2);
                        +    assert.equal(calls, 2);
                        +    $mock.verify(send);
                        +  }
                        """)))
            .assertionsOf("retriesWhenTheBrokerFails");

    assertEquals(
        List.of(
            "assertequals(expected, broker.send(name));",
            "self.asserttrue(broker.retried)",
            "expect(broker.calls()).tohavelength(2);",
            "assert broker.calls == 2",
            "assert_eq!(calls, 2);",
            "let _ = verify(mock).send(name);",
            "if got != want { t.errorf(\"got %d\", got) }",
            "t.fatalf(\"broker: %v\", err)",
            "assertions.assertthat(calls).isequalto(2);",
            "assert.equal(calls, 2);",
            "$mock.verify(send);"),
        assertions);
  }

  /**
   * A variable or helper named after a keyword is not an assertion: {@code should} and {@code
   * require} count only as the bare keyword ({@code should.equal(}, {@code x.should.be}, {@code
   * require.NoError(}), and a suffixed name ({@code assertEquals}) only as a call.
   */
  @Test
  void aNameThatStartsWithAKeywordIsNotAnAssertion() {
    var assertions =
        TestAssertions.of(
                List.of(
                    file(
                        "src/test/RetryTest.java",
                        """
                        @@ -0,0 +1,21 @@
                        +  void retriesOnce() {
                        +    var shouldRetry = shouldRetryFor(response);
                        +    result = shouldRetry(response)
                        +    shouldRetry.set(true);
                        +    assertCount.incrementAndGet();
                        +    verifyCount.set(0);
                        +    expectThrows.add(error);
                        +    requireNonNull(input);
                        +    requireAuth(user)
                        +    x.should.equal(1);
                        +    should.exist(response);
                        +    require.NoError(t, err)
                        +    verifyNoMoreInteractions(mock);
                        +    expectThrows(IllegalStateException.class, () -> run());
                        +    refute_nil(result)
                        +    AssertCount = 0;
                        +    Assert.AreEqual(2, calls);
                        +    calls.Should().Be(2);
                        +    Expect(calls).To(Equal(2))
                        +    mock.Verify(m => m.Send(), Times.Once());
                        +  }
                        """)))
            .assertionsOf("retriesOnce");

    assertEquals(
        List.of(
            "x.should.equal(1);",
            "should.exist(response);",
            "require.noerror(t, err)",
            "verifynomoreinteractions(mock);",
            "expectthrows(illegalstateexception.class, () -> run());",
            "refute_nil(result)",
            "assert.areequal(2, calls);",
            "calls.should().be(2);",
            "expect(calls).to(equal(2))",
            "mock.verify(m => m.send(), times.once());"),
        assertions);
  }

  /**
   * A project's own assert and verify helpers are read as assertions: by convention they wrap
   * checks, and in a failing test the helper's call line is where the failure is reported.
   */
  @Test
  void aProjectsOwnAssertOrVerifyHelperIsAnAssertion() {
    var assertions =
        TestAssertions.of(
                List.of(
                    file(
                        "src/test/UserTest.java",
                        """
                        @@ -0,0 +1,6 @@
                        +  void createsTheUser() {
                        +    var created = service.create(request);
                        +    verifyUserExists(created, repository);
                        +    assertValidOrder(created.order());
                        +    shouldNotify(created);
                        +  }
                        """)))
            .assertionsOf("createsTheUser");

    assertEquals(
        List.of("verifyuserexists(created, repository);", "assertvalidorder(created.order());"),
        assertions);
  }

  /** A brace on its own line under the declaration (Allman style) opens the body, not ends it. */
  @Test
  void aBraceOnItsOwnLineOpensTheBody() {
    var assertions =
        TestAssertions.of(
                List.of(
                    file(
                        "tests/PoolTests.cs",
                        """
                        @@ -0,0 +1,9 @@
                        +    [Fact]
                        +    public void TakeReturnsTheSlotsRequested()
                        +    {
                        +        Assert.Equal(2, pool.Take(2).Count);
                        +    }
                        +
                        +    [Fact]
                        +    public void Other()
                        +    { Assert.True(false); }
                        """)))
            .assertionsOf("TakeReturnsTheSlotsRequested");

    assertEquals(List.of("assert.equal(2, pool.take(2).count);"), assertions);
  }

  /**
   * The patches are read once, however many failing tests the job log names: each test is then
   * looked up, not searched for line by line.
   */
  @Test
  void thePatchesAreReadOncePerManyTests() {
    var patch = new StringBuilder("@@ -0,0 +1,300 @@\n");
    for (int i = 0; i < 100; i++) {
      patch
          .append("+fn case_number_")
          .append(i)
          .append("() {\n+    assert_eq!(value(")
          .append(i)
          .append("), 1);\n+}\n");
    }
    var assertions = TestAssertions.of(List.of(file("tests/cases.rs", patch.toString())));

    for (int i = 0; i < 100; i++) {
      assertEquals(
          List.of("assert_eq!(value(" + i + "), 1);"), assertions.assertionsOf("case_number_" + i));
    }
    assertEquals(300, assertions.linesRead());
  }

  /**
   * A title of several words is found by its longest word and then read whole: a line holding the
   * word but not the title declares nothing, and a name with no word to look up finds nothing.
   */
  @Test
  void aTitleIsLookedUpByItsLongestWordAndReadWhole() {
    var assertions =
        TestAssertions.of(
            List.of(
                file(
                    "src/pool.test.ts",
                    """
                    @@ -0,0 +1,6 @@
                    +  it("requested slots are kept", () => {
                    +    expect(pool.kept()).toBe(true);
                    +  });
                    +  it("returns exactly the slots requested", () => {
                    +    expect(pool.take(2)).toHaveLength(2);
                    +  });
                    """)));

    assertEquals(
        List.of("expect(pool.take(2)).tohavelength(2);"),
        assertions.assertionsOf("returns exactly the slots requested"));
    assertEquals(List.of(), assertions.assertionsOf("» ·"));
  }

  /** A body is read for at most {@link TestAssertions#MAX_BODY_LINES} lines. */
  @Test
  void aBodyIsReadForABoundedNumberOfLines() {
    var patch = new StringBuilder("@@ -0,0 +1,300 @@\n+fn long_body_test() {\n");
    for (int i = 0; i < TestAssertions.MAX_BODY_LINES + 50; i++) {
      patch.append("+    assert_eq!(value(").append(i).append("), ").append(i).append(");\n");
    }
    var assertions =
        TestAssertions.of(List.of(file("tests/long.rs", patch.toString())))
            .assertionsOf("long_body_test");

    assertEquals(TestAssertions.MAX_BODY_LINES, assertions.size());
  }

  /** Quotes are one-line backtick spans long enough to be an assertion's own code. */
  @Test
  void quotesAreLongEnoughBacktickSpans() {
    assertEquals(
        List.of("assert_eq!(zone(7.1, &t), 2);"),
        TestAssertions.quotesIn(
            "`x + 1` and `assert_eq!(zone(7.1,   &t), 2);` and `split\nacross lines here`"));
  }

  /** A finding quotes code in single quotes too, as scala ThrillhouseBot-test#297's did (#1194). */
  @Test
  void singleQuotedSpansAreQuotesToo() {
    assertEquals(
        List.of("assert(excursiondetector.rollingaverage(rs, 3) == 6.0)"),
        TestAssertions.quotesIn(
            "the test asserts 'assert(ExcursionDetector.rollingAverage(rs, 3) == 6.0)', the"
                + " average of the last three; it's 'short'"));
  }

  /** A contraction's apostrophe opens no span that swallows a backtick quote after it. */
  @Test
  void aContractionDoesNotHideABacktickQuote() {
    assertTrue(
        TestAssertions.quotesIn(
                "it's wrong: `assert_eq!(zone(7.1, &t), 2);` isn't what the test expects")
            .contains("assert_eq!(zone(7.1, &t), 2);"));
  }

  /** A check inside a loop or a condition keeps the test function it is in. */
  @Test
  void aNestedCheckLabelLeadsToItsTestFunction() {
    var tests =
        TestAssertions.of(
            List.of(
                file(
                    "c/tests/test_sweep.c",
                    """
                    @@ -0,0 +1,9 @@
                    +static void test_sweep_expires_codes(void)
                    +{
                    +    for (int i = 0; i < 3; i++) {
                    +        if (codes[i].active) {
                    +            CHECK("sweep_expires_every_code", codes[i].expired);
                    +        }
                    +    } while (0);
                    +    CHECK("sweep_keeps_the_dock_free", dock_free);
                    +}
                    """)));

    assertEquals(
        List.of("test_sweep_expires_codes"), tests.functionsLabelling("sweep_expires_every_code"));
  }

  /** A check under a switch's case label or a preprocessor guard keeps its function too. */
  @Test
  void aCheckUnderACaseLabelOrAGuardLeadsToItsTestFunction() {
    var tests =
        TestAssertions.of(
            List.of(
                file(
                    "c/tests/test_expiry.c",
                    """
                    @@ -0,0 +1,11 @@
                    +static void test_expire_codes(void) {
                    +    switch (rc) {
                    +    case DISP_LIMIT:
                    +        CHECK("expiry_rejects_a_fourth_code", ok);
                    +        break;
                    +    }
                    +#ifdef WITH_SWEEP
                    +    CHECK("expiry_sweeps_with_the_option", swept);
                    +#endif
                    +}
                    """)));

    assertEquals(
        List.of("test_expire_codes"), tests.functionsLabelling("expiry_rejects_a_fourth_code"));
    assertEquals(
        List.of("test_expire_codes"), tests.functionsLabelling("expiry_sweeps_with_the_option"));
  }

  /** Lines 59-71 of c ThrillhouseBot-test#302's {@code c/tests/test_dispenser.c}. */
  private static final String C_302_TEST =
      """
      @@ -0,0 +59,13 @@
      +static void test_rider_limit(void)
      +{
      +    static config_t cfg;
      +    static dispenser_t d;
      +    int rc;
      +
      +    config_load(&cfg);
      +    setup(&d, &cfg);
      +    dispenser_issue(&d, "rider1", "+15550101", "harbor", 100, stub_notify, NULL);
      +    dispenser_issue(&d, "rider1", "+15550101", "harbor", 101, stub_notify, NULL);
      +    rc = dispenser_issue(&d, "rider1", "+15550101", "harbor", 102, stub_notify, NULL);
      +    CHECK("rider_limit_rejects_third_code", rc == DISP_LIMIT);
      +}
      """;

  /**
   * A C harness prints the label its {@code CHECK} macro was given, and a finding names the test
   * function the check is in (#1194): the label leads to that function, the Allman brace skipped.
   */
  @Test
  void aCheckLabelLeadsToTheTestFunctionThatPrintsIt() {
    var tests = TestAssertions.of(List.of(file("c/tests/test_dispenser.c", C_302_TEST)));

    assertEquals(
        List.of("test_rider_limit"), tests.functionsLabelling("rider_limit_rejects_third_code"));
    assertEquals(List.of(), tests.functionsLabelling("rider_limit_unknown_label"));
    assertEquals(
        List.of(), tests.functionsLabelling("stub_notify"), "named, not quoted as a label");
    assertEquals(List.of(), TestAssertions.NONE.functionsLabelling("--"), "no identifier");
  }

  /**
   * The label leads nowhere when the line above it indented less declares no test function, when
   * its hunk starts before one, or when the line holding it is not quoted as the label.
   */
  @Test
  void aCheckLabelOutsideATestFunctionLeadsNowhere() {
    var tests =
        TestAssertions.of(
            List.of(
                file(
                    "c/tests/helpers.c",
                    """
                    @@ -0,0 +1,3 @@
                    +static void setup(void)
                    +{
                    +    CHECK("setup_loads_the_config", loaded);
                    @@ -20,0 +20,1 @@
                    +    CHECK("orphan_label_at_hunk_start", ok);
                    """),
                file(
                    "c/tests/flat.c",
                    """
                    @@ -0,0 +1,3 @@
                    +CHECK("top_level_label", ok);
                    +int counter;
                    +    CHECK("indented_under_a_declaration", ok);
                    """)));

    assertEquals(List.of(), tests.functionsLabelling("setup_loads_the_config"));
    assertEquals(List.of(), tests.functionsLabelling("orphan_label_at_hunk_start"));
    assertEquals(List.of(), tests.functionsLabelling("top_level_label"));
    assertEquals(List.of(), tests.functionsLabelling("indented_under_a_declaration"));
  }

  /** The walk up from a label stops after {@link TestAssertions#MAX_BODY_LINES} lines. */
  @Test
  void aCheckLabelTooFarBelowItsFunctionLeadsNowhere() {
    var patch = new StringBuilder("@@ -0,0 +1,202 @@\n+void test_long_body(void) {\n");
    for (int i = 0; i < TestAssertions.MAX_BODY_LINES; i++) {
      patch.append("+    step(").append(i).append(");\n");
    }
    patch.append("+    CHECK(\"long_body_final_check\", ok);\n");

    assertEquals(
        List.of(),
        TestAssertions.of(List.of(file("c/tests/long.c", patch.toString())))
            .functionsLabelling("long_body_final_check"));
  }
}
