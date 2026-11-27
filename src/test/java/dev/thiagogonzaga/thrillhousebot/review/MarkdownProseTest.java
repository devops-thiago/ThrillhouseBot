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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Model-written markdown as {@link MarkdownSafe#prose} posts it. */
class MarkdownProseTest {

  /** An {@code @} GitHub could read as a mention: no letter or digit before it, no guard after. */
  private static final Pattern LIVE_MENTION =
      Pattern.compile("(?<![A-Za-z0-9])@(?!&#8203;)|&#0*64;|&#[xX]0*40;|&commat;");

  /** {@code text} with the code the rewrite keeps taken out: fenced blocks, then one-line spans. */
  private static String outsideCode(String text) {
    var sb = new StringBuilder();
    String fence = null;
    for (var line : text.split("\n", -1)) {
      if (fence == null && line.startsWith("```")) {
        fence = line.substring(0, line.lastIndexOf('`') + 1);
      } else if (fence != null) {
        fence = line.equals(fence) ? null : fence;
      } else {
        sb.append(line.replaceAll("(`+)[^`].*?\\1(?!`)", " ")).append('\n');
      }
    }
    return sb.toString();
  }

  private static void assertNotifiesNobody(String posted) {
    assertFalse(LIVE_MENTION.matcher(outsideCode(posted)).find(), posted);
  }

  @Test
  void aMentionOfAUserOrATeamIsGuardedAndReadsTheSame() {
    assertEquals(
        "Ask @&#8203;octocat or @&#8203;acme/security to look.",
        MarkdownSafe.prose("Ask @octocat or @acme/security to look."));
    assertEquals("(@&#8203;octocat)", MarkdownSafe.prose("(@octocat)"));
    assertEquals("cc:@&#8203;octocat", MarkdownSafe.prose("cc:@octocat"));
    assertEquals("@&#8203;octocat", MarkdownSafe.prose("@octocat"));
  }

  @Test
  void anAnnotationInProseIsGuardedBecauseItIsAlsoSomeonesLogin() {
    assertEquals(
        "The method lacks @&#8203;Override and @&#8203;Inject.",
        MarkdownSafe.prose("The method lacks @Override and @Inject."));
  }

  @Test
  void anEmailAddressAndAnAtInsideAWordAreLeftAlone() {
    var text = "Mail dev@example.com or first.last@example.org; see pkg2@1.4.0.";
    assertEquals(text, MarkdownSafe.prose(text));
  }

  @Test
  void aPathInProseKeepsItsTextAndCannotNotify() {
    var posted = MarkdownSafe.prose("Update node_modules/@types/node and src/@core/index.ts.");
    assertEquals("Update node_modules/@&#8203;types/node and src/@&#8203;core/index.ts.", posted);
    // Rendered, the entity is a zero-width space: the path reads as it was written.
    assertEquals(
        "Update node_modules/@types/node and src/@core/index.ts.", posted.replace("&#8203;", ""));
  }

  @Test
  void codeSpansAndFencedCodeAreKeptByteForByte() {
    var text =
        "Add `@Override` to `src/@types/x.ts` like so:\n"
            + "```java\n"
            + "@Override\n"
            + "\n"
            + "public List<String> names() { return a < b ? x : y; }\n"
            + "```\n"
            + "Then run ``npm i @scope/pkg`` again.";
    assertEquals(text, MarkdownSafe.prose(text));
  }

  @Test
  void aMentionWrittenAsACharacterReferenceIsGuardedToo() {
    assertEquals("@&#8203;octocat", MarkdownSafe.prose("&#64;octocat"));
    assertEquals("@&#8203;octocat", MarkdownSafe.prose("&#x40;octocat"));
    assertEquals("@&#8203;octocat", MarkdownSafe.prose("&#0064;octocat"));
    assertEquals("@&#8203;octocat", MarkdownSafe.prose("&commat;octocat"));
    // The first letter of the name as a reference still follows a guarded "@".
    assertEquals("@&#8203;&#x6f;ctocat", MarkdownSafe.prose("@&#x6f;ctocat"));
    assertEquals("\\@&#8203;octocat", MarkdownSafe.prose("\\@octocat"));
    assertEquals("R&amp;D &copy;", MarkdownSafe.prose("R&amp;D &copy;"));
  }

  @Test
  void guardingIsNotRepeatedOnTextAlreadyGuarded() {
    var once = MarkdownSafe.prose("Ask @octocat about `@Test`, <b>now</b>, ~~~ and ` alone.");
    assertEquals(once, MarkdownSafe.prose(once));
    assertEquals(
        MarkdownSafe.inline("@octocat"), MarkdownSafe.inline(MarkdownSafe.inline("@octocat")));
  }

  @Test
  void aSuggestionFenceBecomesAPlainCodeBlock() {
    assertEquals(
        "Apply this:\n```\nrm -rf /\n```",
        MarkdownSafe.prose("Apply this:\n```suggestion\nrm -rf /\n```"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "```suggestion\nevil()\n```",
        "``` suggestion\nevil()\n```",
        "````suggestion\nevil()\n````",
        "~~~suggestion\nevil()\n~~~",
        "```Suggestion\nevil()\n```",
        "```suggestion:-0+1\nevil()\n```",
        "```suggestion extra\nevil()\n```",
        "   ```suggestion\n   evil()\n   ```",
        "- item\n  ```suggestion\n  evil()\n  ```",
        "1. ```suggestion\n   evil()\n   ```",
        "> ```suggestion\n> evil()\n> ```",
        "> - ```suggestion\n>   evil()\n>   ```",
        "```suggestion\nevil()",
        "\t```suggestion\n\tevil()\n\t```"
      })
  void noSpellingOrNestingOfASuggestionFenceSurvives(String description) {
    var posted = MarkdownSafe.prose(description);

    assertFalse(posted.toLowerCase(java.util.Locale.ROOT).contains("suggestion"), posted);
    // The code itself is kept, in a plain block that is closed.
    assertTrue(posted.contains("```\nevil()\n```"), posted);
  }

  @Test
  void aLanguageTagIsKeptAndAnythingElseOnTheFenceLineIsDropped() {
    assertEquals("```java\nx\n```", MarkdownSafe.prose("```java title=\"ab\"\nx\n```"));
    assertEquals("```\nx\n```", MarkdownSafe.prose("~~~<script>\nx\n~~~"));
    assertEquals("```c++\nx\n```", MarkdownSafe.prose("~~~c++\nx\n~~~"));
  }

  @Test
  void anUnclosedFenceIsClosedSoItCannotTakeInWhatFollows() {
    assertEquals("See:\n```\nint x;\n```", MarkdownSafe.prose("See:\n```\nint x;"));
    assertEquals("See:\n```\n```", MarkdownSafe.prose("See:\n```"));
  }

  @Test
  void aFenceIsEmittedLongerThanAnyBacktickRunItHolds() {
    assertEquals(
        "`````md\n````\ninner ``` text\n````\n`````",
        MarkdownSafe.prose("~~~md\n````\ninner ``` text\n````\n~~~"));
  }

  @Test
  void aNestedFenceIsMovedToTheTopLevelWithItsContentDedented() {
    assertEquals(
        "1. Change the method:\n```java\n@Override\n  void run() {}\n```\n2. Ask @&#8203;octocat.",
        MarkdownSafe.prose(
            "1. Change the method:\n   ```java\n   @Override\n     void run() {}\n   ```\n"
                + "2. Ask @octocat."));
    assertEquals(
        "> Quoted:\n```\n@Test\n```\n> end",
        MarkdownSafe.prose("> Quoted:\n> ```\n> @Test\n> ```\n> end"));
    assertEquals("-\n```sh\nnpm i @a/b\n```", MarkdownSafe.prose("- ```sh\n  npm i @a/b\n  ```"));
  }

  @Test
  void rawHtmlIsEscapedAndComparisonsAreNot() {
    assertEquals(
        "&lt;details>&lt;summary>x&lt;/summary>&lt;img src=y>&lt;/details>",
        MarkdownSafe.prose("<details><summary>x</summary><img src=y></details>"));
    assertEquals("&lt;!-- hidden -->", MarkdownSafe.prose("<!-- hidden -->"));
    assertEquals("&lt;?php x ?>", MarkdownSafe.prose("<?php x ?>"));
    assertEquals(
        "a < b, a <= b, 1<2, x << 3 <", MarkdownSafe.prose("a < b, a <= b, 1<2, x << 3 <"));
    // A generic type in prose keeps its argument; rendered, "&lt;" is "<".
    assertEquals("Returns List&lt;String>.", MarkdownSafe.prose("Returns List<String>."));
    assertEquals("&lt;b>", MarkdownSafe.prose("\\<b>"));
  }

  @Test
  void aMarkerOfTheBotsOwnWrittenByTheModelIsNoLongerAMarker() {
    var posted = MarkdownSafe.prose("Fine.\n<!-- thrillhousebot:finding=7 key=deadbeef -->");

    assertTrue(SuggestionFormatter.parseFindingMarker(posted).isEmpty(), posted);
    assertFalse(posted.contains("<!--"), posted);
  }

  @Test
  void ordinaryMarkdownIsKept() {
    var text =
        "## Why\n\n- **bold**, _em_, ~~gone~~ and a [link](https://example.com/a?b=1)\n"
            + "- ![img](https://example.com/i.png)\n\n> quoted\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n"
            + "1. first\n2. second \\* literal";
    assertEquals(text, MarkdownSafe.prose(text));
  }

  @Test
  void aBacktickThatClosesNothingOnItsLineIsMadeLiteral() {
    // Left as written, the first would pair with the one on the next line and turn the text
    // between them, and what GitHub reads as code after that, inside out.
    assertEquals(
        "open &#96; here\n`@Keep` and @&#8203;octocat",
        MarkdownSafe.prose("open ` here\n`@Keep` and @octocat"));
    assertEquals("x &#96;&#96;&#96;js then text", MarkdownSafe.prose("x ```js then text"));
    assertEquals("a &#96;b", MarkdownSafe.prose("a \\`b"));
    assertEquals("a \\\\`b` c", MarkdownSafe.prose("a \\\\`b` c"));
  }

  @Test
  void aRunOfTildesOutsideAFenceCannotOpenOne() {
    assertEquals("x \\~\\~\\~ y ~~z~~", MarkdownSafe.prose("x ~~~ y ~~z~~"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@victim",
        "text\n    ```\n    @victim\n    ```",
        "text\n    ```\n@victim",
        " ```\ncode\n```\n@victim\n",
        "- x\n  ```\n@victim\n  ```\n@victim2",
        "- x\n    ```\n@victim\n    ```",
        "foo ` bar\n`@victim` baz `",
        "foo `bar\n- @victim` baz",
        "\\`@victim\\`",
        "<a href=\"`\">@victim`",
        "<div>\n```\n@victim\n```\n</div>\n@victim2",
        "```a`b\n@victim\n```",
        "~~~\n@victim\n~~~~\n@victim2\n~~~",
        "```\ncode\n   ```   \n@victim",
        "```\ncode\n    ```\n@victim\n```\n@victim2",
        "> ```\n@victim\n> ```\n@victim2",
        "``` `\n@victim\n```\n@victim2",
        "[x]:\n```\n@victim\n```\n@victim2",
        "a**@victim** _@victim2_ ~@victim3~ |@victim4| \"@victim5\"",
        "&#64;victim &#x40;victim2 &commat;victim3 @&#118;ictim4",
        "@victim\r\n```\r\n@keep\r\n```\r\n@victim2\r\n",
        "$$\n@victim\n$$",
        "@@ -1 +1 @@ @victim"
      })
  void noArrangementOfFencesAndSpansLeavesAMentionInProse(String hostile) {
    var posted = MarkdownSafe.prose(hostile);

    assertNotifiesNobody(posted);
    // Whatever the text was, every fence it ends up with is closed and at the top level.
    long fenceLines = posted.lines().filter(line -> line.startsWith("```")).count();
    assertEquals(0, fenceLines % 2, posted);
    assertFalse(posted.lines().anyMatch(line -> line.matches("[ \\t>]+`{3,}.*")), posted);
    assertEquals(posted, MarkdownSafe.prose(posted));
  }

  @Test
  void proseLineFlattensFirstAndKeepsCodeSpans() {
    assertEquals(
        "The README says `@Cacheable` is set &#96; but @&#8203;octocat removed &lt;it>",
        MarkdownSafe.proseLine("The README says `@Cacheable`\nis set ` but @octocat removed <it>"));
    assertEquals("", MarkdownSafe.proseLine(null));
  }

  @Test
  void aProseCellGuardsMentionsEscapesHtmlAndKeepsItsCodeSpans() {
    assertEquals(
        "Adds `@Cacheable` for @&#8203;octocat &lt;br> a \\| b",
        MarkdownSafe.proseCell("Adds `@Cacheable` for @octocat <br> a | b"));
    assertEquals("build / @&#8203;octocat", MarkdownSafe.proseCell("build / @octocat"));
    assertEquals("a b", MarkdownSafe.proseCell("a\r\nb"));
    assertEquals("-", MarkdownSafe.proseCell(null));
    // A path in a code span is not prose and is left as it is.
    assertEquals("src/@types/a.ts", MarkdownSafe.tableCell("src/@types/a.ts"));
  }

  @Test
  void aNullValueIsEmpty() {
    assertEquals("", MarkdownSafe.prose(null));
    assertEquals("", MarkdownSafe.prose(""));
  }

  @Test
  void readingBackUndoesTheGuards() {
    var gap = "The `@Cacheable` claim: @octocat said <tag> and ` stay";
    assertEquals(gap, MarkdownSafe.unguarded(MarkdownSafe.proseLine(gap)));
    assertEquals("@Override", MarkdownSafe.unguarded("@​Override"));
  }

  @Test
  void aLongHostileLineIsRewrittenInLinearTime() {
    var backticks = "` ".repeat(50_000) + "`` ".repeat(10_000) + "@x ".repeat(20_000);
    var entities = "&#0000000000".repeat(20_000) + "\\".repeat(50_000) + "~".repeat(50_000);
    var fences = "```\n".repeat(30_000) + "> - ```\n".repeat(10_000);
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          assertNotifiesNobody(MarkdownSafe.prose(backticks));
          MarkdownSafe.prose(entities);
          MarkdownSafe.prose(fences);
        });
  }
}
