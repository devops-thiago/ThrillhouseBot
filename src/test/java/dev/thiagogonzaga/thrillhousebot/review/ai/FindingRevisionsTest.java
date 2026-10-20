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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #1080: the verifier's judgment of a kept finding's suggestion and side claims, applied. */
class FindingRevisionsTest {

  /** scala ThrillhouseBot-test#248: the suggested loop never runs. */
  private static final String BROKEN_LOOP =
      """
        def transferBags(flight: String): Seq[ManifestBag] = {
          val builder = scala.collection.mutable.ListBuffer.empty[ManifestBag]
          var cursor: Option[String] = None
          while (cursor.isDefined) {
            val page = api.fetchPage(flight, cursor)
            builder ++= page.bags
            cursor = page.nextCursor
          }
          builder.toSeq
        }""";

  private static final String FIXED_LOOP =
      """
        def transferBags(flight: String): Seq[ManifestBag] = {
          val builder = scala.collection.mutable.ListBuffer.empty[ManifestBag]
          var page = api.fetchPage(flight, None)
          builder ++= page.bags
          while (page.nextCursor.isDefined) {
            page = api.fetchPage(flight, page.nextCursor)
            builder ++= page.bags
          }
          builder.toSeq
        }""";

  private static final String OLD =
      """
        def transferBags(flight: String): Seq[ManifestBag] =
          api.fetchPage(flight, None).bags""";

  private static ReviewResponse.Finding finding(String description, String suggestionNew) {
    return new ReviewResponse.Finding(
        "high",
        "high",
        "scala/src/main/scala/baggage/ManifestClient.scala",
        47,
        "transferBags reads only the first manifest page",
        description,
        OLD,
        suggestionNew);
  }

  private static VerificationResponse.Verdict verdict(
      String suggestion, String suggestionNew, List<String> cut) {
    return new VerificationResponse.Verdict(
        1, "confirmed", "high", "high", "the loop never runs", suggestion, suggestionNew, cut);
  }

  @Test
  void replaceSwapsInTheCorrectedCodeAndKeepsTheLinesItReplaces() {
    var raised = finding("Only page one is read.", BROKEN_LOOP);

    var revised = FindingRevisions.revised(raised, verdict("replace", FIXED_LOOP, List.of()));

    assertEquals(FIXED_LOOP, revised.suggestionNew());
    assertEquals(OLD, revised.suggestionOld());
    assertEquals(raised.title(), revised.title());
    assertEquals(raised.risk(), revised.risk());
    assertEquals(raised.description(), revised.description());
  }

  @Test
  void dropRemovesTheSuggestionAndKeepsTheFinding() {
    var raised = finding("Only page one is read.", BROKEN_LOOP);

    var revised = FindingRevisions.revised(raised, verdict(" Drop ", null, List.of()));

    assertNull(revised.suggestionNew());
    assertEquals(OLD, revised.suggestionOld(), "the anchor the suggestion quoted stays");
    assertEquals(raised.description(), revised.description());
    assertEquals(raised.line(), revised.line());
  }

  @Test
  void aContradictedSentenceIsCut() {
    // zig ThrillhouseBot-test#250: a passing fixture said to break after the fix.
    var raised =
        finding(
            "The slice stops one digit short. Note that fixing the slice also makes the fixture"
                + " LS000123408 fail. Any real barcode is misclassified.",
            BROKEN_LOOP);

    var revised =
        FindingRevisions.revised(
            raised,
            verdict(
                null,
                null,
                List.of(" Note that fixing the slice also makes the fixture LS000123408 fail. ")));

    assertEquals(
        "The slice stops one digit short. Any real barcode is misclassified.",
        revised.description());
    assertEquals(BROKEN_LOOP, revised.suggestionNew());
  }

  @Test
  void theFirstOrLastSentenceIsCutAndLineBreaksStay() {
    var raised =
        finding(
            "An invented first claim is here. The defect.\nA second line.\nAn invented last claim"
                + " stands here.",
            null);

    var revised =
        FindingRevisions.revised(
            raised,
            verdict(
                null,
                null,
                List.of(
                    "An invented first claim is here.", "An invented last claim stands here.")));

    assertEquals("The defect.\nA second line.\n", revised.description());
    assertEquals(
        "Ends a line.\nNext line.",
        FindingRevisions.withoutSentence(
            raised, "Ends a line. Cut this sentence.\nNext line.", "Cut this sentence."));
    assertEquals(
        "Ends a line.\r\nNext line.",
        FindingRevisions.withoutSentence(
            raised, "Ends a line. Cut this sentence.\r\nNext line.", "Cut this sentence."));
  }

  @Test
  void aSuggestionAndACutApplyTogether() {
    var raised = finding("Only page one is read. The constant MAX_PAGES is misspelt.", BROKEN_LOOP);

    var revised =
        FindingRevisions.revised(
            raised, verdict("replace", FIXED_LOOP, List.of("The constant MAX_PAGES is misspelt.")));

    assertEquals(FIXED_LOOP, revised.suggestionNew());
    assertEquals("Only page one is read.", revised.description());
  }

  @Test
  void nothingChangesWithoutAVerdictOrWithNothingToApply() {
    var raised = finding("Only page one is read.", BROKEN_LOOP);

    assertSame(raised, FindingRevisions.revised(raised, null));
    assertSame(raised, FindingRevisions.revised(raised, verdict(null, null, null)));
    assertSame(raised, FindingRevisions.revised(raised, verdict("keep", FIXED_LOOP, List.of())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"rewrite", "", "  "})
  void anUnknownSuggestionLabelKeepsTheSuggestion(String label) {
    var raised = finding("Only page one is read.", BROKEN_LOOP);

    assertSame(raised, FindingRevisions.revised(raised, verdict(label, FIXED_LOOP, List.of())));
  }

  @Test
  void aReplaceWithNoNewOrTheSameCodeKeepsTheSuggestion() {
    var raised = finding("Only page one is read.", BROKEN_LOOP);

    assertSame(raised, FindingRevisions.revised(raised, verdict("replace", null, List.of())));
    assertSame(raised, FindingRevisions.revised(raised, verdict("replace", " \n", List.of())));
    assertSame(
        raised, FindingRevisions.revised(raised, verdict("replace", BROKEN_LOOP, List.of())));
  }

  @Test
  void aFindingWithNoSuggestionGetsNone() {
    for (var none : Arrays.asList(null, " ")) {
      var raised = finding("Only page one is read.", none);

      assertSame(raised, FindingRevisions.revised(raised, verdict("replace", FIXED_LOOP, null)));
      assertSame(raised, FindingRevisions.revised(raised, verdict("drop", null, null)));
    }
  }

  @Test
  void aCutTheDescriptionDoesNotHoldWholeChangesNothing() {
    var raised = finding("Only page one is read. Every bag on page two is lost.", null);

    for (var cut :
        List.of(
            "A sentence the description does not have.",
            "page two is lost.",
            "Every bag on page two",
            "is read.",
            "Short.")) {
      assertSame(raised, FindingRevisions.revised(raised, verdict(null, null, List.of(cut))), cut);
    }
  }

  @Test
  void aSentenceRunOnToTheOneBeforeIsNotCut() {
    var raised = finding("x", null);
    var description = "The loop is never entered.Every bag on page two is lost.";

    assertSame(
        description,
        FindingRevisions.withoutSentence(raised, description, "Every bag on page two is lost."));
  }

  @Test
  void theLastSentenceLeftIsNeverCut() {
    var raised = finding("Only page one is read. Every bag on page two is lost.", null);

    var revised =
        FindingRevisions.revised(
            raised,
            verdict(
                null, null, List.of("Only page one is read.", "Every bag on page two is lost.")));

    assertEquals("Every bag on page two is lost.", revised.description());
    assertSame(
        raised,
        FindingRevisions.revised(
            raised,
            verdict(null, null, List.of("Only page one is read. Every bag on page two is lost."))));
  }

  @Test
  void aFindingWithNoDescriptionIsLeftAlone() {
    var raised = finding(null, null);

    assertSame(
        raised,
        FindingRevisions.revised(raised, verdict(null, null, List.of("A long enough sentence."))));
  }

  @Test
  void aCutEndingInAQuestionOrExclamationMarkIsWholeSentences() {
    var raised = finding("Is the loop ever run? It never runs at all! The page is lost.", null);

    var revised =
        FindingRevisions.revised(
            raised, verdict(null, null, List.of("Is the loop ever run?", "It never runs at all!")));

    assertEquals("The page is lost.", revised.description());
  }
}
