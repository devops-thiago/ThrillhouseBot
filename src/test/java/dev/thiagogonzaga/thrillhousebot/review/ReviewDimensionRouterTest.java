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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDimensionRouter.FileKind;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewDimension;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReviewDimensionRouterTest {

  private static final String PLAIN_LOGIC =
      "@@ -1 +1 @@\n-int a = b + c;\n+int a = Math.max(b, c);";
  private static final String API_CALL =
      "@@ -1 +1 @@\n+var r = client.listComments(owner, repo, n);";
  private static final String STUB =
      "@@ -1 +1 @@\n+when(dispatcher.dispatch(any())).thenReturn(false);";

  private static FileDiff file(String path, String patch) {
    return new FileDiff(path, "modified", 1, 1, 2, patch);
  }

  private static Set<ReviewDimension> routed(FileDiff... files) {
    return ReviewDimensionRouter.routeFiles(List.of(files)).dimensions();
  }

  private static Set<ReviewDimension> alwaysOnPlus(ReviewDimension... routed) {
    var set = EnumSet.of(ReviewDimension.FUNCTIONAL_CORRECTNESS);
    set.add(ReviewDimension.SECURITY);
    set.add(ReviewDimension.REGRESSIONS);
    set.addAll(List.of(routed));
    return set;
  }

  @ParameterizedTest
  @CsvSource({
    "README.md, DOCUMENTATION",
    "docs/guide.adoc, DOCUMENTATION",
    "LICENSE, DOCUMENTATION",
    "CHANGELOG, DOCUMENTATION",
    "Dockerfile, CONFIGURATION",
    "deploy/Dockerfile.prod, CONFIGURATION",
    "images/api.containerfile, CONFIGURATION",
    "Dockerfile.md, DOCUMENTATION",
    "Containerfile.kt, SOURCE",
    ".github/workflows/ci.yml, CONFIGURATION",
    "charts/app/templates/_helpers.tpl, CONFIGURATION",
    "infra/main.tf, CONFIGURATION",
    "pom.xml, CONFIGURATION",
    "build.gradle.kts, CONFIGURATION",
    "src/main/resources/application.properties, CONFIGURATION",
    ".env.example, CONFIGURATION",
    ".gitignore, CONFIGURATION",
    ".eslintrc.json, CONFIGURATION",
    ".prettierrc.js, SOURCE",
    "docker-compose.override.yml, CONFIGURATION",
    "Makefile, CONFIGURATION",
    "requirements-dev.txt, CONFIGURATION",
    "CMakeLists.txt, CONFIGURATION",
    "go.mod, CONFIGURATION",
    "scripts/release.sh, SCRIPT",
    "tools/setup.ps1, SCRIPT",
    "src/main/java/A.java, SOURCE",
    "web/src/App.tsx, SOURCE",
    "db/migration/V1__init.sql, SOURCE",
    "assets/logo.png, UNKNOWN",
    "bin/tool, UNKNOWN",
    "'', UNKNOWN",
  })
  void classifiesFilesByNameAndExtension(String path, FileKind expected) {
    assertEquals(expected, ReviewDimensionRouter.kindOf(path));
  }

  @Test
  void aNullPathIsUnknown() {
    assertEquals(FileKind.UNKNOWN, ReviewDimensionRouter.kindOf(null));
  }

  @Test
  void aDocumentationBatchCarriesOnlyTheDocumentationDimensions() {
    assertEquals(
        alwaysOnPlus(
            ReviewDimension.COMMENT_CONTRADICTS_CODE, ReviewDimension.CONFIG_KEY_DOCUMENTATION),
        routed(file("README.md", "@@ -1 +1 @@\n+| `X` | uses the api client | `1` |")));
  }

  @Test
  void aConfigurationBatchCarriesConfigIacAndTheProbedDimensionsItsPatchShows() {
    assertEquals(
        alwaysOnPlus(
            ReviewDimension.COMMENT_CONTRADICTS_CODE,
            ReviewDimension.CONFIG_IAC,
            ReviewDimension.CONFIG_KEY_DOCUMENTATION),
        routed(file("Dockerfile", "@@ -1 +1 @@\n+FROM eclipse-temurin:21-jre")));
    assertTrue(
        routed(file(".github/workflows/ci.yml", "@@ -1 +1 @@\n+  run: gh api /repos/o/r/issues"))
            .contains(ReviewDimension.PAGINATION),
        "a workflow step calling a REST list endpoint must bring pagination in");
  }

  @Test
  void aSourceBatchWithoutApiOrMockCodeLeavesThoseDimensionsOut() {
    assertEquals(
        alwaysOnPlus(
            ReviewDimension.COMMENT_CONTRADICTS_CODE,
            ReviewDimension.CODE_QUALITY_AND_COMPLEXITY,
            ReviewDimension.PRODUCER_CONSUMER),
        routed(file("src/main/java/A.java", PLAIN_LOGIC)));
  }

  @Test
  void probesBringPaginationAndMockFidelityInFromThePatch() {
    assertTrue(routed(file("src/main/java/A.java", API_CALL)).contains(ReviewDimension.PAGINATION));
    assertTrue(
        routed(file("src/main/java/A.java", "@@ -1 +1 @@\n+for (var f : listFiles(dir)) {}"))
            .contains(ReviewDimension.PAGINATION),
        "a call to something named list… must count as a collection fetch");
    assertTrue(
        routed(file("src/main/java/Support.java", STUB)).contains(ReviewDimension.MOCK_FIDELITY),
        "a stub outside a test path must still bring mock fidelity in");
  }

  @Test
  void aTestFileCarriesMockFidelityAndATestFixtureKeepsItsOwnKind() {
    var routing =
        ReviewDimensionRouter.routeFiles(List.of(file("src/test/java/ATest.java", PLAIN_LOGIC)));
    assertTrue(routing.dimensions().contains(ReviewDimension.MOCK_FIDELITY));
    assertTrue(
        routing.describe().contains("8:mock-fidelity (src/test/java/ATest.java: test file)"));
    // A manifest under a test path is both: the fixture kind must not cost it the IaC block.
    assertTrue(
        routed(file("src/test/resources/k8s/deployment.yaml", "@@ -1 +1 @@\n+kind: Deployment"))
            .containsAll(List.of(ReviewDimension.CONFIG_IAC, ReviewDimension.MOCK_FIDELITY)));
  }

  @Test
  void aScriptCarriesConfigIacForTheArtifactsItsInstructionsName() {
    assertTrue(
        routed(file("scripts/package.sh", "@@ -1 +1 @@\n+cp target/app.jar dist/"))
            .containsAll(
                List.of(ReviewDimension.CONFIG_IAC, ReviewDimension.CODE_QUALITY_AND_COMPLEXITY)));
  }

  @Test
  void anUnrecognizedFileOrMissingPatchResolvesToInclusion() {
    assertEquals(ReviewDimension.ALL, routed(file("assets/blob.bin", "@@ -1 +1 @@\n+x")));
    var noPatch = routed(file("src/main/java/A.java", null));
    assertTrue(noPatch.contains(ReviewDimension.PAGINATION));
    assertTrue(noPatch.contains(ReviewDimension.MOCK_FIDELITY));
    assertTrue(routed(file("src/main/java/A.java", " ")).contains(ReviewDimension.PAGINATION));
  }

  @Test
  void noFilesMeansEveryDimension() {
    assertEquals(ReviewDimension.ALL, ReviewDimensionRouter.routeFiles(List.of()).dimensions());
    assertEquals(ReviewDimension.ALL, ReviewDimensionRouter.routeFiles(null).dimensions());
    assertEquals(ReviewDimension.ALL, ReviewDimensionRouter.dimensionsForPaths(List.of()));
    assertEquals(ReviewDimension.ALL, ReviewDimensionRouter.dimensionsForPaths(null));
  }

  @Test
  void aMixedBatchGetsTheUnionOfItsFiles() {
    assertEquals(
        ReviewDimension.ALL,
        routed(
            file("src/main/java/A.java", API_CALL),
            file("src/test/java/ATest.java", STUB),
            file("Dockerfile", "@@ -1 +1 @@\n+FROM x"),
            file("README.md", "@@ -1 +1 @@\n+x")));
  }

  @Test
  void theAlwaysOnDimensionsAreInEveryRouting() {
    for (var path : List.of("README.md", "Dockerfile", "src/main/java/A.java", "x.bin")) {
      assertTrue(
          routed(file(path, PLAIN_LOGIC))
              .containsAll(
                  List.of(
                      ReviewDimension.FUNCTIONAL_CORRECTNESS,
                      ReviewDimension.SECURITY,
                      ReviewDimension.REGRESSIONS)),
          path);
    }
  }

  @Test
  void routingASubsetOfFilesNeverAddsADimensionTheWholeSetLacks() {
    // The planner sizes every batch's overhead from the whole pull request's prompt; that is only
    // safe while a batch's routing is contained in the pull request's.
    var pool =
        List.of(
            file("README.md", "@@ -1 +1 @@\n+x"),
            file("Dockerfile", "@@ -1 +1 @@\n+FROM x"),
            file("src/main/java/A.java", PLAIN_LOGIC),
            file("src/main/java/B.java", API_CALL),
            file("src/test/java/BTest.java", STUB),
            file("scripts/run.sh", PLAIN_LOGIC),
            file("docs/config.md", "@@ -1 +1 @@\n+`X_KEY`"),
            file("assets/blob.bin", "@@ -1 +1 @@\n+x"));
    var random = new Random(665);
    for (int round = 0; round < 500; round++) {
      var whole = new ArrayList<FileDiff>();
      for (var candidate : pool) {
        if (random.nextBoolean()) {
          whole.add(candidate);
        }
      }
      var subset = new ArrayList<FileDiff>();
      for (var candidate : whole) {
        if (random.nextBoolean()) {
          subset.add(candidate);
        }
      }
      if (subset.isEmpty()) {
        continue;
      }
      var wholeRouting = ReviewDimensionRouter.routeFiles(whole).dimensions();
      assertTrue(
          wholeRouting.containsAll(ReviewDimensionRouter.routeFiles(subset).dimensions()),
          "subset " + subset + " of " + whole);
    }
  }

  @Test
  void pathOnlyRoutingNeverIncludesLessThanPatchRouting() {
    for (var path :
        List.of("README.md", "Dockerfile", "src/main/java/A.java", "src/test/java/ATest.java")) {
      for (var patch : List.of(PLAIN_LOGIC, API_CALL, STUB)) {
        assertTrue(
            ReviewDimensionRouter.dimensionsForPaths(List.of(path))
                .containsAll(routed(file(path, patch))),
            path + " with " + patch);
      }
    }
    var nullPath = new ArrayList<String>();
    nullPath.add(null);
    assertEquals(ReviewDimension.ALL, ReviewDimensionRouter.dimensionsForPaths(nullPath));
  }

  @Test
  void describeNamesTheFileBehindEachIncludedDimensionAndWhatWasLeftOut() {
    var described =
        ReviewDimensionRouter.routeFiles(List.of(file("README.md", "@@ -1 +1 @@\n+x"))).describe();
    assertEquals(
        "included 4:comment-contradicts-code (README.md: documentation),"
            + " 10:config-key-documentation (README.md: documentation); left out"
            + " 5:code-quality-and-complexity, 6:pagination, 7:config-iac, 8:mock-fidelity,"
            + " 9:producer-consumer",
        described);
    assertTrue(
        ReviewDimensionRouter.routeFiles(List.of(file("x.bin", "+x")))
            .describe()
            .endsWith("; left out nothing"));
    var onlyAlwaysOn =
        new ReviewDimensionRouter.Routing(java.util.Map.of(ReviewDimension.SECURITY, "always on"));
    assertTrue(onlyAlwaysOn.describe().startsWith("included no routed dimension;"));
  }

  @Test
  void describeKeepsAnAuthorControlledPathOnOneLine() {
    var described =
        ReviewDimensionRouter.routeFiles(List.of(file("docs/evil\nINFO forged.md", "+x")))
            .describe();
    assertFalse(described.contains("\n"), described);
    var unnamed = ReviewDimensionRouter.routeFiles(List.of(file(null, "+x"))).describe();
    assertTrue(unnamed.contains("(unnamed file)"), unnamed);
  }

  @Test
  void theSystemPromptFollowsTheSwitch() {
    var files = List.of(file("README.md", "@@ -1 +1 @@\n+x"));
    assertSame(PrReviewPrompts.SYSTEM, ReviewDimensionRouter.disabled().systemPromptFor(files));
    assertFalse(ReviewDimensionRouter.disabled().enabled());

    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
    when(review.dimensionRoutingEnabled()).thenReturn(true);
    var router = new ReviewDimensionRouter(config);
    assertTrue(router.enabled());
    assertEquals(
        PrReviewPrompts.reviewSystemPrompt(ReviewDimensionRouter.routeFiles(files).dimensions()),
        router.systemPromptFor(files));
  }
}
