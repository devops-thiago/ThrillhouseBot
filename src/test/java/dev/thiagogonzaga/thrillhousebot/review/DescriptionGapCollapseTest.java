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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Description gaps collapsed at render time, on the round 19 cases (#1205): two gaps sharing only
 * the gap template's wording are kept apart, and a gap that restates a finding stays in the
 * Description vs. Implementation section unless the section names that finding.
 */
class DescriptionGapCollapseTest {

  private static Finding finding(String file, int line, String title, String description) {
    return new Finding(
        RiskLevel.MEDIUM, Confidence.HIGH, file, line, title, description, null, null);
  }

  // Gap against gap: the template wording is not the claim.

  @ParameterizedTest(name = "{0}")
  @MethodSource("gapsSharingOnlyTheTemplate")
  void twoGapsSharingOnlyTheTemplateWordingAreKeptApart(String label, String first, String second) {
    var gaps = List.of(first, second);

    var surfaces = SummarySurfaceDeduplicator.collapse(gaps, Map.of(), List.of());

    assertEquals(gaps, surfaces.descriptionGaps(), label);
  }

  static Stream<Arguments> gapsSharingOnlyTheTemplate() {
    return Stream.of(
        arguments(
            "kotlin #294: payment retry and the #290 413 limit",
            "The description says \"Failed payment lookups are retried up to three times with"
                + " backoff before the household is skipped\"; no changed file declares anything"
                + " that retries lookups or skips households on failure.",
            "Linked issue #290: \"An invoice run covering more than 500 enrolments is rejected"
                + " with HTTP 413\" — no changed file declares any size limit or 413 response on"
                + " the run path."),
        arguments(
            "node #299: the #288 conflict-of-interest check and the audit log",
            "Linked issue #288: \"A reviewer cannot score a proposal they authored; the attempt is"
                + " rejected with a conflict-of-interest error\" — no changed file declares any"
                + " conflict-of-interest check or rejection path.",
            "The description says \"every decision is written to an audit log entry\" — no changed"
                + " file declares anything that writes an audit log entry."),
        arguments(
            "angular #301: the #284 snooze range and the browser cache",
            "Linked issue #284: \"Staff can snooze a reminder for 1 to 14 days\" — no changed file"
                + " declares any snooze handling or validation.",
            "The description says \"Reminder data is cached in the browser for 5 minutes\"; no"
                + " changed file declares any browser caching of reminder data."),
        arguments(
            "angular #301: two description claims about reminders",
            "The description says \"Reminder data is cached in the browser for 5 minutes\"; no"
                + " changed file declares any browser caching of reminder data.",
            "The description says reminders are deduplicated by the board; no changed rule"
                + " confirms the dedupe behavior the description claims beyond the presence of the"
                + " list-handling files."),
        arguments(
            "kotlin #294: two #290 criteria about invoices",
            "Linked issue #290: \"Invoices are flagged overdue once the configurable grace period"
                + " after issue has passed\" — Config's overdueGraceDays is never passed to"
                + " isOverdue, whose only caller is a test, so no code path applies the"
                + " configured grace period.",
            "Linked issue #290: \"An invoice run covering more than 500 enrolments is rejected"
                + " with HTTP 413\" — no changed file declares any size limit or 413 response on"
                + " the run path."));
  }

  @Test
  void aGapRestatingAnEarlierGapsClaimStillCollapses() {
    var first =
        "Linked issue #290: \"An invoice run covering more than 500 enrolments is rejected with"
            + " HTTP 413\" — no changed file declares any size limit or 413 response.";
    var second =
        "The description and linked issue #290 say an invoice run covering more than 500"
            + " enrolments is rejected; no changed file declares that limit.";

    var surfaces = SummarySurfaceDeduplicator.collapse(List.of(first, second), Map.of(), List.of());

    assertEquals(List.of(first), surfaces.descriptionGaps());
  }

  @Test
  void aSingleTemplateWordInsideTheClaimStillCounts() {
    // "file" is template wording only beside other template words; in "JSON audit file" it is the
    // claim, and the two gaps share the run "JSON audit file".
    var first =
        "The description says denied scans are appended to a JSON audit file; nothing writes it.";
    var second = "Every denied scan should reach the JSON audit file the PR text promises.";

    var surfaces = SummarySurfaceDeduplicator.collapse(List.of(first, second), Map.of(), List.of());

    assertEquals(List.of(first), surfaces.descriptionGaps());
  }

  // Gap against finding: removed only in favour of a finding the section names.

  private static final Finding MANIFEST_LIMIT =
      finding(
          "go/manifest.go",
          49,
          "maxManifestRecords size limit is commented and declared but never enforced",
          "The diff declares a manifest size bound with an explanatory comment — '//"
              + " maxManifestRecords: manifests can hold up to 200000 vehicle records on' / '//"
              + " peak holiday sailings.' and 'const maxManifestRecords = 200000' (go/manifest.go"
              + " lines 47-49) — but nothing in the change references it. Fetch decodes the page"
              + " and returns 'page.Items, nil' (go/manifest.go line 44) without comparing against"
              + " maxManifestRecords, and the allocation path puts no bound on the batch either, so"
              + " the documented peak-sailing limit exists only as dead code.");

  private static final String MANIFEST_GAP =
      "Linked issue #281: 'Rejects manifest batches larger than 500 vehicles with HTTP 413' — the"
          + " change declares maxManifestRecords = 200000 but never enforces it and nothing in the"
          + " change returns HTTP 413.";

  private static final Finding LOAD_SLOTS =
      finding(
          "react/src/api.ts",
          22,
          "loadSlots fetches only the first page and drops every slot beyond it",
          "fetchPage returns 'export interface Page<T> { items: T[]; nextCursor: string | null; }'"
              + " (react/src/api.ts lines 5-8) — cursor pagination is built and the API hands back"
              + " a cursor — but the consumer ignores it. It fetches one page and returns only"
              + " page.items, with page.nextCursor never read.");

  private static final String LOAD_SLOTS_GAP =
      "Linked issue #280: the board lists all slots returned by the API \"including those beyond"
          + " the first page\" — loadSlots fetches only the first page and ignores nextCursor.";

  private static final Finding SLOT_HOLD =
      finding(
          "react/src/config.ts",
          18,
          "SLOT_HOLD_TIMEOUT is parsed and documented but consumed by nothing",
          "config.ts parses the key — 'holdTimeout: Number(env.SLOT_HOLD_TIMEOUT ?? \"900\"),'"
              + " (line 18) — and the added docs describe it as \"How long a slot is held for a"
              + " household while they confirm\". Nothing in the changed code ever reads"
              + " config.holdTimeout, and no hold/confirmation code exists anywhere in the added"
              + " files.");

  private static final Finding MAX_DAILY =
      finding(
          "zig/src/compactor.zig",
          10,
          "MAX_DAILY_READINGS cap is declared but never enforced; error.TooManyReadings does not"
              + " exist",
          "The linked issue #286 accepts only when it states: \"A day with more than 2,000,000"
              + " readings is rejected with error.TooManyReadings\". The only touch on this"
              + " criterion in the diff is the declaration in zig/src/compactor.zig, yet no changed"
              + " line reads that constant, and the string `TooManyReadings` does not appear"
              + " anywhere in the added sources or tests.");

  private static final String MAX_DAILY_GAP =
      "Linked issue #286: \"A day with more than 2,000,000 readings is rejected with"
          + " error.TooManyReadings\" — MAX_DAILY_READINGS is declared but no changed line reads"
          + " it, and the error does not exist in the added sources.";

  @ParameterizedTest(name = "{0}")
  @MethodSource("gapsOntoUnnamedFindings")
  void aGapRestatingAFindingTheSectionDoesNotNameStays(String label, String gap, Finding onto) {
    var named = SummarySurfaceDeduplicator.collapse(List.of(gap), Map.of(), List.of(onto));
    var unnamed =
        SummarySurfaceDeduplicator.collapse(List.of(gap), Map.of(), List.of(onto), f -> false);

    // The pair matches: with the finding named, the gap gives way to it.
    assertEquals(List.of(), named.descriptionGaps(), label);
    assertEquals(List.of(gap), unnamed.descriptionGaps(), label);
    assertEquals(List.of(), unnamed.gapFindings(), label);
  }

  static Stream<Arguments> gapsOntoUnnamedFindings() {
    return Stream.of(
        arguments("go #292: the #281 413 criterion", MANIFEST_GAP, MANIFEST_LIMIT),
        arguments(
            "kotlin #294: the #290 grace period",
            "Linked issue #290: \"Invoices are flagged overdue once the configurable grace period"
                + " after issue has passed\" — Config's overdueGraceDays is never passed to"
                + " isOverdue, whose only caller is a test, so no code path applies the"
                + " configured grace period.",
            finding(
                "kotlin/src/main/kotlin/school/invoicer/Config.kt",
                10,
                "Environment configuration is parsed but applied by nothing in the service",
                "Config.fromEnv reads four keys, and the documentation presents them as the"
                    + " service's settings. Each value is dead as written: no code opens a"
                    + " connection from dbUrl, currencies is read by nothing, overdueGraceDays is"
                    + " never passed to isOverdue (whose only caller is a test), and pageSize"
                    + " reaches only loadCourses, which nothing calls.")),
        arguments(
            "csharp #295: the once-a-minute sweep",
            "The description says a once-a-minute sweep \"revokes expired passes\" — the"
                + " per-batch finding shows PassSweeper.Sweep adds every pass to the revoke list"
                + " without any expiry check, so all passes are revoked, the opposite of the"
                + " stated behavior.",
            finding(
                "csharp/src/LiftPassValidator/PassSweeper.cs",
                21,
                "The hourly sweep revokes every pass in the store, not just expired ones",
                "Sweep's list is named expiredPasses, but the loop adds every pass it walks:"
                    + " 'foreach (var pass in _store.All()) { expiredPasses.Add(pass); }' — no"
                    + " expiry check of any kind.")),
        arguments(
            "csharp #295: the #285 rate limit",
            "The description and linked issue #285 say \"The scan endpoint limits each gate to 10"
                + " scans per second and answers 429 beyond that\" — no changed file declares any"
                + " rate limit or 429 response; the /scan route in Program.cs wires ScanService"
                + " directly with no throttling.",
            finding(
                "csharp/src/LiftPassValidator/PassStore.cs",
                38,
                "SQL injection: HTTP-supplied holder is concatenated into the SQL string",
                "The only caller visible in the diff is the HTTP route in Program.cs —"
                    + " app.MapGet(\"/passes\", (string holder, string zone, IPassStore store) =>"
                    + " Results.Ok(store.FindByHolder(holder, zone))) — which binds holder"
                    + " straight from the request query string.")),
        arguments(
            "node #299: the #288 result pages",
            "The description says \"Proposals are imported from the CFP API\" and linked issue"
                + " #288 says \"Proposals are imported from the CFP API across all result pages\";"
                + " importProposals fetches only page one and discards nextPage, so later pages"
                + " are never imported.",
            finding(
                "node/src/cfpClient.js",
                14,
                "importProposals reads only page one and silently drops all later pages",
                "The consumer fetches a single page and returns it as the complete set.")),
        arguments("react #300: the #280 result pages", LOAD_SLOTS_GAP, LOAD_SLOTS),
        arguments(
            "react #300: the #280 full slot",
            "Linked issue #280: \"A slot that has reached its capacity is shown as full\" — isFull"
                + " treats booked === capacity as not full, contradicting both the display and"
                + " the in-diff test.",
            finding(
                "react/src/slots.ts",
                21,
                "isFull treats a slot exactly at capacity as not full, and the in-diff test"
                    + " asserts the opposite",
                "isFull is defined as 'return slot.booked > slot.capacity;', so a slot with"
                    + " booked === capacity is not full by this predicate.")),
        arguments("react #300: the #280 weekly booking limit", WEEKLY_BOOKING_GAP, SLOT_HOLD));
  }

  @Test
  void aGapRestatingANamedFindingIsStillRemovedAndTheFindingReported() {
    // zig #303: the finding quotes the linked issue, so the section names it in the gap's place.
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(MAX_DAILY_GAP), Map.of(), List.of(MAX_DAILY), f -> true);

    assertEquals(List.of(), surfaces.descriptionGaps());
    assertEquals(List.of(MAX_DAILY), surfaces.gapFindings());
  }

  private static final String WEEKLY_BOOKING_GAP =
      "Linked issue #280: a household can \"hold at most one booking per week; a second booking"
          + " attempt is rejected\" — no hold/booking-rejection logic exists in the change;"
          + " SLOT_HOLD_TIMEOUT is parsed and documented but consumed by nothing.";

  private static final Finding CONFIG_TABLE =
      new Finding(
          RiskLevel.LOW,
          Confidence.HIGH,
          "docs/CONFIG-REACT.md",
          8,
          "Config table omits defaults, the comma separator, and the new PICKUP_PAGE_SIZE key",
          "The table row '| `BOARD_ALLOWED_ZONES` | List of zones shown on the board | |'"
              + " (docs/CONFIG-REACT.md line 8) leaves the Default column empty. The row '|"
              + " `SLOT_HOLD_TIMEOUT` | How long a slot is held for a household while they confirm"
              + " | |' (line 9) also omits its default, which config.ts line 18 defines as"
              + " Number(env.SLOT_HOLD_TIMEOUT ?? \"900\").",
          null,
          null);

  @Test
  void aGapWhoseFirstMatchIsUnnamedStaysThoughALaterNamedFindingMatchesToo() {
    // react #300: the weekly booking criterion shares only SLOT_HOLD_TIMEOUT with the config table
    // finding, which the section names; collapsing onto it named an unrelated finding.
    var alone =
        SummarySurfaceDeduplicator.collapse(
            List.of(WEEKLY_BOOKING_GAP), Map.of(), List.of(CONFIG_TABLE), f -> true);
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(WEEKLY_BOOKING_GAP),
            Map.of(),
            List.of(SLOT_HOLD, CONFIG_TABLE),
            f -> f == CONFIG_TABLE);

    assertEquals(List.of(CONFIG_TABLE), alone.gapFindings());
    assertEquals(List.of(WEEKLY_BOOKING_GAP), surfaces.descriptionGaps());
    assertEquals(List.of(), surfaces.gapFindings());
  }

  @Test
  void aGapKeptBesideAnUnnamedFindingStillAbsorbsALaterCopyOfItself() {
    var copy = LOAD_SLOTS_GAP + " Again.";

    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(LOAD_SLOTS_GAP, copy), Map.of(), List.of(LOAD_SLOTS), f -> false);

    assertEquals(List.of(LOAD_SLOTS_GAP), surfaces.descriptionGaps());
  }

  @Test
  void anUnnamedFindingStillTrimsAWalkthroughClauseThatRestatesIt() {
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(),
            Map.of(
                "react/src/api.ts",
                "Slot API client; loadSlots fetches only the first page and drops every slot"
                    + " beyond it"),
            List.of(LOAD_SLOTS),
            f -> false);

    assertEquals("Slot API client", surfaces.fileSummaries().get("react/src/api.ts"));
  }

  // The rendered section, with the claim text echoedBy judges.

  /** ThrillhouseBot-test#300's PR text (react), round 2. */
  private static final String REACT_300 =
      """
      Title: feat(react): add food bank pickup slot board
      Description:
      Adds a React board that lists food bank pickup slots by zone, shows remaining places, \
      renders slot notes, and sends SMS reminders through the gateway.

      - Slots are loaded from the slots API and sorted for display.
      - The board automatically refreshes every 30 seconds so households see new openings \
      without reloading.

      Closes #280
      """;

  private static final String REACT_300_ISSUE =
      """
      Issues this pull request is linked to: #280

      ### Issue #280: Food bank pickup slot board
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] The board lists all pickup slots returned by the slots API, including those beyond \
      the first page
      - [ ] A slot that has reached its capacity is shown as full
      """;

  /** ThrillhouseBot-test#303's PR text (zig), round 2. */
  private static final String ZIG_303 =
      """
      Title: feat(zig): add beehive weight reading compactor
      Description:
      Adds a small Zig service that reads beehive scale samples from stdin and folds them into \
      hourly per-hive buckets.

      Closes #286
      """;

  private static final String ZIG_303_ISSUE =
      """
      Issues this pull request is linked to: #286

      ### Issue #286: Beehive weight reading compactor
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] Raw readings are compacted into hourly per-hive buckets with min, max, mean and count
      - [ ] A day with more than 2,000,000 readings is rejected with error.TooManyReadings
      """;

  /** The Description vs. Implementation section rendered for one gap and one finding. */
  private static String section(String gap, Finding finding, String prText, String issue) {
    var summary = new ReviewResponse.Summary(1, 0, 0, 1, 0, "ok", "Adds a service.", List.of(gap));
    var round =
        new ReviewResult(
            List.of(finding),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "",
            List.of(),
            List.of(),
            0,
            false,
            true,
            null,
            0);
    var sources = new PrSummaryGenerator.DescriptionSources(prText + "\n" + issue, Set.of(), issue);
    var rendered =
        new PrSummaryGenerator(false).generate(1, 10, 0, List.of(), summary, round, sources);
    var start = rendered.indexOf(PrSummaryGenerator.GAPS_HEADING);
    return start < 0 ? "" : rendered.substring(start, rendered.indexOf("\n### ", start));
  }

  @Test
  void theSectionListsALinkedIssueGapWhoseFindingItDoesNotName() {
    // react #300: loadSlots quotes only the code, so the section would not name it.
    var section = section(LOAD_SLOTS_GAP, LOAD_SLOTS, REACT_300, REACT_300_ISSUE);

    assertTrue(section.contains("- " + LOAD_SLOTS_GAP + "\n"), section);
    assertFalse(section.contains(PrSummaryGenerator.GAP_FINDING_PREFIX), section);
    // Listed once: the kept gap stands for its criterion, so no criterion line repeats it.
    assertEquals(1, section.split("beyond the first page", -1).length - 1, section);
  }

  @Test
  void theSectionNamesTheFindingInPlaceOfAGapRestatingIt() {
    // zig #303: the finding quotes the linked issue, so it is named and the gap is not listed.
    var section = section(MAX_DAILY_GAP, MAX_DAILY, ZIG_303, ZIG_303_ISSUE);

    assertFalse(section.contains(MAX_DAILY_GAP), section);
    assertEquals(1, section.split("MAX_DAILY_READINGS cap is declared", -1).length - 1, section);
  }
}
