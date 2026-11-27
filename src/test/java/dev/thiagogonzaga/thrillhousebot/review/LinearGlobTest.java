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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link LinearGlob} against the JDK's own {@code glob:} matcher, which it replaces: the same
 * patterns accepted and rejected, the same paths matched, and a cost that does not grow with the
 * number of wildcards.
 */
class LinearGlobTest {

  private static final List<String> PATHS =
      List.of(
          "",
          "a",
          "b",
          "ab",
          "a.b",
          "a/b",
          "a/b/c",
          "a-c",
          "a,b",
          "abc",
          "x",
          "-x",
          "^",
          "!",
          "&",
          "[",
          "]",
          "\\",
          "{a,b}",
          "a}",
          "a*b",
          "a?b",
          "c.d",
          "bc.d",
          "pom.xml",
          "Main.java",
          "src/Main.java",
          "src/main/java/App.java",
          "src/test/java/AppTest.java",
          "target/classes/App.class",
          "a/target/x",
          "app.generated.ts",
          "web/dist/app.min.js",
          "docs/guide/intro.md",
          "package-lock.json",
          "deep/yarn.lock",
          "img/logo.png",
          "img/photo.jpg",
          "img/anim.gif",
          "file0.txt",
          "file9.txt",
          "fileA.txt",
          ".env",
          "config/.env.local",
          "with space/a b.txt",
          "a\nb",
          "a/b\nc/d",
          "a/b c/d.txt");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*",
        "**",
        "***",
        "?",
        "??",
        "a",
        "a*",
        "*b",
        "*.*",
        "*.java",
        "**/*.java",
        "**.java",
        "src/**",
        "src/**/*.java",
        "src/**/*Test*.java",
        "**/target/**",
        "**/*.generated.*",
        "**/*.min.js",
        "*.lock",
        "**/*.lock",
        "*/*",
        "*/*/**/*",
        "a/**/b",
        "a/**/c",
        "**/a",
        "**a",
        "a**c",
        "*.{png,jpg}",
        "**/*.{png,jpg,gif}",
        "{a,b}",
        "{a,b}{c,}",
        "{,a}b",
        "{a*,*.d}",
        "a{b,c*,d?}e",
        "{src,docs}/**",
        "{a}",
        "{}",
        "a,b",
        "a}",
        "file[0-9].txt",
        "file[!0-9].txt",
        "[a-c]",
        "[!a-c]",
        "[a-c]*",
        "[!a-c]?b",
        "[abc].d",
        "[a-]",
        "[a-]c",
        "[-a]x",
        "[!-a]x",
        "[^a]",
        "[^]",
        "[a^]",
        "[a&&b]",
        "[a&b]",
        "[[]",
        "[\\\\]",
        "[*?]",
        "a\\*b",
        "a\\?b",
        "\\{a,b\\}",
        "\\[",
        "\\\\",
        "\\a",
        ".env",
        "**/.env*",
        "with space/*",
        "a?b",
        "a*b",
        "**/*.md",
        "?/x"
      })
  void matchesTheSamePathsAsTheJdkGlob(String glob) {
    var jdk = FileSystems.getDefault().getPathMatcher("glob:" + glob);
    var linear = LinearGlob.compile(glob);

    for (var path : PATHS) {
      assertEquals(
          jdk.matches(Path.of(path)),
          linear.matches(Path.of(path).toString()),
          glob + " on [" + path + "]");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a\\", "[", "[a", "[a-", "[!", "[a/b]", "[z-a]", "[a-c-e]", "[a--]", "{a", "{a,b", "{a{b}}",
        "{a,{b}", "[]a]", "x[]", "[!]", "[a-c-]"
      })
  void rejectsWhatTheJdkGlobRejects(String glob) {
    var fileSystem = FileSystems.getDefault();
    assertThrows(PatternSyntaxException.class, () -> fileSystem.getPathMatcher("glob:" + glob));

    assertThrows(PatternSyntaxException.class, () -> LinearGlob.compile(glob));
  }

  @Test
  void aStarStaysInsideOneSegmentAndTwoCrossThem() {
    assertTrue(LinearGlob.compile("*.java").matches("App.java"));
    assertFalse(LinearGlob.compile("*.java").matches("src/App.java"));
    assertTrue(LinearGlob.compile("**.java").matches("src/App.java"));
    assertTrue(LinearGlob.compile("src/**").matches("src/a/b"));
    assertFalse(LinearGlob.compile("**/App.java").matches("App.java"));
    assertFalse(LinearGlob.compile("a?c").matches("a/c"));
    assertFalse(LinearGlob.compile("a[!b]c").matches("a/c"));
    assertTrue(LinearGlob.compile("").matches(""));
    assertFalse(LinearGlob.compile("").matches("a"));
  }

  @Test
  void twoStarsDoNotCrossALineBreakInAName() {
    for (var lineBreak : List.of("\n", "\r", "\u0085", " ", " ")) {
      assertFalse(LinearGlob.compile("**").matches("a" + lineBreak + "b"));
      assertTrue(LinearGlob.compile("*").matches("a" + lineBreak + "b"));
    }
  }

  @Test
  void aCharacterOutsideTheBasicPlaneIsOneCharacter() {
    assertTrue(LinearGlob.compile("?").matches("😀"));
    assertTrue(LinearGlob.compile("a😀*").matches("a😀b"));
    assertTrue(LinearGlob.compile("\\😀").matches("😀"));
    assertTrue(LinearGlob.compile("[😀é]").matches("é"));
    assertTrue(LinearGlob.compile("**/日本語.*").matches("dir/日本語.md"));
    assertFalse(LinearGlob.compile("??").matches("😀"));
    assertFalse(LinearGlob.compile("\ud83d").matches("😀"));
    assertTrue(LinearGlob.compile("\ud83d").matches("\ud83d"));
  }

  /**
   * What a pattern after a leading {@code **}{@code /} is matched with: any segment may open it.
   */
  @Test
  void aMatchFromASegmentStartsAtTheHeadOfThePathOrAfterASeparator() {
    var lock = LinearGlob.compile("*.lock");
    assertTrue(lock.matchesFromASegment("yarn.lock"));
    assertTrue(lock.matchesFromASegment("web/app/yarn.lock"));
    assertFalse(lock.matchesFromASegment("web/yarn.lock/readme"));

    var tree = LinearGlob.compile("build/**");
    assertTrue(tree.matchesFromASegment("build/out.js"));
    assertTrue(tree.matchesFromASegment("web/build/js/out.js"));
    assertFalse(tree.matchesFromASegment("web/rebuild/out.js"));
    assertFalse(tree.matchesFromASegment("build"));

    // A match may not open in the middle of a segment, nor after the path's last separator.
    assertFalse(LinearGlob.compile("b/c").matchesFromASegment("ab/c"));
    assertTrue(LinearGlob.compile("b/c").matchesFromASegment("a/b/c"));
    assertTrue(LinearGlob.compile("a/b").matchesFromASegment("/a/b"));
    assertFalse(LinearGlob.compile("/a/b").matchesFromASegment("/a/b"));
    assertFalse(LinearGlob.compile("**").matchesFromASegment("/"));
    assertTrue(LinearGlob.compile("").matchesFromASegment(""));
  }

  @Test
  void aPatternLongerThanTheLimitIsRejected() {
    var longest = "a".repeat(LinearGlob.MAX_PATTERN_CHARS);
    assertTrue(LinearGlob.compile(longest).matches(longest));

    var thrown =
        assertThrows(PatternSyntaxException.class, () -> LinearGlob.compile(longest + "a"));
    assertTrue(thrown.getMessage().contains("512"), thrown.getMessage());
  }

  /**
   * The JDK matcher retries from every wildcard: {@code *a*a*a*a*a*b} on 250 {@code a}s takes it
   * hours. The longest pattern accepted, made of nothing but wildcards, is matched here against a
   * path of 4,000 characters, as many times as a pull request has files to try a few globs on.
   */
  @Test
  void theCostOfAMatchDoesNotGrowWithTheNumberOfWildcards() {
    var name = "a".repeat(250);
    var deep = "a/".repeat(2_000);
    var stars = LinearGlob.compile("*a*a*a*a*a*b");
    var manyStars = LinearGlob.compile("*a".repeat(255) + "*b");
    var manyGlobstars = LinearGlob.compile("**a".repeat(170) + "b");
    var manyChoices = LinearGlob.compile("{" + "*a,".repeat(165) + "b}*b");

    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          for (int i = 0; i < 3_000; i++) {
            assertFalse(stars.matches(name));
          }
          for (int i = 0; i < 20; i++) {
            assertFalse(manyStars.matches(deep.replace("/", "")));
            assertFalse(manyGlobstars.matches(deep));
            assertFalse(manyGlobstars.matchesFromASegment(deep));
            assertFalse(manyChoices.matches(deep.replace("/", "")));
          }
        });
  }
}
