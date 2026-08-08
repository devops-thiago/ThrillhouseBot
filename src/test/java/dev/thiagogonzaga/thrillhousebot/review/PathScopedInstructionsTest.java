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

import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PathScopedInstructions} — the resolution step that decides which of a
 * repository's declared scopes govern which files of the PR under review (#33).
 */
class PathScopedInstructionsTest {

  private static final String YML = ".github/thrillhousebot.yml";

  private static RepoSettings settings(RepoSettings.PathInstructions... scopes) {
    return new RepoSettings(List.of(), List.of(scopes), YML);
  }

  private static RepoSettings.PathInstructions scope(String path, String instructions) {
    return new RepoSettings.PathInstructions(path, instructions);
  }

  @Test
  void appliesAScopeOnlyToTheFilesUnderItsPath() {
    var resolved =
        PathScopedInstructions.resolve(
            settings(scope("payments/**", "Money is in integer cents.")),
            List.of("payments/api/Charge.java", "web/Landing.tsx"));

    assertEquals(1, resolved.scopes().size());
    var applied = resolved.scopes().get(0);
    assertEquals("payments/**", applied.glob());
    assertEquals(List.of("payments/api/Charge.java"), applied.files());
    assertEquals(YML, resolved.source());
  }

  @Test
  void dropsAScopeThatMatchesNothingInThisPullRequest() {
    var resolved =
        PathScopedInstructions.resolve(
            settings(scope("payments/**", "Money is in integer cents.")),
            List.of("web/Landing.tsx"));

    assertSame(PathScopedInstructions.NONE, resolved);
    assertTrue(resolved.isEmpty());
  }

  @Test
  void keepsEveryMatchingScopeSoOverlappingRulesBothApply() {
    var resolved =
        PathScopedInstructions.resolve(
            settings(
                scope("payments/**", "Be strict."),
                scope("**/*.java", "Java conventions."),
                scope("docs/**", "Docs only.")),
            List.of("payments/api/Charge.java"));

    assertEquals(
        List.of("payments/**", "**/*.java"),
        resolved.scopes().stream().map(PathScopedInstructions.AppliedScope::glob).toList());
  }

  @Test
  void matchesTheSameWayTheIgnoreGlobsDoIncludingTheDoubleStarPrefix() {
    // `**/generated/**` must hit a nested path the same way it does in the ignore list, so a
    // maintainer's glob means one thing across the whole config file.
    var resolved =
        PathScopedInstructions.resolve(
            settings(scope("**/generated/**", "Relaxed.")),
            List.of("src/main/generated/Api.java", "src/main/Api.java"));

    assertEquals(List.of("src/main/generated/Api.java"), resolved.scopes().get(0).files());
  }

  @Test
  void aRepositoryDeclaringNoScopesResolvesToNone() {
    assertSame(
        PathScopedInstructions.NONE,
        PathScopedInstructions.resolve(RepoSettings.EMPTY, List.of("src/App.java")));
    assertSame(
        PathScopedInstructions.NONE, PathScopedInstructions.resolve(null, List.of("src/App.java")));
  }

  @Test
  void noReviewableFilesResolvesToNone() {
    var declared = settings(scope("payments/**", "Be strict."));

    assertSame(PathScopedInstructions.NONE, PathScopedInstructions.resolve(declared, List.of()));
    assertSame(PathScopedInstructions.NONE, PathScopedInstructions.resolve(declared, null));
  }

  @Test
  void anUncompilableGlobIsDroppedWithoutLosingTheOtherScopes() {
    var resolved =
        PathScopedInstructions.resolve(
            settings(scope("payments/[", "Broken glob."), scope("api/**", "Kept.")),
            List.of("payments/Charge.java", "api/Route.java"));

    assertEquals(1, resolved.scopes().size());
    assertEquals("api/**", resolved.scopes().get(0).glob());
    assertFalse(resolved.isEmpty());
  }
}
