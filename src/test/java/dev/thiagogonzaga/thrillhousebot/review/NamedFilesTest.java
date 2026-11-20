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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NamedFilesTest {

  private static final List<String> CHANGED =
      List.of(
          "docs/DATABASE.md",
          "src/app/CreditRepository.java",
          "a/README.md",
          "b/README.md",
          "Makefile");

  @Test
  void aFileIsNamedByItsPathOrByAFileNameOnlyItHas() {
    assertEquals(
        List.of("docs/DATABASE.md"), NamedFiles.named("DATABASE.md lists 4 values", CHANGED));
    assertEquals(List.of("docs/DATABASE.md"), NamedFiles.named("see docs/DATABASE.md.", CHANGED));
    assertEquals(List.of("a/README.md"), NamedFiles.named("as a/README.md says", CHANGED));
  }

  @Test
  void aSharedOrBareNameOrANameRunIntoAnotherNamesNothing() {
    assertEquals(List.of(), NamedFiles.named("README.md is stale", CHANGED));
    assertEquals(List.of(), NamedFiles.named("the Makefile target", CHANGED));
    assertEquals(List.of(), NamedFiles.named("OLD_DATABASE.md and DATABASE.md.bak", CHANGED));
    assertEquals(List.of(), NamedFiles.named("  ", CHANGED));
  }

  private static ReviewResponse.Finding finding(String file, String title, String description) {
    return new ReviewResponse.Finding("medium", "high", file, 1, title, description, "", null);
  }

  @Test
  void onlyAnotherNamedFileThatChangedSinceTheRoundCounts() {
    var resolver =
        new DiffLineResolver(
            Map.of(),
            CHANGED,
            DiffLineResolver.HeadFiles.NONE,
            (round, path) ->
                path.equals("docs/DATABASE.md") ? FileChange.CHANGED : FileChange.UNCHANGED);

    assertTrue(
        NamedFiles.changedSince(
            0, finding("src/app/CreditRepository.java", "DATABASE.md disagrees", null), resolver));
    assertFalse(
        NamedFiles.changedSince(
            0, finding("src/app/CreditRepository.java", null, "CreditRepository.java"), resolver));
    assertFalse(
        NamedFiles.changedSince(
            0, finding("docs/DATABASE.md", "DATABASE.md is wrong", "about itself"), resolver));
  }
}
