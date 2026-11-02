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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.FileTriage.Tier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for {@link FileTriage}: tiers over representative paths, and their order. */
class FileTriageTest {

  /** The defaults of {@code application.properties}. */
  private static final List<String> LOW_RISK_PATHS =
      List.of(
          "**/*.md",
          "**/*.mdx",
          "**/*.rst",
          "**/*.txt",
          "**/*.adoc",
          "docs/",
          "**/*.lock",
          "**/go.sum");

  private static final List<String> HIGH_RISK_PATHS =
      List.of(
          ".github/workflows/",
          "**/*.sql",
          "**/migrations/",
          "**/auth/",
          "**/security/",
          "**/Dockerfile");

  private static FileTriage triage() {
    var config = mock(ThrillhouseConfig.class);
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var routing = mock(ThrillhouseConfig.AiPricingConfig.RoutingConfig.class);
    when(config.ai()).thenReturn(ai);
    when(ai.routing()).thenReturn(routing);
    when(routing.lowRiskPaths()).thenReturn(LOW_RISK_PATHS);
    when(routing.highRiskPaths()).thenReturn(HIGH_RISK_PATHS);
    return FileTriage.of(config);
  }

  private static FileDiff file(String name) {
    return new FileDiff(name, "modified", 1, 0, 1, "@@ -1 +1 @@\n+x\n");
  }

  @ParameterizedTest
  @CsvSource({
    "pom.xml, BUILD_MANIFEST",
    "java/build.gradle, BUILD_MANIFEST",
    "rust/Cargo.toml, BUILD_MANIFEST",
    "web/package.json, BUILD_MANIFEST",
    ".github/workflows/ci.yml, HIGH_RISK",
    "db/migrations/V2__scope.sql, HIGH_RISK",
    "src/main/java/app/auth/TokenFilter.java, HIGH_RISK",
    "service/security/Policy.kt, HIGH_RISK",
    "go/Dockerfile, HIGH_RISK",
    "src/main/java/app/OrderService.java, NORMAL",
    "src/test/java/app/OrderServiceTest.java, NORMAL",
    "config/application.yml, NORMAL",
    "README.md, LOW_RISK",
    "docs/guide/setup.adoc, LOW_RISK",
    "Gemfile.lock, LOW_RISK",
    "go/go.sum, LOW_RISK",
  })
  void eachPathGetsItsTier(String path, Tier expected) {
    assertEquals(expected, triage().tier(file(path)));
  }

  @Test
  void aHighRiskPathWinsOverALowRiskGlob() {
    // As for model routing: the operator named that path.
    assertEquals(Tier.HIGH_RISK, triage().tier(file(".github/workflows/README.md")));
    assertEquals(Tier.HIGH_RISK, triage().tier(file("docs/migrations/notes.md")));
  }

  @Test
  void aFileRenamedOffAHighRiskPathKeepsItsTier() {
    var moved =
        new FileDiff("lib/TokenFilter.java", "renamed", 3, 1, 4, "@@", "app/auth/TokenFilter.java");

    assertEquals(Tier.HIGH_RISK, triage().tier(moved));
  }

  @Test
  void aFileRenamedBetweenOrdinaryPathsStaysNormal() {
    var moved = new FileDiff("lib/Orders.java", "renamed", 3, 1, 4, "@@", "app/Orders.java");

    assertEquals(Tier.NORMAL, triage().tier(moved));
  }

  @Test
  void anUnnamedFileIsNormal() {
    assertEquals(Tier.NORMAL, triage().tier(file(null)));
  }

  @Test
  void theOrderIsManifestsThenHighRiskThenTheRestThenLowRisk() {
    var files =
        new ArrayList<>(
            List.of(
                file("README.md"),
                file("src/App.java"),
                file("src/auth/Login.java"),
                file("pom.xml")));

    files.sort(triage().byTier());

    assertEquals(
        List.of("pom.xml", "src/auth/Login.java", "src/App.java", "README.md"),
        files.stream().map(FileDiff::filename).toList());
  }

  @Test
  void noGlobsLeaveEveryFileButTheManifestsNormal() {
    var bare =
        new FileTriage(ReviewDiffFormatter.IgnoreGlobs.NONE, ReviewDiffFormatter.IgnoreGlobs.NONE);

    assertEquals(Tier.NORMAL, bare.tier(file("README.md")));
    assertEquals(Tier.NORMAL, bare.tier(file(".github/workflows/ci.yml")));
    assertEquals(Tier.BUILD_MANIFEST, bare.tier(file("pom.xml")));
  }
}
