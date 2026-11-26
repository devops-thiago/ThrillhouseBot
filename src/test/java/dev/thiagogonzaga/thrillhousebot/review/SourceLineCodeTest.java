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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SourceLineCodeTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '~',
      value = {
        "~~|~~",
        "~   ~|~~",
        "x--|x--",
        "a #[x] #{y}|a #[x] #{y}",
        "a --b|a --b",
        "a ---b|a ---b",
        "a -|a -",
        "\"{x}\"|~  ~",
        "F\"{x}\"|~F x  ~",
        "rrrr\"{x}\"|~rrrr  ~",
        "a -- b|~a ~",
        "x = 1 --|~x = 1 ~",
        "v = 'a' + b|~v =    + b~",
        "f(\"${g(h[0])}\")|~f( g(h[0])  )~",
        "v = 'it|~v = 'it~"
      })
  void shouldKeepOnlyTheCodeOfALine(String line, String code) {
    assertEquals(code, SourceLineCode.of(line));
  }
}
