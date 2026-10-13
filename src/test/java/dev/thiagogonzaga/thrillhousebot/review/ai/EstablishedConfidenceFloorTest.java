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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.Finding;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService.CitedLocations;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService.ContextEvidence;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService.FindingEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link FindingVerificationService#floorEstablishedConfidence}: a finding whose evidence is
 * established — a taint path the diff shows end to end (#926), a check CI already failed (#952) —
 * never publishes at confidence "low".
 */
class EstablishedConfidenceFloorTest {

  /** The round-10 Go case (ThrillhouseBot-test#126): source in handler.go, sink in store.go. */
  private static final String GO_DIFF =
      """
      ### go/handler.go (added, +20 -0)
      ```diff
      +	txs, err := h.store.TransactionsForVehicle(r.Context(), r.PathValue("id"), card)
      ```
      ### go/store.go (added, +60 -0)
      ```diff
      +	q := "SELECT id, card_number, vehicle_id FROM transactions WHERE vehicle_id = '" + vehicleID + "'"
      ```
      """;

  private static final String GO_SQLI =
      "TransactionsForVehicle builds the query by string concatenation with the caller-supplied"
          + " vehicle ID: `q := \"SELECT id, card_number, vehicle_id FROM transactions WHERE"
          + " vehicle_id = '\" + vehicleID + \"'\"`. The caller in go/handler.go passes"
          + " r.PathValue(\"id\") straight in, so a user-authored URL path segment reaches the SQL"
          + " string. No escaping or parameterization is applied to the vehicle ID.";

  /** The round-10 Java case (ThrillhouseBot-test#124). */
  private static final String JAVA_DIFF =
      """
      +        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
      +        int bed = allocator.allocate(q.get("ward"), q.get("unit"));
      +        String sql = "SELECT id, ward, occupied FROM beds WHERE ward = '" + ward
      """;

  private static final String JAVA_SQLI =
      "findBeds builds the SQL by concatenating request-controlled values into the statement"
          + " string. The ward value arrives untouched from the /allocate endpoint:"
          + " AllocationServer.handleAllocate reads it with ex.getRequestURI() and passes"
          + " q.get(\"ward\") into allocate. Only the unit passes through sanitize; ward is not"
          + " sanitized, escaped or parameterized.";

  private static ReviewResponse.Finding finding(
      String risk, String confidence, String title, String description) {
    return new ReviewResponse.Finding(
        risk, confidence, "go/store.go", 45, title, description, null, null);
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(List.of(findings), List.of(), null);
  }

  private static String floored(ReviewResponse.Finding raw, String diff) {
    return FindingVerificationService.floorEstablishedConfidence(
            response(raw), response(raw), diff, FindingEvidence.NONE)
        .findings()
        .get(0)
        .confidence();
  }

  @Test
  void anInjectionWhoseSourceAndSinkAreBothInTheDiffIsNotLowConfidence() {
    assertEquals(
        "medium",
        floored(
            finding("critical", "low", "SQL injection: vehicle ID concatenated", GO_SQLI),
            GO_DIFF));
    assertEquals(
        "medium",
        floored(
            finding("critical", "low", "SQL injection: ward concatenated unsanitized", JAVA_SQLI),
            JAVA_DIFF));
  }

  /** Only "low" moves, and only to "medium": a hedge or a verifier demotion to medium stands. */
  @ParameterizedTest
  @ValueSource(strings = {"medium", "high"})
  void aFindingAlreadyAboveLowKeepsItsConfidence(String confidence) {
    var raw = finding("critical", confidence, "SQL injection: vehicle ID concatenated", GO_SQLI);

    assertSame(
        raw,
        FindingVerificationService.floorEstablishedConfidence(
                response(raw), response(raw), GO_DIFF, FindingEvidence.NONE)
            .findings()
            .get(0));
  }

  /**
   * The audit answers "low" for a real defect whose suggested fix is wrong. Lifting such a finding
   * restores the finding, not the fix: its suggestion is dropped, its anchor kept. A finding the
   * review itself raised at "low" keeps its suggestion.
   */
  @Test
  void aFindingTheAuditLoweredIsLiftedWithoutItsSuggestion() {
    var asRaised =
        new ReviewResponse.Finding(
            "critical",
            "high",
            "go/store.go",
            45,
            "SQL injection: vehicle ID concatenated",
            GO_SQLI,
            "q := ...",
            "q := \"... WHERE vehicle_id = $1\"");
    var audited =
        new ReviewResponse.Finding(
            "critical",
            "low",
            asRaised.file(),
            asRaised.line(),
            asRaised.title(),
            asRaised.description(),
            asRaised.suggestionOld(),
            asRaised.suggestionNew());

    var lifted =
        FindingVerificationService.floorEstablishedConfidence(
                response(audited), response(asRaised), GO_DIFF, FindingEvidence.NONE)
            .findings()
            .get(0);
    var selfRated =
        FindingVerificationService.floorEstablishedConfidence(
                response(audited), response(audited), GO_DIFF, FindingEvidence.NONE)
            .findings()
            .get(0);

    assertEquals("medium", lifted.confidence());
    assertEquals("q := ...", lifted.suggestionOld());
    assertNull(lifted.suggestionNew());
    assertEquals("medium", selfRated.confidence());
    assertEquals(asRaised.suggestionNew(), selfRated.suggestionNew());
  }

  /** The source the finding names is not in the diff: the path is not visible end to end. */
  @Test
  void anInjectionWhoseSourceIsNotInTheDiffStaysLow() {
    String sinkOnly =
        """
        +	q := "SELECT id, card_number, vehicle_id FROM transactions WHERE vehicle_id = '" + vehicleID + "'"
        """;

    assertEquals(
        "low",
        floored(
            finding("critical", "low", "SQL injection: vehicle ID concatenated", GO_SQLI),
            sinkOnly));
  }

  /** Two quotes of the one concatenated query are one line of the diff, not a path. */
  @Test
  void twoQuotesOfTheSameLineAreNotAPath() {
    String oneLine =
        """
        +        String sql = "SELECT id FROM bookings WHERE organizer = '" + organizer + "'";
        """;
    String twoQuotes =
        "The query \"SELECT id FROM bookings WHERE organizer = '\" is joined with `+ organizer +`"
            + " into a string-built SQL statement; nothing escapes or parameterizes organizer.";

    assertEquals(
        "low", floored(finding("high", "low", "SQL injection: organizer", twoQuotes), oneLine));
  }

  /** Two apostrophes inside words are not a quotation: they cannot make a second path line. */
  @Test
  void apostrophesInsideWordsDoNotQuoteALine() {
    String diff = GO_DIFF + "+\t// can't resolve user's intent here\n";
    String sinkAndProse =
        "TransactionsForVehicle builds the query by string concatenation: `q := \"SELECT id,"
            + " card_number, vehicle_id FROM transactions WHERE vehicle_id = '\" + vehicleID +"
            + " \"'\"`. The handler can't resolve user's intent here; nothing escapes or"
            + " parameterizes the vehicle ID, an SQL injection.";

    assertEquals(
        "low",
        floored(finding("critical", "low", "SQL injection: vehicle ID", sinkAndProse), diff));
  }

  /** File names match the diff's headings, which are not code: naming two files quotes none. */
  @Test
  void quotingTheFilesAPathRunsThroughIsNotQuotingItsCode() {
    String filesOnly =
        "In `go/handler.go` the vehicle ID reaches `go/store.go`, where it is concatenated into"
            + " the SQL query; nothing escapes or parameterizes it, an SQL injection.";

    assertEquals(
        "low",
        floored(
            finding("critical", "low", "SQL injection: vehicle ID", filesOnly),
            "--- a/go/handler.go\n+++ b/go/store.go\n" + GO_DIFF));
  }

  /** A finding that says the value IS parameterized is not the unmitigated class at all. */
  @Test
  void aFindingThatRulesTheSinkOutStaysLow() {
    String denied =
        "The query `q := \"SELECT id, card_number, vehicle_id FROM transactions WHERE vehicle_id ="
            + " '\" + vehicleID + \"'\"` is built from r.PathValue(\"id\"), but the vehicle ID is"
            + " parameterized before it reaches the driver, so no SQL injection is possible.";

    assertEquals("low", floored(finding("low", "low", "Query construction", denied), GO_DIFF));
  }

  @Test
  void aFindingThatCitesAFailingCheckIsNotLowConfidence() {
    var raw =
        finding(
            "high",
            "low",
            "Capacity check off by one",
            "The guard is `if (shift.signups.length > shift.capacity)`.");

    var evidence =
        new FindingEvidence(CitedLocations.NONE, ContextEvidence.NONE, finding -> finding == raw);

    assertEquals(
        "medium",
        FindingVerificationService.floorEstablishedConfidence(
                response(raw), response(raw), "", evidence)
            .findings()
            .get(0)
            .confidence());
  }

  /**
   * #1077: placement reads the floored grade. A medium finding the review hedged at "low" is listed
   * under "Things to double-check"; once a failing check it cites lifts it to "medium", it opens a
   * thread, on whichever lane verified it.
   */
  @Test
  void aFlooredFindingIsPlacedByItsFlooredConfidence() {
    var raw =
        finding(
            "medium",
            "low",
            "Pagination stops after the first page",
            "fetchOpenSlots reads only page 1; the failing check 'test' asserts the second page.");
    var evidence =
        new FindingEvidence(CitedLocations.NONE, ContextEvidence.NONE, finding -> finding == raw);

    var floored =
        FindingVerificationService.floorEstablishedConfidence(
                response(raw), response(raw), "", evidence)
            .findings()
            .get(0);

    assertFalse(Finding.fromAiResponse(raw).postsInline());
    assertTrue(Finding.fromAiResponse(floored).postsInline());
  }

  @Test
  void aResponseWithNothingToFloorIsReturnedUntouched() {
    var unrelated =
        response(finding("medium", "low", "Unused import", "The import java.util.Map is unused."));

    assertSame(
        unrelated,
        FindingVerificationService.floorEstablishedConfidence(
            unrelated, unrelated, GO_DIFF, FindingEvidence.NONE));
    var empty = response();
    assertSame(
        empty,
        FindingVerificationService.floorEstablishedConfidence(
            empty, empty, null, FindingEvidence.NONE));
  }

  /** A finding with no words asserts no sink; a missing diff shows no path. */
  @Test
  void aBareFindingOrAMissingDiffIsLeftAlone() {
    var bare = new ReviewResponse.Finding("high", "low", "a.go", 1, null, null, null, null);
    var sqli = finding("critical", "low", "SQL injection: vehicle ID concatenated", GO_SQLI);

    assertEquals("low", floored(bare, GO_DIFF));
    assertEquals("low", floored(sqli, null));
  }

  @Test
  void theTwoComponentEvidenceCitesNoFailingCheck() {
    var evidence = new FindingEvidence(CitedLocations.NONE, ContextEvidence.NONE);

    assertEquals(false, evidence.failingChecks().cites(finding("low", "low", "t", "d")));
  }

  @Test
  void incrementAndDecrementHunkLinesRemainCodeEvidence() {
    var raw =
        finding(
            "high",
            "low",
            "SQL injection: organizer concatenated",
            "The value from `request.getParameter(\"organizer\")` reaches"
                + " `statement.execute(sql)` without parameterization.");
    for (var prefix :
        List.of(
            "+++counter;",
            "---counter;",
            "+++ counter;",
            "--- counter;",
            "+counter++;",
            " counter++;")) {
      var diff =
          "index abc..def 100644\n\n@@ -1,2 +1,2 @@\n"
              + prefix
              + " String organizer = request.getParameter(\"organizer\");\n"
              + "+String sql = \"SELECT * FROM bookings WHERE organizer=\" + organizer; statement.execute(sql);\n";
      assertEquals("medium", floored(raw, diff), prefix);
    }
  }

  @Test
  void fileHeadersAfterAHunkNeverSupplyCodeEvidence() {
    var raw =
        finding(
            "high",
            "low",
            "SQL injection: organizer concatenated",
            "The value from `request.getParameter(\"organizer\")` reaches `statement.execute(sql)` without parameterization.");
    for (String boundary : List.of("diff --git a/new b/new", "### next.java", "```")) {
      var diff =
          "@@ -1 +1 @@\n+statement.execute(sql);\n"
              + boundary
              + "\n--- request.getParameter(\"organizer\")\n+++ request.getParameter(\"organizer\")\n";
      assertEquals("low", floored(raw, diff), boundary);
    }
  }
}
