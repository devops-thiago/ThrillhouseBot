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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewResponseParserTest {

  private ReviewResponseParser parser;

  @BeforeEach
  void setUp() {
    parser = new ReviewResponseParser(new ObjectMapper());
  }

  @Test
  void shouldRejectBlankModelResponse() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));
  }

  @Test
  void shouldRejectNullModelResponse() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
  }

  @Test
  void shouldHandleUnterminatedOrInlineFences() {
    // Opening fence without a closing one: fence stripping is skipped, noise stripping applies
    assertEquals(
        "{\"findings\":[]}", ReviewResponseParser.extractJson("```json\n{\"findings\":[]}"));
    // Fence with no newline at all: nothing is stripped before the JSON start
    assertEquals(
        "{\"findings\":[]}```", ReviewResponseParser.extractJson("```{\"findings\":[]}```"));
  }

  @Test
  void shouldRejectInvalidJson() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{not-json"));
    assertTrue(ex.getMessage().contains("not valid review JSON"));
  }

  @Test
  void shouldParseValidJsonPayload() {
    var response = parser.parse("{\"findings\":[],\"previous_findings_status\":[]}");

    assertNotNull(response);
    assertTrue(response.findings().isEmpty());
  }

  @Test
  void shouldParseSuggestedLabelsFromSummary() {
    var response =
        parser.parse(
            "{\"findings\":[],\"summary\":{\"total_findings\":0,"
                + "\"suggested_labels\":[\"bug\",\"area/api\"]}}");

    assertNotNull(response.summary());
    assertEquals(java.util.List.of("bug", "area/api"), response.summary().suggestedLabels());
  }

  @Test
  void shouldDefaultSuggestedLabelsToEmptyWhenAbsent() {
    var response = parser.parse("{\"findings\":[],\"summary\":{\"total_findings\":0}}");

    assertNotNull(response.summary());
    assertTrue(response.summary().suggestedLabels().isEmpty());
  }

  @Test
  void shouldParseFindingConfidence() {
    var response =
        parser.parse(
            """
            {"findings": [{"risk": "critical", "confidence": "medium", "file": "f",
            "line": 1, "title": "t", "description": "d"}]}
            """);

    assertEquals("medium", response.findings().get(0).confidence());
  }

  @Test
  void shouldLeaveConfidenceNullWhenOmitted() {
    var response =
        parser.parse(
            """
            {"findings": [{"risk": "high", "file": "f", "line": 1, "title": "t",
            "description": "d"}]}
            """);

    assertNull(response.findings().get(0).confidence());
  }

  @Test
  void shouldNormalizeStatusMapKeyedByFindingId() {
    var response =
        parser.parse(
            """
            {"findings": [], "previous_findings_status": {
              "1": "resolved",
              "finding_2": {"status": "unresolved", "note": "still there"}
            }}
            """);

    assertEquals(2, response.previousFindingsStatus().size());
    assertEquals(1, response.previousFindingsStatus().get(0).id());
    assertEquals("resolved", response.previousFindingsStatus().get(0).status());
    assertEquals(2, response.previousFindingsStatus().get(1).id());
    assertEquals("unresolved", response.previousFindingsStatus().get(1).status());
    assertEquals("still there", response.previousFindingsStatus().get(1).note());
  }

  @Test
  void shouldNormalizeBareSingleStatusObject() {
    var response =
        parser.parse(
            """
            {"findings": [], "previous_findings_status":
              {"id": 3, "status": "justified", "note": "intentional"}}
            """);

    assertEquals(1, response.previousFindingsStatus().size());
    assertEquals(3, response.previousFindingsStatus().get(0).id());
    assertEquals("justified", response.previousFindingsStatus().get(0).status());
  }

  @Test
  void shouldNormalizeEmptyObjectAndNonObjectStatusesToEmptyList() {
    assertTrue(
        parser
            .parse("{\"findings\": [], \"previous_findings_status\": {}}")
            .previousFindingsStatus()
            .isEmpty());
    assertTrue(
        parser
            .parse("{\"findings\": [], \"previous_findings_status\": \"none\"}")
            .previousFindingsStatus()
            .isEmpty());
  }

  @Test
  void shouldHandleStatusEdgeShapes() {
    var response =
        parser.parse(
            """
            {"findings": [], "previous_findings_status": {
              "3": {"id": 9, "status": "resolved"},
              "no-digits": "unresolved"
            }}
            """);

    // An explicit id inside the value wins over the map key; keys without digits map to id 0
    assertEquals(9, response.previousFindingsStatus().get(0).id());
    assertEquals(0, response.previousFindingsStatus().get(1).id());
  }

  @Test
  void shouldTreatExplicitNullStatusesAsAbsent() {
    var response = parser.parse("{\"findings\": [], \"previous_findings_status\": null}");

    assertTrue(response.previousFindingsStatus().isEmpty());
  }

  @Test
  void shouldRejectNonObjectRootPayload() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("[1, 2, 3]"));
  }

  @Test
  void shouldKeepWellFormedStatusArrayUnchanged() {
    var response =
        parser.parse(
            """
            {"findings": [], "previous_findings_status":
              [{"id": 1, "status": "resolved", "note": "ok"}]}
            """);

    assertEquals(1, response.previousFindingsStatus().size());
    assertEquals(1, response.previousFindingsStatus().get(0).id());
  }

  @Test
  void shouldExtractJsonFromMarkdownFence() {
    String json = ReviewResponseParser.extractJson("```json\n{\"findings\":[]}\n```");
    assertEquals("{\"findings\":[]}", json);
  }

  @Test
  void shouldStripLeadingNoiseBeforeJsonObject() {
    String json = ReviewResponseParser.extractJson("Here is the payload: {\"findings\":[]}");
    assertEquals("{\"findings\":[]}", json);
  }

  @Test
  void shouldStripLeadingNoiseBeforeJsonArrayWhenNoObjectPresent() {
    String json = ReviewResponseParser.extractJson("Here is the list: [1, 2, 3]");
    assertEquals("[1, 2, 3]", json);
  }
}
