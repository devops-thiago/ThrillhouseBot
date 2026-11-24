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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.kohsuke.github.GHPermissionType;

/** Who may act through the bot (#1221): write, maintain and admin may; triage and read may not. */
class RepositoryPermissionsTest {

  @org.junit.jupiter.api.Test
  void adminAndWriteAct() {
    assertTrue(RepositoryPermissions.canAct(GHPermissionType.ADMIN));
    assertTrue(RepositoryPermissions.canAct(GHPermissionType.WRITE));
  }

  @org.junit.jupiter.api.Test
  void readAndNoneDoNotAct() {
    assertFalse(RepositoryPermissions.canAct(GHPermissionType.READ));
    assertFalse(RepositoryPermissions.canAct(GHPermissionType.NONE));
    assertFalse(RepositoryPermissions.canAct(GHPermissionType.UNKNOWN));
    assertFalse(RepositoryPermissions.canAct(null));
  }

  @org.junit.jupiter.api.Test
  void noAnswerGrantsNothing() {
    assertFalse(RepositoryPermissions.canAct(null));
  }
}
