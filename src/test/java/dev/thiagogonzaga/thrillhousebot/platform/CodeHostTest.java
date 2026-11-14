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
package dev.thiagogonzaga.thrillhousebot.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.platform.CodeHost.CompareResponse;
import dev.thiagogonzaga.thrillhousebot.platform.CodeHost.FileDiff;
import dev.thiagogonzaga.thrillhousebot.platform.CodeHost.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.platform.CodeHost.Ref;
import dev.thiagogonzaga.thrillhousebot.platform.CodeHost.TreeEntry;
import dev.thiagogonzaga.thrillhousebot.platform.CodeHost.TreeResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeHostTest {

  @Test
  void aMissingListReadsAsEmpty() {
    assertTrue(new CompareResponse(0, null).files().isEmpty());
    assertTrue(new TreeResponse("sha", null, false).tree().isEmpty());
  }

  @Test
  void theListsAreCopiedAndCannotBeChanged() {
    var files = new ArrayList<>(List.of(new FileDiff("A.java", "added", 1, 0, 1, "p")));
    var entries = new ArrayList<>(List.of(new TreeEntry("A.java", "blob", 1)));
    var comparison = new CompareResponse(1, files);
    var tree = new TreeResponse("sha", entries, false);
    files.clear();
    entries.clear();

    assertEquals(1, comparison.files().size());
    assertEquals(1, tree.tree().size());
    assertThrows(UnsupportedOperationException.class, () -> comparison.files().clear());
    assertThrows(UnsupportedOperationException.class, () -> tree.tree().clear());
  }

  @Test
  void theShortFormsLeaveTheUnknownFieldsAtTheirDefaults() {
    var pr = new PullRequestDetails("t", "b", new Ref("head"), new Ref("base", "main"));

    assertEquals(0, pr.changedFiles());
    assertEquals(0, pr.additions());
    assertEquals(0, pr.deletions());
    assertNull(pr.head().ref());
    assertEquals("main", pr.base().ref());
    assertNull(new FileDiff("A.java", "added", 1, 0, 1, "p").previousFilename());
  }
}
