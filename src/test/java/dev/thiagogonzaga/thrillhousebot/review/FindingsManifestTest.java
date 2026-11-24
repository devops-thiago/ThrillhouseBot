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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The open-findings manifest (#1433): what it carries, that it is the set the summary renders, and
 * that nothing in it can end the HTML comment it sits in.
 */
class FindingsManifestTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Pattern LINE =
      Pattern.compile("^<!-- thrillhousebot:findings v=1 (.*) -->$");

  private static Finding finding(
      RiskLevel risk, Confidence confidence, String file, int line, String title, String fix) {
    return new Finding(
        risk, confidence, file, line, title, "description", fix == null ? null : "old", fix);
  }

  private static ReviewResult result(List<Finding> findings, List<Finding> openPrevious) {
    return new ReviewResult(
        findings,
        0,
        0,
        0,
        0,
        null,
        ReviewState.COMMENT,
        false,
        "summary",
        List.of(),
        List.of(),
        0,
        false,
        true,
        null,
        0,
        openPrevious);
  }

  private static JsonNode manifest(ReviewResult result) throws Exception {
    var line = FindingsManifest.render(PrSummaryGenerator.currentState(result));
    var matcher = LINE.matcher(line);
    assertTrue(matcher.matches(), line);
    return MAPPER.readTree(matcher.group(1));
  }

  @Test
  void anEmptySetRendersAnEmptyArrayNotNoBlock() {
    assertEquals(
        "<!-- thrillhousebot:findings v=1 [] -->",
        FindingsManifest.render(PrSummaryGenerator.currentState(result(List.of(), List.of()))));
  }

  @Test
  void everyEntryCarriesTheSummarysOwnValuesAndTheInlineMarkersKey() throws Exception {
    var inline = finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 12, "NPE on load", "fix");
    var listed = finding(RiskLevel.MEDIUM, Confidence.LOW, "src/B.java", 7, "Maybe stale", null);

    var entries = manifest(result(List.of(listed, inline), List.of()));

    assertEquals(2, entries.size());
    var first = entries.get(0);
    assertEquals(
        SuggestionFormatter.findingKey("src/A.java", 12, "NPE on load"), first.get("key").asText());
    assertEquals("src/A.java", first.get("file").asText());
    assertEquals(12, first.get("line").asInt());
    assertEquals("high", first.get("risk").asText());
    assertEquals("high", first.get("confidence").asText());
    assertEquals("open", first.get("status").asText());
    assertTrue(first.get("thread").asBoolean());
    assertTrue(first.get("hasSuggestion").asBoolean());
    assertFalse(first.get("carried").asBoolean());
    var second = entries.get(1);
    assertEquals("medium", second.get("risk").asText());
    assertEquals("low", second.get("confidence").asText());
    assertFalse(second.get("thread").asBoolean());
    assertFalse(second.get("hasSuggestion").asBoolean());
  }

  @Test
  void aFindingOpenSinceAnEarlierReviewIsMarkedCarriedAndTheOthersAreNot() throws Exception {
    var fresh = finding(RiskLevel.LOW, Confidence.HIGH, "src/New.java", 3, "Fresh", null);
    var earlier = finding(RiskLevel.CRITICAL, Confidence.HIGH, "src/Old.java", 9, "Old", null);

    var entries = manifest(result(List.of(fresh), List.of(earlier)));

    assertEquals(2, entries.size());
    assertEquals("src/Old.java", entries.get(0).get("file").asText());
    assertTrue(entries.get(0).get("carried").asBoolean());
    assertEquals("src/New.java", entries.get(1).get("file").asText());
    assertFalse(entries.get(1).get("carried").asBoolean());
  }

  @Test
  void entriesRunMostSevereFirstThenMostCertain() throws Exception {
    var entries =
        manifest(
            result(
                List.of(
                    finding(RiskLevel.LOW, Confidence.HIGH, "a", 1, "t1", null),
                    finding(RiskLevel.HIGH, Confidence.LOW, "b", 1, "t2", null),
                    finding(RiskLevel.HIGH, Confidence.HIGH, "c", 1, "t3", null),
                    finding(RiskLevel.CRITICAL, Confidence.MEDIUM, "d", 1, "t4", null)),
                List.of()));

    var files = new ArrayList<String>();
    entries.forEach(e -> files.add(e.get("file").asText()));
    assertEquals(List.of("d", "c", "b", "a"), files);
  }

  @Test
  void aFindingWithNoRiskIsGradedLowAsTheSummaryDoes() throws Exception {
    var entries =
        manifest(result(List.of(finding(null, Confidence.HIGH, "a", 1, "t", null)), List.of()));

    assertEquals("low", entries.get(0).get("risk").asText());
  }

  @Test
  void aFindingWithNoConfidenceIsGradedHighAsTheRecordDefaultsIt() throws Exception {
    var entries =
        manifest(result(List.of(finding(RiskLevel.HIGH, null, "a", 1, "t", null)), List.of()));

    assertEquals("high", entries.get(0).get("confidence").asText());
  }

  @Test
  void aPathCannotCloseTheCommentOrBreakTheLine() throws Exception {
    var path = "src/--> <script>\"x\"\\\n&.java";
    var line =
        FindingsManifest.render(
            PrSummaryGenerator.currentState(
                result(
                    List.of(finding(RiskLevel.HIGH, Confidence.HIGH, path, 1, "t", null)),
                    List.of())));

    assertFalse(line.contains("\n"));
    assertEquals(line.length() - 4, line.indexOf(" -->"), "the only comment end is the last");
    assertFalse(line.contains("<script>"));
    assertEquals(
        path,
        manifest(
                result(
                    List.of(finding(RiskLevel.HIGH, Confidence.HIGH, path, 1, "t", null)),
                    List.of()))
            .get(0)
            .get("file")
            .asText());
  }

  @Test
  void tabsCarriageReturnsAndOtherControlCharactersInAPathSurviveTheRoundTrip() throws Exception {
    var path = "a\tb\rc\u0001d\u001fe";
    var findings = List.of(finding(RiskLevel.HIGH, Confidence.HIGH, path, 1, "t", null));
    var line =
        FindingsManifest.render(PrSummaryGenerator.currentState(result(findings, List.of())));

    assertFalse(line.contains("\t"));
    assertFalse(line.contains("\r"));
    assertFalse(line.contains("\u0001"));
    assertEquals(path, manifest(result(findings, List.of())).get(0).get("file").asText());
  }

  @Test
  void aNullFileRendersAsAnEmptyString() throws Exception {
    var entries =
        manifest(
            result(
                List.of(finding(RiskLevel.HIGH, Confidence.HIGH, null, 1, "t", null)), List.of()));

    assertEquals("", entries.get(0).get("file").asText());
  }

  @Test
  void aPathologicalSetIsCutAtTheCapAndSaysHowManyWereLeftOut() throws Exception {
    var findings = new ArrayList<Finding>();
    for (int i = 0; i < FindingsManifest.MAX_ENTRIES + 5; i++) {
      var risk = i < FindingsManifest.MAX_ENTRIES ? RiskLevel.HIGH : RiskLevel.LOW;
      findings.add(finding(risk, Confidence.HIGH, "f" + i, i, "t" + i, null));
    }
    var line =
        FindingsManifest.render(PrSummaryGenerator.currentState(result(findings, List.of())));

    assertTrue(
        line.startsWith("<!-- thrillhousebot:findings v=1 omitted=5 ["), line.substring(0, 60));
    var array = line.substring(line.indexOf('['), line.length() - " -->".length());
    var entries = MAPPER.readTree(array);
    assertEquals(FindingsManifest.MAX_ENTRIES, entries.size());
    entries.forEach(e -> assertEquals("high", e.get("risk").asText()));
  }
}
