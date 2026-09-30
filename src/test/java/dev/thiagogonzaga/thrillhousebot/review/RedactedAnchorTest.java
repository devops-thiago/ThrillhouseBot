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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
