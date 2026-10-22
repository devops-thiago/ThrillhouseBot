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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepositoryScopeTest {

  private final RepositoryScope scope =
      new RepositoryScope(false, 9L, Map.of(11L, "Acme/Allowed", 12L, "acme/other"));

  @Test
  void matchesRepositoryNamesIgnoringCaseAndSurroundingSpace() {
    assertTrue(scope.allows(" ACME/allowed "));
    assertEquals(Optional.of(new RepoScope(9L, 11L)), scope.scopeOf("acme/ALLOWED"));
    assertFalse(scope.allows("acme/secret"));
    assertFalse(scope.allows(null));
    assertTrue(scope.scopeOf("acme/secret").isEmpty());
  }

  @Test
  void aRowIsReadableOnlyUnderTheSameInstallationAndAReadableRepositoryId() {
    assertTrue(scope.allows(9L, 11L));
    assertFalse(scope.allows(8L, 11L), "the same repository id under another installation");
    assertFalse(scope.allows(9L, 13L), "a repository outside the scope");
    assertFalse(scope.allows(null, 11L), "a row the backfill could not match");
    assertFalse(scope.allows(9L, null));
    assertFalse(scope.isEmpty());
  }

  @Test
  void theEmptyScopeAllowsNothing() {
    assertTrue(RepositoryScope.NONE.isEmpty());
    assertTrue(RepositoryScope.NONE.installations().isEmpty());
    assertTrue(RepositoryScope.NONE.installationWideIds().isEmpty());
    assertFalse(RepositoryScope.NONE.allows("acme/allowed"));
    assertNull(RepositoryScope.NONE.nameOf(9L, 11L));
    var params = new HashMap<String, Object>();
    assertEquals("1 = 0", RepositoryScope.NONE.where("s", params), "never an empty IN");
    assertTrue(params.isEmpty());
  }

  @Test
  void anInstallationWithNoReadableRepositoryReadsNothing() {
    var memberOnly = new RepositoryScope(true, 9L, Map.of());
    assertTrue(memberOnly.isEmpty());
    assertEquals(Set.of(9L), memberOnly.installationWideIds());
    assertEquals("1 = 0", memberOnly.where(null, new HashMap<>()));
    assertTrue(new RepositoryScope(false, 9L, Map.of()).installations().isEmpty());
  }

  @Test
  void eachInstallationKeepsItsOwnRepositories() {
    var two =
        new RepositoryScope(
            Map.of(
                9L,
                new RepositoryScope.Installation(9L, "acme", true, Map.of(11L, "acme/app")),
                5L,
                new RepositoryScope.Installation(5L, "globex", false, Map.of(21L, "globex/app"))));

    assertTrue(two.allows(5L, 21L));
    assertFalse(two.allows(5L, 11L), "a repository id is read under its own installation only");
    assertEquals(List.of(5L, 9L), two.sortedInstallations().stream().map(i -> i.id()).toList());
    assertEquals(Set.of(9L), two.installationWideIds());
    assertEquals("globex/app", two.nameOf(5L, 21L));
    assertNull(two.nameOf(5L, 11L));

    var params = new HashMap<String, Object>();
    assertEquals(
        "((s.installationId = :scopeInstallation0 and s.repositoryId in :scopeRepositories0)"
            + " or (s.installationId = :scopeInstallation1 and s.repositoryId in"
            + " :scopeRepositories1))",
        two.where("s", params));
    assertEquals(5L, params.get("scopeInstallation0"));
    assertEquals(List.of(21L), params.get("scopeRepositories0"));
    assertEquals(9L, params.get("scopeInstallation1"));
    assertTrue(two.where(null, new HashMap<>()).startsWith("((installationId = "));
  }

  @Test
  void narrowingKeepsOneInstallationOrNothing() {
    var two =
        new RepositoryScope(
            Map.of(
                9L,
                new RepositoryScope.Installation(9L, "acme", true, Map.of(11L, "shared/name")),
                5L,
                new RepositoryScope.Installation(5L, "globex", false, Map.of(21L, "Shared/Name"))));

    assertSame(two, two.only(null));
    assertEquals(Set.of(9L), two.only(9L).installations().keySet());
    assertSame(RepositoryScope.NONE, two.only(77L));
    assertEquals(
        Optional.of(new RepoScope(5L, 21L)),
        two.scopeOf("shared/name"),
        "the same name in two installations resolves to the lowest installation id");
    assertEquals(Optional.of(new RepoScope(9L, 11L)), two.only(9L).scopeOf("shared/name"));
  }

  @Test
  void theRepositoriesCannotBeChangedFromOutside() {
    var source = new HashMap<Long, String>();
    source.put(11L, "acme/allowed");
    var copied = new RepositoryScope(true, 9L, source);
    source.put(12L, "acme/secret");

    assertFalse(copied.allows("acme/secret"));
    var repositories = copied.installations().get(9L).repositories();
    assertThrows(UnsupportedOperationException.class, () -> repositories.put(13L, "x/y"));
    var installations = copied.installations();
    assertThrows(UnsupportedOperationException.class, () -> installations.remove(9L));
  }
}
