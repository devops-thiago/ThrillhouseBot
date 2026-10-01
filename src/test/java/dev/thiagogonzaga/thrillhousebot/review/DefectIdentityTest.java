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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
