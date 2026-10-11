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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #1031: the path index returns exactly the items {@link FilePaths#same} matches. */
class PathIndexTest {

  private static final List<String> PATHS =
      List.of(
          "Dockerfile",
          "svc/Dockerfile",
          "a/svc/Dockerfile",
          "b/svc/Dockerfile",
          "src/main/App.java",
          "main/App.java",
          "App.java",
          "x/src/main/App.java",
          "other/App.java");

  @Test
  void aLookupFindsExactlyTheSameFileInInsertionOrder() {
    var index = PathIndex.of(PATHS, p -> p, p -> "k");
    for (var query :
        List.of(
            "Dockerfile",
            "svc/Dockerfile",
            "a/svc/Dockerfile",
            "z/a/svc/Dockerfile",
            "App.java",
            "main/App.java",
            "x/src/main/App.java",
            "nowhere/else.txt")) {
      var expected = new ArrayList<String>();
      for (var path : PATHS) {
        if (FilePaths.same(path, query)) {
          expected.add(path);
        }
      }
      assertEquals(expected, index.candidates(query, "k"), query);
    }
  }

  @Test
  void theKeyAndANullPathNarrowTheLookup() {
    var index =
        PathIndex.of(
            Arrays.asList("svc/Dockerfile", null, "svc/Dockerfile"),
            p -> p,
            p -> p == null ? "none" : "k");
    assertEquals(2, index.candidates("svc/Dockerfile", "k").size());
    assertTrue(index.candidates("svc/Dockerfile", "other").isEmpty());
    assertTrue(index.candidates(null, "k").isEmpty());
  }
}
