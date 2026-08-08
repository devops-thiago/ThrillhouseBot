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
package dev.thiagogonzaga.thrillhousebot.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link RepoSettingsResolver} — the per-repo structured-settings source added for
 * issue #51. Mirrors {@link InstructionsResolverTest}: fallback chain, TTL cache, and the fail-soft
 * contract that a missing or malformed config can never break a review.
 */
class RepoSettingsResolverTest {

  @Mock private GitHubAuthClient authClient;
  @Mock private GitHubPullRequestClient prClient;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  private final AtomicLong currentTimeMs = new AtomicLong(1_000_000L);

  private static final String OWNER = "test-owner";
  private static final String REPO = "test-repo";
  private static final String DEFAULT_BRANCH = "main";
  private static final long INSTALLATION_ID = 42L;
  private static final String AUTH_HEADER = "Bearer test-jwt";
  private static final String ACCEPT = "application/vnd.github+json";
  private static final String YML = ".github/thrillhousebot.yml";
  private static final String YAML = ".github/thrillhousebot.yaml";

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.repoConfigEnabled()).thenReturn(true);
    lenient().when(authClient.getAuthHeader(INSTALLATION_ID)).thenReturn(AUTH_HEADER);
  }

  private RepoSettingsResolver resolver() {
    return new RepoSettingsResolver(config, authClient, prClient, currentTimeMs::get);
  }

  private static GitHubPullRequestClient.FileContent content(String text) {
    var encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    return new GitHubPullRequestClient.FileContent(
        "thrillhousebot.yml", ".github/thrillhousebot.yml", encoded, "base64", text.length());
  }

  private void stubFile(String path, String text) {
    when(prClient.getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, path, DEFAULT_BRANCH))
        .thenReturn(content(text));
  }

  private void stubMissing(String path) {
    when(prClient.getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, path, DEFAULT_BRANCH))
        .thenThrow(new NotFoundException(Response.status(404).build()));
  }

  private RepoSettings resolve() {
    return resolver().resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
  }

  @Nested
  class Parsing {

    @Test
    void readsIgnoredFilesFromTheYmlFile() {
      stubFile(
          YML,
          """
          review:
            ignored-files:
              - "docs/generated/**"
              - "**/*.snap"
          """);

      var settings = resolve();

      assertEquals(java.util.List.of("docs/generated/**", "**/*.snap"), settings.ignoredFiles());
      assertEquals(YML, settings.source());
    }

    @Test
    void fallsBackToTheYamlExtension() {
      stubMissing(YML);
      stubFile(
          YAML,
          """
          review:
            ignored-files:
              - "vendored/**"
          """);

      var settings = resolve();

      assertEquals(java.util.List.of("vendored/**"), settings.ignoredFiles());
      assertEquals(YAML, settings.source());
    }

    @Test
    void acceptsACommaSeparatedScalarLikeTheEnvVarForm() {
      stubFile(YML, "review:\n  ignored-files: \"docs/generated/**, **/*.snap\"\n");

      var settings = resolve();

      assertEquals(java.util.List.of("docs/generated/**", "**/*.snap"), settings.ignoredFiles());
    }

    @Test
    void ignoresAConfigWithoutTheReviewKey() {
      stubFile(YML, "something-else:\n  enabled: true\n");

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void capsHowManyPatternsARepositoryMayContribute() {
      var sb = new StringBuilder("review:\n  ignored-files:\n");
      for (var i = 0; i < RepoSettingsParser.MAX_PATTERNS + 25; i++) {
        sb.append("    - \"dir").append(i).append("/**\"\n");
      }
      stubFile(YML, sb.toString());

      var settings = resolve();

      assertEquals(RepoSettingsParser.MAX_PATTERNS, settings.ignoredFiles().size());
    }

    @Test
    void dropsBlankAndOverLongPatterns() {
      var overLong = "x".repeat(RepoSettingsParser.MAX_PATTERN_LENGTH + 1);
      stubFile(
          YML,
          "review:\n  ignored-files:\n    - \"  \"\n    - \""
              + overLong
              + "\"\n    - \"kept/**\"\n");

      var settings = resolve();

      assertEquals(java.util.List.of("kept/**"), settings.ignoredFiles());
    }

    @Test
    void ignoresAnExplicitlyEmptyIgnoredFilesKey() {
      // `ignored-files:` with no value parses to a NullNode, not a missing node.
      stubFile(YML, "review:\n  ignored-files:\n");
      stubMissing(YAML);

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void skipsNonScalarEntriesInsideTheList() {
      stubFile(
          YML,
          """
          review:
            ignored-files:
              - "kept/**"
              - nested: mapping
              - ["a", "b"]
          """);

      var settings = resolve();

      assertEquals(java.util.List.of("kept/**"), settings.ignoredFiles());
    }
  }

  @Nested
  class FailSoft {

    @Test
    void returnsEmptyWhenNoConfigFileExists() {
      stubMissing(YML);
      stubMissing(YAML);

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void malformedYamlDegradesToEmptyWithoutThrowing() {
      stubFile(YML, "review:\n  ignored-files: [unterminated\n   : : :\n");

      var settings = assertDoesNotThrow(RepoSettingsResolverTest.this::resolve);

      assertEquals(RepoSettings.EMPTY, settings);
    }

    @Test
    void nonMappingYamlDegradesToEmpty() {
      stubFile(YML, "- just\n- a\n- list\n");

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void blankConfigFileDegradesToEmpty() {
      stubFile(YML, "   \n\n");
      stubMissing(YAML);

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void commentOnlyConfigFileDegradesToEmpty() {
      // Parses to a missing root — no mapping, so nothing to read.
      stubFile(YML, "# nothing configured yet\n");
      stubMissing(YAML);

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void nullFileContentIsTreatedAsAbsentAndFallsThroughTheChain() {
      // The contents endpoint can answer without an inline payload (e.g. an over-size blob);
      // decoding null would NPE, so it must be handled as "nothing readable here".
      when(prClient.getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, YML, DEFAULT_BRANCH))
          .thenReturn(
              new GitHubPullRequestClient.FileContent("thrillhousebot.yml", YML, null, "none", 0));
      stubFile(YAML, "review:\n  ignored-files:\n    - \"from-yaml/**\"\n");

      var settings = assertDoesNotThrow(RepoSettingsResolverTest.this::resolve);

      assertEquals(java.util.List.of("from-yaml/**"), settings.ignoredFiles());
      assertEquals(YAML, settings.source());
    }

    @Test
    void wrongShapeForIgnoredFilesDegradesToEmpty() {
      stubFile(YML, "review:\n  ignored-files:\n    nested: mapping\n");

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void transportFailureDegradesToEmpty() {
      when(prClient.getFileContent(any(), any(), any(), any(), any(), any()))
          .thenThrow(new ProcessingException("connection reset"));

      assertEquals(RepoSettings.EMPTY, resolve());
    }

    @Test
    void undecodableContentDegradesToEmpty() {
      when(prClient.getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, YML, DEFAULT_BRANCH))
          .thenReturn(
              new GitHubPullRequestClient.FileContent(
                  "thrillhousebot.yml", YML, "!!! not base64 !!!", "base64", 3));
      stubMissing(YAML);

      assertEquals(RepoSettings.EMPTY, assertDoesNotThrow(RepoSettingsResolverTest.this::resolve));
    }

    @Test
    void doesNotFetchAnythingWhenTheFeatureIsDisabled() {
      when(reviewConfig.repoConfigEnabled()).thenReturn(false);

      assertEquals(RepoSettings.EMPTY, resolve());
      verifyNoInteractions(prClient);
      verifyNoInteractions(authClient);
    }
  }

  /**
   * Direct contract tests for the parsing seam {@link RepoSettingsParser}, which #33 will reuse for
   * its own settings: it must answer {@link RepoSettings#EMPTY} for any input rather than throw.
   */
  @Nested
  class ParserContract {

    @Test
    void nullInputYieldsEmptyRatherThanThrowing() {
      assertEquals(
          RepoSettings.EMPTY, assertDoesNotThrow(() -> RepoSettingsParser.parse(null, YML)));
    }

    @Test
    void aReadableFileWithNoUsableSettingsIsAttributedToNoSource() {
      // EMPTY carries source "none": only a config that actually yielded settings names its file.
      assertEquals("none", RepoSettingsParser.parse("review: {}\n", YML).source());
    }
  }

  /** The {@code @Inject} constructor CDI actually uses, which the other tests bypass. */
  @Nested
  class InjectionConstructor {

    @Test
    void wiresConfigAndClientsAndResolvesWithTheSystemClock() {
      stubFile(YML, "review:\n  ignored-files:\n    - \"gen/**\"\n");

      var settings =
          new RepoSettingsResolver(config, authClient, prClient)
              .resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

      assertEquals(java.util.List.of("gen/**"), settings.ignoredFiles());
      assertEquals(YML, settings.source());
    }

    @Test
    void readsTheEnabledFlagFromConfig() {
      when(reviewConfig.repoConfigEnabled()).thenReturn(false);

      var settings =
          new RepoSettingsResolver(config, authClient, prClient)
              .resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

      assertEquals(RepoSettings.EMPTY, settings);
      verifyNoInteractions(prClient);
    }
  }

  @Nested
  class Caching {

    @Test
    void cachesPerRepositoryAndRefetchesAfterTheTtl() {
      stubFile(YML, "review:\n  ignored-files:\n    - \"gen/**\"\n");
      var resolver = resolver();

      assertEquals(
          java.util.List.of("gen/**"),
          resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).ignoredFiles());
      assertEquals(
          java.util.List.of("gen/**"),
          resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).ignoredFiles());

      verify(prClient, times(1))
          .getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, YML, DEFAULT_BRANCH);

      currentTimeMs.addAndGet(RepoSettingsResolver.CACHE_TTL_MS + 1);
      resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

      verify(prClient, times(2))
          .getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, YML, DEFAULT_BRANCH);
    }

    @Test
    void cachesAreKeyedPerRepository() {
      stubFile(YML, "review:\n  ignored-files:\n    - \"gen/**\"\n");
      when(prClient.getFileContent(AUTH_HEADER, ACCEPT, OWNER, "other", YML, DEFAULT_BRANCH))
          .thenReturn(content("review:\n  ignored-files:\n    - \"other/**\"\n"));
      var resolver = resolver();

      assertEquals(
          java.util.List.of("gen/**"),
          resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).ignoredFiles());
      assertEquals(
          java.util.List.of("other/**"),
          resolver.resolve(OWNER, "other", DEFAULT_BRANCH, INSTALLATION_ID).ignoredFiles());
    }

    @Test
    void negativeResultIsCachedBrieflyThenRetried() {
      stubMissing(YML);
      stubMissing(YAML);
      var resolver = resolver();

      resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
      resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

      verify(prClient, times(1))
          .getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, YML, DEFAULT_BRANCH);

      currentTimeMs.addAndGet(RepoSettingsResolver.NEGATIVE_CACHE_TTL_MS + 1);
      resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

      verify(prClient, times(2))
          .getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, YML, DEFAULT_BRANCH);
    }

    @Test
    void sweepDropsExpiredEntriesOnceTheCacheIsLarge() {
      stubMissing(YML);
      stubMissing(YAML);
      var resolver = resolver();
      for (var i = 0; i < RepoSettingsResolver.CACHE_SWEEP_THRESHOLD; i++) {
        resolver.resolve(OWNER, "repo-" + i, DEFAULT_BRANCH, INSTALLATION_ID);
      }
      assertEquals(RepoSettingsResolver.CACHE_SWEEP_THRESHOLD, resolver.cache.size());

      currentTimeMs.addAndGet(RepoSettingsResolver.CACHE_TTL_MS + 1);
      resolver.sweepExpiredEntries();

      assertEquals(0, resolver.cache.size());
    }
  }
}
