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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ModelRouter}: which pull requests count as low risk (#43). */
class ModelRouterTest {

  private static final List<String> LOW_RISK_PATHS = List.of("**/*.md", "docs/", "**/*.lock");
  private static final List<String> HIGH_RISK_PATHS = List.of(".github/workflows/", "**/auth/");

  private static ModelRouter router(Optional<String> lowRiskModel, int maxChangedLines) {
    var config = mock(ThrillhouseConfig.class);
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var routing = mock(ThrillhouseConfig.AiPricingConfig.RoutingConfig.class);
    when(config.ai()).thenReturn(ai);
    when(ai.routing()).thenReturn(routing);
    when(routing.lowRiskModel()).thenReturn(lowRiskModel);
    when(routing.lowRiskMaxChangedLines()).thenReturn(maxChangedLines);
    when(routing.lowRiskPaths()).thenReturn(LOW_RISK_PATHS);
    when(routing.highRiskPaths()).thenReturn(HIGH_RISK_PATHS);
    return new ModelRouter(config);
  }

  private static ModelRouter router() {
    return router(Optional.of("cheap"), 50);
  }

  private static FileDiff file(String name, int additions, int deletions) {
    return new FileDiff(name, "modified", additions, deletions, additions + deletions, "@@");
  }

  @Test
  void routesNothingWhileNoLowRiskModelIsSet() {
    var files = List.of(file("README.md", 1, 0));

    assertEquals(Optional.empty(), router(Optional.empty(), 50).route(files));
    assertEquals(Optional.empty(), router(Optional.of("  "), 50).route(files));
  }

  @Test
  void routesNothingWhenNoFileIsReviewed() {
    assertEquals(Optional.empty(), router().route(List.of()));
  }

  @Test
  void aDocsOnlyChangeIsLowRiskWhateverItsSize() {
    var files = List.of(file("README.md", 900, 100), file("docs/guide/setup.txt", 300, 0));

    assertEquals(Optional.of("cheap"), router().route(files));
  }

  @Test
  void aSmallChangeIsLowRisk() {
    var files = List.of(file("src/Main.java", 30, 10), file("README.md", 10, 0));

    assertEquals(Optional.of("cheap"), router().route(files));
  }

  @Test
  void aLargeCodeChangeIsNot() {
    var files = List.of(file("src/Main.java", 40, 11));

    assertEquals(Optional.empty(), router().route(files));
  }

  @Test
  void aSmallChangeTouchingAHighRiskPathIsNot() {
    assertEquals(Optional.empty(), router().route(List.of(file(".github/workflows/ci.yml", 1, 1))));
    assertEquals(Optional.empty(), router().route(List.of(file("src/auth/Login.java", 2, 0))));
  }

  @Test
  void aHighRiskPathWinsOverAnOverlappingLowRiskGlob() {
    // A doc under a directory the operator listed as high risk matches both lists; the doubt
    // resolves to the stronger model, whatever the size.
    assertEquals(
        Optional.empty(), router().route(List.of(file(".github/workflows/README.md", 10, 0))));
    assertEquals(
        Optional.empty(),
        router().route(List.of(file("README.md", 1, 0), file("src/auth/NOTES.md", 1, 0))));
  }

  @Test
  void aFileRenamedOutOfAHighRiskPathIsStillHighRisk() {
    var renamed =
        new FileDiff("src/login/Login.java", "renamed", 1, 0, 1, "@@", "src/auth/Login.java");

    assertEquals(Optional.empty(), router().route(List.of(renamed)));
  }

  @Test
  void aSmallFileRenamedFromAnOrdinaryPathIsLowRisk() {
    var renamed = new FileDiff("src/login/Login.java", "renamed", 1, 0, 1, "@@", "src/Login.java");

    assertEquals(Optional.of("cheap"), router().route(List.of(renamed)));
  }

  @Test
  void aZeroLineThresholdRoutesByPathsAlone() {
    var zero = router(Optional.of("cheap"), 0);

    assertEquals(Optional.empty(), zero.route(List.of(file("src/Main.java", 0, 0))));
    assertEquals(Optional.of("cheap"), zero.route(List.of(file("CHANGELOG.md", 5, 0))));
  }
}
