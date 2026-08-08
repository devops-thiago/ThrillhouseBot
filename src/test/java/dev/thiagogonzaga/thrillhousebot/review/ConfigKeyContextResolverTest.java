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
            List.of(),
            "",
            "",
            configKeyContext,
            files,
            () -> new DiffLineResolver(Map.of()),
            null);
    var req =
        new ReviewOrchestrator.ReviewRequest(
            "o", "r", 1, "headsha", "title", "body", "basesha", "main", 1L, false, "main", false);
    return assembler.assemble(ctx, req);
  }
}
