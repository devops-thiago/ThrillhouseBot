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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewDimension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which review dimension blocks a review call's system prompt carries (#665), from cheap
 * deterministic signals in the files the call reviews: what kind of file each one is (its name and
 * extension), and two probes over its patch text. The generalization of what the pipeline already
 * did for the heuristic failure-mode section and the assembler for the mock-fidelity request.
 *
 * <p>The rule is inclusion by default. A dimension is left out only when no file in the call is of
 * a kind it covers; a file of a kind the router does not recognize brings every dimension in, and a
 * batch mixing kinds gets the union of what each of its files needs. Correctness, security and
 * regressions are never routed. Missing a dimension is a recall regression nobody sees, while an
 * extra block costs tokens, so every doubtful case resolves to including the block.
 *
 * <p>Routing is monotone in the files: a call over a subset of a pull request's files never gets a
 * dimension the whole pull request would not. That is what lets the budget planner size the shared
 * overhead from the pull request's routing and know no batch's prompt is larger.
 *
 * <p>Off unless {@code REVIEW_DIMENSION_ROUTING_ENABLED} is set: every call then carries every
 * dimension, and the system prompt is byte for byte the one before the split.
 */
@ApplicationScoped
public class ReviewDimensionRouter {

  /** A routing decision: the dimensions a call carries, and for each one why. */
  public record Routing(Map<ReviewDimension, String> reasons) {

    public Routing {
      reasons = Map.copyOf(reasons);
    }

    /** The dimensions the call carries — always including the always-on ones. */
    public Set<ReviewDimension> dimensions() {
      return reasons.keySet();
    }

    /**
     * One line for the log: each included routed dimension with the file that brought it in, then
     * the dimensions left out. The always-on dimensions are not listed; they are in every call.
     */
    public String describe() {
      var included = new ArrayList<String>();
      var omitted = new ArrayList<String>();
      for (var dimension : ReviewDimension.values()) {
        if (dimension.alwaysOn()) {
          continue;
        }
        var reason = reasons.get(dimension);
        if (reason == null) {
          omitted.add(dimension.label());
        } else {
          included.add(dimension.label() + " (" + reason + ")");
        }
      }
      return "included "
          + (included.isEmpty() ? "no routed dimension" : String.join(", ", included))
          + "; left out "
          + (omitted.isEmpty() ? "nothing" : String.join(", ", omitted));
    }
  }

  /** The kinds of file the router tells apart, each with the routed dimensions it brings in. */
  enum FileKind {
    DOCUMENTATION(
        "documentation",
        ReviewDimension.COMMENT_CONTRADICTS_CODE,
        ReviewDimension.CONFIG_KEY_DOCUMENTATION),
    CONFIGURATION(
        "configuration or infrastructure file",
        ReviewDimension.COMMENT_CONTRADICTS_CODE,
        ReviewDimension.CONFIG_IAC,
        ReviewDimension.CONFIG_KEY_DOCUMENTATION),
    SCRIPT(
        "script",
        ReviewDimension.COMMENT_CONTRADICTS_CODE,
        ReviewDimension.CODE_QUALITY_AND_COMPLEXITY,
        ReviewDimension.CONFIG_IAC,
        ReviewDimension.PRODUCER_CONSUMER),
    SOURCE(
        "source code",
        ReviewDimension.COMMENT_CONTRADICTS_CODE,
        ReviewDimension.CODE_QUALITY_AND_COMPLEXITY,
        ReviewDimension.PRODUCER_CONSUMER),
    TEST(
        "test file",
        ReviewDimension.COMMENT_CONTRADICTS_CODE,
        ReviewDimension.CODE_QUALITY_AND_COMPLEXITY,
        ReviewDimension.PRODUCER_CONSUMER,
        ReviewDimension.MOCK_FIDELITY),
    UNKNOWN(
        "file type not recognized",
        ReviewDimension.COMMENT_CONTRADICTS_CODE,
        ReviewDimension.CODE_QUALITY_AND_COMPLEXITY,
        ReviewDimension.PAGINATION,
        ReviewDimension.CONFIG_IAC,
        ReviewDimension.MOCK_FIDELITY,
        ReviewDimension.PRODUCER_CONSUMER,
        ReviewDimension.CONFIG_KEY_DOCUMENTATION);

    private final String description;
    private final Set<ReviewDimension> dimensions;

    FileKind(String description, ReviewDimension first, ReviewDimension... rest) {
      this.description = description;
      this.dimensions = Set.copyOf(EnumSet.of(first, rest));
    }
  }

  private static final Set<String> DOCUMENTATION_EXTENSIONS =
      Set.of("md", "markdown", "mdx", "rst", "adoc", "asciidoc", "txt", "rdoc", "org", "textile");

  /** Extension-less documentation files, by their conventional names. */
  private static final Set<String> DOCUMENTATION_NAMES =
      Set.of(
          "readme",
          "license",
          "licence",
          "changelog",
          "changes",
          "notice",
          "authors",
          "contributors",
          "contributing",
          "copying");

  private static final Set<String> CONFIGURATION_EXTENSIONS =
      Set.of(
          "yml",
          "yaml",
          "json",
          "jsonc",
          "json5",
          "toml",
          "ini",
          "cfg",
          "conf",
          "config",
          "properties",
          "env",
          "tf",
          "tfvars",
          "hcl",
          "nomad",
          "xml",
          "gradle",
          "sbt",
          "tpl",
          "j2",
          "jinja",
          "jinja2",
          "plist",
          "cue",
          "jsonnet",
          "libsonnet",
          "nix",
          "bzl",
          "bazel",
          "cmake",
          "mk",
          "lock",
          "mod",
          "sum");

  /** Extension-less build, CI and container files, by their conventional names (lower case). */
  private static final Set<String> CONFIGURATION_NAMES =
      Set.of(
          "makefile",
          "gnumakefile",
          "jenkinsfile",
          "procfile",
          "vagrantfile",
          "brewfile",
          "gemfile",
          "pipfile",
          "justfile",
          "tiltfile",
          "earthfile",
          "build",
          "workspace",
          "codeowners",
          "cmakelists.txt");

  /**
   * The container-image files, matched as a whole dot-separated segment of the name so every
   * spelling counts: {@code Dockerfile}, {@code Dockerfile.prod}, {@code prod.dockerfile}.
   */
  private static final Set<String> CONTAINER_FILE_SEGMENTS = Set.of("dockerfile", "containerfile");

  /** Dependency lists that carry a documentation-looking {@code .txt} extension. */
  private static final Pattern DEPENDENCY_LIST_NAME =
      Pattern.compile("(?:requirements|constraints)[\\w.-]*\\.(?:txt|in)");

  private static final Set<String> SCRIPT_EXTENSIONS =
      Set.of("sh", "bash", "zsh", "fish", "ksh", "ps1", "psm1", "bat", "cmd");

  private static final Set<String> SOURCE_EXTENSIONS =
      Set.of(
          "java", "kt", "kts", "scala", "sc", "groovy", "clj", "cljs", "cljc", "go", "rs", "py",
          "pyi", "rb", "php", "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "vue", "svelte",
          "astro", "cs", "fs", "fsx", "vb", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "m",
          "mm", "swift", "dart", "lua", "pl", "pm", "r", "jl", "ex", "exs", "erl", "hrl", "hs",
          "elm", "ml", "mli", "zig", "nim", "sql", "graphql", "gql", "proto", "sol", "html", "htm",
          "css", "scss", "sass", "less", "jsp", "erb", "cshtml", "razor", "ejs", "hbs", "twig");

  /**
   * Substrings (matched against the lower-cased patch) that suggest code which lists, pages or
   * queries a collection from an API or a store — the material dimension 6 needs. Deliberately
   * broad: a false hit costs one block, a false miss costs the dimension.
   */
  private static final List<String> PAGINATION_SIGNALS =
      List.of(
          "pag",
          "cursor",
          "offset",
          "limit",
          "graphql",
          "nodes",
          "edges",
          "/repos/",
          "http",
          "api",
          "query",
          "select",
          "fetch",
          "findall",
          "find_all",
          "getall",
          "get_all",
          "client",
          "endpoint",
          "request",
          "response",
          "scan",
          "iterat");

  /** A call to something named list…, the usual shape of a collection fetch. */
  private static final Pattern LIST_CALL = Pattern.compile("list\\w*\\s*\\(");

  /**
   * Substrings (matched against the lower-cased patch) that suggest a stub or a fake, across the
   * test frameworks the bot reviews — the material dimension 8 needs. A mock outside a test path
   * still brings the dimension in.
   */
  private static final List<String> MOCK_SIGNALS =
      List.of(
          "mock",
          "stub",
          "fake",
          "spy(",
          "spyon",
          "when(",
          "thenreturn",
          "thenthrow",
          "doreturn",
          "dothrow",
          "doanswer",
          "patch(",
          "patch.object",
          "allow(",
          "receive(",
          "every {",
          "every{",
          "sinon",
          "double(");

  private static final String ALWAYS_ON = "always on";

  private final boolean enabled;

  @Inject
  public ReviewDimensionRouter(ThrillhouseConfig config) {
    this(config.review().dimensionRoutingEnabled());
  }

  ReviewDimensionRouter(boolean enabled) {
    this.enabled = enabled;
  }

  /** A router with routing off: every call carries every dimension. */
  public static ReviewDimensionRouter disabled() {
    return new ReviewDimensionRouter(false);
  }

  /** Whether routing is on ({@code REVIEW_DIMENSION_ROUTING_ENABLED}). */
  public boolean enabled() {
    return enabled;
  }

  /**
   * The review system prompt for a call reviewing {@code files}: {@link PrReviewPrompts#SYSTEM}
   * with routing off, otherwise the routed prompt for {@link #routeFiles}.
   */
  public String systemPromptFor(List<GitHubPullRequestClient.FileDiff> files) {
    return enabled
        ? PrReviewPrompts.reviewSystemPrompt(routeFiles(files).dimensions())
        : PrReviewPrompts.SYSTEM;
  }

  /**
   * The routing decided from the files alone: the always-on dimensions, plus each routed dimension
   * some file brings in, with the first such file as the reason. A call with no file to decide on
   * gets every dimension — there is no signal, so nothing is left out.
   */
  public static Routing routeFiles(List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null || files.isEmpty()) {
      return everyDimension("no file to route on");
    }
    var reasons = alwaysOnReasons();
    for (var file : files) {
      var path = file.filename();
      dimensionsOf(path, file.patch())
          .forEach((dimension, why) -> reasons.putIfAbsent(dimension, label(path) + ": " + why));
    }
    return new Routing(reasons);
  }

  /**
   * The dimensions files at {@code paths} bring in, judged from the path alone — for a caller that
   * holds paths but no patch text, such as the finding verifier routing its carve-outs by where the
   * candidates are anchored. With no patch to probe, both content-probed dimensions come in for any
   * file that is not documentation, so this never includes less than {@link #routeFiles} would for
   * the same files. No paths, or a blank one, means every dimension.
   */
  public static Set<ReviewDimension> dimensionsForPaths(Collection<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return ReviewDimension.ALL;
    }
    var dimensions = EnumSet.noneOf(ReviewDimension.class);
    dimensions.addAll(alwaysOnReasons().keySet());
    for (var path : paths) {
      dimensions.addAll(dimensionsOf(path, null).keySet());
    }
    return Set.copyOf(dimensions);
  }

  /**
   * The routed dimensions one file brings in, each with why. A path that also names a test brings
   * in the test dimensions on top of its own kind's — a fixture manifest under {@code src/test} is
   * both. Every file but documentation also runs the two content probes; a file with no patch to
   * probe gets both dimensions, since there is nothing to rule them out on.
   */
  static Map<ReviewDimension, String> dimensionsOf(String path, String patch) {
    var out = new EnumMap<ReviewDimension, String>(ReviewDimension.class);
    if (ReviewDiffFormatter.isTestFile(path)) {
      FileKind.TEST.dimensions.forEach(
          dimension -> out.putIfAbsent(dimension, FileKind.TEST.description));
    }
    var kind = kindOf(path);
    kind.dimensions.forEach(dimension -> out.putIfAbsent(dimension, kind.description));
    if (kind == FileKind.DOCUMENTATION) {
      return out;
    }
    if (patch == null || patch.isBlank()) {
      out.putIfAbsent(ReviewDimension.PAGINATION, "no patch text to probe");
      out.putIfAbsent(ReviewDimension.MOCK_FIDELITY, "no patch text to probe");
      return out;
    }
    var lower = patch.toLowerCase(Locale.ROOT);
    if (PAGINATION_SIGNALS.stream().anyMatch(lower::contains) || LIST_CALL.matcher(lower).find()) {
      out.putIfAbsent(ReviewDimension.PAGINATION, "API, query or list code in the patch");
    }
    if (MOCK_SIGNALS.stream().anyMatch(lower::contains)) {
      out.putIfAbsent(ReviewDimension.MOCK_FIDELITY, "mock or stub in the patch");
    }
    return out;
  }

  /** What kind of file {@code path} names, from its name and extension. */
  static FileKind kindOf(String path) {
    if (path == null || path.isBlank()) {
      return FileKind.UNKNOWN;
    }
    var name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    var dot = name.lastIndexOf('.');
    var extension = dot >= 0 ? name.substring(dot + 1) : "";
    var stem = dot > 0 ? name.substring(0, dot) : name;
    if (isConfigurationName(name, extension)) {
      return FileKind.CONFIGURATION;
    }
    if (DOCUMENTATION_EXTENSIONS.contains(extension) || DOCUMENTATION_NAMES.contains(stem)) {
      return FileKind.DOCUMENTATION;
    }
    if (SCRIPT_EXTENSIONS.contains(extension)) {
      return FileKind.SCRIPT;
    }
    if (SOURCE_EXTENSIONS.contains(extension)) {
      return FileKind.SOURCE;
    }
    return FileKind.UNKNOWN;
  }

  private static boolean isConfigurationName(String name, String extension) {
    if (name.startsWith(".env")
        || name.startsWith("docker-compose")
        || name.endsWith(".gradle.kts")
        || CONFIGURATION_NAMES.contains(name)
        || DEPENDENCY_LIST_NAME.matcher(name).matches()) {
      return true;
    }
    for (var segment : name.split("\\.")) {
      if (CONTAINER_FILE_SEGMENTS.contains(segment)) {
        // Dockerfile.md and Containerfile.kt are ABOUT the image, not the image.
        return !DOCUMENTATION_EXTENSIONS.contains(extension)
            && !SOURCE_EXTENSIONS.contains(extension);
      }
    }
    // A dotfile such as .gitignore or .npmrc has no extension of its own: its whole name is the
    // type, and outside the source tree those are configuration.
    if (name.startsWith(".") && name.indexOf('.', 1) < 0) {
      return true;
    }
    return CONFIGURATION_EXTENSIONS.contains(extension);
  }

  private static EnumMap<ReviewDimension, String> alwaysOnReasons() {
    var reasons = new EnumMap<ReviewDimension, String>(ReviewDimension.class);
    for (var dimension : ReviewDimension.values()) {
      if (dimension.alwaysOn()) {
        reasons.put(dimension, ALWAYS_ON);
      }
    }
    return reasons;
  }

  private static Routing everyDimension(String why) {
    var reasons = alwaysOnReasons();
    for (var dimension : ReviewDimension.values()) {
      reasons.putIfAbsent(dimension, why);
    }
    return new Routing(reasons);
  }

  /** A path as it may appear in a log line: pull request paths are author-controlled. */
  private static String label(String path) {
    return path == null ? "(unnamed file)" : LogSafe.oneLine(path);
  }
}
