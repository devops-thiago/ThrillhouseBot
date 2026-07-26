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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the #123 trigger in both directions. Recall matters (a missed regex means the review never
 * characterizes it) but so does precision: the guidance costs prompt budget on every PR it fires
 * for, so ordinary string handling must not trigger it.
 */
class HeuristicCodeDetectorTest {

  private static String diff(String path, String... addedLines) {
    var sb = new StringBuilder("diff --git a/").append(path).append(" b/").append(path);
    sb.append("\n--- a/")
        .append(path)
        .append("\n+++ b/")
        .append(path)
        .append("\n@@ -1,3 +1,6 @@\n");
    for (String line : addedLines) {
      sb.append('+').append(line).append('\n');
    }
    return sb.toString();
  }

  @Test
  void shouldNotTriggerOnAbsentOrEmptyDiff() {
    assertFalse(HeuristicCodeDetector.introducesHeuristicCode(null));
    assertFalse(HeuristicCodeDetector.introducesHeuristicCode(""));
    assertFalse(HeuristicCodeDetector.introducesHeuristicCode("   "));
  }

  @Test
  void shouldTriggerOnNewCompiledRegex() {
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/thrillhousebot/webhook/TriggerDetector.java",
                "  private static final Pattern PAUSE ="
                    + " Pattern.compile(\".*(?:^|\\\\s)/pause(?:\\\\s|$).*\", Pattern.DOTALL);")));
  }

  @Test
  void shouldTriggerOnRegexConstructionInOtherLanguages() {
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff("frontend/src/parse.ts", "const RE = new RegExp('^v[0-9]+');")));
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff("scripts/check.py", "TAG = re.compile(r'^v\\\\d+')")));
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff("cmd/main.go", "var tag = regexp.MustCompile(`^v\\\\d+`)")));
  }

  @Test
  void shouldTriggerOnCharLevelScanning() {
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/Formatter.java",
                "    if (raw.charAt(0) == '@') {")));
  }

  @Test
  void shouldTriggerOnUnicodeOrWhitespaceNormalization() {
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/Compact.java",
                "    return Normalizer.normalize(in, Normalizer.Form.NFKC);")));
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/Compact.java",
                "    while (Character.isWhitespace(c)) {")));
  }

  @Test
  void shouldTriggerOnDeclaredValidatorSoPresenceOnlyChecksGetProbed() {
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/thrillhousebot/config/StartupConfigValidator.java",
                "  private void validateAppId(String appId) {")));
  }

  @Test
  void shouldTriggerOnWindowOrThresholdConstant() {
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/thrillhousebot/review/FindingQuoteValidator.java",
                "  private static final int AMBIGUITY_WINDOW_LINES = 3;")));
  }

  @Test
  void shouldIgnoreHeuristicCodeAddedOnlyInTestFiles() {
    assertFalse(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/test/java/dev/thiagogonzaga/thrillhousebot/review/SomethingTest.java",
                "    var re = Pattern.compile(\"^fixture$\");")));
    assertFalse(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff("frontend/src/__tests__/page.spec.ts", "const RE = new RegExp('fixture');")));
  }

  @Test
  void shouldStillTriggerWhenProductionAndTestFilesBothChange() {
    String combined =
        diff("src/test/java/dev/thiagogonzaga/FooTest.java", "    var re = Pattern.compile(\"x\");")
            + diff("src/main/java/dev/thiagogonzaga/Foo.java", "  int i = s.charAt(2);");
    assertTrue(HeuristicCodeDetector.introducesHeuristicCode(combined));
  }

  @Test
  void shouldNotTriggerOnOrdinaryStringHandling() {
    assertFalse(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/Service.java",
                "    var name = payload.trim();",
                "    if (body.indexOf(\"x\") > 0) {",
                "    var head = body.substring(0, 10);",
                "    int id = Integer.parseInt(raw);",
                "    if (path.matches(other)) {")));
  }

  @Test
  void shouldNotTriggerOnReceiverCallsThatMerelyStartWithAHeuristicName() {
    // A declaration keyword plus Integer.parseInt(...) is an assignment, not a declared parser.
    // The earlier ordinary-string-handling case used no declaration keyword and so missed this.
    assertFalse(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/Service.java",
                "  private int id = Integer.parseInt(raw);",
                "  private static final long WAIT = Long.parseLong(env);",
                "  public boolean ok = validator.validate(input);")));
  }

  @Test
  void shouldNotTriggerOnImportOrPackageLines() {
    assertFalse(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/Service.java",
                "import java.text.Normalizer;",
                "import java.util.regex.Pattern;")));
    assertFalse(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff("scripts/tool.py", "from re import compile")));
  }

  @Test
  void shouldStillTriggerWhenTheImportIsAccompaniedByRealUse() {
    assertTrue(
        HeuristicCodeDetector.introducesHeuristicCode(
            diff(
                "src/main/java/dev/thiagogonzaga/Compact.java",
                "import java.text.Normalizer;",
                "    return Normalizer.normalize(in, Normalizer.Form.NFKC);")));
  }

  @Test
  void shouldNotTriggerOnRemovedHeuristicCode() {
    String removal =
        "--- a/src/main/java/dev/thiagogonzaga/Old.java\n"
            + "+++ b/src/main/java/dev/thiagogonzaga/Old.java\n"
            + "@@ -1,3 +1,2 @@\n"
            + "-  private static final Pattern P = Pattern.compile(\"x\");\n"
            + "   int keep = 1;\n";
    assertFalse(HeuristicCodeDetector.introducesHeuristicCode(removal));
  }

  @Test
  void shouldNotCarryTheTestFileFlagPastADeletedFile() {
    // A deletion's "+++ /dev/null" must reset the flag, or the production file that follows a
    // deleted test file would inherit inTestFile and go unscanned.
    String combined =
        "--- a/src/test/java/dev/thiagogonzaga/GoneTest.java\n"
            + "+++ b/src/test/java/dev/thiagogonzaga/GoneTest.java\n"
            + "@@ -1,2 +1,3 @@\n"
            + "+    var re = Pattern.compile(\"fixture\");\n"
            + "--- a/src/main/java/dev/thiagogonzaga/Dropped.java\n"
            + "+++ /dev/null\n"
            + "@@ -1,2 +0,0 @@\n"
            + "-  int gone = 1;\n"
            + diff("src/main/java/dev/thiagogonzaga/Kept.java", "  int i = s.charAt(2);");
    assertTrue(HeuristicCodeDetector.introducesHeuristicCode(combined));
  }

  @Test
  void shouldNotTriggerOnDiffHeaderTextAlone() {
    assertFalse(
        HeuristicCodeDetector.introducesHeuristicCode(
            "--- a/src/main/java/dev/thiagogonzaga/Parser.java\n"
                + "+++ b/src/main/java/dev/thiagogonzaga/Parser.java\n"
                + "@@ -1,2 +1,2 @@\n"
                + "   int unchanged = 1;\n"));
  }
}
