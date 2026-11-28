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

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Fuzzes {@link SourceLineCode#of(String)}, which strips comments and string-literal text from a
 * source line of a pull request in any language. The line is whatever a contributor committed, so
 * the scanner must finish on any string without throwing, and it only ever removes text: what it
 * returns is never longer than the line, and a blank line has no code.
 */
class SourceLineCodeFuzzTest {

  @FuzzTest
  void readsAnyLine(FuzzedDataProvider data) {
    var line = data.consumeRemainingAsString();

    var code = SourceLineCode.of(line);

    assertThat(code).hasSizeLessThanOrEqualTo(line.length());
    if (line.isBlank()) {
      assertThat(code).isEmpty();
    }
  }
}
