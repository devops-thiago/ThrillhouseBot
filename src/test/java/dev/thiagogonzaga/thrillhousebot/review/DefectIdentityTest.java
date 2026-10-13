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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #961: a later finding replaces an earlier double-check item only when it is the same defect. */
class DefectIdentityTest {

  static final String DOC = "docs/CONFIG-SCALA.md";

  static final String MAIN = "scala/src/main/scala/port/Main.scala";

  /** ThrillhouseBot-test#176 round 1, a LOW double-check item on the doc table's row 9. */
  static final ReviewResponse.Finding POLL_INTERVAL =
      new ReviewResponse.Finding(
          "low",
          "low",
          DOC,
          9,
          "POLL_INTERVAL documented without its unit (seconds)",
          "The doc row reads \"POLL_INTERVAL | How often the carrier feed is polled | 30\" "
              + "with no unit, while the definition in Config.scala is \"pollIntervalSeconds = "
              + "env.get(\"POLL_INTERVAL\").map(_.toLong).getOrElse(30L)\" and its consumer is "
              + "\"scheduleAtFixedRate(() => tracker.sync(), 0L, config.pollIntervalSeconds, "
              + "TimeUnit.SECONDS)\" — the value is seconds. An operator who assumes "
              + "milliseconds or another unit polls at the wrong rate; state the unit in the "
              + "description.",
          null,
          null);

  /** ThrillhouseBot-test#176 round 3, a MEDIUM double-check item about another key, row 8. */
  static final ReviewResponse.Finding TRACKED_PORTS =
      new ReviewResponse.Finding(
          "medium",
          "low",
          DOC,
          8,
          "TRACKED_PORTS doc example is space-separated but the code splits on commas",
          "The configuration table documents the value with a space-separated example: "
              + "\"| `TRACKED_PORTS` | UN/LOCODE port list to keep events for, e.g. `NLRTM "
              + "SGSIN` | empty |\" (docs/CONFIG-SCALA.md line 8). The definition line that "
              + "reads this key splits it on commas: \"trackedPorts = "
              + "env.getOrElse(\"TRACKED_PORTS\", "
              + "\"\").split(\",\").map(_.trim).filter(_.nonEmpty).toList\" "
              + "(scala/src/main/scala/port/Config.scala, Config.fromEnv). An operator who "
              + "copies the documented example verbatim sets a value containing no comma, so "
              + "split(\",\") yields a single element \"NLRTM SGSIN\" and trackedPorts becomes "
              + "List(\"NLRTM SGSIN\"). The consumer in Tracker.sync — "
              + "\"ingest(page.items.filter(e => config.trackedPorts.contains(e.portCode)))\" — "
              + "then never matches a real UN/LOCODE port code, and every carrier event is "
              + "silently dropped with no error. The documentation states neither the "
              + "separator nor that the example is wrong, and this plausible reading of the "
              + "doc as written produces a configuration that keeps no events. The comma-split "
              + "behavior is itself confirmed by the diff's own test \"config splits tracked "
              + "ports on commas\" asserting Config.fromEnv(Map(\"TRACKED_PORTS\" -> \"NLRTM, "
              + "SGSIN\")).trackedPorts == List(\"NLRTM\", \"SGSIN\"), while the doc's example uses "
              + "a space.",
          null,
          null);

  /** ThrillhouseBot-test#176 round 1, a LOW double-check item on Main.scala:46. */
  static final ReviewResponse.Finding SCHEMA =
      new ReviewResponse.Finding(
          "low",
          "low",
          MAIN,
          46,
          "Verify the port_events schema is actually created",
          "The handler at line 46 queries repo.findByContainer against a connection "
              + "opened as \"DriverManager.getConnection(sys.env.getOrElse(\"DB_URL\", "
              + "\"jdbc:h2:mem:port\"))\". Nothing in the shown diff creates the port_events "
              + "table the query depends on, nor any rows, so the first GET "
              + "/containers/{id}/events against the default in-memory database would fail "
              + "with table-not-found unless a schema initializer exists elsewhere. The pom "
              + "for this module was withheld from this review, so this may be settled there — "
              + "please verify that a schema/seed initializer runs at startup (e.g. a "
              + "Flyway/Liquibase step or a CREATE TABLE in the build's dependencies) before "
              + "merging.",
          null,
          null);

  /** ThrillhouseBot-test#176 round 3, the same defect posted inline as a MEDIUM, same line. */
  static final ReviewResponse.Finding EVENTS_TABLE =
      new ReviewResponse.Finding(
          "medium",
          "medium",
          MAIN,
          46,
          "GET /containers/{id}/events queries a table nothing in the service writes",
          "Producer→consumer trace across the new files: the consumer gate is \"case "
              + "(\"GET\", Some(\"events\")) => ... reply(ex, 200, repo.findByContainer(id, "
              + "q).mkString(\"\\n\"))\" (Main.scala lines 44-46), which executes "
              + "EventRepository.findByContainer's \"SELECT id, status FROM port_events WHERE "
              + "container_id = '\" + containerId + \"' AND port_code = '\" + port + \"'\" "
              + "(EventRepository.scala line 15). The only producer for port_events rows would "
              + "have to be this same diff, and nothing in it writes any: the sole ingest "
              + "paths — the POST /events handler \"reply(ex, 202, s\"accepted "
              + "${tracker.ingest(lines.map(PortEvent.parse))}\")\" and Tracker.sync feeding "
              + "Tracker.ingest — persist events only into the in-memory "
              + "\"history(e.containerId) += e\" (Tracker.scala line 51), and the sole reference "
              + "to the repository in the service is repo.findByContainer itself. Unless an "
              + "out-of-repo system populates port_events, the endpoint deterministically "
              + "returns an empty body for every container this service ingests, which "
              + "contradicts the stated endpoint \"GET /containers/{id}/events\" in the PR "
              + "description. Verify whether port_events is populated externally; otherwise "
              + "persist on ingest or serve the events from the tracker's history.",
          null,
          null);

  private static ReviewResponse.Finding at(
      String risk, String confidence, String file, int line, String title, String description) {
    return new ReviewResponse.Finding(risk, confidence, file, line, title, description, null, null);
  }

  @Test
  void aFindingAboutAnotherKeyOnTheNextRowIsADifferentDefect() {
    // What #955 replaced it by: the two descriptions share enough config-table vocabulary.
    assertTrue(FollowUpAnalyzer.isSameFinding(TRACKED_PORTS, POLL_INTERVAL), "precondition");
    assertFalse(DefectIdentity.sameDefect(TRACKED_PORTS, POLL_INTERVAL));
    assertFalse(FollowUpAnalyzer.replacesUnthreaded(TRACKED_PORTS, POLL_INTERVAL));
  }

  @Test
  void theEventsTableFindingIsTheSchemaItemOnTheSameLine() {
    // What #955 missed it by: worded differently, the two overlap too little.
    assertFalse(FollowUpAnalyzer.isSameFinding(EVENTS_TABLE, SCHEMA), "precondition");
    assertTrue(DefectIdentity.sameDefect(EVENTS_TABLE, SCHEMA));
    assertTrue(FollowUpAnalyzer.replacesUnthreaded(EVENTS_TABLE, SCHEMA));
    assertFalse(
        FollowUpAnalyzer.replacesUnthreaded(SCHEMA, EVENTS_TABLE),
        "the LOW item says less than the MEDIUM finding and replaces nothing");
  }

  @Test
  void differentKeysWithAlikeTitlesOnAdjacentRowsAreNeverOneDefect() {
    var pollUnit =
        at(
            "low",
            "low",
            DOC,
            9,
            "`POLL_INTERVAL` doc row lacks its unit of measure",
            "The row omits it.");
    var freeDaysUnit =
        at(
            "medium",
            "low",
            DOC,
            10,
            "`FREE_DAYS` doc row lacks its unit of measure",
            "The row omits it.");
    assertTrue(
        FindingDeduplicator.titleSimilarity(pollUnit.title(), freeDaysUnit.title())
            >= FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD,
        "precondition: the titles read alike");
    assertFalse(DefectIdentity.sameDefect(freeDaysUnit, pollUnit));

    var sameKey =
        at(
            "medium",
            "low",
            DOC,
            9,
            "POLL_INTERVAL doc row lacks its unit of measure",
            "The row omits it.");
    assertTrue(DefectIdentity.sameDefect(sameKey, pollUnit), "the same key, backticked or not");
  }

  @Test
  void contentOverlapAwayFromTheLineIsNotIdentity() {
    var reworded =
        at(
            "medium",
            "low",
            DOC,
            30,
            "Seconds unit undocumented for the polling interval",
            POLL_INTERVAL.description());
    assertTrue(FollowUpAnalyzer.isSameFinding(reworded, POLL_INTERVAL), "precondition");
    assertFalse(DefectIdentity.sameDefect(reworded, POLL_INTERVAL));
  }

  @Test
  void aSameLineFindingNeedsTwoSharedTitleWordsOrACommonIdentifier() {
    var prior = at("low", "low", MAIN, 46, "Possible null user before logging", "d");
    assertTrue(
        DefectIdentity.sameDefect(
            at("high", "high", MAIN, 46, "Null user dereferenced in the request logger", "x"),
            prior),
        "two shared title words on the same line, though the titles read apart");
    assertFalse(
        DefectIdentity.sameDefect(
            at("high", "high", MAIN, 46, "Unbounded retry loop around logging", "x"), prior),
        "one shared word is not the same defect");
    assertFalse(
        DefectIdentity.sameDefect(
            at("high", "high", MAIN, 47, "Null user dereferenced before logging", "x"),
            at("low", "low", MAIN, 46, "Possible user crash", "d")),
        "the shared-words arm needs the same line");
    assertFalse(
        DefectIdentity.sameDefect(
            at("high", "high", "other/Main.scala", 46, prior.title(), prior.description()), prior),
        "another file");
    assertFalse(
        DefectIdentity.sameDefect(
            at("high", "high", null, 46, prior.title(), prior.description()), prior),
        "no file");
    assertTrue(
        DefectIdentity.sameDefect(
            at("high", "high", MAIN, 46, "Rows never reach the table", "nothing writes events"),
            at("low", "low", MAIN, 46, "Is the `events` table created?", "d")),
        "the prior's identifier named by the later finding");
    assertTrue(
        DefectIdentity.sameDefect(
            at("high", "high", MAIN, 46, "Nothing writes `port_events`", "x"),
            at("low", "low", MAIN, 46, "Is the schema created?", "the port_events table")),
        "the later finding's identifier named by the prior");
  }

  @Test
  void anIdentifierInsideALongerWordIsNotNamed() {
    assertFalse(
        DefectIdentity.sameDefect(
            at("high", "high", MAIN, 46, "New retry loop added", "It prevents a crash."),
            at("low", "low", MAIN, 46, "Is the `events` table created?", "d")),
        "\"prevents\" does not name `events`");
    assertTrue(DefectIdentity.namesWhole("the events table", "events"));
    assertTrue(DefectIdentity.namesWhole("events", "events"));
    assertTrue(DefectIdentity.namesWhole("prevents (events)", "events"), "a later whole match");
    assertFalse(DefectIdentity.namesWhole("eventsource", "events"));
    assertFalse(DefectIdentity.namesWhole("port_events", "events"), "underscore continues a word");
    assertTrue(DefectIdentity.namesWhole("call x.user.name()s", "user.name()"));
    assertTrue(DefectIdentity.namesWhole("(a)", "(a)"));
    assertFalse(DefectIdentity.namesWhole("no match", "events"));
  }

  @Test
  void identifiersAreBacktickedSnakeOrCamelNames() {
    assertEquals(
        Set.of("tracked_ports", "port_events", "findbycontainer", "x y"),
        DefectIdentity.identifiers(
            "TRACKED_PORTS and port_events read by findByContainer with `x y`"));
    assertTrue(DefectIdentity.identifiers(null).isEmpty());
    assertTrue(DefectIdentity.identifiers("GET /containers/{id}/events queries a table").isEmpty());
    assertTrue(DefectIdentity.identifiers("an empty `` span").isEmpty());
    assertTrue(DefectIdentity.identifiers("a blank `   ` span").isEmpty());
  }

  /**
   * The single alternation {@link DefectIdentity#identifiers} read titles with before it was split
   * into one pattern per shape: the reference the split scan must agree with.
   */
  private static final Pattern ORIGINAL_IDENTIFIER =
      Pattern.compile(
          "`([^`\\n]{1,120})`"
              + "|\\b([A-Za-z][A-Za-z0-9]{0,63}(?:_[A-Za-z0-9]{1,64}){1,16})\\b"
              + "|\\b([a-z][a-z0-9]{0,63}(?:[A-Z][a-z0-9]{0,63}){1,16})\\b");

  private static Set<String> originalIdentifiers(String title) {
    var names = new HashSet<String>();
    var matcher = ORIGINAL_IDENTIFIER.matcher(title);
    while (matcher.find()) {
      for (var group = 1; group <= matcher.groupCount(); group++) {
        var name = matcher.group(group);
        if (name != null && !name.isBlank()) {
          names.add(name.strip().toLowerCase(Locale.ROOT));
        }
      }
    }
    return names;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "TRACKED_PORTS and port_events read by findByContainer with `x y`",
        "`foo_bar` then fooBar",
        "foo_bar`baz`qux",
        "fooBar_baz is one name",
        "a`b_c`d and x_y`z` and aB`c`",
        "`fooBar` `` `a` `unclosed fooBar",
        "snake_x_y_z_1_2 and camelCaseNameHere",
        "aBcDeFgHiJkLmNoPqRsTuVwXyZaBcDeFgHiJ is past the hump bound",
        "a_b_c_d_e_f_g_h_i_j_k_l_m_n_o_p_q_r is past the segment bound",
        "x1_ _y 1_a a_ _ __ a__b",
        "`multi\nline` is not a span",
      })
  void identifiersMatchTheSingleAlternation(String title) {
    assertEquals(originalIdentifiers(title), DefectIdentity.identifiers(title), title);
  }

  @Test
  void identifiersMatchTheSingleAlternationOnGeneratedTitles() {
    String[] parts = {
      "a", "b", "Ab", "fooBar", "x_y", "X_Y", "_", "`", "``", "1", " ", "\n", "-", ".", "aB", "zz_",
      "Q", "qQ", "(", "é"
    };
    var random = new Random(42);
    for (var n = 0; n < 20_000; n++) {
      var title = new StringBuilder();
      for (var k = random.nextInt(14); k > 0; k--) {
        title.append(parts[random.nextInt(parts.length)]);
      }
      var text = title.toString();
      assertEquals(originalIdentifiers(text), DefectIdentity.identifiers(text), text);
    }
  }

  @Test
  void identifiersOfALongTitleAreFoundInOnePass() {
    var title = "aB_c `x` dE ".repeat(20_000);
    var names =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> DefectIdentity.identifiers(title));
    assertEquals(originalIdentifiers(title), names);
  }

  @Test
  void namesDifferentIdentifiersOnlyWhenBothNameSomeAndNoneMatch() {
    assertTrue(DefectIdentity.namesDifferentIdentifiers("POLL_INTERVAL unit", "TRACKED_PORTS sep"));
    assertFalse(DefectIdentity.namesDifferentIdentifiers("POLL_INTERVAL unit", "No unit given"));
    assertFalse(DefectIdentity.namesDifferentIdentifiers("Missing unit", "POLL_INTERVAL unit"));
    assertFalse(
        DefectIdentity.namesDifferentIdentifiers(
            "POLL_INTERVAL unit", "`poll_interval_seconds` is seconds"),
        "one name containing the other is the same name");
    assertFalse(
        DefectIdentity.namesDifferentIdentifiers(
            "`poll_interval_seconds` is seconds", "POLL_INTERVAL unit"));
  }

  @Test
  void theOpenThreadGuardKeepsARestatementAboutAnotherKey() {
    var pollThread = at("low", "high", DOC, 9, POLL_INTERVAL.title(), POLL_INTERVAL.description());
    var freeDays =
        at(
            "low",
            "high",
            DOC,
            9,
            "FREE_DAYS documented without its unit (days)",
            POLL_INTERVAL.description());
    assertTrue(FollowUpAnalyzer.isSameFinding(freeDays, pollThread), "precondition");
    assertFalse(FollowUpAnalyzer.duplicatesOpenThread(freeDays, pollThread));
  }

  @Test
  void aDoubleCheckBulletAboutAnotherKeyIsNotTheSameIssueAsAnInlineFinding() {
    var bullet = Finding.fromAiResponse(POLL_INTERVAL);
    var inline =
        Finding.fromAiResponse(
            at(
                "medium",
                "high",
                DOC,
                8,
                "TRACKED_PORTS documented without its unit (seconds)",
                POLL_INTERVAL.description()));
    assertEquals(Optional.empty(), SummarySurfaceDeduplicator.restatedBy(bullet, List.of(inline)));
  }

  // #956, #972, #964: ThrillhouseBot-test's titles, verbatim. Each pair is asked of sameDefect and
  // of the summary's "Same issue as" pairing, which must agree.

  private static void assertPaired(ReviewResponse.Finding later, ReviewResponse.Finding item) {
    assertTrue(DefectIdentity.sameDefect(later, item), later.title());
    assertTrue(DefectIdentity.sameDefect(item, later), "symmetric: " + later.title());
    assertEquals(
        Optional.of(Finding.fromAiResponse(later)),
        SummarySurfaceDeduplicator.restatedBy(
            Finding.fromAiResponse(item), List.of(Finding.fromAiResponse(later))));
  }

  private static void assertApart(ReviewResponse.Finding later, ReviewResponse.Finding item) {
    assertFalse(DefectIdentity.sameDefect(later, item), later.title());
    assertFalse(DefectIdentity.sameDefect(item, later), "symmetric: " + later.title());
    assertEquals(
        Optional.empty(),
        SummarySurfaceDeduplicator.restatedBy(
            Finding.fromAiResponse(item), List.of(Finding.fromAiResponse(later))));
    assertFalse(FollowUpAnalyzer.replacesUnthreaded(later, item));
  }

  /** ThrillhouseBot-test#151: the same missing key restated on its paired code file (#956). */
  @Test
  void aKeyRestatedOnItsPairedCodeFileIsTheSameDefect() {
    var docItem =
        at(
            "low",
            "low",
            "docs/CONFIG-GO.md",
            7,
            "FUELRECON_DATABASE_URL is read by the service but absent from the config table",
            "d");
    var codeItem =
        at(
            "medium",
            "low",
            "go/config.go",
            40,
            "FUELRECON_DATABASE_URL is read by the code but absent from the configuration"
                + " documentation",
            "d");

    assertPaired(codeItem, docItem);
    assertTrue(FollowUpAnalyzer.replacesUnthreaded(codeItem, docItem));
  }

  /**
   * ThrillhouseBot-test#219: one key, two defects (a blank documented default, a parsed value
   * nothing enforces). The key alone does not pair them, nor does a key the other finding names
   * only in its description.
   */
  @Test
  void aSharedKeyAloneIsNotTheSameDefect() {
    var docItem =
        at(
            "low",
            "low",
            "docs/CONFIG-RUST.md",
            10,
            "COLDCHAIN_MAX_BATCH documentation leaves the default blank and the type unstated",
            "d");
    var enforced =
        at(
            "medium",
            "high",
            "rust/src/config.rs",
            24,
            "COLDCHAIN_MAX_BATCH is parsed into Config but never enforced anywhere",
            "d");
    assertApart(enforced, docItem);

    var mentionsTheKey =
        at(
            "medium",
            "high",
            "rust/src/batch.rs",
            7,
            "Batch writer ignores the configured limit and the documented default",
            "The writer never reads COLDCHAIN_MAX_BATCH, so the documented default is not applied.");
    assertApart(mentionsTheKey, docItem);
  }

  /** ThrillhouseBot-test#186: a reworded concurrency re-raise two lines away (#972). */
  @Test
  void aReworderedRaceTwoLinesAwayNamingTheSameTypeIsTheSameDefect() {
    var item =
        at(
            "medium",
            "low",
            "csharp/src/ParkingPermits/ZoneQuota.cs",
            13,
            "ZoneQuota mutates an unsynchronized Dictionary from concurrent request handlers and the"
                + " expiry worker",
            "d");
    var inline =
        at(
            "high",
            "high",
            "csharp/src/ParkingPermits/ZoneQuota.cs",
            15,
            "ZoneQuota dictionary is read and written from concurrent HTTP requests with no"
                + " synchronization",
            "d");

    assertPaired(inline, item);
    assertTrue(FollowUpAnalyzer.replacesUnthreaded(inline, item));
  }

  /** ThrillhouseBot-test#209: the waitlist nothing serves, raised on its caller's file (#972). */
  @Test
  void aDefectRaisedOnTheFileThatCallsTheClassIsTheSameDefect() {
    var item =
        at(
            "medium",
            "low",
            "java/src/main/java/com/acme/beds/Waitlist.java",
            24,
            "Waitlist is never served: ordered() and topN() have no production caller",
            "d");
    var inline =
        at(
            "high",
            "high",
            "java/src/main/java/com/acme/beds/BedAllocator.java",
            47,
            "Nothing ever serves the waitlist; freed beds go to whoever asks next, not highest"
                + " acuity",
            "The waitlist in this diff is write-only: no production code ever calls"
                + " Waitlist.ordered() or Waitlist.topN().");

    assertPaired(inline, item);
    // The file's class name alone is common prose; without the symbol it stays apart.
    assertApart(
        at("high", "high", inline.file(), inline.line(), inline.title(), "Beds are freed early."),
        item);
  }

  /** Two titles in one file that open alike but name different places, far apart, stay apart. */
  @Test
  void titlesThatOpenAlikeInOneFileAreNotOneDefectAtAnyDistance() {
    assertApart(
        at(
            "high",
            "high",
            "src/Auth.java",
            120,
            "Auth rejects valid tokens on the token refresh endpoint",
            "d"),
        at(
            "medium",
            "low",
            "src/Auth.java",
            32,
            "Auth rejects valid tokens on the login page",
            "d"));
  }

  /** A class name shared by two titles, with a generic verb, is not one defect. */
  @Test
  void aFileClassNameWithGenericWordsIsNotTheSameDefect() {
    assertApart(
        at(
            "high",
            "high",
            "src/Dashboard.java",
            88,
            "Auth always rejects the first valid token after startup",
            "d"),
        at(
            "medium",
            "low",
            "src/Auth.java",
            32,
            "Auth rejects valid tokens when the cache is cold",
            "d"));
  }

  /** ThrillhouseBot-test#212: the crash raised where the parse is called, citing it (#972). */
  @Test
  void aFindingCitingTheOtherFindingsCodeAndLocationIsTheSameDefect() {
    var item =
        at(
            "medium",
            "low",
            "scala/src/main/scala/port/Main.scala",
            32,
            "PortEvent.parse indexes f(4) without checking the field count, crashing on malformed"
                + " lines",
            "d");
    var citing =
        "The /events handler feeds the untrusted request body straight into it: `reply(ex, 202,"
            + " s\"accepted ${tracker.ingest(lines.map(PortEvent.parse))}\")`"
            + " (scala/src/main/scala/port/Main.scala:31-32).";
    var inline =
        at(
            "medium",
            "high",
            "scala/src/main/scala/port/Models.scala",
            11,
            "Malformed event line crashes POST /events with no HTTP response",
            citing);
    assertPaired(inline, item);

    var elsewhere = citing.replace("Main.scala:31-32", "Main.scala:80");
    assertApart(
        at("medium", "high", inline.file(), inline.line(), inline.title(), elsewhere), item);
    assertApart(
        at(
            "medium",
            "high",
            inline.file(),
            inline.line(),
            inline.title(),
            citing.replace("PortEvent.parse", "decode")),
        item);
    assertApart(
        at("medium", "high", inline.file(), inline.line(), "Event handler logs too much", citing),
        item);
  }

  /** ThrillhouseBot-test#186 (round 14): a race and an off-by-one on one limit check (#964). */
  @Test
  void twoDefectClassesOnOneLineAreTwoDefects() {
    var race =
        at(
            "medium",
            "low",
            "csharp/src/ParkingPermits/IssuanceService.cs",
            34,
            "Per-address limit is a check-then-act race: concurrent applications both pass the"
                + " count guard",
            "d");
    var offByOne =
        at(
            "high",
            "high",
            "csharp/src/ParkingPermits/IssuanceService.cs",
            34,
            "Per-address limit check is off by one: an address at the maximum still gets another"
                + " permit",
            "d");

    assertTrue(DefectIdentity.classesConflict(race.title(), offByOne.title()));
    // A race worded without "race" still names its class.
    assertApart(
        offByOne,
        at(
            "medium",
            "low",
            race.file(),
            34,
            "Per-address limit guard can be passed by two requests at once",
            "d"));
    assertApart(offByOne, race);
  }

  /** ThrillhouseBot-test#196 (round 15): one key, a docs gap and a hardcoded credential. */
  @Test
  void aDocumentationGapIsNotTheCredentialFindingOnTheSameKey() {
    assertApart(
        at(
            "high",
            "high",
            "python/marketplace/config.py",
            5,
            "Security scan: hardcoded credential in PAYMENT_API_KEY (aWpm…, 40 chars)",
            "d"),
        at(
            "medium",
            "low",
            "python/marketplace/config.py",
            13,
            "docs recommend setting PAYMENT_API_KEY via env, but the code never reads it",
            "d"));
  }

  /** ThrillhouseBot-test#214 and #175 (rounds 16 and 13): common words, or another key. */
  @Test
  void commonTitleWordsOrAnotherKeyNeverPair() {
    assertApart(
        at(
            "medium",
            "high",
            "zig/src/queue.zig",
            58,
            "Approval list records every submission and held jobs can never print",
            "d"),
        at(
            "medium",
            "low",
            "zig/src/queue.zig",
            70,
            "dequeue scans the whole job list on every job",
            "d"));
    var databaseUrl =
        at(
            "low",
            "high",
            "docs/CONFIG-GO.md",
            10,
            "FUELRECON_DATABASE_URL is read by the service but absent from the configuration table",
            "d");
    assertApart(
        databaseUrl,
        at(
            "low",
            "low",
            "docs/CONFIG-GO.md",
            10,
            "Configuration table documents no units or default for FUELRECON_PAGE_TIMEOUT",
            "d"));
    assertApart(
        databaseUrl,
        at(
            "low",
            "low",
            "docs/CONFIG-GO.md",
            9,
            "FUELRECON_PROVIDER_HOSTS is comma-separated but never documented as a list",
            "d"));
  }

  @Test
  void subjectsAndDefectClassesAreReadFromTheTitle() {
    assertEquals(
        Set.of("portevent.parse", "portevent"),
        DefectIdentity.subjects(
            Finding.fromAiResponse(
                at("low", "low", "src/Main.scala", 1, "PortEvent.parse indexes f(4)", "d"))));
    assertEquals(
        Set.of("topn", "ordered", "waitlist"),
        DefectIdentity.subjects(
            Finding.fromAiResponse(
                at(
                    "low",
                    "low",
                    "src/Waitlist.java",
                    1,
                    "Waitlist is never served: ordered() and topN() have no caller",
                    "d"))));
    assertTrue(
        DefectIdentity.subjects(Finding.fromAiResponse(at("low", "low", "Makefile", 1, null, "d")))
            .isEmpty());
    assertEquals(Set.of(), DefectIdentity.defectClasses(null));
    assertEquals(
        Set.of("complexity"), DefectIdentity.defectClasses("dedupe is O(n^2) over 2M samples"));
    assertFalse(DefectIdentity.classesConflict("Possible null user", "Unrelated wording"));
    assertEquals(
        Set.of("nullness"),
        DefectIdentity.defectClasses("Logger throws NullPointerException on anonymous requests"));
    assertEquals(
        Set.of("nullness"), DefectIdentity.defectClasses("Nullable user reaches the logger"));
    assertEquals(Set.of(), DefectIdentity.symbols(null));
    assertEquals(Set.of(), DefectIdentity.symbols("go() fails"), "two-character names say nothing");
  }

  /**
   * The unverified note a fail-open round appends to every finding it kept says nothing about
   * either defect: an identifier it happens to contain ("audit") does not pair two findings.
   */
  @Test
  void theUnverifiedNoteNeverPairsTwoFindings() {
    var description =
        "The handler rejects the request.\n\n"
            + dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService.UNVERIFIED_NOTE;
    var later = at("high", "medium", MAIN, 46, "Request rejected on retry", description);
    var prior = at("low", "low", MAIN, 46, "Is the `audit` table created?", "d");

    assertFalse(DefectIdentity.sameDefect(later, prior));
    assertTrue(
        DefectIdentity.sameDefect(
            at("high", "medium", MAIN, 46, "Request rejected on retry", "writes the audit row"),
            prior),
        "the same identifier in the finding's own words still pairs");
  }

  @Test
  void theEdgesOfEachArmStayApart() {
    var withFile = at("high", "high", "src/A.java", 1, "Race on the counter", "d");
    assertFalse(
        DefectIdentity.sameDefect(
            at("high", "high", null, 1, "Race on the counter", "d"), withFile));
    assertFalse(
        DefectIdentity.sameDefect(
            withFile, at("high", "high", null, 1, "Race on the counter", "d")));

    // Titles that restate each other word for word but with opposite polarity are no pair.
    assertFalse(
        SummarySurfaceDeduplicator.titlesRestate(
            "Request input is sanitized before the query runs",
            "Request input is not sanitized before the query runs"));
    assertFalse(
        SummarySurfaceDeduplicator.titlesRestate(
            "Cache entry expires after one hour in staging only",
            "Cache entry expires immediately, so every request misses and recomputes the report"));

    // A shared subject and a shared defect class, with no other shared word, pair.
    assertTrue(
        DefectIdentity.sameDefect(
            at("high", "high", "src/Ledger.java", 5, "LedgerCache race", "d"),
            at("medium", "low", "src/Api.java", 9, "LedgerCache unsynchronized", "d")));
    // A file class name is prose ("main", "config"): a shared word or defect class beside it is not
    // enough; only a description naming a symbol of the other's title corroborates it.
    assertFalse(
        DefectIdentity.sameDefect(
            at("high", "high", "src/Ledger.java", 5, "Ledger race", "d"),
            at("medium", "low", "src/Api.java", 9, "Concurrent Ledger writes", "d")));
    assertFalse(
        DefectIdentity.sameDefect(
            at("medium", "low", "api/Main.java", 5, "Main thread races the expiry worker", "d"),
            at(
                "high",
                "high",
                "worker/ThreadWorker.java",
                9,
                "Main thread races the shutdown hook",
                "d")));
  }

  @Test
  void aCitationNeedsADescriptionATitleAFileNameAndALineInRange() {
    var cited = at("medium", "low", "src/port/Main.scala", 32, "PortEvent.parse crashes", "d");
    var citing = "Calls PortEvent.parse (src/port/Main.scala:%s).";

    assertFalse(
        DefectIdentity.sameDefect(
            at("medium", "high", "src/port/Models.scala", 11, "Malformed line crashes", null),
            cited));
    assertFalse(
        DefectIdentity.sameDefect(
            at("medium", "high", "src/port/Models.scala", 11, "Malformed line crashes", "x"),
            at("medium", "low", "src/port/Main.scala", 32, null, "d")));
    assertFalse(
        DefectIdentity.sameDefect(
            at("medium", "high", "src/port/Models.scala", 11, "Malformed line crashes", "x"),
            at("medium", "low", "src/port/", 32, "PortEvent.parse crashes", "d")));
    for (var outOfRange : List.of("10", "40-50")) {
      assertFalse(
          DefectIdentity.sameDefect(
              at(
                  "medium",
                  "high",
                  "src/port/Models.scala",
                  11,
                  "Malformed line crashes",
                  citing.formatted(outOfRange)),
              cited),
          outOfRange);
    }
    assertTrue(
        DefectIdentity.sameDefect(
            at(
                "medium",
                "high",
                "src/port/Models.scala",
                11,
                "Handler panics on bad input",
                citing.formatted("30")),
            cited),
        "one shared defect class (crash) is enough beside the citation");
  }

  /** A citation worded in prose counts as one in the file:line form does. */
  @Test
  void aCitationMayBeWorded() {
    var cited = at("medium", "low", "src/port/Main.scala", 32, "PortEvent.parse crashes", "d");
    for (var citation :
        List.of(
            "Calls PortEvent.parse (src/port/Main.scala, lines 31-32).",
            "Calls PortEvent.parse at line 32 of src/port/Main.scala.",
            "Calls PortEvent.parse in lines 30 to 31 in Main.scala.",
            "Calls PortEvent.parse (Main.scala:31..32).")) {
      assertTrue(
          DefectIdentity.sameDefect(
              at("medium", "high", "src/port/Models.scala", 11, "Handler panics", citation), cited),
          citation);
    }
    assertFalse(
        DefectIdentity.sameDefect(
            at(
                "medium",
                "high",
                "src/port/Models.scala",
                11,
                "Handler panics",
                "Calls PortEvent.parse at line 90 of src/port/Main.scala."),
            cited));
    // "malformed" names an input format as often as a crash; it is not the crash class.
    assertEquals(Set.of(), DefectIdentity.defectClasses("Malformed date format in the config"));
  }

  /** The description arm reads only a shared run of words, never the loose overlap. */
  @Test
  void aTitleMatchesADescriptionOnlyByAPhrase() {
    assertFalse(
        SummarySurfaceDeduplicator.titleSharesPhraseWith(
            "Cache entry expires after one hour",
            "The hour cache entry is set; it expires once per deploy after restart."));
    assertTrue(
        SummarySurfaceDeduplicator.titleSharesPhraseWith(
            "Only the first page of usage records reaches the aggregator",
            "only the first page of usage records is returned"));
  }

  @Test
  void aQualifiedCitationCannotBindAnotherDirectorysSameNamedFile() {
    var cited =
        at(
            "medium",
            "low",
            "src/port/Main.scala",
            32,
            "PortEvent.parse crashes on missing columns",
            "d");
    for (var citation :
        List.of(
            "src/registry/Main.scala:32", "src/registry/Main.scala, lines 31-32",
            "line 32 of src/registry/Main.scala", "lines 31 to 32 in src/registry/Main.scala",
            "line 32 of `src/registry/Main.scala`", "line 32 of 'src/registry/Main.scala'")) {
      var other =
          at(
              "medium",
              "high",
              "src/registry/Reader.scala",
              10,
              "Registry request crashes on invalid input",
              "Calls PortEvent.parse in " + citation + ".");
      assertFalse(DefectIdentity.sameDefect(cited, other), citation);
      assertFalse(DefectIdentity.sameDefect(other, cited), citation);
    }
    for (var citation :
        List.of(
            "src/port/Main.scala:32",
            "port/Main.scala:32",
            "line 32 of src/port/Main.scala",
            "line 32 of `src/port/Main.scala`",
            "line 32 of 'src/port/Main.scala'",
            "line 32 of \"src/port/Main.scala\"",
            "lines 31 to 32 in port/Main.scala",
            "Main.scala:32")) {
      var same =
          at(
              "medium",
              "high",
              "src/registry/Reader.scala",
              10,
              "Registry request crashes on invalid input",
              "Calls PortEvent.parse in " + citation + ".");
      assertTrue(DefectIdentity.sameDefect(cited, same), citation);
      assertTrue(DefectIdentity.sameDefect(same, cited), citation);
    }
  }
}
