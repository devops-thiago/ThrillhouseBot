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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a short "project stack" summary from the repository's dependency manifests so the reviewer
 * can ground framework- and library-behavior claims instead of guessing from memory.
 */
@ApplicationScoped
public class ProjectStackResolver {

  private static final Logger log = LoggerFactory.getLogger(ProjectStackResolver.class);
  private static final String ACCEPT_HEADER = "application/vnd.github+json";

  /** Dependency manifests probed at the repository root. Package-private for tests. */
  static final List<String> MANIFEST_PATHS =
      List.of(
          "pom.xml",
          "build.gradle",
          "build.gradle.kts",
          "package.json",
          "go.mod",
          "Cargo.toml",
          "pyproject.toml",
          "requirements.txt",
          "Gemfile",
          "composer.json");

  private static final String MAVEN_ARTIFACT_TAG = "<artifactId>";

  /** Gradle dependency configurations; a matching line starts with one followed by space or '('. */
  private static final List<String> GRADLE_CONFIGURATIONS =
      List.of(
          "implementation",
          "api",
          "compileOnly",
          "compileOnlyApi",
          "runtimeOnly",
          "testImplementation",
          "testRuntimeOnly",
          "annotationProcessor",
          "kapt",
          "classpath");

  static final int MAX_MANIFESTS = 3;
  static final int MAX_RAW_LINES = 80;
  static final int MAX_SECTION_CHARS = 2_500;
  static final long CACHE_TTL_MS = 30L * 60 * 1000;
  static final int CACHE_SWEEP_THRESHOLD = 1_000;

  private record CachedStack(String content, long expiresAt) {}

  // Package-private for tests.
  final ConcurrentHashMap<String, CachedStack> cache = new ConcurrentHashMap<>();

  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;
  private final ObjectMapper mapper;
  private final LongSupplier clock;

  @Inject
  public ProjectStackResolver(
      GitHubAuthClient authClient,
      @RestClient GitHubPullRequestClient prClient,
      ObjectMapper mapper) {
    this(authClient, prClient, mapper, System::currentTimeMillis);
  }

  /** Visible for tests: allows controlling the cache clock. */
  ProjectStackResolver(
      GitHubAuthClient authClient,
      GitHubPullRequestClient prClient,
      ObjectMapper mapper,
      LongSupplier clock) {
    this.authClient = authClient;
    this.prClient = prClient;
    this.mapper = mapper;
    this.clock = clock;
  }

  /**
   * Resolves the stack summary for a repository, empty string when no manifest is found. Results
   * are cached per repo — manifests change far less often than PRs arrive.
   */
  public String resolve(String owner, String repo, String defaultBranch, long installationId) {
    var cacheKey = owner + "/" + repo;
    var cached = cache.get(cacheKey);
    if (cached != null) {
      if (clock.getAsLong() < cached.expiresAt()) {
        return cached.content();
      }
      cache.remove(cacheKey, cached);
    }

    var auth = authClient.getAuthHeader(installationId);
    var sections = new ArrayList<String>();
    for (String path : MANIFEST_PATHS) {
      if (sections.size() >= MAX_MANIFESTS) {
        break;
      }
      fetchManifestSection(auth, owner, repo, path, defaultBranch).ifPresent(sections::add);
    }

    String stack = String.join("\n\n", sections);
    cache.put(cacheKey, new CachedStack(stack, clock.getAsLong() + CACHE_TTL_MS));
    sweepExpiredEntries();
    if (!stack.isEmpty()) {
      log.info(
          "Project stack for {}/{}: {} manifest(s), {} chars",
          owner,
          repo,
          sections.size(),
          stack.length());
    }
    return stack;
  }

  /** One manifest's summarized section, or empty when the file is absent or unusable. */
  private Optional<String> fetchManifestSection(
      String auth, String owner, String repo, String path, String defaultBranch) {
    try {
      var file = prClient.getFileContent(auth, ACCEPT_HEADER, owner, repo, path, defaultBranch);
      if (file.content() == null) {
        // Directories, submodules, and oversized files come back without base64 content.
        log.debug("No content for manifest at {} in {}/{}", path, owner, repo);
        return Optional.empty();
      }
      // GitHub wraps base64 content in newlines — the MIME decoder tolerates them.
      var content =
          new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);
      if (content.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(summarize(path, content));
    } catch (WebApplicationException | ProcessingException | IllegalArgumentException _) {
      log.debug("No usable manifest at {} for {}/{}", path, owner, repo);
      return Optional.empty();
    }
  }

  /** See {@link InstructionsResolver#sweepExpiredEntries()} — same eviction rationale. */
  void sweepExpiredEntries() {
    if (cache.size() < CACHE_SWEEP_THRESHOLD) {
      return;
    }
    var now = clock.getAsLong();
    cache.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
  }

  String summarize(String path, String content) {
    var summary =
        switch (path) {
          case "pom.xml" -> mavenArtifacts(content);
          case "build.gradle", "build.gradle.kts" -> gradleDependencies(content);
          case "package.json" -> npmDependencies(content);
          default -> "";
        };
    if (summary.isEmpty()) {
      summary = rawHead(content);
    }
    if (summary.length() > MAX_SECTION_CHARS) {
      summary = summary.substring(0, MAX_SECTION_CHARS) + "\n… (truncated)";
    }
    return "### " + path + "\n" + summary;
  }

  private static String mavenArtifacts(String content) {
    var artifacts = new LinkedHashSet<String>();
    var index = 0;
    while ((index = content.indexOf(MAVEN_ARTIFACT_TAG, index)) >= 0) {
      index += MAVEN_ARTIFACT_TAG.length();
      var end = content.indexOf('<', index);
      if (end < 0) {
        break;
      }
      var artifact = content.substring(index, end).strip();
      if (!artifact.isEmpty()) {
        artifacts.add(artifact);
      }
      index = end;
    }
    return artifacts.isEmpty() ? "" : "Maven artifacts: " + String.join(", ", artifacts);
  }

  private static String gradleDependencies(String content) {
    List<String> lines =
        content
            .lines()
            .map(String::strip)
            .filter(ProjectStackResolver::isGradleDependencyLine)
            .limit(MAX_RAW_LINES)
            .toList();
    return String.join("\n", lines);
  }

  private static boolean isGradleDependencyLine(String strippedLine) {
    for (String configuration : GRADLE_CONFIGURATIONS) {
      if (strippedLine.length() > configuration.length()
          && strippedLine.startsWith(configuration)) {
        var next = strippedLine.charAt(configuration.length());
        if (next == ' ' || next == '\t' || next == '(') {
          return true;
        }
      }
    }
    return false;
  }

  private String npmDependencies(String content) {
    try {
      var root = mapper.readTree(content);
      var dependencies = new ArrayList<String>();
      for (String section : List.of("dependencies", "devDependencies")) {
        var node = root.path(section);
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
          dependencies.add(entry.getKey() + " " + entry.getValue().asText());
        }
      }
      return dependencies.isEmpty() ? "" : "npm packages: " + String.join(", ", dependencies);
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      log.debug("Unparseable package.json, falling back to raw excerpt", e);
      return "";
    }
  }

  private static String rawHead(String content) {
    return content.lines().limit(MAX_RAW_LINES).reduce((a, b) -> a + "\n" + b).orElse("");
  }
}
