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
import org.junit.jupiter.params.provider.CsvSource;
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
        sb.append(withoutSpans(line)).append('\n');
      }
    }
    return sb.toString();
  }

  /** {@code line} with each code span that closes on it replaced by a blank. */
  private static String withoutSpans(String line) {
    var sb = new StringBuilder();
    int i = 0;
    while (i < line.length()) {
      int open = line.indexOf('`', i);
      if (open < 0) {
        break;
      }
      int run = runEnd(line, open) - open;
      int close = closingRun(line, open + run, run);
      sb.append(line, i, open).append(close < 0 ? line.substring(open, open + run) : " ");
      i = close < 0 ? open + run : close + run;
    }
    return sb.append(line.substring(i)).toString();
  }

  /** Where the next backtick run of exactly {@code run} starts at or after {@code from}, or -1. */
  private static int closingRun(String line, int from, int run) {
    for (int at = line.indexOf('`', from); at >= 0; at = line.indexOf('`', runEnd(line, at))) {
      if (runEnd(line, at) - at == run) {
        return at;
      }
    }
    return -1;
  }

  private static int runEnd(String line, int at) {
    int end = at;
    while (end < line.length() && line.charAt(end) == '`') {
      end++;
    }
    return end;
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://www.npmjs.com/package/@scope/name",
        "See https://github.com/@octocat for the profile.",
        "https://unpkg.com/@scope/pkg@1.2.3/file.js",
        "[the package](https://www.npmjs.com/package/@types/node)",
        "[the package](https://www.npmjs.com/package/@types/node \"its page\")",
        "(https://medium.com/@author/a-post-1a2b3c)",
        "<https://host.example/@x>",
        "[text](<https://host.example/@x>)",
        "HTTPS://Host.Example:8443/@x?tab=1#top",
        "https://my-host.example/a%20b+c=d:e/@x",
        "<https://host.example/a_b~c&d,e;f!g$h'i(j)k*l/@x>",
        "http://host.example/a/@b/@c",
        "[docs]: https://host.example/@x"
      })
  void anAddressKeepsTheAtInItsPath(String text) {
    assertEquals(text, MarkdownSafe.prose(text));
    assertEquals(text, MarkdownSafe.proseLine(text));
    assertEquals(text, MarkdownSafe.proseCell(text));
  }

  @Test
  void anAddressInATitleKeepsItsAtAndABracketedOneIsEscapedAndGuarded() {
    assertEquals(
        "Pin https://www.npmjs.com/package/@types/node (https://unpkg.com/@a/b@1.0.0/x.js)",
        MarkdownSafe.inline(
            "Pin https://www.npmjs.com/package/@types/node (https://unpkg.com/@a/b@1.0.0/x.js)"));
    // inline escapes every "<", so the brackets are no autolink there and the "@" is guarded.
    assertEquals(
        "&lt;https://host.example/@&#8203;x>", MarkdownSafe.inline("<https://host.example/@x>"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '^',
      value = {
        // A blank ends the address.
        "https://host.example/x @user|https://host.example/x @&#8203;user",
        "https://x @user|https://x @&#8203;user",
        // A closing parenthesis ends it as far as this reads, whatever GitHub links.
        "https://x)@user|https://x)@&#8203;user",
        "(https://host.example/x)@user|(https://host.example/x)@&#8203;user",
        "https://host.example/a)@user|https://host.example/a)@&#8203;user",
        // No scheme, no address.
        "www.host.example/@user|www.host.example/@&#8203;user",
        "host.example/@user|host.example/@&#8203;user",
        "ftp://host.example/@user|ftp://host.example/@&#8203;user",
        // Not where an address may start.
        "xhttps://host.example/@user|xhttps://host.example/@&#8203;user",
        "see:https://host.example/@user|see:https://host.example/@&#8203;user",
        "*https://host.example/@user*|*https://host.example/@&#8203;user*",
        // Not a host GitHub is sure to link.
        "https://@user|https://@&#8203;user",
        "https://host/@user|https://host/@&#8203;user",
        "https://.host.example/@user|https://.host.example/@&#8203;user",
        "https://host..example/@user|https://host..example/@&#8203;user",
        "https://host.example./@user|https://host.example./@&#8203;user",
        "https://host.example:80@user|https://host.example:80@user",
        "https://host.example:x/@user|https://host.example:x/@&#8203;user",
        "https://host.example?@user|https://host.example?@&#8203;user",
        "https://host.example|https://host.example",
        "https://host.example:80|https://host.example:80",
        "see http|see http",
        "https|https",
        "https://host.example/a_/@user|https://host.example/a_/@&#8203;user",
        "https://host.example/a,/@user|https://host.example/a,/@&#8203;user",
        "<https://host.example/a\"b/@user>|&lt;https://host.example/a\"b/@&#8203;user>",
        "http:/host.example/@user|http:/host.example/@&#8203;user",
        // Markup that could split the link before the name ends the address.
        "https://host.example/_x_@user|https://host.example/_x_@&#8203;user",
        "https://host.example/_/@user_|https://host.example/_/@&#8203;user_",
        "https://host.example/*x*/@user|https://host.example/*x*/@&#8203;user",
        "https://host.example/~~x~~/@user|https://host.example/~~x~~/@&#8203;user",
        "https://host.example/$x$/@user|https://host.example/$x$/@&#8203;user",
        "https://host.example/&lt;/@user|https://host.example/&lt;/@&#8203;user",
        "https://host.example/x\"/@user|https://host.example/x\"/@&#8203;user",
        "https://host.example/[x]/@user|https://host.example/[x]/@&#8203;user",
        // Inside an address, only an "@" right after a "/" is kept as it is.
        "https://host.example/a?to=@user|https://host.example/a?to=@&#8203;user",
        "https://host.example/a/@@user|https://host.example/a/@@&#8203;user",
        "https://host.example/&#64;user|https://host.example/@&#8203;user",
        "https://host.example/x:&commat;user|https://host.example/x:@&#8203;user",
        // The text of a link is prose; only its destination is an address.
        "[@user](https://host.example/x)|[@&#8203;user](https://host.example/x)",
        "[x](https://host.example/@a) @user|[x](https://host.example/@a) @&#8203;user",
        // Angle brackets keep an address only when they close around one.
        "<https://host.example/@user|&lt;https://host.example/@&#8203;user",
        "<https://host.example/x @user>|&lt;https://host.example/x @&#8203;user>",
        "<https://host/@user>|&lt;https://host/@&#8203;user>",
        "<www.host.example/@user>|&lt;www.host.example/@&#8203;user>",
        "<mailto:x@host.example> <@user>|&lt;mailto:x@host.example> <@&#8203;user>",
        "<https://host.example/x>@user|<https://host.example/x>@&#8203;user",
        "\\<https://host.example/@user>|&lt;https://host.example/@&#8203;user>"
      })
  void whatIsNotAnAddressIsStillGuarded(String text, String posted) {
    assertEquals(posted, MarkdownSafe.prose(text));
    assertEquals(posted, MarkdownSafe.prose(posted));
  }

  @Test
  void addressesOnSeveralLinesAndInListItemsAreKept() {
    var text =
        "- https://host.example/@x\n- <https://host.example/a_b/@x~y(z)>\n\n> (http://a.b/@c)";
    assertEquals(text, MarkdownSafe.prose(text));
  }

  @Test
  void anAddressEndsWithItsLine() {
    assertEquals(
        "https://host.example/x\n@&#8203;user",
        MarkdownSafe.prose("https://host.example/x\n@user"));
    assertEquals("https://x\n@&#8203;user", MarkdownSafe.prose("https://x\n@user"));
    assertEquals(
        "see\thttps://host.example/@x", MarkdownSafe.prose("see\thttps://host.example/@x"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1. item",
        "123456789) item",
        "1234567890. not a list marker",
        "12 apples",
        "- ",
        "-\t```",
        "3.\t~~~",
        "12",
        "1.no blank",
        "+ ```",
        "> 2) ~~~"
      })
  void aLineIsReadForNestingWithoutChangingIt(String line) {
    var posted = MarkdownSafe.prose(line + "\ncode\n```");
    // A fence under a list marker is hoisted with the marker on a line of its own.
    int end = line.length();
    while (end > 0 && " \t`~".indexOf(line.charAt(end - 1)) >= 0) {
      end--;
    }
    assertTrue(posted.startsWith(line.endsWith(" ") ? line : line.substring(0, end)), posted);
  }

  @Test
  void codeSpansAndFencedCodeAreKeptByteForByte() {
    var text =
        """
        Add `@Override` to `src/@types/x.ts` like so:
        ```java
        @Override

        public List<String> names() { return a < b ? x : y; }
        ```
        Then run ``npm i @scope/pkg`` again.""";
    assertEquals(7, text.lines().count());
    assertFalse(text.endsWith("\n"));
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
            """
            1. Change the method:
               ```java
               @Override
                 void run() {}
               ```
            2. Ask @octocat."""));
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
        """
        ## Why

        - **bold**, _em_, ~~gone~~ and a [link](https://example.com/a?b=1)
        - ![img](https://example.com/i.png)

        > quoted

        | a | b |
        |---|---|
        | 1 | 2 |

        1. first
        2. second \\* literal""";
    assertTrue(text.endsWith("2. second \\* literal"), text);
    assertEquals(text, MarkdownSafe.prose(text));
  }

  @Test
  void aFenceClosesOnlyAtARunOfItsOwnMarkerAsLongAsItsOpenerWithNothingAfter() {
    // A shorter run, a run with text after it and a run of the other marker are all content.
    assertEquals(
        "`````\n```\n```` x\n~~~~\n`````\nafter @&#8203;octocat",
        MarkdownSafe.prose("````\n```\n```` x\n~~~~\n````\nafter @octocat"));
  }

  @Test
  void contentKeepsWhatLiesBeyondTheNestingItsFenceWasOpenedUnder() {
    // One quote marker and two blanks were the fence's; a second marker and the rest are content.
    assertEquals(
        "```\n> quoted code\n  indented\nx\n```",
        MarkdownSafe.prose("> ```\n>> quoted code\n>   indented\nx\n> ```"));
    assertEquals("a trailing backslash \\", MarkdownSafe.prose("a trailing backslash \\"));
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
    assertFalse(
        posted.lines().anyMatch(line -> !line.startsWith("```") && line.strip().startsWith("```")),
        posted);
    assertEquals(posted, MarkdownSafe.prose(posted));
  }

  // In a table row GitHub ends a cell at each pipe before it reads a code span, so a span holding
  // one is text there: it is posted as text, and what it held is neutralized like the rest.
  @Test
  void aCodeSpanHoldingAPipeIsPostedAsText() {
    var table =
        """
        | a | b | c |
        |---|---|---|
        | `x | @victim <img src="https://evil.example/p.png"> \
        <!-- thrillhousebot:finding=1 key=deadbeef --> | y` |""";

    var posted = MarkdownSafe.prose(table);

    assertEquals(
        """
        | a | b | c |
        |---|---|---|
        | &#96;x | @&#8203;victim &lt;img src="https://evil.example/p.png"> \
        &lt;!-- thrillhousebot:finding=1 key=deadbeef --> | y&#96; |""",
        posted);
    assertEquals(posted, MarkdownSafe.prose(posted));
    assertEquals("Use &#96;a || b&#96; here.", MarkdownSafe.prose("Use `a || b` here."));
    assertEquals("&#96;&#96;a | @&#8203;v&#96;&#96;", MarkdownSafe.proseLine("``a | @v``"));
  }

  // Its closing run is read as a run of its own: it pairs with the next one when no pipe is between
  // them, as GitHub pairs them, so a span after it reads the same in a row and out of it.
  @Test
  void aSpanAfterOneHoldingAPipeIsPairedTheSameInATableRowAndOutOfIt() {
    assertEquals(
        "&#96;a | b` c `@&#8203;Keep` and `@&#8203;Kept&#96;",
        MarkdownSafe.prose("`a | b` c `@Keep` and `@Kept`"));
    assertEquals("`a` | `@Kept`", MarkdownSafe.prose("`a` | `@Kept`"));
  }

  // The span its closing run opens is one GitHub reads too: what it holds is code in a row and out
  // of one, and a pipe before the next run leaves the closing run literal and the text prose.
  @Test
  void theClosingRunOfASpanHoldingAPipeOpensASpanOnlyWhereGitHubReadsOne() {
    var paired = MarkdownSafe.prose("`a | b` @Kept <b> `x` @octocat");

    assertEquals("&#96;a | b` @Kept <b> `x&#96; @&#8203;octocat", paired);
    assertEquals(paired, MarkdownSafe.prose(paired));
    assertEquals(
        "&#96;a | b&#96; @&#8203;victim &lt;img> | `x`",
        MarkdownSafe.prose("`a | b` @victim <img> | `x`"));
    assertEquals(
        "| &#96;a | b` @Kept `x&#96; @&#8203;octocat |",
        MarkdownSafe.prose("| `a | b` @Kept `x` @octocat |"));
  }

  // The pipes of a table cell are escaped after this, so no cell ends inside its span.
  @Test
  void aProseCellKeepsACodeSpanHoldingAPipe() {
    assertEquals("`a \\| @Kept` @&#8203;octocat", MarkdownSafe.proseCell("`a | @Kept` @octocat"));
  }

  // GitHub ends a line at a carriage return as at a line feed, so text after one is a new line.
  @Test
  void aBareCarriageReturnEndsALine() {
    assertEquals(
        "Fix it:\n```\nmalicious();\n```\ndone",
        MarkdownSafe.prose("Fix it:\r```suggestion\rmalicious();\r```\rdone"));
    assertEquals(
        "see &#96;a\n\n@&#8203;victim please look\n\nb&#96; end",
        MarkdownSafe.prose("see `a\r\r@victim please look\r\rb` end"));
    assertEquals(
        "&#96;a\n\n&lt;img src=x> &lt;!-- thrillhousebot:finding=1 -->\n\nb&#96;",
        MarkdownSafe.prose("`a\r\r<img src=x> <!-- thrillhousebot:finding=1 -->\r\rb`"));
    assertEquals("a\nb\n\nc", MarkdownSafe.prose("a\r\nb\n\rc"));
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
  void readingBackUndoesAnEscapedRunOfTildes() {
    var gap = "The ~~~ fence, ~~struck~~ text and a longer ~~~~~ run stay";
    var posted = MarkdownSafe.proseLine(gap);

    assertTrue(posted.contains("\\~\\~\\~ fence"), posted);
    assertEquals(gap, MarkdownSafe.unguarded(posted));
    // Fewer than three were never escaped, so a model's own escaped tilde is left as written.
    assertEquals("a \\~ b \\~\\~ c", MarkdownSafe.unguarded("a \\~ b \\~\\~ c"));
  }

  @Test
  void aLongHostileLineIsRewrittenInLinearTime() {
    var backticks = "` ".repeat(50_000) + "`` ".repeat(10_000) + "@x ".repeat(20_000);
    var addresses =
        "https://"
            + "a".repeat(50_000)
            + " "
            + "https://a.b/@x ".repeat(10_000)
            + "<https://a.b/".repeat(10_000)
            + "(http://a.".repeat(10_000)
            + "9".repeat(50_000);
    var entities = "&#0000000000".repeat(20_000) + "\\".repeat(50_000) + "~".repeat(50_000);
    var fences = "```\n".repeat(30_000) + "> - ```\n".repeat(10_000);
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          assertNotifiesNobody(MarkdownSafe.prose(backticks));
          MarkdownSafe.prose(entities);
          MarkdownSafe.prose(addresses);
          MarkdownSafe.inline(addresses);
          MarkdownSafe.prose(fences);
        });
  }
}
