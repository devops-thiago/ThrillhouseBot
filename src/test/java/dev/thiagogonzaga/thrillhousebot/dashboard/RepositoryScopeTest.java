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

import java.util.Set;
import org.junit.jupiter.api.Test;

class RepositoryScopeTest {

  @Test
  void matchesRepositoriesIgnoringCaseAndSurroundingSpace() {
    var scope = new RepositoryScope(false, Set.of("Acme/Allowed", "acme/allowed"));

    assertEquals(Set.of("acme/allowed"), scope.repositories());
    assertTrue(scope.allows(" ACME/allowed "));
    assertFalse(scope.allows("acme/secret"));
    assertFalse(scope.allows(null));
  }

  @Test
  void theEmptyScopeAllowsNothing() {
    assertFalse(RepositoryScope.NONE.accountOwner());
    assertTrue(RepositoryScope.NONE.repositories().isEmpty());
    assertFalse(RepositoryScope.NONE.allows("acme/allowed"));
  }

  @Test
  void theRepositorySetCannotBeChangedFromOutside() {
    var scope = new RepositoryScope(true, Set.of("acme/allowed"));

    assertThrows(UnsupportedOperationException.class, () -> scope.repositories().add("x/y"));
  }
}
