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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cutting overview sentences that introduce an artifact the change does not hold (#1090). */
class InventedArtifactsTest {

  private static FileDiff added(String path, String... lines) {
    var patch = new StringBuilder("@@ -0,0 +1," + lines.length + " @@\n");
    for (var line : lines) {
      patch.append('+').append(line).append('\n');
    }
    return new FileDiff(path, "added", lines.length, 0, lines.length, patch.toString());
  }

  /** ThrillhouseBot-test#250's changed files, with lines like the ones they add. */
  private static final List<FileDiff> ZIG_250 =
      List.of(
          added(".github/workflows/zig-ci.yml", "name: zig-ci"),
          added("docs/CONFIG-ZIG.md", "# Configuration", "All settings are read from the env."),
          added("zig/build.zig", "const std = @import(\"std\");"),
          added("zig/src/main.zig", "if (std.mem.eql(u8, path, \"/scan\")) return scan(req);"),
          added("zig/src/manifest.zig", "pub fn write(rack: []const u8) !void {}"),
          new FileDiff("zig/assets/logo.png", "added", 1, 0, 1, null),
          new FileDiff("zig/src/empty.zig", "added", 0, 0, 0, null));

  /** The #250 overview's second sentence (summary comment 6126003362). */
  private static final String INVENTED =
      "It also adds a LIMS pendingOrders fetcher (lims.zig), environment-based config loading, a"
          + " build script, unit tests, a Dockerfile, the zig-ci workflow, and two docs files"
          + " (CONFIG-ZIG.md and a settings page), with the original README purely renamed to"
          + " docs/ZIG-V17.md and withheld from review.";

  private static final String FIRST =
      "This pull request adds labq, a new Zig HTTP service: a /scan endpoint ingests trays and"
          + " /export writes a per-rack CSV manifest.";

  private static ReviewResponse.Summary summary(String purpose, String assessment) {
    return new ReviewResponse.Summary(0, 0, 0, 0, 0, assessment, purpose, List.of());
  }

  @Test
  void theSettingsPageOf250IsRemoved() {
    var screened = InventedArtifacts.screen(summary(FIRST + " " + INVENTED, "ok"), ZIG_250);

    assertEquals(FIRST, screened.prPurpose());
    assertEquals("ok", screened.overallAssessment());
    // The assessment is screened the same way.
    assertEquals(
        "Fine.",
        InventedArtifacts.screen(summary(FIRST, "Fine. It adds a settings page."), ZIG_250)
            .overallAssessment());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // A changed file described by its role.
        "It adds the config doc and the zig-ci workflow.",
        // A page a changed path names, through a word of its family.
        "It adds a configuration page for the service.",
        // An endpoint an added line declares.
        "It serves a new /scan endpoint.",
        // No qualifier, or only a vague one: nothing to judge.
        "It writes results to a page.",
        "It adds a separate docs page.",
        "It serves a new HTTPS endpoint."
      })
  void aSentenceTheChangeBacksIsKept(String sentence) {
    var original = summary(sentence, sentence);

    assertSame(original, InventedArtifacts.screen(original, ZIG_250));
  }

  @Test
  void eachKindIsBackedByWhatDeclaresIt() {
    var change =
        InventedArtifacts.Change.of(
            List.of(
                added("web/src/SettingsScreen.tsx", "export const SettingsScreen = () => null;"),
                added("web/src/App.tsx", "<Route path=\"/billing\" element={<BillingPage />} />"),
                added("db/migrate/001_orders.rb", "create_table :orders"),
                added(
                    "api/routes.go", "mux.HandleFunc(\"/health\", health)", "invoices := load()")));

    assertFalse(InventedArtifacts.invents("It adds a settings screen.", change));
    assertFalse(InventedArtifacts.invents("It adds a billing page.", change));
    assertFalse(InventedArtifacts.invents("It adds an orders migration.", change));
    assertFalse(InventedArtifacts.invents("It adds a health endpoint.", change));

    assertTrue(InventedArtifacts.invents("It adds a profile page.", change));
    // A kind joined to its qualifier by a hyphen or an underscore, and a hyphenated qualifier.
    assertTrue(InventedArtifacts.invents("It adds a profile-page.", change));
    assertTrue(InventedArtifacts.invents("It adds a profile_page.", change));
    assertTrue(InventedArtifacts.invents("It adds a self-service page.", change));
    assertFalse(InventedArtifacts.invents("It adds a settings-screen.", change));
    // A camel-hump name in the sentence reads as its words.
    assertTrue(InventedArtifacts.invents("It adds a ProfilePage for admins.", change));
    assertFalse(InventedArtifacts.invents("It adds a SettingsScreen for admins.", change));
    // An acronym's last capital starts the next word: "a SAMLPage" is a SAML page.
    assertTrue(InventedArtifacts.invents("It adds a SAMLPage for admins.", change));
  }

  @Test
  void aDeletedFileBacksNoAdditionAndARemovalIsNotJudged() {
    var change =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    assertTrue(InventedArtifacts.invents("It adds a settings page.", change));
    assertTrue(InventedArtifacts.invents("It adds a **settings page** for admins.", change));
    assertTrue(InventedArtifacts.invents("It adds **a** *new* settings page.", change));
    assertFalse(InventedArtifacts.invents("It removes a settings page.", change));
    // A removal elsewhere in the sentence does not cover an added artifact.
    assertTrue(
        InventedArtifacts.invents("It adds a settings page and removes the old one.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It adds a settings page for data dropped from the flow.", change));
    assertFalse(
        InventedArtifacts.invents("It drops the old flow and deletes a settings page.", change));
    assertFalse(InventedArtifacts.invents("It has removed a settings page.", change));
    assertFalse(InventedArtifacts.invents("It deletes the old flow and a settings page.", change));
    assertTrue(InventedArtifacts.invents("It removes cruft, a settings page is added.", change));
    // A hyphenated word is not a removal verb.
    assertTrue(InventedArtifacts.invents("It adds a drop-down, a settings page too.", change));
    // A removal that does not govern the artifact leaves it judged.
    assertTrue(
        InventedArtifacts.invents("It drops the old flow then adds a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It drops the legacy flow — a settings page is added.", change));
    assertTrue(InventedArtifacts.invents("It adds an audit screen.", change));
    assertTrue(InventedArtifacts.invents("It adds a metrics endpoint.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It adds a users migration.",
            InventedArtifacts.Change.of(List.of(added("api/routes.go", "users := 1")))));
    // "invoices" is in an added line, but not beside "page".
    assertTrue(InventedArtifacts.invents("It adds an invoices page.", change));
  }

  @Test
  void aNegatedArtifactIsNoInventionButANegationOfSomethingElseLeavesItJudged() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertFalse(InventedArtifacts.invents("It does not add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It doesn't ship a settings page.", change));
    assertFalse(InventedArtifacts.invents("It does not yet add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It ships without a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It does not add an audit screen or a settings page.", change));
    // A negation of something else leaves the added artifact judged.
    assertTrue(
        InventedArtifacts.invents("It does not touch billing but adds a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It does not touch billing and adds a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It doesn't add tests, instead it adds a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It never adds tests though it adds a settings page.", change));
  }

  @Test
  void norAndNeitherNegateTheArtifactTheyGovern() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertFalse(
        InventedArtifacts.invents(
            "It does not add tests, nor does it add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It adds neither a screen nor a settings page.", change));
    assertFalse(InventedArtifacts.invents("It adds neither an audit screen nor tests.", change));
  }

  @Test
  void anAdditionJoinedByAConjunctionAfterANegatedObjectIsJudged() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    // The negation governs "config" or the verb before "and"; "adds" starts a new statement.
    assertTrue(
        InventedArtifacts.invents("It ships without config and adds a settings page.", change));
    assertTrue(InventedArtifacts.invents("It does not bill and adds a settings page.", change));
    assertTrue(InventedArtifacts.invents("It never fails, then adds a settings page.", change));
    // "Or" carries the negation to the next verb.
    assertFalse(InventedArtifacts.invents("It does not bill or add a settings page.", change));
    // More than two words between the negation and the addition verb, with no conjunction.
    assertTrue(
        InventedArtifacts.invents(
            "It does not touch billing code yet adds a settings page.", change));
    // A negation over two coordinated verbs still covers the artifact.
    assertFalse(InventedArtifacts.invents("It does not add or ship a settings page.", change));
  }

  @Test
  void underscoreEmphasisIsReadAsItsWordsButAJoinedNameStaysOneArtifact() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertTrue(InventedArtifacts.invents("It adds a _settings page_ for admins.", change));
    assertTrue(InventedArtifacts.invents("It adds a __settings page__ for admins.", change));
    assertTrue(InventedArtifacts.invents("It adds _a_ settings page.", change));
    assertTrue(InventedArtifacts.invents("It adds a settings_page.", change));
    assertFalse(
        InventedArtifacts.invents(
            "It adds a _settings page_.",
            InventedArtifacts.Change.of(List.of(added("web/src/SettingsPage.tsx", "x")))));
  }

  @Test
  void aNounOfRemovalDoesNotGovernTheArtifact() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertTrue(
        InventedArtifacts.invents(
            "It completes the removal of the legacy flow with a settings page.", change));
    assertTrue(InventedArtifacts.invents("It handles a dropout with a settings page.", change));
    assertTrue(InventedArtifacts.invents("It speeds up deletion with a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It plans the retirement of the flow via a settings page.", change));
    assertTrue(InventedArtifacts.invents("It adds a removable settings page.", change));
    assertTrue(InventedArtifacts.invents("It adds a dropdown with a settings page.", change));
    // The verbs still govern it.
    assertFalse(InventedArtifacts.invents("It removes a settings page.", change));
    assertFalse(InventedArtifacts.invents("It retires a settings page.", change));
    assertFalse(InventedArtifacts.invents("It is deleting a settings page.", change));
    assertFalse(InventedArtifacts.invents("It dropped a settings page.", change));
  }

  @Test
  void aPeriodOrCommaInsideANumberOrAnAbbreviationEndsNoClause() {
    var change =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    assertFalse(
        InventedArtifacts.invents("It deletes the old v1.2 flow and a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It removes legacy endpoints, e.g. a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It removes legacy endpoints, i.e. a settings page.", change));
    assertFalse(InventedArtifacts.invents("It removes logs etc. and a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It removes 1,000 stale rows and a settings page.", change));
    assertFalse(InventedArtifacts.invents("It drops the 10:30 job and a settings page.", change));
    assertFalse(InventedArtifacts.invents("It does not bump v2.0 or add a settings page.", change));
    assertFalse(
        InventedArtifacts.invents(
            "It removes the old vs. new toggle and a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It removes legacy pages, including a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It removes legacy pages: such as a settings page.", change));
    // A real sentence or clause end still bounds the removal.
    assertTrue(InventedArtifacts.invents("It removes v1. It adds a settings page.", change));
    assertTrue(InventedArtifacts.invents("It removes cruft, 2 is a settings page.", change));
    assertTrue(InventedArtifacts.invents("It drops rows: a settings page is added.", change));
  }

  @Test
  void cannotIsANegation() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertFalse(InventedArtifacts.invents("It cannot add a settings page yet.", change));
    assertFalse(InventedArtifacts.invents("It Cannot ship a settings page.", change));
  }

  @Test
  void aNounOfAdditionDoesNotTakeTheArtifactFromAnEarlierRemoval() {
    var change =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    assertFalse(
        InventedArtifacts.invents(
            "It removes the old implementation and a settings page.", change));
    assertFalse(InventedArtifacts.invents("It deletes an additional settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It drops the address book and a settings page.", change));
    assertFalse(InventedArtifacts.invents("It retires the wireframe and a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It removes the creation flow and a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It removes the introduction and a settings page.", change));
    // Nouns and adjectives that share a stem with an addition verb, whatever the stem.
    for (var noun :
        List.of(
            "build",
            "builds",
            "builder",
            "building",
            "shipment",
            "ship",
            "creator",
            "adder",
            "wires",
            "implementer",
            "introducer")) {
      assertFalse(
          InventedArtifacts.invents(
              "It removes the " + noun + " script and a settings page.", change),
          noun);
    }
    assertFalse(
        InventedArtifacts.invents("It removes the nightly builds and a settings page.", change));
    // A bare form after "and" is read as a noun: "and build files".
    assertFalse(
        InventedArtifacts.invents(
            "It removes the cache and build files and a settings page.", change));
    // The verbs still take it, in any inflection, wherever a verb stands.
    assertTrue(
        InventedArtifacts.invents("It removes the old flow and adds a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It removes the old flow and is building a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It drops the old flow and ships a settings page.", change));
    assertTrue(InventedArtifacts.invents("It drops the old flow, built a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It drops the old flow and then wires a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It drops the old flow and added tests with a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It drops the old flow to build a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It removes the flow and introduced a new settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It drops the old flow and creates a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It drops the flow and implements a settings page.", change));
  }

  @Test
  void aParentheticalAfterTheNegationKeepsItInTheClause() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertFalse(InventedArtifacts.invents("It does not, however, add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It does not, in fact, add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It will not, presumably, add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It does not (yet) add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It does not, in v2, add a settings page.", change));
    assertFalse(InventedArtifacts.invents("It does not — as noted — add a settings page.", change));
    // A removal reaches past one too.
    assertFalse(
        InventedArtifacts.invents(
            "It removes, as planned, a settings page.",
            InventedArtifacts.Change.of(
                List.of(
                    new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                    added("web/src/App.tsx", "const app = 1;")))));
    // A clause with its own verb after a single comma is still a new clause.
    assertTrue(
        InventedArtifacts.invents("It does not touch billing, it adds a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It does not touch billing, and as a result it adds a settings page.", change));
  }

  @Test
  void aListAfterAColonCommaOrDashStaysInTheDenialsClause() {
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertFalse(
        InventedArtifacts.invents(
            "The change does not add: a settings page, a metrics endpoint, or a users migration.",
            change));
    assertFalse(
        InventedArtifacts.invents("It does not add, ship or modify a settings page.", change));
    assertFalse(
        InventedArtifacts.invents(
            "It does not add a screen, a dashboard, or a settings page.", change));
    assertFalse(InventedArtifacts.invents("It does not add — a settings page.", change));
    assertFalse(
        InventedArtifacts.invents(
            "It deletes the old flow, the docs, and a settings page.",
            InventedArtifacts.Change.of(
                List.of(
                    new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                    added("web/src/App.tsx", "const app = 1;")))));
    // A segment with its own negation, removal, verb, subject or auxiliary is a new clause.
    assertFalse(InventedArtifacts.invents("It adds tests, not a settings page.", change));
    assertFalse(
        InventedArtifacts.invents("It does not touch billing, removes a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It removes the old flow, the docs, and adds a settings page.", change));
    // A list item longer than a parenthetical, before a later clause that adds.
    assertTrue(
        InventedArtifacts.invents(
            "It does not touch billing, the old docs and the tests, and adds a settings page.",
            change));
    assertTrue(InventedArtifacts.invents("It adds tests, docs, and a settings page.", change));
    assertTrue(
        InventedArtifacts.invents(
            "It does not touch billing, docs, or tests, and adds a settings page.", change));
    assertTrue(
        InventedArtifacts.invents("It does not touch billing, this is a settings page.", change));
    assertTrue(InventedArtifacts.invents("It does not touch billing; a settings page.", change));
  }

  @Test
  void aPassiveAdditionAfterTheArtifactIsJudgedAndANegatedOneIsNot() {
    var removed =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));

    assertTrue(
        InventedArtifacts.invents(
            "It removes the old page and a settings page is added.", removed));
    assertTrue(
        InventedArtifacts.invents(
            "It deletes the old flow and a settings page for admins gets added.", removed));
    assertTrue(
        InventedArtifacts.invents(
            "It does not touch billing and a settings page is now introduced.", change));
    assertTrue(
        InventedArtifacts.invents("It drops the old flow — a settings page was built.", removed));
    // A negated passive denies the artifact, whatever came before.
    assertFalse(
        InventedArtifacts.invents("It adds tests and a settings page is not added.", change));
    assertFalse(
        InventedArtifacts.invents("It adds tests, but a settings page isn't added.", change));
    assertFalse(
        InventedArtifacts.invents("It adds tests and a settings page was never built.", change));
    // With no break after it, the clause runs to the end; a "be" with no participle is no passive.
    assertTrue(
        InventedArtifacts.invents("It removes the old page and a settings page is added", removed));
    assertFalse(
        InventedArtifacts.invents(
            "It removes the old page and a settings page that is still around.", removed));
    // A passive in a later clause is not the artifact's.
    assertFalse(
        InventedArtifacts.invents("It removes a settings page; the cache is added.", removed));
  }

  @Test
  void everyCommonRemovalVerbGovernsTheArtifactInAnyInflection() {
    var removed =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    for (var verb :
        List.of(
            "removes",
            "deletes",
            "drops",
            "retires",
            "deprecates",
            "strips",
            "eliminates",
            "replaces",
            "renames",
            "moves",
            "purges",
            "discards",
            "prunes")) {
      assertFalse(
          InventedArtifacts.invents("It " + verb + " the old flow and a settings page.", removed),
          verb);
    }
    assertFalse(InventedArtifacts.invents("It has deprecated a settings page.", removed));
    assertFalse(InventedArtifacts.invents("It is stripping a settings page.", removed));
    assertFalse(InventedArtifacts.invents("It eliminated a settings page.", removed));
    assertFalse(InventedArtifacts.invents("It will purge a settings page.", removed));
    // A removal reaches over a list of what it removes.
    assertFalse(
        InventedArtifacts.invents(
            "It removes the old flow, the old docs and the tests, and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents(
            "It does not touch billing, the old docs and the tests, and removes a settings page.",
            removed));
    // A summary line opening with the verb.
    assertFalse(InventedArtifacts.invents("Deprecates the old flow and a settings page.", removed));
    assertFalse(InventedArtifacts.invents("Drops support and a settings page.", removed));
    // A noun sharing a removal stem governs nothing.
    assertTrue(InventedArtifacts.invents("It plans the move of a settings page.", removed));
    assertTrue(InventedArtifacts.invents("It adds a replacement for a settings page.", removed));
    assertTrue(InventedArtifacts.invents("It speeds up the purge with a settings page.", removed));
    // What a replace, rename or move leads to is the new artifact, and is judged.
    assertTrue(
        InventedArtifacts.invents("It replaces the old page with a settings page.", removed));
    assertTrue(InventedArtifacts.invents("It renames the old screen to a settings page.", removed));
    assertTrue(InventedArtifacts.invents("It moves the docs into a settings page.", removed));
    assertTrue(
        InventedArtifacts.invents(
            "It deprecates the old page, replaced by a settings page.", removed));
  }

  @Test
  void aRemovalOpeningAClauseGovernsABareNounObject() {
    var removed =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    // After a comma, a dash or a list item, the verb opens its clause whatever its object.
    assertFalse(
        InventedArtifacts.invents(
            "It refactors the router, removing old settings and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents(
            "It refactors the router — drops admin screens and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents(
            "It refactors the router, the long docs page, deleting stale links and a settings page.",
            removed));
    // After a semicolon.
    assertFalse(
        InventedArtifacts.invents(
            "It refactors the router; removes old settings and a settings page.", removed));
    // A bare form after "and" is a verb when an earlier modal or "to" governs both.
    assertFalse(
        InventedArtifacts.invents(
            "It will refactor the router and remove old settings and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents(
            "It is meant to refactor the router and drop old settings and a settings page.",
            removed));
    // Without one, it stays a noun.
    assertTrue(
        InventedArtifacts.invents(
            "It removes nothing; it adds the cache and drop shadows and a settings page.",
            removed));
    // A bare removal form opening a segment continues a list of negated verbs.
    var change = InventedArtifacts.Change.of(List.of(added("web/src/App.tsx", "const app = 1;")));
    assertFalse(
        InventedArtifacts.invents("It does not ship, remove or modify a settings page.", change));
    // A bare form inside the segment, after "to", is a verb and opens the clause.
    assertTrue(
        InventedArtifacts.invents("It removes the old router, to build a settings page.", removed));
    // An addition opening a clause after a comma is judged the same way.
    assertTrue(
        InventedArtifacts.invents(
            "It removes the old router, adding new settings and a settings page.", removed));
  }

  @Test
  void aVerbAfterANounSubjectGovernsABareNounObject() {
    var removed =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    assertFalse(
        InventedArtifacts.invents(
            "It adds a dashboard; the script removes cruft and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents("The migration drops settings and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents("Cleanup deletes stale links and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents(
            "It adds a dashboard; the new migration deprecated admin screens and a settings page.",
            removed));
    // An addition after a noun subject takes the artifact from an earlier removal.
    assertTrue(
        InventedArtifacts.invents(
            "It removes the old page and the router adds settings and a settings page.", removed));
    // A plural noun after a preposition, or before a function word, is no verb.
    assertFalse(
        InventedArtifacts.invents(
            "It removes the guide for builds pages and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents("It removes the nightly builds and a settings page.", removed));
    assertFalse(
        InventedArtifacts.invents(
            "It removes the nightly builds for admins and a settings page.", removed));
  }

  @Test
  void aModalOrToGovernsOnlyTheVerbPairItOpens() {
    var removed =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    // A "to" before a noun phrase is a preposition, and governs no verb.
    assertTrue(
        InventedArtifacts.invents(
            "It refers to the docs and adds the cache and drop shadows and a settings page.",
            removed));
    // A finite verb after the modal ends its reach.
    assertTrue(
        InventedArtifacts.invents(
            "It will refactor the router, and it adds the cache and drop shadows and a settings page.",
            removed));
    assertTrue(
        InventedArtifacts.invents(
            "It is meant to refactor the router, adds the cache and drop shadows and a settings page.",
            removed));
    assertTrue(
        InventedArtifacts.invents(
            "It will refactor the router and adds the cache and drop shadows and a settings page.",
            removed));
    // The pair a modal opens still reads as two verbs.
    assertFalse(
        InventedArtifacts.invents(
            "It will refactor the router and remove old settings and a settings page.", removed));
  }

  @Test
  void nothingToJudgeLeavesTheSummaryAsItIs() {
    var fenced = "Adds the queue.\n```\na settings page\n```";
    var original = summary(fenced, null);

    assertNull(InventedArtifacts.screen(null, ZIG_250));
    assertSame(original, InventedArtifacts.screen(original, List.of()));
    assertSame(original, InventedArtifacts.screen(original, ZIG_250));
    assertEquals("", InventedArtifacts.withoutInvented("", InventedArtifacts.Change.of(ZIG_250)));
  }

  @Test
  void aCutKeepsTheParagraphBreaks() {
    var text = "Adds the queue. It adds a settings page.\n\nIt also adds a build script.";

    assertEquals(
        "Adds the queue.\n\nIt also adds a build script.",
        InventedArtifacts.withoutInvented(text, InventedArtifacts.Change.of(ZIG_250)));
  }

  @Test
  void aCutKeepsACrlfParagraphBreak() {
    var text = "Adds the queue. It adds a settings page.\r\n\r\nIt also adds a build script.";

    assertEquals(
        "Adds the queue.\r\n\r\nIt also adds a build script.",
        InventedArtifacts.withoutInvented(text, InventedArtifacts.Change.of(ZIG_250)));
  }

  @Test
  void onlyAMarkerOpeningALineIsAFence() {
    var change = InventedArtifacts.Change.of(ZIG_250);

    // A marker inside a sentence opens no block: the prose after it is still screened.
    assertEquals(
        "The notes quote ``` inline. Done.",
        InventedArtifacts.withoutInvented(
            "The notes quote ``` inline. It adds a settings page. Done.", change));
    assertEquals(
        "Use ~~~ for blocks. Done.",
        InventedArtifacts.withoutInvented(
            "Use ~~~ for blocks.\nIt adds a settings page. Done.", change));
    // A block closes only at a line opening with its marker.
    var text =
        """
        Adds the queue.
          ```
        It adds a settings page. Keep ``` here.
          ```
        It adds a settings page. Done.""";
    assertEquals(
        """
        Adds the queue.
          ```
        It adds a settings page. Keep ``` here.
          ```
        Done.""",
        InventedArtifacts.withoutInvented(text, change));
    // The other marker inside a block does not close it.
    assertEquals(
        "Done.\n~~~\n```\nIt adds a settings page.\n~~~\nKept.",
        InventedArtifacts.withoutInvented(
            "It adds a settings page. Done.\n~~~\n```\nIt adds a settings page.\n~~~\nKept.",
            change));
    // A longer fence is closed only by a run at least as long, with nothing after it.
    var longer =
        """
        It adds a settings page. Done.
        ````
        ```
        It adds a settings page.
        ```java
        ````
        Kept.""";
    assertEquals(
        """
        Done.
        ````
        ```
        It adds a settings page.
        ```java
        ````
        Kept.""",
        InventedArtifacts.withoutInvented(longer, change));
    assertEquals(
        "```\nIt adds a settings page.\n```java\nStill inside.\n```  \nKept.",
        InventedArtifacts.withoutInvented(
            "```\nIt adds a settings page.\n```java\nStill inside.\n```  \nKept.", change));
    // The blank between paragraphs before a fence stays.
    assertEquals(
        "Done.\r\n```\na settings page\n```",
        InventedArtifacts.withoutInvented(
            "It adds a settings page. Done.\r\n```\na settings page\n```", change));
  }

  @Test
  void aFenceKeepsItsBlockButNotTheProseAroundIt() {
    var change = InventedArtifacts.Change.of(ZIG_250);
    var text =
        """
        Adds the queue. It adds a settings page.
        ~~~
        It adds a settings page.
        ~~~
        It adds a settings page. Done.
        ```
        unclosed""";

    assertEquals(
        """
        Adds the queue.
        ~~~
        It adds a settings page.
        ~~~
        Done.
        ```
        unclosed""",
        InventedArtifacts.withoutInvented(text, change));
  }

  @Test
  void aQualifierInACodeSpanIsReadAndUnknownAddedLinesBackWhatOnlyTheyCouldShow() {
    var change = InventedArtifacts.Change.of(ZIG_250);
    assertTrue(InventedArtifacts.invents("It adds a `profile` page.", change));

    // An oversized file's patch is not sent: its added lines might declare the endpoint.
    var oversized =
        InventedArtifacts.Change.of(
            List.of(new FileDiff("api/routes.go", "modified", 4000, 0, 4000, null)));
    assertFalse(InventedArtifacts.invents("It adds a metrics endpoint.", oversized));
    assertTrue(InventedArtifacts.invents("It adds a users migration.", oversized));
  }

  @Test
  void wordsSplitCamelHumpsAndFamiliesNeedFourLetters() {
    assertEquals(List.of("settings", "page", "tsx"), InventedArtifacts.words("SettingsPage.tsx"));
    assertEquals(List.of("saml", "page"), InventedArtifacts.words("SAMLPage"));
    assertEquals(List.of("html", "settings", "page"), InventedArtifacts.words("HTMLSettingsPage"));
    assertTrue(InventedArtifacts.family("config", "configuration"));
    assertTrue(InventedArtifacts.family("configuration", "config"));
    assertFalse(InventedArtifacts.family("api", "apiary"));
  }
}
