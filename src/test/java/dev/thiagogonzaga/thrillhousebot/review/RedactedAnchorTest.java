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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #932: a finding whose anchor the security scan redacted is still located — its redacted form is
 * matched against the diff line, and the literal is never needed to do it.
 */
class RedactedAnchorTest {

  private final FakeCredentials fake = new FakeCredentials(932);

  private static FileDiff added(String name, String... lines) {
    var sb = new StringBuilder("@@ -0,0 +1," + lines.length + " @@\n");
    for (var line : lines) {
      sb.append('+').append(line).append('\n');
    }
    return new FileDiff(name, "added", lines.length, 0, lines.length, sb.toString());
  }

  /** The line as the scan's scrub stores it in a model finding's anchor. */
  private static String scrubbed(String line, FileDiff file) {
    var result = new SecurityScan(true, false, 3.5, List.of()).scan(List.of(file));
    return SecurityScan.Scrubber.of(result.redactions(), 3.5).scrub(line);
  }

  @Test
  void aLineWithNoMarkerMatchesOnlyItself() {
    var matcher = RedactedAnchor.lineMatcher("return total;");
    assertTrue(matcher.test("return total;"));
    assertFalse(matcher.test("return total + 1;"));
  }

  @Test
  void aScrubbedAssignmentMatchesTheLineItWasScrubbedFrom() {
    var value = fake.genericSecret(40);
    var line = "var apiToken string = \"" + value + "\"";
    var anchor = scrubbed(line, added("config.go", line));

    assertNotEquals(line, anchor);
    assertFalse(anchor.contains(value.substring(4)), "the stored anchor carries no literal");
    assertTrue(RedactedAnchor.lineMatcher(anchor).test(line));
  }

  @Test
  void aScrubbedKnownFormatMatchesTheLineItWasScrubbedFrom() {
    var token = fake.githubToken();
    var line = "GITHUB_TOKEN=" + token;
    var anchor = scrubbed(line, added("deploy/.env", line));

    assertEquals("GITHUB_TOKEN=[redacted: ghp_…, 40 chars]", anchor);
    assertTrue(RedactedAnchor.lineMatcher(anchor).test(line));
  }

  @Test
  void aMarkerForAValueLongerThanFourDigitsOfCharactersIsRecognized() {
    // A JWT has no length cap; a redaction of 12,000 characters must still read as a marker.
    var value = "eyJh" + "a".repeat(11_996);
    var line = "TOKEN=" + value;
    var anchor = "TOKEN=[redacted: eyJh…, 12000 chars]";

    assertTrue(RedactedAnchor.lineMatcher(anchor).test(line));
    assertFalse(RedactedAnchor.lineMatcher(anchor).test("TOKEN=other"));
  }

  @Test
  void everyRulesRedactedFormIsRecognized() {
    // The marker's prefix cap is SecretScanner's own, so no rule can show a prefix it misses.
    var value = fake.alnum(60);
    for (var rule : SecurityRule.values()) {
      if (rule.category() != SecurityRule.Category.SECRET || rule == SecurityRule.PRIVATE_KEY) {
        continue;
      }
      var marker =
          "[redacted: " + SecretScanner.redact(new SecretScanner.Hit(rule, value, null)) + "]";
      assertTrue(RedactedAnchor.lineMatcher("k = " + marker).test("k = " + value), rule.name());
    }
  }

  @Test
  void theMarkerStandsOnlyForAValueOfItsPrefixAndLength() {
    var matcher = RedactedAnchor.lineMatcher("key = \"[redacted: zKXq…, 10 chars]\";");

    assertTrue(matcher.test("key = \"zKXq123456\";"));
    assertFalse(matcher.test("key = \"zKXq1234567\";"), "one character too long");
    assertFalse(matcher.test("key = \"zKXq12345\";"), "one character too short");
    assertFalse(matcher.test("key = \"aKXq123456\";"), "a different prefix");
    assertFalse(matcher.test("key = \"zKXq12 456\";"), "a value never spans whitespace");
    assertFalse(matcher.test("other = \"zKXq123456\";"), "the text around it must match");
  }

  @Test
  void aValueShownWithNoPrefixStillMatchesByLength() {
    var matcher = RedactedAnchor.lineMatcher("pw: '[redacted: …, 7 chars]'");
    assertTrue(matcher.test("pw: 'a1b2c3d'"));
    assertFalse(matcher.test("pw: 'a1b2c3'"));
  }

  @Test
  void privateKeyMaterialMatchesABase64Run() {
    var body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7";
    var matcher = RedactedAnchor.lineMatcher("\"" + SecurityScan.PEM_MATERIAL_REDACTION + "\\n\"");
    assertTrue(matcher.test("\"" + body + "\\n\""));
    assertFalse(matcher.test("\"short\\n\""));
  }

  @Test
  void aMalformedMarkerIsLiteralText() {
    var anchor = "x = \"[redacted: abcd…, 2 chars]\"";
    var matcher = RedactedAnchor.lineMatcher(anchor);
    assertTrue(matcher.test(anchor));
    assertFalse(matcher.test("x = \"ab\""));
  }

  @Test
  void aRedactedAnchorLocatesItsFindingAndItsSuggestionRange() {
    var value = fake.genericSecret(40);
    var line = "static const char API_TOKEN[] = \"" + value + "\";";
    var file = added("src/client.c", "#include \"client.h\"", line, "int main(void);");
    var resolver = new DiffLineResolver(Map.of(file.filename(), file.patch()));
    var anchor = scrubbed(line, file);

    assertTrue(resolver.isFindingPresent("src/client.c", anchor));
    var range = resolver.resolveSuggestionRange("src/client.c", "#include \"client.h\"\n" + anchor);
    assertTrue(range.isPresent());
    assertEquals(1, range.get().startLine());
    assertEquals(2, range.get().endLine());

    // Once the line is gone from the diff, the redacted anchor no longer reads as present.
    var fixed = added("src/client.c", "#include \"client.h\"", "int main(void);");
    assertFalse(
        new DiffLineResolver(Map.of(fixed.filename(), fixed.patch()))
            .isFindingPresent("src/client.c", anchor));
  }

  private static final String KEY = SecurityScan.PEM_MATERIAL_REDACTION;

  @Test
  void keyMaterialMarkersSideBySideStandForOneRunOfBothLengths() {
    var matcher = RedactedAnchor.lineMatcher("k: " + KEY + KEY + ";");

    assertTrue(matcher.test("k: " + "A".repeat(80) + ";"));
    assertTrue(matcher.test("k: " + "A".repeat(200) + ";"));
    assertFalse(matcher.test("k: " + "A".repeat(79) + ";"), "each marker is 40 characters or more");
  }

  @Test
  void keyMaterialMayBeFollowedByTextOrAValueOfItsOwnCharacters() {
    var beforeText = RedactedAnchor.lineMatcher(KEY + "AB" + KEY + "AB");
    assertTrue(beforeText.test("A".repeat(40) + "AB" + "A".repeat(40) + "AB"));
    assertTrue(beforeText.test("A".repeat(60) + "B" + "A".repeat(50) + "AB"));
    assertFalse(beforeText.test("A".repeat(41) + "B" + "A".repeat(39) + "AB"));

    var beforeValue = RedactedAnchor.lineMatcher(KEY + "[redacted: AB…, 5 chars]-" + KEY);
    assertTrue(beforeValue.test("A".repeat(40) + "ABxyz-" + "A".repeat(40)));
    assertTrue(beforeValue.test("A".repeat(50) + "ABA-z-" + "A".repeat(40)));
    assertFalse(beforeValue.test("A".repeat(40) + "ABxy-" + "A".repeat(40)));
  }

  @Test
  void aValueIsCountedInCodePointsAndHoldsNoAsciiWhitespaceOrQuote() {
    var matcher = RedactedAnchor.lineMatcher("[redacted: …, 3 chars]");

    assertTrue(matcher.test("a😀b"), "a pair is one character");
    assertTrue(matcher.test("a b"), "a no-break space is not the scrub's whitespace");
    assertFalse(matcher.test("a\tb"));
    assertFalse(matcher.test("a`b"));
    assertFalse(matcher.test("ab"));
    assertFalse(RedactedAnchor.lineMatcher("x[redacted: …, 999999999 chars]").test("xabc"));
  }

  /**
   * Lines of one base64 character against anchors of many key material markers. The pattern these
   * were once compiled to tried every way the markers could share the run.
   */
  @ParameterizedTest
  @MethodSource("anchorsOfManyMarkers")
  void aLongLineIsReadOncePerMarker(String unit, String closing) {
    var matcher = RedactedAnchor.lineMatcher(unit.repeat(12) + closing);
    var run = "A".repeat(20_000);

    assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(10), () -> matcher.test(run + "?")));
    assertEquals(
        closing.isEmpty() || closing.equals("A"),
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> matcher.test(run)));
  }

  private static Stream<Arguments> anchorsOfManyMarkers() {
    return Stream.of(
        Arguments.of(KEY, ""),
        Arguments.of(KEY, "!"),
        Arguments.of(KEY + "A", ""),
        Arguments.of(KEY + "[redacted: A…, 3 chars]", "A"),
        Arguments.of(KEY + "[redacted: …, 0 chars]", "!"));
  }

  /** The pattern an anchor line was compiled to before it was matched piece by piece. */
  private static Pattern formerPattern(String anchorLine) {
    var marker =
        Pattern.compile(
                "\\[redacted: (.{0,"
                    + SecretScanner.REDACTED_PREFIX
                    + "}?)…, (\\d{1,9}) chars]|"
                    + Pattern.quote(KEY))
            .matcher(anchorLine);
    var regex = new StringBuilder();
    int last = 0;
    while (marker.find()) {
      String shape = "[A-Za-z0-9+/=]{40,}";
      if (marker.group(2) != null) {
        int rest = Integer.parseInt(marker.group(2)) - marker.group(1).length();
        shape = rest < 0 ? null : Pattern.quote(marker.group(1)) + "[^\\s\"'`]{" + rest + "}";
      }
      if (shape != null) {
        regex.append(Pattern.quote(anchorLine.substring(last, marker.start()))).append(shape);
        last = marker.end();
      }
    }
    return Pattern.compile(regex.append(Pattern.quote(anchorLine.substring(last))).toString());
  }

  private static final String[] ANCHOR_PIECES = {
    KEY,
    KEY,
    "[redacted: …, 2 chars]",
    "[redacted: A…, 3 chars]",
    "[redacted: -😀…, 4 chars]",
    "[redacted: AB…, 1 chars]",
    "[redacted: …, 0 chars]",
    "[redacted: \uDE00…, 2 chars]",
    "A",
    "AA",
    "-",
    "\"",
    " ",
    " ",
    "😀",
    "\uD83D",
    "\uDE00"
  };

  private static final String[] LINE_PIECES = {
    "A", "A", "AA", "-", "\"", " ", " ", "😀", "\uD83D", "\uDE00", "B", "\t"
  };

  /** A line the anchor could have been scrubbed from, or one close to it. */
  private static String lineFor(String anchor, Random random) {
    var line = new StringBuilder();
    var marker = Pattern.compile("\\[redacted: (.*?)…, (\\d) chars]|" + Pattern.quote(KEY));
    var found = marker.matcher(anchor);
    int last = 0;
    while (found.find()) {
      line.append(anchor, last, found.start());
      if (found.group(2) == null) {
        line.append("A".repeat(38 + random.nextInt(6)));
      } else {
        line.append(found.group(1));
        int rest = Integer.parseInt(found.group(2)) - found.group(1).length();
        for (int i = 0; i < rest + random.nextInt(3) - 1; i++) {
          line.append(LINE_PIECES[random.nextInt(LINE_PIECES.length)]);
        }
      }
      last = found.end();
    }
    line.append(anchor, last, anchor.length());
    if (random.nextInt(4) == 0 && !line.isEmpty()) {
      line.setCharAt(random.nextInt(line.length()), 'A');
    }
    return line.toString();
  }

  /** {@code text} with what a report could not show (half a pair, a tab) written as its code. */
  private static String escaped(CharSequence text) {
    return text.chars()
        .mapToObj(c -> c > ' ' && c < 127 ? String.valueOf((char) c) : "\\u%04X".formatted(c))
        .collect(Collectors.joining());
  }

  @Test
  void randomAnchorsMatchTheLinesTheirFormerPatternMatched() {
    var random = new Random(932);
    int matched = 0;
    for (int round = 0; round < 40_000; round++) {
      var anchor = new StringBuilder();
      int pieces = 1 + random.nextInt(6);
      for (int piece = 0; piece < pieces; piece++) {
        anchor.append(ANCHOR_PIECES[random.nextInt(ANCHOR_PIECES.length)]);
      }
      var line = lineFor(anchor.toString(), random);
      boolean expected = formerPattern(anchor.toString()).matcher(line).matches();
      matched += expected ? 1 : 0;

      assertEquals(
          expected,
          RedactedAnchor.lineMatcher(anchor.toString()).test(line),
          () -> "anchor <" + escaped(anchor) + "> line <" + escaped(line) + ">");
    }
    assertTrue(matched > 4_000, "the lines include many that match: " + matched);
  }
}
