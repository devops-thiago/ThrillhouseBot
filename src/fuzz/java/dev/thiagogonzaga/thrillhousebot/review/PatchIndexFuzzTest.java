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
 * Fuzzes {@link PatchIndex#parse(String)}, the one unified-diff parser every patch reader shares,
 * and {@link PatchLines}, the right-side view built on it. A patch is text from the GitHub API and
 * from the formatted review diff, so the parser must accept any string: it may read no line at all,
 * but it must not throw, and every line it does read must point back into the input.
 */
class PatchIndexFuzzTest {

  @FuzzTest
  void parsesAnyPatch(FuzzedDataProvider data) {
    var patch = data.consumeRemainingAsString();

    var index = PatchIndex.parse(patch);

    var raw = patch.split("\n", -1);
    assertThat(index.lastHunk()).isBetween(-1, raw.length - 1);
    assertThat(index.lines()).hasSizeLessThanOrEqualTo(raw.length);
    assertThat(index.hunks()).hasSize(index.lastHunk() + 1);
    var previous = -1;
    var previousHunk = 0;
    for (var line : index.lines()) {
      assertThat(line.position()).isGreaterThan(previous).isLessThan(raw.length);
      assertThat(line.hunk()).isBetween(previousHunk, index.lastHunk());
      assertThat(raw[line.position()]).isEqualTo(line.marker() + line.text());
      previous = line.position();
      previousHunk = line.hunk();
    }
    for (var hunk : index.hunks()) {
      assertNumbersAdvance(hunk);
    }

    var rightSide = PatchLines.parse(patch);
    assertThat(rightSide.lastHunk()).isEqualTo(index.lastHunk());
    assertThat(rightSide.lines()).hasSameSizeAs(index.rightSide());
  }

  /** Within a hunk each side's line number grows by one per line on that side. */
  private static void assertNumbersAdvance(Iterable<PatchIndex.Line> hunk) {
    PatchIndex.Line previous = null;
    for (var line : hunk) {
      if (previous != null) {
        assertThat(line.leftLine()).isEqualTo(previous.leftLine() + (previous.onLeft() ? 1 : 0));
        assertThat(line.rightLine()).isEqualTo(previous.rightLine() + (previous.onRight() ? 1 : 0));
      }
      previous = line;
    }
  }
}
