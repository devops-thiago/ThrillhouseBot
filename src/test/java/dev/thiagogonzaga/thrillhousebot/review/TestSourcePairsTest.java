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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TestSourcePairsTest {

  @ParameterizedTest
  @CsvSource({
    "src/test/java/a/FooTest.java, src/main/java/a/Foo.java",
    "src/test/java/a/FooTests.java, src/main/java/a/Foo.java",
    "src/test/java/a/FooIT.java, src/main/java/a/Foo.java",
    "src/test/kotlin/a/FooSpec.kt, src/main/java/a/Foo.java",
    "src/foo.test.ts, src/foo.tsx",
    "src/foo.spec.js, src/foo.js",
    "src/__tests__/foo.js, src/foo.js",
    "pkg/foo_test.go, pkg/foo.go",
    "tests/test_foo.py, app/foo.py",
    "tests/helpers/Makefile, build/Makefile"
  })
  void aTestIsLinkedToTheSourceItNames(String test, String source) {
    assertEquals(Map.of(0, 1), TestSourcePairs.link(List.of(test, source)));
  }

  @ParameterizedTest
  @CsvSource({
    "src/foo.test.ts, config/foo.yaml",
    "src/test/java/a/FooTest.java, src/main/java/a/Bar.java",
    "src/test/java/a/FooTest.java, src/test/java/a/Foo.java"
  })
  void anotherLanguageNameOrTestFileIsNotASource(String test, String other) {
    assertEquals(Map.of(), TestSourcePairs.link(List.of(test, other)));
  }

  @Test
  void theNearestSourceOfTheSameNameWins() {
    var files =
        List.of(
            "two/src/main/java/a/Foo.java",
            "one/src/main/java/b/Foo.java",
            "one/src/main/java/a/Foo.java",
            "one/src/test/java/a/FooTest.java");

    assertEquals(Map.of(3, 2), TestSourcePairs.link(files));
  }

  @Test
  void anUnnamedFileIsNeitherATestNorASource() {
    assertEquals(Map.of(), TestSourcePairs.link(Arrays.asList(null, "src/test/java/FooTest.java")));
  }
}
