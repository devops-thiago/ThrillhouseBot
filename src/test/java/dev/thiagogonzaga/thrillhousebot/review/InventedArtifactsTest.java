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
          new FileDiff("zig/src/queue.zig", "added", 1, 0, 1, null));

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
        "It adds a separate docs page."
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
  }

  @Test
  void aDeletedFileBacksNoAdditionAndARemovalIsNotJudged() {
    var change =
        InventedArtifacts.Change.of(
            List.of(
                new FileDiff("web/src/SettingsPage.tsx", "removed", 0, 12, 12, "-x"),
                added("web/src/App.tsx", "const app = 1;")));

    assertTrue(InventedArtifacts.invents("It adds a settings page.", change));
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
  void nothingToJudgeLeavesTheSummaryAsItIs() {
    var fenced = "It adds a settings page:\n```\ncode\n```";
    var original = summary(fenced, null);

    assertNull(InventedArtifacts.screen(null, ZIG_250));
    assertSame(original, InventedArtifacts.screen(original, List.of()));
    assertSame(original, InventedArtifacts.screen(original, ZIG_250));
    var tilde = "It adds a settings page:\n~~~\ncode\n~~~";
    assertEquals(
        tilde, InventedArtifacts.withoutInvented(tilde, InventedArtifacts.Change.of(ZIG_250)));
    assertEquals("", InventedArtifacts.withoutInvented("", InventedArtifacts.Change.of(ZIG_250)));
  }

  @Test
  void wordsSplitCamelHumpsAndFamiliesNeedFourLetters() {
    assertEquals(List.of("settings", "page", "tsx"), InventedArtifacts.words("SettingsPage.tsx"));
    assertTrue(InventedArtifacts.family("config", "configuration"));
    assertTrue(InventedArtifacts.family("configuration", "config"));
    assertFalse(InventedArtifacts.family("api", "apiary"));
  }
}
