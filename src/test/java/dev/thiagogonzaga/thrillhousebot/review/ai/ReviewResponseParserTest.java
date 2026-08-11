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
  void shouldFilterNullElementsFromSummaryLabelArrays() {
    // A null element inside a best-effort label/gap array must not crash the review.
    var response =
        parser.parse(
            "{\"findings\":[],\"summary\":{\"total_findings\":0,"
                + "\"suggested_labels\":[\"bug\",null,\"area/api\"],"
                + "\"description_gaps\":[null,\"missing tests\"]}}");

    assertNotNull(response.summary());
    assertEquals(java.util.List.of("bug", "area/api"), response.summary().suggestedLabels());
    assertEquals(java.util.List.of("missing tests"), response.summary().descriptionGaps());
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
  void shouldYieldEmptyExtractionForAnAbsentBody() {
    // #534: a model can return no content at all (a reasoning model that spent its whole output
    // budget on reasoning tokens). Every caller treats "no response" as its own soft failure, so
    // the extraction must not turn it into a NullPointerException inside the strip.
    assertEquals("", ReviewResponseParser.extractJson(null));
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

  @Test
  void shouldParsePayloadWithRawControlCharsInsideStringValue() {
    // Models sometimes emit a literal tab/newline inside a code-bearing field (escaping \n but not
    // the tab); strict JSON would reject the whole response and force a full-cost retry.
    var raw =
        "{\"findings\": [{\"risk\": \"low\", \"confidence\": \"low\", \"file\": \"f\","
            + " \"line\": 1, \"title\": \"t\", \"description\": \"line1\tline2\nline3\"}]}";

    var response = parser.parse(raw);

    assertEquals(1, response.findings().size());
    assertTrue(response.findings().get(0).description().contains("line2"));
  }

  @Test
  void shouldEscapeRawControlCharsInsideStringLiterals() {
    assertEquals("{\"d\":\"a\\tb\\nc\"}", ReviewResponseParser.extractJson("{\"d\":\"a\tb\nc\"}"));
  }

  @Test
  void shouldEscapeEveryControlCharForm() {
    // carriage-return, backspace, form-feed and an arbitrary control char cover every arm.
    var input = "{\"d\":\"" + (char) 0x0d + (char) 0x08 + (char) 0x0c + (char) 0x01 + "\"}";
    assertEquals("{\"d\":\"\\r\\b\\f\\u0001\"}", ReviewResponseParser.extractJson(input));
  }

  @Test
  void shouldLeaveControlCharsOutsideStringsUntouched() {
    // A newline between tokens is valid JSON whitespace, not inside a string — leave it alone.
    var in = "{\n\"findings\":[]\n}";
    assertEquals(in, ReviewResponseParser.extractJson(in));
  }

  @Test
  void shouldNotDoubleEscapeAlreadyEscapedSequences() {
    // \n, \" and \\ are already valid escapes and must pass through unchanged.
    var in = "{\"d\":\"a\\nb\\\"c\\\\d\"}";
    assertEquals(in, ReviewResponseParser.extractJson(in));
  }

  @Test
  void shouldPassThroughALiteralNullDescriptionGaps() {
    // A model may emit "description_gaps": null rather than omitting the field. The normalizer
    // must leave it alone (the Summary constructor already drops nulls), not wrap it in an array.
    var response =
        parser.parse(
            """
            {"findings": [],
             "summary": {"total_findings": 0, "critical": 0, "high": 0, "medium": 0, "low": 0,
               "overall_assessment": "fine", "pr_purpose": "p", "description_gaps": null}}
            """);

    assertNotNull(response.summary());
    assertEquals(java.util.List.of(), response.summary().descriptionGaps());
  }

  @Test
  void shouldSkipANonTextualClaimWhenFlatteningAGapObject() {
    // The "claim"-first ordering guard must not blow up (or emit "42") when a model puts a
    // non-string value under "claim" — the remaining string-valued fields still carry the gap.
    var response =
        parser.parse(
            """
            {"findings": [],
             "summary": {"total_findings": 0, "critical": 0, "high": 0, "medium": 0, "low": 0,
               "overall_assessment": "fine", "pr_purpose": "p",
               "description_gaps": [{"claim": 42, "code": "the stated cap is never sent"}]}}
            """);

    assertNotNull(response.summary());
    assertEquals(
        java.util.List.of("the stated cap is never sent"), response.summary().descriptionGaps());
  }

  @Test
  void shouldFlattenObjectDescriptionGapsToStrings() {
    // The exact production shape from issue #508: deepseek-v4-flash emitted description_gaps
    // elements as {"claim": …, "code": …} objects instead of the strings the schema asks for,
    // failing the whole (paid) review 5/5 times.
    var response =
        parser.parse(
            """
            {"findings": [{"risk": "low", "confidence": "low", "file": "f", "line": 1,
              "title": "t", "description": "d"}],
             "previous_findings_status": [{"id": 1, "status": "resolved", "note": "ok"}],
             "summary": {"total_findings": 1, "critical": 0, "high": 0, "medium": 0, "low": 1,
               "overall_assessment": "fine", "pr_purpose": "purpose",
               "description_gaps": [{"claim": "Additional Notes: 'no max_tokens is sent'",
                                     "code": "maxTokens is always set"}]}}
            """);

    assertEquals(1, response.findings().size());
    assertEquals(1, response.previousFindingsStatus().size());
    assertNotNull(response.summary());
    assertEquals(
        java.util.List.of("Additional Notes: 'no max_tokens is sent': maxTokens is always set"),
        response.summary().descriptionGaps());
    // Nothing else about the summary changed
    assertEquals(1, response.summary().totalFindings());
    assertEquals("fine", response.summary().overallAssessment());
    assertEquals("purpose", response.summary().prPurpose());
  }

  @Test
  void shouldFlattenGapObjectFieldsWithClaimFirstRegardlessOfEmissionOrder() {
    var response =
        parser.parse(
            """
            {"findings": [], "summary": {"total_findings": 0,
              "description_gaps": [{"code": "the code", "claim": "the claim"}]}}
            """);

    assertEquals(java.util.List.of("the claim: the code"), response.summary().descriptionGaps());
  }

  @Test
  void shouldFlattenMixedGapElementShapes() {
    // Non-string scalars flatten via asText(); arrays and objects without string-valued
    // fields degrade to their JSON text; plain strings pass through unchanged.
    var response =
        parser.parse(
            """
            {"findings": [], "summary": {"total_findings": 0,
              "description_gaps": ["already a string", 42, ["a", "b"], {"n": 7}]}}
            """);

    assertEquals(
        java.util.List.of("already a string", "42", "[\"a\",\"b\"]", "{\"n\":7}"),
        response.summary().descriptionGaps());
  }

  @Test
  void shouldWrapSingleObjectDescriptionGaps() {
    var response =
        parser.parse(
            """
            {"findings": [], "summary": {"total_findings": 0,
              "description_gaps": {"claim": "c1", "code": "c2"}}}
            """);

    assertEquals(java.util.List.of("c1: c2"), response.summary().descriptionGaps());
  }

  @Test
  void shouldWrapBareStringDescriptionGaps() {
    var response =
        parser.parse(
            """
            {"findings": [], "summary": {"total_findings": 0,
              "description_gaps": "just one gap"}}
            """);

    assertEquals(java.util.List.of("just one gap"), response.summary().descriptionGaps());
  }

  @Test
  void shouldSalvageFindingsWhenSummaryStillDoesNotMap() {
    // A mapping failure confined to the summary must not discard findings that mapped
    // cleanly — the summary degrades to null (every consumer null-guards it).
    var response =
        parser.parse(
            """
            {"findings": [{"risk": "high", "confidence": "high", "file": "f", "line": 2,
              "title": "t", "description": "d"}],
             "previous_findings_status": [{"id": 4, "status": "unresolved", "note": "n"}],
             "summary": {"total_findings": {"oops": "an object"}}}
            """);

    assertEquals(1, response.findings().size());
    assertEquals("high", response.findings().get(0).risk());
    assertEquals(1, response.previousFindingsStatus().size());
    assertEquals(4, response.previousFindingsStatus().get(0).id());
    assertNull(response.summary());
  }

  @Test
  void shouldReportSchemaPathWhenMappingFailsOutsideSummary() {
    // Valid JSON whose failure is NOT confined to the summary is a schema mismatch, not a
    // parse failure — the message must say so and name the failing path.
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parser.parse(
                    """
                    {"findings": [{"risk": "low", "file": "f", "line": {"bad": true},
                      "title": "t", "description": "d"}]}
                    """));

    assertTrue(ex.getMessage().contains("did not match the review schema"), ex.getMessage());
    assertTrue(ex.getMessage().contains("findings"), ex.getMessage());
    assertFalse(ex.getMessage().contains("not valid review JSON"));
  }

  @Test
  void shouldNotSalvageWhenFailureIsOutsideSummaryEvenIfSummaryPresent() {
    // Findings themselves are broken: dropping the summary cannot save the mapping, so the
    // original schema-mismatch error must surface instead of a silently degraded response.
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parser.parse(
                    """
                    {"findings": [{"risk": "low", "file": "f", "line": {"bad": true},
                      "title": "t", "description": "d"}],
                     "summary": {"total_findings": 0}}
                    """));

    assertTrue(ex.getMessage().contains("did not match the review schema"), ex.getMessage());
  }

  @Test
  void shouldKeepParseFailureMessageForNonDatabindProcessingErrors() {
    // Defensive fallback: a JsonProcessingException that is not a databind failure keeps the
    // parse-failure message.
    var ex =
        ReviewResponseParser.schemaMismatch(
            new com.fasterxml.jackson.core.JsonParseException(null, "stream broke"));

    assertTrue(ex.getMessage().contains("not valid review JSON"), ex.getMessage());
  }

  @Test
  void shouldStillRejectInvalidJsonWithParseFailureMessage() {
    // Guard: a true parse failure keeps the existing message, distinct from schema mismatch.
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"findings\": [truncat"));
    assertTrue(ex.getMessage().contains("not valid review JSON"), ex.getMessage());
    assertFalse(ex.getMessage().contains("did not match the review schema"));
  }

  @Test
  void shouldLeaveFullyValidResponseUnchanged() throws Exception {
    var raw =
        """
        {"findings": [{"risk": "medium", "confidence": "high", "file": "src/A.java",
          "line": 12, "title": "t", "description": "d",
          "suggestion_old": "a", "suggestion_new": "b"}],
         "previous_findings_status": [{"id": 2, "status": "justified", "note": "why"}],
         "summary": {"total_findings": 1, "critical": 0, "high": 0, "medium": 1, "low": 0,
           "overall_assessment": "ok", "pr_purpose": "p",
           "description_gaps": ["gap one", "gap two"],
           "suggested_labels": ["bug"],
           "file_summaries": [{"path": "src/A.java", "summary": "changed"}],
           "walkthrough_diagram": "graph TD; A-->B"}}
        """;

    var response = parser.parse(raw);
    var direct = new ObjectMapper().readValue(raw, ReviewResponse.class);

    // Normalization must be a no-op on a conforming response — identical to a plain mapping.
    assertEquals(direct, response);
    assertEquals(java.util.List.of("gap one", "gap two"), response.summary().descriptionGaps());
  }
}
