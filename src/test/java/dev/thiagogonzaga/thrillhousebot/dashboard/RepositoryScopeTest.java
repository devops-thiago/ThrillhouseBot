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
    assertEquals(List.of(11L, 12L), scope.repositoryIds().stream().sorted().toList());
  }

  @Test
  void theEmptyScopeAllowsNothing() {
    assertFalse(RepositoryScope.NONE.accountOwner());
    assertTrue(RepositoryScope.NONE.repositories().isEmpty());
    assertFalse(RepositoryScope.NONE.allows("acme/allowed"));
    assertTrue(RepositoryScope.NONE.repositoryIds().isEmpty());
  }

  @Test
  void theRepositoriesCannotBeChangedFromOutside() {
    var source = new HashMap<Long, String>();
    source.put(11L, "acme/allowed");
    var copied = new RepositoryScope(true, 9L, source);
    source.put(12L, "acme/secret");

    assertFalse(copied.allows("acme/secret"));
    var repositories = copied.repositories();
    assertThrows(UnsupportedOperationException.class, () -> repositories.put(13L, "x/y"));
  }
}
