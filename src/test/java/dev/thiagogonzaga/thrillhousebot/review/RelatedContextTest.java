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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelatedContextTest {

  private static final String PATH = "src/app/util/InputSanitizer.java";

  private static final RelatedContext.RelatedFile FILE =
      new RelatedContext.RelatedFile(
          PATH,
          "sanitizer-or-validator",
          List.of("src/app/web/Handler.java"),
          120,
          List.of(
              new RelatedContext.Region(3, List.of("public final class InputSanitizer {", "")),
              new RelatedContext.Region(
                  40, List.of("  String clean(String raw) {", "    return raw.strip();", "  }"))));

  private static ReviewResponse.Finding finding(String title, String description) {
    return new ReviewResponse.Finding(
        "high", "medium", "src/app/web/Handler.java", 12, title, description, null, null);
  }

  @Test
  void theSectionNamesEachFileItsReasonAndItsNumberedLines() {
    var section = new RelatedContext(List.of(FILE, FILE), 1).section();

    assertTrue(
        section.startsWith(
            "File: "
                + PATH
                + " (sanitizer-or-validator; referenced by src/app/web/Handler.java; 120 lines in"
                + " all)\nLines 3-4:\n3 | public final class InputSanitizer {\n4 | \nLines 40-42:\n"
                + "40 |   String clean(String raw) {"),
        section);
    assertTrue(section.contains("  }\n\nFile: "), section);
    assertEquals("", RelatedContext.NONE.section());
    assertTrue(RelatedContext.NONE.isEmpty());
  }

  @Test
  void aFindingNamingTheFileCarriesItsExcerptWithTheMatchingRegionFirst() {
    var note =
        new RelatedContext(List.of(FILE), 1)
            .noteFor(
                finding(
                    "Path is built from an unvalidated id",
                    "`InputSanitizer.clean` only calls strip, so `..` survives."));

    assertNotNull(note);
    assertTrue(note.startsWith("Related context this review supplied: `" + PATH + "`"), note);
    assertTrue(note.contains("(sanitizer-or-validator)"), note);
    assertTrue(note.indexOf("Lines 40-42:") < note.indexOf("Lines 3-4:"), note);
  }

  @Test
  void aFindingNamingNoRelatedFileCarriesNothing() {
    var context = new RelatedContext(List.of(FILE), 1);

    assertNull(context.noteFor(finding("Null check missing", "The sanitizer is not called.")));
    assertNull(context.noteFor(null));
    assertNull(RelatedContext.NONE.noteFor(finding("t", PATH)));
  }

  @Test
  void aFileIsNamedByPathFileNameOrADistinctiveStemAsAWholeWord() {
    assertTrue(RelatedContext.names("see " + PATH + " line 4", PATH));
    assertTrue(RelatedContext.names("see InputSanitizer.java", PATH));
    assertTrue(RelatedContext.names("InputSanitizer strips only", PATH));
    assertFalse(RelatedContext.names("MyInputSanitizerImpl strips only", PATH));
    assertTrue(RelatedContext.names("the rate_limiter module", "app/rate_limiter.py"));
    assertFalse(RelatedContext.names("some utils are used", "app/utils.py"));
    assertTrue(RelatedContext.names("in utils.py the helper", "app/utils.py"));
    assertFalse(RelatedContext.names("Api is called", "src/Api.java"));
  }

  @Test
  void anExcerptLongerThanTheNoteAllowsIsCut() {
    var longRegion =
        new RelatedContext.Region(1, List.of("x".repeat(RelatedContext.MAX_NOTE_EXCERPT_CHARS)));
    var file = new RelatedContext.RelatedFile(PATH, "interface", List.of(), 1, List.of(longRegion));

    var note = new RelatedContext(List.of(file), 1).noteFor(finding("t", PATH));

    assertTrue(note.endsWith("…"), note);
    assertTrue(note.length() < RelatedContext.MAX_NOTE_EXCERPT_CHARS + 400, note);
    assertTrue(file.heading().startsWith("File: " + PATH + " (interface; 1 lines"), file.heading());
  }
}
