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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Parses the YAML in a repository's {@code .github/thrillhousebot.yml} into {@link RepoSettings}.
 *
 * <p>Shape (every key optional):
 *
 * <pre>{@code
 * review:
 *   ignored-files:
 *     - "docs/generated/**"
 *     - "**''/''*.snap"
 *   path-instructions:
 *     - path: "payments/**"
 *       instructions: |
 *         Money is handled in integer cents; flag any floating-point arithmetic.
 * }</pre>
 *
 * <p>The file is untrusted input from an arbitrary repository, so parsing is deliberately
 * defensive: it reads a generic tree rather than binding to a POJO (no reflection, no type
 * coercion), bounds the document with snakeyaml loader limits, caps how many patterns and scoped
 * instruction blocks a repository may contribute, and returns {@link RepoSettings#EMPTY} for
 * anything it cannot make sense of. It never throws — a malformed config must degrade to "no
 * per-repo settings", never fail a review.
 */
final class RepoSettingsParser {

  private static final Logger log = LoggerFactory.getLogger(RepoSettingsParser.class);

  /** Ceiling on the YAML document size, guarding against an oversized or hostile config. */
  private static final int MAX_CODE_POINTS = 256 * 1024;

  /** Ceiling on YAML nesting, guarding against deeply nested documents. */
  private static final int MAX_NESTING_DEPTH = 20;

  /** Ceiling on anchor/alias expansion, guarding against "billion laughs"-style blowups. */
  private static final int MAX_ALIASES = 50;

  /** Ceiling on how many extra ignore globs one repository may contribute. */
  static final int MAX_PATTERNS = 200;

  /** Ceiling on a single glob's length — a pathological pattern is dropped, not compiled. */
  static final int MAX_PATTERN_LENGTH = 512;

  /** Ceiling on how many path-scoped instruction blocks one repository may declare. */
  static final int MAX_PATH_SCOPES = 25;

  /** Ceiling on one scope's prose — an over-long block is dropped rather than sent to the model. */
  static final int MAX_SCOPE_INSTRUCTIONS_LENGTH = 4_000;

  /** Ceiling on the coverage-artifact name; GitHub's own artifact names are far shorter. */
  static final int MAX_ARTIFACT_NAME_LENGTH = 256;

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(yamlFactory());

  private RepoSettingsParser() {}

  private static YAMLFactory yamlFactory() {
    var options = new LoaderOptions();
    options.setCodePointLimit(MAX_CODE_POINTS);
    options.setNestingDepthLimit(MAX_NESTING_DEPTH);
    options.setMaxAliasesForCollections(MAX_ALIASES);
    options.setAllowDuplicateKeys(false);
    return YAMLFactory.builder().loaderOptions(options).build();
  }

  /**
   * Parses {@code yaml}, attributing the result to {@code source} (the repo-relative path it came
   * from). Returns {@link RepoSettings#EMPTY} for blank, malformed, or setting-less content.
   */
  static RepoSettings parse(String yaml, String source) {
    if (yaml == null || yaml.isBlank()) {
      return RepoSettings.EMPTY;
    }
    try {
      // Pattern match rather than isObject(): one test rejects a scalar or sequence document
      // (which carries no settings) and a null/missing root alike.
      if (!(YAML_MAPPER.readTree(yaml) instanceof ObjectNode root)) {
        log.warn("Repository config {} is not a YAML mapping; ignoring it", source);
        return RepoSettings.EMPTY;
      }
      var review = root.path("review");
      var ignoredFiles = readPatterns(review.path("ignored-files"), source);
      var pathInstructions = readPathInstructions(review.path("path-instructions"), source);
      var coverageArtifact = readCoverageArtifact(review.path("coverage-artifact"), source);
      return ignoredFiles.isEmpty() && pathInstructions.isEmpty() && coverageArtifact.isEmpty()
          ? RepoSettings.EMPTY
          : new RepoSettings(ignoredFiles, pathInstructions, coverageArtifact, source);
    } catch (IOException | RuntimeException e) {
      log.warn(
          "Could not parse repository config {}; continuing with the global settings only",
          source,
          e);
      return RepoSettings.EMPTY;
    }
  }

  /**
   * Reads {@code review.ignored-files} as a list of globs. A sequence of scalars is the documented
   * form; a single scalar is also accepted and split on commas, matching how the global key is
   * written as an environment variable. Anything else is ignored.
   */
  private static List<String> readPatterns(JsonNode node, String source) {
    return switch (node.getNodeType()) {
      // path() yields a MissingNode when the key is absent, and `ignored-files:` with no value
      // yields a NullNode. Both mean the repository declared nothing — not a malformed config.
      case MISSING, NULL -> List.of();
      case ARRAY -> sanitize(scalarEntries(node), source);
      // A lone scalar is split on commas, matching how the global key is written as an env var.
      case STRING -> sanitize(List.of(node.asText().split(",")), source);
      default -> {
        log.warn("Repository config {}: review.ignored-files is not a list; ignoring it", source);
        yield List.of();
      }
    };
  }

  /**
   * Reads {@code review.coverage-artifact} — the name of the workflow artifact holding this
   * repository's JaCoCo XML coverage report. A scalar is the only accepted shape; anything else
   * (including an explicit YAML null, whose {@code asText()} is the literal {@code "null"}) is a
   * name nobody wrote and yields {@code ""}, which switches patch-coverage context off for the
   * repository rather than sending the bot looking for a nonexistent artifact.
   */
  private static String readCoverageArtifact(JsonNode node, String source) {
    if (node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (!node.isValueNode()) {
      log.warn("Repository config {}: review.coverage-artifact is not a name; ignoring it", source);
      return "";
    }
    var name = node.asText().trim();
    if (name.length() > MAX_ARTIFACT_NAME_LENGTH) {
      log.warn("Repository config {}: dropping an over-long coverage-artifact name", source);
      return "";
    }
    return name;
  }

  /**
   * Reads {@code review.path-instructions} as a sequence of {@code {path, instructions}} mappings.
   * Every other shape — a scalar, a mapping, an entry missing either key, a non-scalar value — is
   * skipped with a warning rather than failing the parse, so one bad entry costs only itself and a
   * wholly malformed block costs only the path-scoped rules.
   */
  private static List<RepoSettings.PathInstructions> readPathInstructions(
      JsonNode node, String source) {
    return switch (node.getNodeType()) {
      // A MissingNode (key absent) or NullNode (`path-instructions:` with no value) means the
      // repository declared nothing — not a malformed config.
      case MISSING, NULL -> List.of();
      case ARRAY -> scopeEntries(node, source);
      default -> {
        log.warn(
            "Repository config {}: review.path-instructions is not a list; ignoring it", source);
        yield List.of();
      }
    };
  }

  /** The well-formed {@code {path, instructions}} entries of a sequence, capped and trimmed. */
  private static List<RepoSettings.PathInstructions> scopeEntries(JsonNode array, String source) {
    var scopes =
        new ArrayList<RepoSettings.PathInstructions>(Math.min(array.size(), MAX_PATH_SCOPES));
    for (var element : array) {
      if (scopes.size() >= MAX_PATH_SCOPES) {
        log.warn(
            "Repository config {}: more than {} path-instruction scopes; using the first {}",
            source,
            MAX_PATH_SCOPES,
            MAX_PATH_SCOPES);
        break;
      }
      var scope = readScope(element, source);
      if (scope != null) {
        scopes.add(scope);
      }
    }
    return List.copyOf(scopes);
  }

  /** One scope entry, or {@code null} when it is not a usable {@code {path, instructions}} pair. */
  private static RepoSettings.PathInstructions readScope(JsonNode element, String source) {
    var pathNode = element.path("path");
    var instructionsNode = element.path("instructions");
    // Two shapes of "no value" have to be told apart. A key written out with nothing after it
    // (`path:`) reaches us as an empty string and is caught by the blank check below; an explicit
    // `path: null` (or `path: ~`) is a NullNode, whose asText() is the literal "null" — a glob
    // nobody wrote — so isScalar() rejects it before it can be trimmed into a usable pattern.
    if (!isScalar(pathNode) || !isScalar(instructionsNode)) {
      log.warn(
          "Repository config {}: skipping a path-instructions entry without a scalar"
              + " 'path' and 'instructions'",
          source);
      return null;
    }
    var path = pathNode.asText().trim();
    var instructions = instructionsNode.asText().strip();
    if (path.isEmpty() || instructions.isEmpty()) {
      log.warn(
          "Repository config {}: skipping a path-instructions entry with a blank field", source);
      return null;
    }
    if (path.length() > MAX_PATTERN_LENGTH) {
      log.warn("Repository config {}: dropping an over-long path-instructions glob", source);
      return null;
    }
    if (instructions.length() > MAX_SCOPE_INSTRUCTIONS_LENGTH) {
      log.warn(
          "Repository config {}: dropping the rules for scope {} — over {} characters",
          source,
          path,
          MAX_SCOPE_INSTRUCTIONS_LENGTH);
      return null;
    }
    return new RepoSettings.PathInstructions(path, instructions);
  }

  /**
   * A usable scalar — not an absent key ({@code MissingNode} is not a value node), not an explicit
   * YAML null, not a nested sequence or mapping.
   */
  private static boolean isScalar(JsonNode node) {
    return node.isValueNode() && !node.isNull();
  }

  /** The scalar entries of a sequence; a nested mapping or sequence entry is not a glob. */
  private static List<String> scalarEntries(JsonNode array) {
    var raw = new ArrayList<String>(array.size());
    for (var element : array) {
      if (element.isValueNode()) {
        raw.add(element.asText());
      }
    }
    return raw;
  }

  /** Trims, drops blank/oversized entries, and caps the total a repository may contribute. */
  private static List<String> sanitize(List<String> raw, String source) {
    var patterns = new ArrayList<String>(Math.min(raw.size(), MAX_PATTERNS));
    for (String value : raw) {
      // Never null: entries come from asText() (empty string at worst) or String.split.
      var pattern = value.trim();
      if (pattern.isEmpty()) {
        continue;
      }
      if (pattern.length() > MAX_PATTERN_LENGTH) {
        log.warn("Repository config {}: dropping over-long ignore pattern", source);
        continue;
      }
      if (patterns.size() >= MAX_PATTERNS) {
        log.warn(
            "Repository config {}: more than {} ignore patterns; using the first {}",
            source,
            MAX_PATTERNS,
            MAX_PATTERNS);
        break;
      }
      patterns.add(pattern);
    }
    return List.copyOf(patterns);
  }
}
