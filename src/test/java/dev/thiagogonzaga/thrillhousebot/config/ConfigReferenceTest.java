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
package dev.thiagogonzaga.thrillhousebot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/CONFIGURATION.md} and the README's most-used settings table equal to what
 * {@link ConfigReference} generates from the config mapping and {@code application.properties}. Run
 * with {@code -Dconfig-reference.write=true} to rewrite both files instead of failing.
 */
class ConfigReferenceTest {

  private static final boolean WRITE = Boolean.getBoolean("config-reference.write");

  @Test
  void committedReferenceMatchesTheConfigMapping() throws IOException {
    var expected = ConfigReference.renderReference(ConfigReference.load());
    if (WRITE) {
      Files.writeString(ConfigReference.REFERENCE, expected, StandardCharsets.UTF_8);
    }
    assertEquals(
        expected,
        Files.readString(ConfigReference.REFERENCE, StandardCharsets.UTF_8),
        ConfigReference.REFERENCE
            + " is out of date. Regenerate it with "
            + ConfigReference.REGENERATE);
  }

  @Test
  void readmeMostUsedSettingsMatchTheReference() throws IOException {
    var readme = Files.readString(ConfigReference.README, StandardCharsets.UTF_8);
    var expected =
        ConfigReference.spliceEssentials(
            readme, ConfigReference.renderEssentials(ConfigReference.load()));
    if (WRITE) {
      Files.writeString(ConfigReference.README, expected, StandardCharsets.UTF_8);
      readme = expected;
    }
    assertEquals(
        expected,
        readme,
        "The README's most-used settings table is out of date. Regenerate it with "
            + ConfigReference.REGENERATE);
  }

  // --- generator behaviour, on a small mapping parsed from the source text below ---

  interface Probe {
    int MAX = 20;

    /**
     * Turns the {@code probe} on. Bounded by {@value #MAX}; see <a
     * href="https://example.com/doc">the doc</a> and {@code **}{@code /build/**}.
     *
     * <p>Maintainer note that is not published.
     */
    @WithDefault("false")
    boolean enabled();

    /** Comma-separated globs, a | b. */
    @WithName("skipped-files")
    @WithDefault("a/**,b/**")
    List<String> skippedFiles();

    /** Base address. */
    Optional<String> url();

    Nested nested();

    /** Per-model settings, keyed by model name. */
    Map<String, Model> models();

    interface Nested {
      /** How long to wait. */
      @WithDefault("5s")
      java.time.Duration timeout();
    }

    interface Model {
      /** Most input tokens. */
      @WithName("max-input-tokens")
      Optional<Integer> maxInputTokens();
    }
  }

  private static final String PROBE_SOURCE =
      """
      package dev.thiagogonzaga.thrillhousebot.config;
      class ConfigReferenceTest {
        interface Probe {
          /**
           * Turns the {@code probe} on. Bounded by {@value #MAX}; see <a
           * href="https://example.com/doc">the doc</a> and {@code **}{@code /build/**}.
           *
           * <p>Maintainer note that is not published.
           */
          boolean enabled();

          /** Comma-separated globs, a | b. */
          List<String> skippedFiles();

          /** Base address. */
          Optional<String> url();

          /** Per-model settings, keyed by model name. */
          Map<String, Model> models();

          interface Nested {
            /** How long to wait. */
            Duration timeout();
          }

          interface Model {
            /** Most input tokens. */
            Optional<Integer> maxInputTokens();
          }
        }
      }
      """;

  private static ConfigReference.Reference probe(String... properties) {
    return ConfigReference.build(
        Probe.class,
        ConfigReference.javadoc(PROBE_SOURCE),
        ConfigReference.parseProperties(List.of(properties)));
  }

  private static ConfigReference.Row row(ConfigReference.Reference ref, String property) {
    return ref.sections().stream()
        .flatMap(s -> s.rows().stream())
        .filter(r -> r.property().equals(property))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void firstParagraphIsPublishedWithValuesLinksAndMergedCodeSpans() {
    var enabled = row(probe(), "thrillhousebot.enabled");

    assertEquals(
        "Turns the `probe` on. Bounded by 20; see [the doc](https://example.com/doc) and"
            + " `**/build/**`.",
        enabled.description());
    assertEquals("`THRILLHOUSEBOT_ENABLED`", enabled.variable());
    assertEquals("boolean", enabled.type());
    assertEquals("`false`", enabled.defaultValue());
  }

  @Test
  void aliasAndPropertiesValueReplaceTheMappingDefault() {
    var ref =
        probe(
            "thrillhousebot.enabled=${PROBE_ENABLED:true}",
            "thrillhousebot.nested.timeout=10s",
            "%prod.thrillhousebot.nested.timeout=30s");

    assertEquals("`PROBE_ENABLED`", row(ref, "thrillhousebot.enabled").variable());
    assertEquals("`true`", row(ref, "thrillhousebot.enabled").defaultValue());
    var timeout = row(ref, "thrillhousebot.nested.timeout");
    assertEquals("`THRILLHOUSEBOT_NESTED_TIMEOUT`", timeout.variable());
    assertEquals("`10s`; `%prod`: `30s`", timeout.defaultValue());
    assertEquals("duration", timeout.type());
  }

  @Test
  void listsPipesAndEmptyDefaultsRenderAsTableCells() {
    var ref = probe("thrillhousebot.url=${PROBE_URL:}");

    var files = row(ref, "thrillhousebot.skipped-files");
    assertEquals("Comma-separated globs, a \\| b.", files.description());
    assertEquals("`a/**`, `b/**`", files.defaultValue());
    assertEquals("list", files.type());
    assertEquals("_(none)_", row(ref, "thrillhousebot.url").defaultValue());
  }

  @Test
  void aKeyThatFollowsAnotherShowsTheOtherKeysDefault() {
    var ref =
        probe(
            "# doc: Where the probe points.",
            "quarkus.probe.url=${PROBE_BASE:https://x.test}",
            "thrillhousebot.url=${quarkus.probe.url}");

    var url = row(ref, "thrillhousebot.url");
    assertEquals("_(follows `quarkus.probe.url`)_", url.variable());
    assertEquals("`https://x.test`", url.defaultValue());
    var other = ref.byVariable("`PROBE_BASE`").orElseThrow();
    assertEquals("Where the probe points.", other.description());
    assertEquals("`quarkus.probe.url`", other.property());
  }

  @Test
  void mapEntriesUseAPlaceholderAndTheMapJavadocAsIntro() {
    var ref = probe();

    var section =
        ref.sections().stream()
            .filter(s -> s.prefix().equals("thrillhousebot.models.<model>"))
            .findFirst()
            .orElseThrow();
    assertEquals("Per-model settings, keyed by model name.", section.intro());
    assertEquals(
        "`THRILLHOUSEBOT_MODELS_<MODEL>_MAX_INPUT_TOKENS`", section.rows().get(0).variable());
    assertEquals("integer", section.rows().get(0).type());
  }

  @Test
  void otherVariablesCollectEveryKeyAndProfileThatReadsThem() {
    var ref =
        probe(
            "# Maintainer comment, not published.",
            "# doc: Database kind.",
            "quarkus.datasource.db-kind=${DB_KIND:h2}",
            "%prod.quarkus.datasource.db-kind=${DB_KIND:postgresql}",
            "quarkus.native.args=\\",
            "  --a,\\",
            "  --b");

    var kind = ref.byVariable("`DB_KIND`").orElseThrow();
    assertEquals("`h2`; `%prod`: `postgresql`", kind.defaultValue());
    assertEquals("`quarkus.datasource.db-kind`", kind.property());
    assertEquals(1, ref.other().size());
  }

  @Test
  void onlyAListDefaultIsSplitAtCommas() {
    assertEquals(
        "`a,b`", row(probe("thrillhousebot.url=a,b"), "thrillhousebot.url").defaultValue());
  }

  @Test
  void aCompositeValueDocumentsTheVariableItReads() {
    var error =
        assertThrows(
            IllegalStateException.class, () -> probe("thrillhousebot.url=${PROBE_HOST:x}/api"));
    assertTrue(error.getMessage().contains("PROBE_HOST: no '# doc:' comment"));

    var ref =
        probe(
            "thrillhousebot.url=${PROBE_ENABLED:x}/api",
            "thrillhousebot.enabled=${PROBE_ENABLED:true}");
    assertEquals("`${PROBE_ENABLED}/api`", row(ref, "thrillhousebot.url").defaultValue());
    assertTrue(ref.other().isEmpty());
  }

  @Test
  void anUndocumentedVariableOrPropertyFailsGeneration() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                ConfigReference.build(
                    Probe.class,
                    Map.of(),
                    ConfigReference.parseProperties(List.of("quarkus.x=${UNDOCUMENTED:1}"))));

    assertTrue(error.getMessage().contains("ConfigReferenceTest.Probe#enabled: no javadoc"));
    assertTrue(error.getMessage().contains("UNDOCUMENTED: no '# doc:' comment"));
  }

  @Test
  void aLinkInThePublishedParagraphFailsGeneration() {
    var source = PROBE_SOURCE.replace("Base address.", "Base address, see {@link #enabled()}.");

    var error =
        assertThrows(
            IllegalStateException.class,
            () -> ConfigReference.build(Probe.class, ConfigReference.javadoc(source), List.of()));

    assertTrue(error.getMessage().contains("Probe#url: unsupported javadoc element LINK"));
  }

  @Test
  void aDocCommentOnAMappingKeyFailsGeneration() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> probe("# doc: Says too much.", "thrillhousebot.enabled=${PROBE_ENABLED:true}"));

    assertTrue(error.getMessage().contains("thrillhousebot.enabled: a mapping key is described"));
  }

  @Test
  void essentialsAreSplicedBetweenTheMarkers() {
    var readme =
        "a\n"
            + ConfigReference.ESSENTIALS_START
            + "\nold\n"
            + ConfigReference.ESSENTIALS_END
            + "\nb";

    assertEquals(
        "a\n"
            + ConfigReference.ESSENTIALS_START
            + "\nnew\n"
            + ConfigReference.ESSENTIALS_END
            + "\nb",
        ConfigReference.spliceEssentials(readme, "\nnew\n"));
    assertThrows(IllegalStateException.class, () -> ConfigReference.spliceEssentials("a", "x"));
  }
}
