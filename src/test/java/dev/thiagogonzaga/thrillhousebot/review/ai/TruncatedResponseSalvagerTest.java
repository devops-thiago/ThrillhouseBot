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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TruncatedResponseSalvager}: the complete leading elements of a length-cut
 * body are recovered, the trailing cut-off value is dropped, and every paranoia bound holds.
 */
class TruncatedResponseSalvagerTest {

  private final TruncatedResponseSalvager salvager =
      new TruncatedResponseSalvager(new ObjectMapper());

  private static String finding(String title) {
    return """
        {"risk":"medium","confidence":"high","file":"a.java","line":3,"title":"%s",\
        "description":"d","suggestion_old":"old","suggestion_new":"new"}"""
        .formatted(title);
  }

  @Test
  void keepsTheCompleteFindingsAndDropsTheCutTrailingElement() {
    var body =
        "{\"findings\":["
            + finding("F1")
            + ","
            + finding("F2")
            + ","
            + finding("F3")
            + ",{\"risk\":\"high\",\"confidence\":\"high\",\"file\":\"b.java\",\"line\":9,\"ti";

    var salvaged = salvager.salvage(body);

    assertEquals(3, salvaged.findings().size());
    assertEquals("F1", salvaged.findings().get(0).title());
    assertEquals("F3", salvaged.findings().get(2).title());
    assertTrue(salvaged.previousFindingsStatus().isEmpty());
    assertNull(salvaged.summary());
    assertTrue(salvaged.hasFindingsOrStatuses());
  }

  @Test
  void keepsTheStatusesThatClosedBeforeTheCut() {
    var body =
        "{\"findings\":["
            + finding("F1")
            + "],\"previous_findings_status\":[{\"id\":1,\"status\":\"resolved\",\"note\":\"ok\"},"
            + "{\"id\":2,\"status\":\"unres";

    var salvaged = salvager.salvage(body);

    assertEquals(1, salvaged.findings().size());
    assertEquals(1, salvaged.previousFindingsStatus().size());
    assertEquals("resolved", salvaged.previousFindingsStatus().get(0).status());
  }

  @Test
  void keepsTheSummaryObjectWhenItClosedBeforeTheCut() {
    // A summary-call response cut after the summary object completed — the paid-for summary is
    // recoverable even though the body as a whole is unparseable.
    var body =
        """
        {"findings":[],"summary":{"total_findings":2,"critical":0,"high":1,"medium":1,"low":0,\
        "overall_assessment":"solid","pr_purpose":"adds things","description_gaps":[]},\
        "previous_findings_status":[{"id":7,"status":"resol""";

    var salvaged = salvager.salvage(body);

    assertNotNull(salvaged.summary());
    assertEquals(2, salvaged.summary().totalFindings());
    assertEquals("solid", salvaged.summary().overallAssessment());
    assertTrue(salvaged.previousFindingsStatus().isEmpty());
    assertFalse(salvaged.hasFindingsOrStatuses());
  }

  @Test
  void returnsNothingWhenTheCutPrecedesTheFirstCompleteElement() {
    var salvaged = salvager.salvage("{\"findings\":[{\"risk\":\"high\",\"confidence\":\"hi");

    assertFalse(salvaged.hasFindingsOrStatuses());
    assertNull(salvaged.summary());
  }

  @Test
  void returnsNothingForBodiesThatCannotCarryElements() {
    assertFalse(salvager.salvage(null).hasFindingsOrStatuses());
    assertFalse(salvager.salvage("   ").hasFindingsOrStatuses());
    // Prose with no JSON opener, and a root that is not an object.
    assertFalse(
        salvager.salvage("the model rambled instead of emitting JSON").hasFindingsOrStatuses());
    assertFalse(salvager.salvage("[1,2").hasFindingsOrStatuses());
  }

  @Test
  void stripsTheOpeningFenceAndLeadingProseLikeTheParserDoes() {
    // A cut body has an opening fence but no closing one; extractJson's noise stripping must
    // still land on the object so the elements before the cut are reachable.
    var body = "Here is my review:\n```json\n{\"findings\":[" + finding("F1") + ",{\"risk\":\"lo";

    var salvaged = salvager.salvage(body);

    assertEquals(1, salvaged.findings().size());
    assertEquals("F1", salvaged.findings().get(0).title());
  }

  @Test
  void escapesRawControlCharactersInsideStringsLikeTheParserDoes() {
    var body =
        "{\"findings\":[{\"risk\":\"low\",\"confidence\":\"high\",\"file\":\"a.java\",\"line\":1,"
            + "\"title\":\"T\",\"description\":\"has a raw\ttab\",\"suggestion_old\":\"o\","
            + "\"suggestion_new\":\"n\"},{\"risk\":\"hi";

    var salvaged = salvager.salvage(body);

    assertEquals(1, salvaged.findings().size());
    assertEquals("has a raw\ttab", salvaged.findings().get(0).description());
  }

  @Test
  void skipsAnElementThatDoesNotMapOntoTheSchemaAndKeepsTheRest() {
    // risk as an object cannot map onto Finding; the element is dropped individually instead of
    // poisoning the whole salvage.
    var body =
        "{\"findings\":[{\"risk\":{\"nested\":true},\"file\":\"a.java\"},"
            + finding("F2")
            + ",{\"risk\":\"hi";

    var salvaged = salvager.salvage(body);

    assertEquals(1, salvaged.findings().size());
    assertEquals("F2", salvaged.findings().get(0).title());
  }

  @Test
  void skipsScalarArrayElementsAndKeepsTheObjects() {
    var body = "{\"findings\":[\"not a finding\"," + finding("F2") + ",{\"risk\":\"hi";

    var salvaged = salvager.salvage(body);

    assertEquals(1, salvaged.findings().size());
    assertEquals("F2", salvaged.findings().get(0).title());
  }

  @Test
  void skipsANonArrayFindingsValueAndStillSalvagesLaterFields() {
    // Some malformed bodies emit findings as an object; salvage keeps only the unambiguous array
    // shape but must stay aligned and recover what follows.
    var body =
        "{\"findings\":{\"oops\":true},\"previous_findings_status\":"
            + "[{\"id\":3,\"status\":\"unresolved\",\"note\":\"n\"},{\"id\":4,\"status\":\"ju";

    var salvaged = salvager.salvage(body);

    assertTrue(salvaged.findings().isEmpty());
    assertEquals(1, salvaged.previousFindingsStatus().size());
    assertEquals(3, salvaged.previousFindingsStatus().get(0).id());
  }

  @Test
  void skipsTheObjectFormPreviousFindingsStatusConservatively() {
    // The object-form statuses ReviewResponseParser normalizes on the complete-body path are not
    // salvaged — a cut body's object form is ambiguous, and salvage keeps only what is provably
    // whole. Later fields still salvage.
    var body =
        "{\"previous_findings_status\":{\"1\":\"resolved\"},\"findings\":["
            + finding("F1")
            + ",{\"risk\":\"hi";

    var salvaged = salvager.salvage(body);

    assertTrue(salvaged.previousFindingsStatus().isEmpty());
    assertEquals(1, salvaged.findings().size());
  }

  @Test
  void skipsUnknownFieldsWhole() {
    var body = "{\"chatter\":{\"deep\":[1,2,3]},\"findings\":[" + finding("F1") + ",{\"risk\":\"hi";

    var salvaged = salvager.salvage(body);

    assertEquals(1, salvaged.findings().size());
  }

  @Test
  void salvagesEverythingFromABodyThatCompletedDespiteTheLengthStop() {
    // #508 flavor: finish_reason=length can arrive after the JSON completed. Salvage then simply
    // recovers the whole response.
    var body =
        "```json\n{\"findings\":["
            + finding("F1")
            + "],\"previous_findings_status\":[{\"id\":1,\"status\":\"resolved\",\"note\":\"ok\"}]}\n```";

    var salvaged = salvager.salvage(body);

    assertEquals(1, salvaged.findings().size());
    assertEquals(1, salvaged.previousFindingsStatus().size());
  }

  @Test
  void capsTheElementsKeptPerArray() {
    var sb = new StringBuilder("{\"findings\":[");
    for (var i = 0; i <= TruncatedResponseSalvager.MAX_ELEMENTS_PER_ARRAY; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(finding("F" + i));
    }
    sb.append(
        "],\"previous_findings_status\":[{\"id\":1,\"status\":\"resolved\",\"note\":\"n\"}]}");

    var salvaged = salvager.salvage(sb.toString());

    assertEquals(TruncatedResponseSalvager.MAX_ELEMENTS_PER_ARRAY, salvaged.findings().size());
    // Runaway output: the pass stops at the cap instead of scanning the remainder, so the later
    // field is deliberately not reached.
    assertTrue(salvaged.previousFindingsStatus().isEmpty());
  }

  @Test
  void refusesARunawayBodyOutright() {
    var runaway =
        "{\"findings\":[" + " ".repeat(TruncatedResponseSalvager.MAX_SALVAGED_BODY_CHARS) + "]";

    assertFalse(salvager.salvage(runaway).hasFindingsOrStatuses());
  }

  @Test
  void statusesAloneMakeTheSalvageWorthDisclosing() {
    // A body cut after the statuses but before any finding closed: findings stay empty, yet the
    // salvage is still worth a partially-reviewed disclosure — the right-hand side of
    // hasFindingsOrStatuses carries it.
    var salvaged =
        salvager.salvage(
            """
            {"findings": [],
             "previous_findings_status": [{"id": 1, "status": "resolved", "note": "done"}],
             "summary": {"total_findi""");

    assertTrue(salvaged.findings().isEmpty());
    assertEquals(1, salvaged.previousFindingsStatus().size());
    assertTrue(salvaged.hasFindingsOrStatuses());
  }

  @Test
  void aLengthStoppedButCompleteBodySalvagesEverythingWithoutHittingACut() {
    // finish_reason=length does not guarantee a syntactic cut: production has produced complete
    // JSON that still stopped on the cap. The salvage pass must run to the body's natural end —
    // the no-IOException side of the cut handling — and keep everything.
    var salvaged =
        salvager.salvage(
            """
            {"findings": [{"risk": "low", "confidence": "low", "file": "f", "line": 1,
              "title": "t", "description": "d"}],
             "previous_findings_status": [{"id": 2, "status": "unresolved", "note": "n"}]}""");

    assertEquals(1, salvaged.findings().size());
    assertEquals(1, salvaged.previousFindingsStatus().size());
    assertTrue(salvaged.hasFindingsOrStatuses());
  }

  @Test
  void closeQuietlyToleratesANullParser() {
    // Reached only if createParser itself threw; the guard is contract, tested directly.
    assertDoesNotThrow(() -> TruncatedResponseSalvager.closeQuietly(null));
  }

  @Test
  void closeQuietlySwallowsACloseFailure() throws Exception {
    // A string-backed parser cannot fail to close; the swallow is contract, tested directly so
    // the finally block can never mask a salvage result with a close-time surprise.
    var parser = org.mockito.Mockito.mock(com.fasterxml.jackson.core.JsonParser.class);
    org.mockito.Mockito.doThrow(new java.io.IOException("boom")).when(parser).close();

    assertDoesNotThrow(() -> TruncatedResponseSalvager.closeQuietly(parser));
    org.mockito.Mockito.verify(parser).close();
  }
}
