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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
