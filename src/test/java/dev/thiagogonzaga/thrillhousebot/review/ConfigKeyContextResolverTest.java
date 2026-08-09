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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.TreeEntry;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.TreeResponse;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigKeyContextResolver} — extracting config-key tokens from the doc/.env
 * files a PR changes and resolving each to its definition site so the reviewer can judge whether
 * the documentation matches the implementation (#108).
 */
class ConfigKeyContextResolverTest {

  private static final String CONFIG_PATH =
      "src/main/java/dev/thiagogonzaga/thrillhousebot/config/ThrillhouseConfig.java";
  private static final String PROPERTIES_PATH = "src/main/resources/application.properties";

  /** The SmallRye-derived form: the env name exists only through the @WithName mapping. */
  private static final String CONFIG_SOURCE =
      """
      public interface ThrillhouseConfig {
        interface ReviewConfig {
          /**
           * GitHub logins permitted to manually trigger reviews.
           */
          @WithName("manual-trigger-allowed-logins")
          Optional<List<String>> manualTriggerAllowedLogins();

          @WithDefault("strict")
          @WithName("ci-gating")
          String ciGating();
        }
      }
      """;

  /** The explicit-override form: the env name is written out as a ${ENV:default} placeholder. */
  private static final String PROPERTIES_SOURCE =
      """
      quarkus.http.port=8080
      thrillhousebot.webhook.dedup-ttl=${WEBHOOK_DEDUP_TTL:24h}
      thrillhousebot.review.ci-gating=${REVIEW_CI_GATING:strict}
      """;

  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final ConfigKeyContextResolver resolver = new ConfigKeyContextResolver(prClient);

  private void givenRepository(String... paths) {
    var entries = new ArrayList<TreeEntry>();
    for (String path : paths) {
      entries.add(new TreeEntry(path, "blob", 4_000));
    }
    when(prClient.getTree(any(), any(), eq("o"), eq("r"), eq("headsha"), eq("1")))
        .thenReturn(new TreeResponse("treesha", entries, false));
  }

  private void givenFile(String path, String content) {
    when(prClient.getFileContent(any(), any(), eq("o"), eq("r"), eq(path), eq("headsha")))
        .thenReturn(
            new GitHubPullRequestClient.FileContent(
                path,
                path,
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
                "base64",
                content.length()));
  }

  private static FileDiff docDiff(String filename, String addedLine) {
    return new FileDiff(
        filename,
        "modified",
        1,
        0,
        1,
        "@@ -10,2 +10,3 @@\n context line\n+" + addedLine + "\n more context");
  }

  private String resolve(List<FileDiff> files) {
    return resolver.resolve("auth", "o", "r", "headsha", files);
  }

  @Nested
  class ExtractTokens {

    @Test
    void shouldExtractEnvAndPropertyTokensFromMarkdownAndDotenvFiles() {
      var tokens =
          ConfigKeyContextResolver.extractTokens(
              List.of(
                  docDiff("README.md", "| `THRILLHOUSEBOT_REVIEW_CI_GATING` | how strict | yes |"),
                  docDiff(".env.example", "#WEBHOOK_DEDUP_TTL=24h"),
                  docDiff("docs/config.md", "set `thrillhousebot.review.ci-gating` to `warn`")));

      assertEquals(
          List.of(
              "THRILLHOUSEBOT_REVIEW_CI_GATING",
              "WEBHOOK_DEDUP_TTL",
              "thrillhousebot.review.ci-gating"),
          tokens);
    }

    @Test
    void shouldIgnoreNonDocumentationFilesAndContextLines() {
      var javaFile =
          new FileDiff(
              "src/main/java/Service.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+ENV_VAR_HERE");
      var contextOnly =
          new FileDiff("README.md", "modified", 0, 0, 0, "@@ -1 +1 @@\n UNCHANGED_DOC_KEY");

      assertEquals(List.of(), ConfigKeyContextResolver.extractTokens(List.of(javaFile)));
      assertEquals(List.of(), ConfigKeyContextResolver.extractTokens(List.of(contextOnly)));
      assertEquals(List.of(), ConfigKeyContextResolver.extractTokens(List.of()));
      assertEquals(List.of(), ConfigKeyContextResolver.extractTokens(null));
    }

    @Test
    void shouldMatchRealKeysWithoutUnboundedBacktrackingOnAdversarialInput() {
      // A crafted doc line: thousands of segments, well past any real key. The bounded quantifiers
      // must return promptly and must not report the whole run as one token.
      var adversarial = "A" + "_A".repeat(20_000) + " and `THRILLHOUSEBOT_REVIEW_CI_GATING`";

      var start = System.nanoTime();
      var tokens =
          ConfigKeyContextResolver.extractTokens(List.of(docDiff("README.md", adversarial)));
      var elapsedMs = (System.nanoTime() - start) / 1_000_000;

      assertTrue(
          tokens.contains("THRILLHOUSEBOT_REVIEW_CI_GATING"),
          () -> "the real key after the crafted run must still be found: " + tokens);
      assertTrue(
          tokens.stream().noneMatch(t -> t.length() > 2_000),
          () -> "a token longer than any real config key was accepted: " + tokens);
      assertTrue(elapsedMs < 5_000, () -> "token extraction took " + elapsedMs + "ms");
    }

    @Test
    void shouldRejectHostnamesAndFqnsAndPreserveFirstMentionOrder() {
      // A doc line mixing a URL host, a Java FQN, a real property key and a real env name. The
      // hostname (trailing TLD, inside a URL) and the FQN (leading reverse-domain TLD) must be
      // dropped, and the survivors kept in the order the line mentions them — property first.
      var tokens =
          ConfigKeyContextResolver.extractTokens(
              List.of(
                  docDiff(
                      "README.md",
                      "reach https://api.github.com/repos then set"
                          + " `thrillhousebot.review.ci-gating`; the"
                          + " org.eclipse.microprofile.rest.client package reads"
                          + " `THRILLHOUSEBOT_REVIEW_CI_GATING`")));

      assertFalse(
          tokens.contains("api.github.com"),
          () -> "a hostname inside a URL is not a config key: " + tokens);
      assertFalse(
          tokens.contains("org.eclipse.microprofile.rest.client"),
          () -> "a Java fully-qualified name is not a config key: " + tokens);
      assertEquals(
          List.of("thrillhousebot.review.ci-gating", "THRILLHOUSEBOT_REVIEW_CI_GATING"),
          tokens,
          () ->
              "the property is mentioned before the env name; order must follow position: "
                  + tokens);
    }

    @Test
    void shouldNotMistakeFilenamesForPropertyKeys() {
      var tokens =
          ConfigKeyContextResolver.extractTokens(
              List.of(
                  docDiff("README.md", "see application.properties and README.md for details")));

      assertEquals(List.of(), tokens);
    }

    @Test
    void shouldRecognizeDocumentationAndDotenvPathsOnly() {
      assertTrue(ConfigKeyContextResolver.isDocumentationFile("README.md"));
      assertTrue(ConfigKeyContextResolver.isDocumentationFile("docs/nested/CONFIG.MD"));
      assertTrue(ConfigKeyContextResolver.isDocumentationFile(".env"));
      assertTrue(ConfigKeyContextResolver.isDocumentationFile("deploy/.env.example"));
      assertFalse(ConfigKeyContextResolver.isDocumentationFile("src/main/java/App.java"));
      assertFalse(ConfigKeyContextResolver.isDocumentationFile("environment.ts"));
      assertFalse(ConfigKeyContextResolver.isDocumentationFile(null));
      assertFalse(ConfigKeyContextResolver.isDocumentationFile("   "));
    }

    @Test
    void shouldIgnoreThePatchFileHeaderLine() {
      var withHeader =
          new FileDiff(
              "README.md",
              "modified",
              1,
              0,
              1,
              "--- a/README.md\n+++ b/DOC_HEADER_KEY.md\n@@ -1 +1 @@\n+`REAL_DOC_KEY` matters");

      assertEquals(
          List.of("REAL_DOC_KEY"), ConfigKeyContextResolver.extractTokens(List.of(withHeader)));
    }

    @Test
    void shouldStopScanningAfterTheDocFileCap() {
      var files = new ArrayList<FileDiff>();
      for (int i = 0; i < ConfigKeyContextResolver.MAX_DOC_FILES + 5; i++) {
        files.add(docDiff("docs/page" + i + ".md", "`DOC_KEY_NUMBER_" + i + "` exists"));
      }

      var tokens = ConfigKeyContextResolver.extractTokens(files);

      assertEquals(ConfigKeyContextResolver.MAX_DOC_FILES, tokens.size());
      assertTrue(tokens.contains("DOC_KEY_NUMBER_0"), tokens::toString);
      assertFalse(
          tokens.contains("DOC_KEY_NUMBER_" + ConfigKeyContextResolver.MAX_DOC_FILES),
          () -> "files past the cap must not be scanned: " + tokens);
    }

    @Test
    void shouldStopCollectingAfterTheTokenCap() {
      var doc = new StringBuilder();
      for (int i = 0; i < ConfigKeyContextResolver.MAX_TOKENS + 10; i++) {
        doc.append("`MANY_DOC_KEY_")
            .append(i)
            .append("` and `many.doc.key-")
            .append(i)
            .append("` ");
      }

      var tokens =
          ConfigKeyContextResolver.extractTokens(List.of(docDiff("README.md", doc.toString())));

      assertEquals(ConfigKeyContextResolver.MAX_TOKENS, tokens.size());
    }

    @Test
    void shouldStopScanningFurtherFilesOnceTheTokenCapIsReached() {
      var doc = new StringBuilder();
      for (int i = 0; i < ConfigKeyContextResolver.MAX_TOKENS; i++) {
        doc.append("`FIRST_FILE_KEY_").append(i).append("` ");
      }
      var files =
          List.of(
              docDiff("README.md", doc.toString()),
              docDiff("docs/second.md", "`SECOND_FILE_KEY_ONE` also exists"));

      var tokens = ConfigKeyContextResolver.extractTokens(files);

      assertEquals(ConfigKeyContextResolver.MAX_TOKENS, tokens.size());
      assertFalse(
          tokens.contains("SECOND_FILE_KEY_ONE"),
          () ->
              "the token cap must stop the file scan, not just the per-file collection: " + tokens);
    }
  }

  /** Path classification driving which repository files are worth fetching. */
  @Nested
  class PathClassification {

    @Test
    void shouldRecognizeApplicationConfigResources() {
      assertTrue(
          ConfigKeyContextResolver.isConfigResource("src/main/resources/application.properties"));
      assertTrue(ConfigKeyContextResolver.isConfigResource("application.yaml"));
      assertTrue(ConfigKeyContextResolver.isConfigResource("application-prod.yml"));
      assertFalse(ConfigKeyContextResolver.isConfigResource("application.txt"));
      assertFalse(ConfigKeyContextResolver.isConfigResource("sonar-project.properties"));
    }

    @Test
    void shouldRecognizeConfigSourceFilesByStemAndExtension() {
      assertTrue(ConfigKeyContextResolver.isConfigSource("a/ThrillhouseConfig.java"));
      assertTrue(ConfigKeyContextResolver.isConfigSource("a/AppConfiguration.kt"));
      assertTrue(ConfigKeyContextResolver.isConfigSource("a/settings.py"));
      assertTrue(ConfigKeyContextResolver.isConfigSource("a/env.ts"));
      assertFalse(ConfigKeyContextResolver.isConfigSource("a/Service.java"), "wrong stem");
      assertFalse(ConfigKeyContextResolver.isConfigSource("a/Config.md"), "wrong extension");
      assertFalse(ConfigKeyContextResolver.isConfigSource("Makefile"), "no extension at all");
      assertFalse(ConfigKeyContextResolver.isConfigSource(".config"), "leading dot is not a stem");
    }

    @Test
    void shouldRecognizeTestPathsInEveryLayout() {
      assertTrue(ConfigKeyContextResolver.isTestPath("src/test/java/AppConfigTest.java"));
      assertTrue(ConfigKeyContextResolver.isTestPath("module/tests/conftest.py"));
      assertTrue(ConfigKeyContextResolver.isTestPath("test/settings.py"));
      assertTrue(ConfigKeyContextResolver.isTestPath("tests/settings.py"));
      assertTrue(ConfigKeyContextResolver.isTestPath("src/config.test.ts"));
      assertTrue(ConfigKeyContextResolver.isTestPath("src/config.spec.ts"));
      assertFalse(ConfigKeyContextResolver.isTestPath("src/main/resources/application.properties"));
      assertFalse(ConfigKeyContextResolver.isTestPath("src/latest/config.java"));
    }
  }

  /** Line matching and snippet rendering — the parts that decide what the model actually reads. */
  @Nested
  class Matching {

    private static final String TOKEN = ConfigKeyContextResolver.normalize("WEBHOOK_DEDUP_TTL");

    @Test
    void shouldNormalizeSeparatorsAndCaseToUpperSnake() {
      assertEquals(
          "THRILLHOUSEBOT_REVIEW_CI_GATING",
          ConfigKeyContextResolver.normalize("thrillhousebot.review.ci-gating"));
      assertEquals("A_B__C_", ConfigKeyContextResolver.normalize("a b??c/"));
      assertEquals("", ConfigKeyContextResolver.normalize(""));
    }

    @Test
    void shouldRequireWholeSegmentBoundariesAndKeepSearchingPastAPartialHit() {
      // The first occurrence is glued to a preceding letter, so the search must continue.
      assertTrue(
          ConfigKeyContextResolver.lineDefines("XWEBHOOK_DEDUP_TTL WEBHOOK_DEDUP_TTL", TOKEN));
      assertFalse(
          ConfigKeyContextResolver.lineDefines("XWEBHOOK_DEDUP_TTLY only", TOKEN),
          "a hit inside a longer word is not a definition");
    }

    @Test
    void shouldIgnoreBlankAndAbsentLines() {
      assertFalse(ConfigKeyContextResolver.lineDefines(null, TOKEN));
      assertFalse(ConfigKeyContextResolver.lineDefines("   ", TOKEN));
    }

    @Test
    void shouldNotSuffixMatchATokenTooShortToHaveAPrefix() {
      // FOO_BAR is two segments: dropping a prefix segment would leave one, which matches far too
      // much, so only a whole-token hit counts.
      assertFalse(
          ConfigKeyContextResolver.lineDefines(
              "  @WithName(\"bar\")", ConfigKeyContextResolver.normalize("FOO_BAR")));
      assertTrue(
          ConfigKeyContextResolver.lineDefines(
              "x=${FOO_BAR:1}", ConfigKeyContextResolver.normalize("FOO_BAR")));
    }

    @Test
    void shouldMergeAdjacentMatchesIntoOneSnippetAndCapTheRest() {
      var lines =
          new String[] {
            "unrelated",
            "a.b.dedup-ttl=${WEBHOOK_DEDUP_TTL:1h}",
            "a.b.other=${WEBHOOK_DEDUP_TTL:2h}",
            "filler",
            "filler",
            "filler",
            "c.d=${WEBHOOK_DEDUP_TTL:3h}",
            "filler",
            "filler",
            "filler",
            "e.f=${WEBHOOK_DEDUP_TTL:4h}"
          };

      var snippets = ConfigKeyContextResolver.snippetsFor("app.properties", lines, TOKEN);

      assertEquals(
          ConfigKeyContextResolver.MAX_SNIPPETS_PER_KEY,
          snippets.size(),
          () -> "adjacent matches share a snippet and the total is capped: " + snippets);
      assertTrue(
          snippets.get(0).contains("1h") && snippets.get(0).contains("2h"), snippets::toString);
      assertTrue(snippets.get(1).contains("3h"), snippets::toString);
    }

    @Test
    void shouldDropBlankBoundaryLinesAndTruncateAnOversizedSnippet() {
      var huge =
          "x=${WEBHOOK_DEDUP_TTL:"
              + "y".repeat(ConfigKeyContextResolver.MAX_SNIPPET_CHARS * 2)
              + "}";
      var lines = new String[] {"   ", huge, "   "};

      var snippets = ConfigKeyContextResolver.snippetsFor("app.properties", lines, TOKEN);

      assertEquals(1, snippets.size());
      var snippet = snippets.get(0);
      assertTrue(snippet.endsWith("… (truncated)"), snippet);
      assertTrue(
          snippet.length() <= ConfigKeyContextResolver.MAX_SNIPPET_CHARS + 16,
          () -> "snippet not truncated: " + snippet.length());
      assertFalse(snippet.contains("|    \n"), () -> "blank boundary lines kept: " + snippet);
    }

    @Test
    void shouldKeepABlankLineInsideTheWindowAndDropOnlyTheBoundaries() {
      var lines = new String[] {"   ", "x=${WEBHOOK_DEDUP_TTL:1h}", "   ", "tail"};

      var snippet = ConfigKeyContextResolver.snippetsFor("app.properties", lines, TOKEN).get(0);

      assertFalse(snippet.contains("    1 |"), () -> "leading blank line kept: " + snippet);
      assertTrue(snippet.contains("    2 |"), snippet);
      assertTrue(
          snippet.contains("    3 |"),
          () -> "a blank line inside the window is structure, not padding: " + snippet);
      assertTrue(snippet.contains("    4 | tail"), snippet);
    }

    @Test
    void shouldNotSplitASurrogatePairWhenTruncating() {
      var emoji = "ab😀cd";

      // Limit 3 would land between the emoji's high and low surrogate; the cut backs off to 2.
      assertEquals("ab", ConfigKeyContextResolver.truncate(emoji, 3));
      assertEquals("ab😀", ConfigKeyContextResolver.truncate(emoji, 4));
      assertTrue(
          ConfigKeyContextResolver.truncate(emoji, 3)
              .chars()
              .noneMatch(c -> Character.isSurrogate((char) c)),
          "truncation left an unpaired surrogate");
    }
  }

  @Nested
  class Resolution {

    @Test
    void shouldResolveDerivedEnvVarToItsWithNameMapping() {
      givenRepository(PROPERTIES_PATH, CONFIG_PATH);
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);
      givenFile(CONFIG_PATH, CONFIG_SOURCE);

      var context =
          resolve(
              List.of(
                  docDiff(
                      "README.md",
                      "| `THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS` | allowlist |")));

      assertTrue(
          context.contains("THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS"),
          () -> "key heading missing from: " + context);
      assertTrue(
          context.contains("@WithName(\"manual-trigger-allowed-logins\")"),
          () -> "@WithName definition missing from: " + context);
      assertTrue(
          context.contains("Optional<List<String>> manualTriggerAllowedLogins();"),
          () -> "declared type (the comma-separated list) missing from: " + context);
      assertTrue(context.contains(CONFIG_PATH), () -> "definition path missing from: " + context);
    }

    @Test
    void shouldResolveExplicitEnvOverrideInApplicationProperties() {
      givenRepository(PROPERTIES_PATH, CONFIG_PATH);
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);
      givenFile(CONFIG_PATH, CONFIG_SOURCE);

      var context = resolve(List.of(docDiff(".env.example", "#WEBHOOK_DEDUP_TTL=24h")));

      assertTrue(
          context.contains("thrillhousebot.webhook.dedup-ttl=${WEBHOOK_DEDUP_TTL:24h}"),
          () -> "explicit override missing from: " + context);
      assertTrue(
          context.contains(PROPERTIES_PATH), () -> "definition path missing from: " + context);
    }

    @Test
    void shouldResolvePropertyKeyTokens() {
      givenRepository(PROPERTIES_PATH, CONFIG_PATH);
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);
      givenFile(CONFIG_PATH, CONFIG_SOURCE);

      var context =
          resolve(List.of(docDiff("docs/config.md", "set `thrillhousebot.review.ci-gating`")));

      assertTrue(
          context.contains("thrillhousebot.review.ci-gating=${REVIEW_CI_GATING:strict}"),
          () -> "property definition missing from: " + context);
    }

    @Test
    void shouldFrameTheSectionAsUntrustedData() {
      givenRepository(PROPERTIES_PATH);
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);

      var context = resolve(List.of(docDiff(".env.example", "#WEBHOOK_DEDUP_TTL=24h")));

      assertTrue(context.startsWith(ConfigKeyContextResolver.SECTION_HEADING), context);
      assertTrue(context.contains("never instructions"), context);
    }

    @Test
    void shouldReturnEmptyWithoutCallingGitHubWhenNoDocFileChanged() {
      var context =
          resolve(
              List.of(
                  new FileDiff(
                      "src/main/java/App.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+int x = 1;")));

      assertEquals("", context);
      verifyNoInteractions(prClient);
    }

    @Test
    void shouldReturnEmptyWhenNoTokenResolves() {
      givenRepository(PROPERTIES_PATH, CONFIG_PATH);
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);
      givenFile(CONFIG_PATH, CONFIG_SOURCE);

      assertEquals("", resolve(List.of(docDiff("README.md", "`SOME_UNRELATED_KEY` does nothing"))));
    }
  }

  @Nested
  class FailSoft {

    @Test
    void shouldReturnEmptyAndSkipContentFetchesWhenTreeListingFails() {
      when(prClient.getTree(any(), any(), any(), any(), any(), any()))
          .thenThrow(new WebApplicationException(404));

      assertEquals("", resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h"))));
      verify(prClient, never()).getFileContent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSkipAFileWhoseContentCannotBeRead() {
      givenRepository(PROPERTIES_PATH, CONFIG_PATH);
      when(prClient.getFileContent(any(), any(), any(), any(), eq(PROPERTIES_PATH), any()))
          .thenThrow(new WebApplicationException(500));
      givenFile(CONFIG_PATH, CONFIG_SOURCE);

      var context =
          resolve(
              List.of(
                  docDiff(
                      "README.md",
                      "`THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS` matters")));

      assertTrue(
          context.contains("@WithName(\"manual-trigger-allowed-logins\")"),
          () -> "a failed fetch must not lose the other definition: " + context);
    }

    @Test
    void shouldReturnEmptyWhenTheRepositoryHasNoConfigFiles() {
      givenRepository("src/main/java/App.java", "docs/guide.md");

      assertEquals("", resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h"))));
      verify(prClient, never()).getFileContent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnEmptyWhenTheTreeListingComesBackNull() {
      when(prClient.getTree(any(), any(), any(), any(), any(), any())).thenReturn(null);

      assertEquals("", resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h"))));
      verify(prClient, never()).getFileContent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldStillResolveFromATruncatedTreeListing() {
      when(prClient.getTree(any(), any(), eq("o"), eq("r"), eq("headsha"), eq("1")))
          .thenReturn(
              new TreeResponse(
                  "treesha", List.of(new TreeEntry(PROPERTIES_PATH, "blob", 4_000)), true));
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);

      var context = resolve(List.of(docDiff(".env.example", "#WEBHOOK_DEDUP_TTL=24h")));

      assertTrue(
          context.contains("thrillhousebot.webhook.dedup-ttl=${WEBHOOK_DEDUP_TTL:24h}"),
          () -> "a truncated listing must still use what it did return: " + context);
    }

    @Test
    void shouldSkipTreeEntriesWithoutAPath() {
      when(prClient.getTree(any(), any(), eq("o"), eq("r"), eq("headsha"), eq("1")))
          .thenReturn(
              new TreeResponse(
                  "treesha",
                  List.of(
                      new TreeEntry(null, "blob", 10), new TreeEntry(PROPERTIES_PATH, "blob", 10)),
                  false));
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);

      var context = resolve(List.of(docDiff(".env.example", "#WEBHOOK_DEDUP_TTL=24h")));

      assertTrue(context.contains("${WEBHOOK_DEDUP_TTL:24h}"), context);
    }

    @Test
    void shouldSkipFilesWithNoBodyBlankFilesAndAbsentResponses() {
      givenRepository(
          "blank/application.properties",
          "nobody/application.properties",
          "missing/application.properties");
      when(prClient.getFileContent(
              any(), any(), any(), any(), eq("nobody/application.properties"), any()))
          .thenReturn(
              new GitHubPullRequestClient.FileContent("a", "a", null, "base64", 0)); // directory
      when(prClient.getFileContent(
              any(), any(), any(), any(), eq("missing/application.properties"), any()))
          .thenReturn(null);
      givenFile("blank/application.properties", "   \n  \n");

      assertEquals("", resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h"))));
      verify(prClient, times(3)).getFileContent(any(), any(), any(), any(), any(), eq("headsha"));
    }

    @Test
    void shouldSkipADocFileThatCarriesNoPatch() {
      var noPatch = new FileDiff("README.md", "renamed", 0, 0, 0, null, "OLD.md");

      assertEquals(List.of(), ConfigKeyContextResolver.extractTokens(List.of(noPatch)));
    }
  }

  @Nested
  class Bounds {

    @Test
    void shouldRankConfigResourcesFirstAndSkipTestsAndOversizedFiles() {
      when(prClient.getTree(any(), any(), eq("o"), eq("r"), eq("headsha"), eq("1")))
          .thenReturn(
              new TreeResponse(
                  "treesha",
                  List.of(
                      new TreeEntry("modules/api/src/main/resources/application.yaml", "blob", 10),
                      new TreeEntry(CONFIG_PATH, "blob", 10),
                      new TreeEntry("application.properties", "blob", 10),
                      new TreeEntry("src/test/java/ThrillhouseConfigTest.java", "blob", 10),
                      new TreeEntry("huge/BigConfig.java", "blob", 999_999_999L),
                      new TreeEntry("src/main/resources", "tree", 0),
                      new TreeEntry("README.md", "blob", 10)),
                  false));

      var candidates = resolver.candidatePaths("auth", "o", "r", "headsha");

      assertEquals(
          List.of(
              "application.properties",
              "modules/api/src/main/resources/application.yaml",
              CONFIG_PATH),
          candidates);
    }

    @Test
    void shouldNotFetchMoreFilesThanTheBudgetAllows() {
      var paths = new ArrayList<String>();
      for (int i = 0; i < ConfigKeyContextResolver.MAX_FILES_FETCHED + 4; i++) {
        paths.add("module" + i + "/application.properties");
      }
      givenRepository(paths.toArray(new String[0]));
      for (String path : paths) {
        givenFile(path, "unrelated.property.key=1\n");
      }

      assertEquals("", resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h"))));
      verify(prClient, times(ConfigKeyContextResolver.MAX_FILES_FETCHED))
          .getFileContent(any(), any(), any(), any(), any(), eq("headsha"));
    }

    @Test
    void shouldChargeTheFetchBudgetPerAttemptNotPerSuccess() {
      // Every candidate is blank, so fetchContent returns null for each. Under a blanket failure
      // (rate limit, expired token) or a repo of blank candidates the budget must still be spent
      // per
      // ATTEMPT, or the loop issues one serial API call per candidate against a budget of 8.
      var paths = new ArrayList<String>();
      for (int i = 0; i < ConfigKeyContextResolver.MAX_FILES_FETCHED + 4; i++) {
        paths.add("module" + i + "/application.properties");
      }
      givenRepository(paths.toArray(new String[0]));
      for (String path : paths) {
        givenFile(path, "   \n  \n");
      }

      assertEquals("", resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h"))));
      verify(prClient, times(ConfigKeyContextResolver.MAX_FILES_FETCHED))
          .getFileContent(any(), any(), any(), any(), any(), eq("headsha"));
    }

    @Test
    void shouldCapRenderedKeysAndTotalCharacters() {
      var properties = new StringBuilder();
      var documented = new StringBuilder();
      for (int i = 0; i < ConfigKeyContextResolver.MAX_KEYS_RENDERED + 3; i++) {
        properties
            .append("thrillhousebot.review.key-number-")
            .append(i)
            .append("=${DOCUMENTED_KEY_NUMBER_")
            .append(i)
            .append(":a value long enough to make each rendered snippet substantial}\n");
        documented.append("`DOCUMENTED_KEY_NUMBER_").append(i).append("` is a knob. ");
      }
      givenRepository(PROPERTIES_PATH);
      givenFile(PROPERTIES_PATH, properties.toString());

      var context = resolve(List.of(docDiff("README.md", documented.toString())));

      assertEquals(
          ConfigKeyContextResolver.MAX_KEYS_RENDERED,
          context.lines().filter(line -> line.startsWith("#### ")).count(),
          () -> "rendered key count is not capped: " + context);
      assertTrue(
          context.length() <= ConfigKeyContextResolver.MAX_TOTAL_CHARS + 64,
          () -> "rendered section is not size-capped: " + context.length() + " chars");
    }

    @Test
    void shouldStopFetchingOnceEnoughKeysHaveResolved() {
      var documented = new StringBuilder();
      var properties = new StringBuilder();
      for (int i = 0; i < ConfigKeyContextResolver.MAX_KEYS_RENDERED; i++) {
        properties
            .append("a.b.key-")
            .append(i)
            .append("=${EARLY_KEY_NUMBER_")
            .append(i)
            .append(":v}\n");
        documented.append("`EARLY_KEY_NUMBER_").append(i).append("` ");
      }
      // The first file resolves every key; the remaining candidates must never be fetched.
      givenRepository("application.properties", "later/application.properties", CONFIG_PATH);
      givenFile("application.properties", properties.toString());
      givenFile("later/application.properties", properties.toString());
      givenFile(CONFIG_PATH, CONFIG_SOURCE);

      var context = resolve(List.of(docDiff("README.md", documented.toString())));

      assertEquals(
          ConfigKeyContextResolver.MAX_KEYS_RENDERED,
          context.lines().filter(line -> line.startsWith("#### ")).count(),
          context);
      verify(prClient, times(1)).getFileContent(any(), any(), any(), any(), any(), eq("headsha"));
    }

    @Test
    void shouldNotRenderMoreSnippetsPerKeyThanTheCapAcrossFiles() {
      var body =
          "a.b.dedup-ttl=${WEBHOOK_DEDUP_TTL:1h}\nfiller\nfiller\nfiller\nc.d=${WEBHOOK_DEDUP_TTL:2h}\n";
      givenRepository("application.properties", "second/application.properties");
      givenFile("application.properties", body);
      givenFile("second/application.properties", body);

      var context = resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h")));

      assertEquals(
          ConfigKeyContextResolver.MAX_SNIPPETS_PER_KEY,
          context.lines().filter(line -> line.contains("application.properties")).count(),
          () -> "snippets per key must be capped across files: " + context);
      assertFalse(
          context.contains("second/application.properties"),
          () -> "the cap must be reached before the second file contributes: " + context);
    }

    @Test
    void shouldStopMidFileOnceTheSnippetCapIsReached() {
      // The first file contributes one snippet; the second offers two, so the cap is hit partway
      // through that file rather than before it is read.
      givenRepository("application.properties", "second/application.properties");
      givenFile("application.properties", "only=${WEBHOOK_DEDUP_TTL:1h}\n");
      givenFile(
          "second/application.properties",
          "a=${WEBHOOK_DEDUP_TTL:2h}\nf\nf\nf\nb=${WEBHOOK_DEDUP_TTL:3h}\n");

      var context = resolve(List.of(docDiff(".env.example", "WEBHOOK_DEDUP_TTL=24h")));

      assertTrue(context.contains("1h"), context);
      assertTrue(context.contains("2h"), context);
      assertFalse(
          context.contains("3h"),
          () -> "the third definition is past the per-key cap and must be dropped: " + context);
    }
  }

  @Nested
  class AssembledPrompt {

    /**
     * The acceptance case of #108: a diff that documents an env var must put that var's definition
     * into the prompt the model is actually called with, not merely into the resolver's return
     * value.
     */
    @Test
    void shouldCarryTheDefinitionIntoTheAssembledPrompt() {
      givenRepository(PROPERTIES_PATH, CONFIG_PATH);
      givenFile(PROPERTIES_PATH, PROPERTIES_SOURCE);
      givenFile(CONFIG_PATH, CONFIG_SOURCE);
      var files =
          List.of(
              docDiff(
                  "README.md",
                  "| `THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS` | allowlist |"));

      var configKeyContext = resolve(files);
      var assembled = assemble(files, configKeyContext);

      assertTrue(
          assembled.repoInstructions().contains("@WithName(\"manual-trigger-allowed-logins\")"),
          () -> "assembled prompt lost the definition: " + assembled.repoInstructions());
      assertTrue(
          assembled.repoInstructions().contains("Optional<List<String>>"),
          () -> "assembled prompt lost the declared type: " + assembled.repoInstructions());
    }

    @Test
    void shouldOmitTheSectionWhenNothingResolved() {
      assertEquals("", ReviewPromptAssembler.configKeyContextSection(null));
      assertEquals("", ReviewPromptAssembler.configKeyContextSection(""));
      assertEquals("", ReviewPromptAssembler.configKeyContextSection("   "));
      assertFalse(assemble(List.of(), "").repoInstructions().contains("Config key definitions"));
    }
  }

  /** Runs the real prompt assembler over a context carrying the resolved config-key material. */
  private static dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService.PromptInputs assemble(
      List<FileDiff> files, String configKeyContext) {
    var config = mock(ThrillhouseConfig.class, RETURNS_DEEP_STUBS);
    when(config.review().diagram().enabled()).thenReturn(false);
    var labeler = mock(PrLabeler.class);
    when(labeler.allowNewLabels()).thenReturn(false);
    var assembler =
        new ReviewPromptAssembler(config, labeler, new ReviewDiffFormatter(List.of(), 5000));
    var ctx =
        new ReviewContextLoader.ReviewContext(
            files,
            "diff",
            "",
            0,
            List.of(),
            List.of(),
            List.of(),
            true,
            false,
            null,
            List.of(),
            "",
            new InstructionsResolver.ResolvedInstructions("", ""),
            PathScopedInstructions.NONE,
            List.of(),
            "",
            "",
            configKeyContext,
            "",
            files,
            () -> new DiffLineResolver(Map.of()),
            null);
    var req =
        new ReviewOrchestrator.ReviewRequest(
            "o", "r", 1, "headsha", "title", "body", "basesha", "main", 1L, false, "main", false);
    return assembler.assemble(ctx, req);
  }
}
