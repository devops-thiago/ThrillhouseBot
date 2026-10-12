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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checking the summary call's overview against the change it describes (#964). */
class OverviewScreenTest {

  private static final List<FileDiff> FILES =
      List.of(
          new FileDiff(
              "src/main/java/dev/acme/SecretScanner.java", "added", 9, 0, 9, "+class S {}"),
          new FileDiff("react/src/Dashboard.tsx", "added", 5, 0, 5, "+export function D() {}"),
          new FileDiff(
              "docs/CONFIG-REACT.md",
              "added",
              4,
              0,
              4,
              "+| VITE_API_URL | url |\n+| VITE_PAGE_SIZE | 50 |"),
          new FileDiff("react/src/main.tsx", "added", 3, 0, 3, "+render()"),
          new FileDiff("img/logo.png", "added", 0, 0, 0, null));

  private static ReviewResponse.Summary summary(
      String purpose, List<ReviewResponse.FileSummary> files) {
    return new ReviewResponse.Summary(
        1, 0, 1, 0, 0, "ok", purpose, List.of(), List.of(), files, null, List.of());
  }

  private static ReviewResponse.FileSummary row(String path, String text) {
    return new ReviewResponse.FileSummary(path, text);
  }

  @Test
  void aNearMissPathIsMappedAndAPlaceholderIsDropped() {
    // devops-thiago/ThrillhouseBot#966: one junk entry with a misspelled path and the text
    // "placeholder" blanked every row.
    var screened =
        OverviewScreen.screen(
            summary(
                "p",
                List.of(
                    row("src/main/java/dev/thiagosonzaga-placeholder/SecretScanner.java", "scans"),
                    row("src/Dashboard.tsx", "renders the dashboard"),
                    row("react/src/main.tsx", "placeholder"))),
            FILES,
            "");

    assertEquals(
        List.of(
            row("src/main/java/dev/acme/SecretScanner.java", "scans"),
            row("react/src/Dashboard.tsx", "renders the dashboard")),
        screened.fileSummaries());
  }

  @Test
  void aPaddedExactPathStillWinsOverANearMiss() {
    var screened =
        OverviewScreen.screen(
            summary(
                "p",
                List.of(
                    row("src/Dashboard.tsx", "a near miss"),
                    row("  react/src/Dashboard.tsx ", "the exact one"))),
            FILES,
            "");

    assertEquals(
        List.of(row("react/src/Dashboard.tsx", "the exact one")), screened.fileSummaries());
  }

  @Test
  void anEntryNamingTheFileExactlyWinsOverANearMiss() {
    var screened =
        OverviewScreen.screen(
            summary(
                "p",
                List.of(
                    row("src/Dashboard.tsx", "a near miss"),
                    row("react/src/Dashboard.tsx", "the exact one"))),
            FILES,
            "");

    assertEquals(
        List.of(row("react/src/Dashboard.tsx", "the exact one")), screened.fileSummaries());
  }

  @ParameterizedTest
  @ValueSource(strings = {"placeholder", "TODO", "n/a", "…", "-", "  ", "Summary.", "TBD"})
  void placeholderTextIsNotASummary(String text) {
    assertEquals("", OverviewScreen.screenedText(text, "+x", ""));
  }

  @Test
  void findingsTalkIsCutFromARow() {
    // react #177: the main.tsx row said it "hosts the two confirmed findings"; kotlin #192: "no
    // findings in this batch" with six open.
    assertEquals(
        "Entry point that renders the dashboard",
        OverviewScreen.screenedText(
            "Entry point that renders the dashboard; hosts the two confirmed findings", "+x", ""));
    assertEquals("", OverviewScreen.screenedText("No findings in this batch", "+x", ""));
    assertTrue(OverviewScreen.talksAboutFindings("Findings for this batch are posted inline"));
    // A batch-processing file's role names "this batch" without the review's findings.
    assertEquals(
        "Processes this batch of rows before the next one is read",
        OverviewScreen.screenedText(
            "Processes this batch of rows before the next one is read", "+x", ""));
    // A leading separator leaves an empty token before "findings": it is not a count.
    assertEquals(
        "(findings) parser for the dashboard",
        OverviewScreen.screenedText("(findings) parser for the dashboard", "+x", ""));
    assertEquals("", OverviewScreen.screenedText("Holds 3 findings", "+x", ""));
    // A file about findings is still described.
    assertEquals(
        "Refines findings before they are posted",
        OverviewScreen.screenedText("Refines findings before they are posted", "+x", ""));
  }

  @Test
  void onlyThePartNamingAnInventedSettingIsCut() {
    var patch = "+| VITE_API_URL | url |";

    assertEquals(
        "Documents the API URL",
        OverviewScreen.screenedText(
            "Documents the API URL, and caps pages at VITE_PAGE_LIMIT", patch, ""));
    assertEquals(
        "Documents VITE_API_URL",
        OverviewScreen.screenedText("Documents VITE_API_URL and MAX_PER_ZONE", patch, ""));
  }

  @Test
  void aSettingTheFilesPatchDoesNotHoldIsCut() {
    var patch = "+| VITE_API_URL | url |\n+| VITE_PAGE_SIZE | 50 |";

    assertEquals(
        "Documents VITE_API_URL",
        OverviewScreen.screenedText("Documents VITE_API_URL; adds MAX_PER_ZONE", patch, ""));
    assertEquals("", OverviewScreen.screenedText("Documents MAX_PER_ZONE and currency", patch, ""));
    // No patch to read: nothing is judged.
    assertEquals(
        "Logo for MAX_PER_ZONE", OverviewScreen.screenedText("Logo for MAX_PER_ZONE", "", ""));
    assertEquals(
        "Logo for MAX_PER_ZONE", OverviewScreen.screenedText("Logo for MAX_PER_ZONE", null, ""));
  }

  @Test
  void aSettingAFindingOrThePrTextNamesIsNotInvented() {
    // A finding can quote a constant on a line the patch does not show.
    var patch = "+| VITE_API_URL | url |";

    assertEquals(
        "Documents MAX_PER_ZONE",
        OverviewScreen.screenedText(
            "Documents MAX_PER_ZONE", patch, "Finding: MAX_PER_ZONE is never enforced"));
  }

  @Test
  void emptyClausesAreDroppedAndAnExactPathKeepsItsScreenedText() {
    assertEquals("one; two", OverviewScreen.screenedText("one;; two;", "+x", ""));
    var screened =
        OverviewScreen.screen(
            summary(
                "p",
                List.of(row("react/src/main.tsx", "Renders it; hosts the two confirmed findings"))),
            FILES,
            null);

    assertEquals(List.of(row("react/src/main.tsx", "Renders it")), screened.fileSummaries());
  }

  @Test
  void onlyThePurposeChangingStillYieldsANewSummary() {
    var clean =
        summary("Adds it (THRILLHOUSEBOT-9). Done.", List.of(row("react/src/main.tsx", "renders")));

    var screened = OverviewScreen.screen(clean, FILES, "");

    assertEquals("Done.", screened.prPurpose());
    assertEquals(clean.fileSummaries(), screened.fileSummaries());
  }

  @Test
  void aPlaceholderClauseIsDroppedFromARow() {
    assertEquals(
        "renders the dashboard",
        OverviewScreen.screenedText("TBD; renders the dashboard", "+x", ""));
    assertEquals(
        "renders the dashboard",
        OverviewScreen.screenedText("renders the dashboard; n/a", "+x", ""));
    // Nothing but placeholder clauses is no summary.
    assertEquals("", OverviewScreen.screenedText("TBD; n/a", "+x", ""));
  }

  @Test
  void anAbbreviationDoesNotEndTheSentenceItsTicketKeyIsIn() {
    var purpose = "Adjusts limits, e.g. THRILLHOUSEBOT-100. Keeps the rest, i.e. the defaults.";

    assertEquals(
        "Keeps the rest, i.e. the defaults.",
        OverviewScreen.withoutInventedTickets(purpose, "Title: limits"));
    assertEquals(
        "Adjusts limits, E.G. ABCD-1, vs. EFGH-2. Ships it.",
        OverviewScreen.withoutInventedTickets(
            "Adjusts limits, E.G. ABCD-1, vs. EFGH-2. Ships it.", "ABCD-1 EFGH-2"));
  }

  @Test
  void sentencesSplitAtEveryEndButAMidSentenceAbbreviation() {
    assertEquals(
        List.of("One, e.g.\nthis.", "Two vs. three, etc. and so on.", "Four etc.", "Five!"),
        OverviewScreen.sentences(
            "  One, e.g.\nthis. Two vs. three, etc. and so on. Four etc. Five!  "));
  }

  @Test
  void anAbbreviationBeforeALowerCaseWordOrANameDoesNotEndTheSentence() {
    assertEquals(
        List.of(
            "Adds approx. ten retries, as Dr. Smith and Mr. Jones asked in No. 5 of Fig. 2.",
            "Ships Acme Inc. tooling.",
            "Fixes the API.",
            "Done."),
        OverviewScreen.sentences(
            "Adds approx. ten retries, as Dr. Smith and Mr. Jones asked in No. 5 of Fig. 2. Ships"
                + " Acme Inc. tooling. Fixes the API. Done."));
  }

  @Test
  void aCapitalisedWordEndingASentenceIsNotTakenForATitle() {
    assertEquals(
        List.of(
            "Runs the dev server via Vite.",
            "Tracks ABCD-1.",
            "Uses Java.",
            "No.",
            "Then it ships."),
        OverviewScreen.sentences(
            "Runs the dev server via Vite. Tracks ABCD-1. Uses Java. No. Then it ships."));
    assertEquals(
        "Runs the dev server via Vite.",
        OverviewScreen.withoutInventedTickets(
            "Runs the dev server via Vite. Tracks ABCD-1.", "Title: dev server"));
  }

  @Test
  void anAbbreviationAfterPunctuationIsStillRecognised() {
    assertEquals(
        List.of("Adds retries (e.g. Backoff) as (Dr. Smith) asked, —approx. ten of them.", "Done."),
        OverviewScreen.sentences(
            "Adds retries (e.g. Backoff) as (Dr. Smith) asked, —approx. ten of them. Done."));
  }

  @Test
  void anInitialismDoesNotEndTheSentence() {
    assertEquals(
        List.of("Bills U.S. Customers at 9 A.M. Eastern time.", "Done."),
        OverviewScreen.sentences("Bills U.S. Customers at 9 A.M. Eastern time. Done."));
  }

  @Test
  void aSentenceEndingInEtcIsSplitBeforeACapital() {
    assertEquals(
        "Lists the parsers, codecs, etc.",
        OverviewScreen.withoutInventedTickets(
            "Lists the parsers, codecs, etc. Adds ABCD-2 for the rotator.", "Title: parsers"));
    // Followed by lower case, "etc." stays inside its sentence.
    assertEquals(
        "",
        OverviewScreen.withoutInventedTickets(
            "Lists parsers, etc. and adds ABCD-2 for the rotator.", "Title: parsers"));
  }

  @Test
  void aKeyTheMaterialWritesWithoutItsHyphenIsKnown() {
    assertEquals(
        "Hashes with BLAKE3-256 for the cache. Uses WHIRL-512.",
        OverviewScreen.withoutInventedTickets(
            "Hashes with BLAKE3-256 for the cache. Uses WHIRL-512. Fixes ABCD-9.",
            "Title: cache\nWHIRL512 digests"));
    // Material that repeats its words, as every real PR text does, is read as well.
    assertEquals(
        "Uses WHIRL-512.",
        OverviewScreen.withoutInventedTickets(
            "Uses WHIRL-512.",
            "Title: the cache\nThe cache keeps the WHIRL512 digests of the cache"));
    // Pieced together across two words of the material, it is not held.
    assertEquals(
        "",
        OverviewScreen.withoutInventedTickets("Fixes ABCD-9.", "Title: steps\nRun ABCD 9 times"));
  }

  @Test
  void aKeyWrittenInAnyCaseIsJudged() {
    // The material names JIRA-7 and nothing else, so the sentence holding another key goes.
    var material = "Title: ship\nCloses JIRA-7";
    var keptSentences =
        "Ships it. Fixes jira-7. Speaks Http-2 and soap-1.2. Runs on Java-17. Ships Phase-2.";

    // A key beside a tracker cue ("Fixes ticket", "Closes") is judged; it is not in the material.
    assertEquals(
        "Ships it.",
        OverviewScreen.withoutInventedTickets(
            "Ships it. Fixes ticket abcd-12. Closes Proj-7.", material));
    // A closing verb elsewhere in the sentence does not make a compound a key.
    var compounds = "Fixes the phase-2 layout bug. Closes the stage-1 gap.";
    assertEquals(compounds, OverviewScreen.withoutInventedTickets(compounds, material));
    // A key the material holds in another case is kept, as are words without a tracker cue.
    assertEquals(keptSentences, OverviewScreen.withoutInventedTickets(keptSentences, material));
  }

  @Test
  void aLowerCaseKeyNeedsATrackerCue() {
    // Ordinary hyphenated words stay.
    var plain =
        "Adds python-3 support. Runs phase-2 of the migration. Speeds the stage-1 build."
            + " level-3 headings render too.";
    assertEquals(plain, OverviewScreen.withoutInventedTickets(plain, "Title: build"));
    assertEquals(
        "abcd-12 builds first.",
        OverviewScreen.withoutInventedTickets("abcd-12 builds first.", ""));
    // A tracker word, a leading #, or the prefix in capitals elsewhere makes one a key.
    assertEquals(
        "Ships it.",
        OverviewScreen.withoutInventedTickets(
            "Ships it. Fixes ticket abcd-12. Lands #wxyz-3. Also proj-9, as in PROJ-1.",
            "Title: ship\nPROJ-1"));
  }

  @Test
  void aStandardsBodyCodeIsNotTakenForAnInventedTicket() {
    var purpose =
        "Follows ISO-8601 dates. Implements RFC-7231. Fixes CVE-2024-1234. Verifies payloads with"
            + " HMAC-256. Packs with ZSTD-1. Meets NIST-800 and OWASP-10. Fixes ABCD-9.";

    assertEquals(
        "Follows ISO-8601 dates. Implements RFC-7231. Fixes CVE-2024-1234. Verifies payloads with"
            + " HMAC-256. Packs with ZSTD-1. Meets NIST-800 and OWASP-10.",
        OverviewScreen.withoutInventedTickets(purpose, "Title: dates"));
  }

  @Test
  void aStandardsCodeIsNotTakenForAnInventedTicket() {
    var purpose =
        "Returns HTTP-404 for unknown routes. Adds FIPS-140 validation. Speaks HTTP-2 and"
            + " OAUTH-2. Adds SAML-2.0 and SAML-2 logins over SOAP-1.2. Hashes with SHAKE-256 and"
            + " BASE-32. Fixes ABCD-9.";

    assertEquals(
        "Returns HTTP-404 for unknown routes. Adds FIPS-140 validation. Speaks HTTP-2 and OAUTH-2."
            + " Adds SAML-2.0 and SAML-2 logins over SOAP-1.2. Hashes with SHAKE-256 and BASE-32.",
        OverviewScreen.withoutInventedTickets(purpose, "Title: routes\nReturns 404"));
  }

  @Test
  void anInventedTicketKeyTakesItsSentenceOut() {
    // c #205 round 2: "THRILLHOUSEBOT-122" appears nowhere in the PR or its issues.
    var purpose =
        "Adds a C log rotator (THRILLHOUSEBOT-122). It ships a Makefile. Fixes JIRA-7 and uses"
            + " SHA-256.";

    assertEquals(
        "It ships a Makefile. Fixes JIRA-7 and uses SHA-256.",
        OverviewScreen.withoutInventedTickets(purpose, "Title: rotator\nCloses jira-7"));
    assertEquals("No key here.", OverviewScreen.withoutInventedTickets("No key here.", ""));
    assertNull(OverviewScreen.withoutInventedTickets(null, ""));
    // Every key of a sentence must be known; the first unknown one takes it out.
    assertEquals(
        "Known.",
        OverviewScreen.withoutInventedTickets("Known. Fixes JIRA-7 and ABCD-9.", "JIRA-7"));
  }

  @Test
  void aSummaryThatPassesIsReturnedAsItIs() {
    var clean = summary("Adds a dashboard.", List.of(row("react/src/main.tsx", "renders it")));

    assertSame(clean, OverviewScreen.screen(clean, FILES, ""));
    assertNull(OverviewScreen.screen(null, FILES, ""));
  }

  @Test
  void aSummaryWhoseRowsNameNoChangedFileIsKeptForTheRendererToReport() {
    var stray = summary("p", List.of(row("elsewhere/Unknown.kt", "something")));

    var screened = OverviewScreen.screen(stray, FILES, "");

    assertEquals(stray.fileSummaries(), screened.fileSummaries());
  }

  @Test
  void anEntryWithNoPathOrTextIsDropped() {
    var screened =
        OverviewScreen.screen(
            summary(
                "p (THRILLHOUSEBOT-1).", List.of(row(null, "x"), row("react/src/main.tsx", null))),
            FILES,
            "");

    assertEquals(List.of(), screened.fileSummaries());
    assertEquals("", screened.prPurpose());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "hosts 2 findings",
        // A clause the finding word opens is judged by the words after it.
        "Findings are reported inline for the parser",
        "(Findings tracked elsewhere)",
        "Findings: two confirmed here",
        "This file has two confirmed findings",
        // An adverb before the count, a spelled count above five, or the PR as the subject.
        "hosts only two findings",
        "hosts six findings",
        "holds exactly twelve findings",
        "This PR hosts the two findings",
        "The review has no findings here",
        "Holds no security findings",
        "Hosts all the findings from the review",
        "Hosts all of the findings",
        "Contains open findings",
        // Any spelled count, and any -ly adverb before it.
        "hosts one finding",
        // Any quantifier or possessive between the holding verb and the findings.
        // The clause is the findings' phrase alone.
        "Two confirmed findings here",
        "Confirmed findings in this batch",
        "3 findings left",
        "No findings yet",
        // An existential "there is/are" holds the findings as a holding verb does.
        "There are no findings in this file",
        "There are two findings here",
        "There is one finding",
        "There were not any findings",
        // Contracted existentials, with either apostrophe.
        "There's no findings in this file",
        "There’s one finding here",
        "There're two findings",
        "Theres no findings",
        // "and" inside a spelled count.
        "hosts a hundred and one findings",
        // Any connector between two counts is part of the count or range.
        // A partitive subject stating the findings' state.
        "None of the findings are open",
        "All of the findings were confirmed",
        "Some of its findings remain",
        "Two of these findings are posted inline",
        "Each of the findings is tracked",
        "hosts two or three findings",
        "holds five to ten findings",
        "lists two through four findings",
        "has 10–20 findings",
        "hosts 3-5 findings",
        "Lists numerous findings",
        "Has some findings",
        "Holds all findings",
        "Contains various findings",
        "Carries a handful of findings",
        "Has a couple of findings",
        "Hosts its findings",
        "Holds this PR's findings",
        "Contains the review's findings",
        "Reports open findings",
        "hosts thirteen findings",
        "holds twenty-three findings",
        "hosts roughly two hundred findings",
        "holds merely four findings",
        "Has reported findings",
        "and hosts the two findings",
        "hosts two high findings"
      })
  void aClauseAboutWhatTheFileHoldsIsFindingsTalk(String clause) {
    assertTrue(OverviewScreen.talksAboutFindings(clause));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // The leading empty word the split yields is no finding count.
        "(findings tracker)",
        "(the findings tracker)",
        "(the findings from the parser)",
        "Findings",
        "Findings model with a severity and a title",
        // What the file does, before the finding word, is its role.
        "Loads open findings from the store",
        "Posts reported findings as inline threads",
        "Resolves tracked findings",
        "Returns no findings for an empty diff",
        "Re-checks confirmed findings against the code",
        "Processes findings in this batch of files",
        // A finding word that names a part of the file.
        "Contains the findings table for the dashboard",
        "Holds the findings store",
        // Any noun after the finding word, not only a listed one.
        "Contains the findings handler for the dashboard",
        "Wires the findings exporter",
        // A name as written after the finding word continues the phrase.
        "Hosts the findings FindingStore",
        "Holds the findings HTTP2 client",
        "Contains FindingStore",
        "Contains the findings Open panel",
        // A holding verb that heads another phrase.
        "Holds the code that parses findings",
        "There are helpers that format findings",
        // A form of "be" without "there" before it is no existential.
        "Here are findings",
        "Are two findings",
        // "and" between words that are not both numbers still breaks the phrase.
        "Holds two tests and three findings",
        "Hosts the tests and one finding",
        "And the findings",
        "Holds tests and findings",
        "Holds two and the findings",
        "Holds two or the findings",
        // A partitive whose findings name a part, or that states nothing about them.
        "One of the findings handlers",
        "None of the findings",
        "Most of the findings render here",
        "None of the code formats findings",
        "The new findings here",
        "Keeps settings and refines findings",
        "Holds the findingsTable",
        // Plural artifact nouns.
        "Contains the findings tables for the dashboard",
        "Holds the findings queries and classes",
        "Defines the findings classes"
      })
  void aClauseAboutWhatTheFileDoesIsNotFindingsTalk(String clause) {
    assertFalse(OverviewScreen.talksAboutFindings(clause));
  }

  @Test
  void theFindingsCutKeepsTheRoleAndLeavesNoPlaceholder() {
    assertEquals(
        "(findings tracker) page for the dashboard",
        OverviewScreen.screenedText("(findings tracker) page for the dashboard", "+x", ""));
    assertEquals(
        "Returns no findings for an empty diff",
        OverviewScreen.screenedText("Returns no findings for an empty diff", "+x", ""));
    assertEquals("", OverviewScreen.screenedText("TBD, hosts the two findings", "+x", ""));
    // A placeholder part is cut like a findings part; the role around it stays.
    assertEquals(
        "renders the dashboard",
        OverviewScreen.screenedText("renders the dashboard, TBD", "+x", ""));
    assertEquals(
        "renders the dashboard",
        OverviewScreen.screenedText("TBD, renders the dashboard", "+x", ""));
    // A first part of findings talk takes its clause with it, and the summary with that.
    assertEquals(
        "", OverviewScreen.screenedText("hosts the two findings, renders the dashboard", "+x", ""));
    assertEquals(
        "renders the table",
        OverviewScreen.screenedText(
            "renders the table; hosts the two findings, renders the dashboard", "+x", ""));
    // A relative tail goes with the findings part it qualifies.
    assertEquals(
        "",
        OverviewScreen.screenedText(
            "hosts the two findings, which are security-related", "+x", ""));
    assertEquals(
        "renders the dashboard",
        OverviewScreen.screenedText(
            "renders the dashboard; hosts the two findings, which are security-related", "+x", ""));
    assertEquals(
        "renders the dashboard, which the parser feeds",
        OverviewScreen.screenedText(
            "renders the dashboard, which the parser feeds, and hosts two findings", "+x", ""));
    // A comma inside a number does not split the clause.
    assertEquals(
        "Pages through 1,000 findings per request",
        OverviewScreen.screenedText("Pages through 1,000 findings per request", "+x", ""));
    assertEquals(
        "renders it",
        OverviewScreen.screenedText("renders it; TBD, hosts the two findings", "+x", ""));
    assertEquals(
        "Renders it",
        OverviewScreen.screenedText("Renders it, and hosts the two findings", "+x", ""));
    // Any coordinating conjunction separates the findings part, with or without a comma.
    assertEquals(
        "renders it",
        OverviewScreen.screenedText("renders it, but hosts the two findings", "+x", ""));
    assertEquals(
        "renders it", OverviewScreen.screenedText("renders it yet hosts two findings", "+x", ""));
    assertEquals(
        "renders it", OverviewScreen.screenedText("renders it or holds no findings", "+x", ""));
    assertEquals(
        "renders it, so pages stay fast",
        OverviewScreen.screenedText(
            "renders it, so pages stay fast, for it hosts two findings", "+x", ""));
    assertEquals(
        "Loads findings for the dashboard",
        OverviewScreen.screenedText("Loads findings for the dashboard", "+x", ""));
    // Joined by "and" alone, the findings part is cut the same way, and kept parts keep their
    // separators.
    assertEquals(
        "Renders the dashboard",
        OverviewScreen.screenedText(
            "Renders the dashboard and hosts the two confirmed findings", "+x", ""));
    assertEquals(
        "Parses rows, caches pages and renders the table",
        OverviewScreen.screenedText(
            "Parses rows, hosts two findings, caches pages and renders the table", "+x", ""));
    assertEquals(
        "Contains the findings table for the dashboard",
        OverviewScreen.screenedText("Contains the findings table for the dashboard", "+x", ""));
    // A comma part of findings talk inside a role clause is cut and the role kept.
    assertEquals(
        "renders the dashboard",
        OverviewScreen.screenedText("renders the dashboard, hosts the two findings", "+x", ""));
    assertEquals(
        "Loads, parses and caches findings",
        OverviewScreen.screenedText("Loads, parses and caches findings", "+x", ""));
    assertEquals(
        "Loads open findings from the store",
        OverviewScreen.screenedText("Loads open findings from the store", "+x", ""));
    assertEquals(
        "Renders the parser",
        OverviewScreen.screenedText("Renders the parser; findings are reported inline", "+x", ""));
  }

  @Test
  void theWarningNamesTheScreenWhenEveryRowWasDroppedByIt() {
    var exactButPlaceholder = summary("p", List.of(row("react/src/main.tsx", "placeholder")));
    var unmapped = summary("p", List.of(row("elsewhere/Unknown.kt", "something")));

    var dropped = warningsWhileScreening(exactButPlaceholder);
    var stray = warningsWhileScreening(unmapped);
    var none = warningsWhileScreening(summary("p", List.of()));

    assertEquals(1, dropped.size(), dropped.toString());
    assertTrue(dropped.getFirst().contains("Dropped all 1 file summary"), dropped.toString());
    assertTrue(
        dropped.getFirst().endsWith("unless the previous overview of this head is carried"),
        dropped.toString());
    assertEquals(1, stray.size(), stray.toString());
    assertTrue(
        stray.getFirst().contains("none is left on a changed file (0 failed"), stray.toString());
    assertEquals(List.of(), none);
  }

  /** The warnings {@link OverviewScreen#screen} logs for {@code summary}, formatted. */
  private static List<String> warningsWhileScreening(ReviewResponse.Summary summary) {
    var warnings = new ArrayList<String>();
    var logger = Logger.getLogger(OverviewScreen.class.getName());
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            if (entry.getLevel().intValue() >= Level.WARNING.intValue()) {
              var parameters = entry.getParameters();
              warnings.add(
                  parameters == null
                      ? entry.getMessage()
                      : String.format(entry.getMessage(), parameters));
            }
          }

          @Override
          public void flush() {
            // Records land straight in the list; nothing is buffered.
          }

          @Override
          public void close() {
            // The list outlives the handler; nothing to release.
          }
        };
    logger.addHandler(handler);
    try {
      OverviewScreen.screen(summary, FILES, "");
    } finally {
      logger.removeHandler(handler);
    }
    return warnings;
  }
}
