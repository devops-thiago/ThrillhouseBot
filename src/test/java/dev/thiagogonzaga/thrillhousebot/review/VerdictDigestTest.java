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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VerdictDigestTest {

  private static final OpenFindingNames.Threads NONE_POSTED =
      new OpenFindingNames.Threads(
          List.of(),
          List.of(),
          new BotIdentity(Set.of("thrillhousebot[bot]")),
          "https://github.com/o/r/pull/1");

  private static Finding finding(int line, RiskLevel risk, Confidence confidence) {
    return new Finding(risk, confidence, "src/A.java", line, "Title " + line, "desc", null, null);
  }

  @Test
  void blockingCountsThisRoundAndEarlierOpenFindingsOnceMostSevereFirst() {
    var high = finding(1, RiskLevel.HIGH, Confidence.HIGH);
    var critical = finding(2, RiskLevel.CRITICAL, Confidence.HIGH);
    var medium = finding(3, RiskLevel.MEDIUM, Confidence.HIGH);

    assertEquals(
        List.of(critical, high),
        VerdictDigest.blocking(
            List.of(high, medium), List.of(high, critical), BlockingStrictness.BALANCED));
  }

  @Test
  void changeRequestNamesEachBlockingFindingAndHowToClearIt() {
    var finding = finding(1, RiskLevel.HIGH, Confidence.HIGH);
    var posted =
        new OpenFindingNames.Threads(
            List.of(), List.of(finding), NONE_POSTED.botIdentity(), NONE_POSTED.pullRequestUrl());

    var body = VerdictDigest.changesRequested(List.of(finding), posted);

    assertTrue(body.startsWith(VerdictDigest.START), body);
    assertTrue(body.contains("**Changes requested: 1 finding blocks this merge.**"), body);
    assertTrue(body.contains("- **HIGH** `src/A.java:1` — Title 1"), body);
    assertTrue(body.contains(OpenFindingNames.NEW_THREAD_MARK), body);
    assertTrue(body.contains("reply on its thread"), body);
    assertFalse(body.contains("@thrillhousebot declined"), body);
  }

  @Test
  void aBlockingFindingWithoutAThreadGetsTheCommandThatDeclinesIt() {
    var body =
        VerdictDigest.changesRequested(
            List.of(
                finding(1, RiskLevel.HIGH, Confidence.HIGH),
                finding(2, RiskLevel.HIGH, Confidence.HIGH)),
            NONE_POSTED);

    assertTrue(body.contains("2 findings block this merge"), body);
    assertTrue(body.contains(OpenFindingNames.NO_THREAD_MARK), body);
    assertTrue(body.contains("`@thrillhousebot declined <path>:<line> — <title>`"), body);
  }

  @Test
  void theDeclineCommandNamesTheConfiguredBot() {
    var custom =
        new OpenFindingNames.Threads(
            List.of(),
            List.of(),
            new BotIdentity(Set.of("acme-reviewer[bot]")),
            NONE_POSTED.pullRequestUrl());

    var body =
        VerdictDigest.changesRequested(
            List.of(finding(1, RiskLevel.HIGH, Confidence.HIGH)), custom);

    assertTrue(body.contains("`@acme-reviewer declined <path>:<line> — <title>`"), body);
    assertTrue(body.contains("Declining needs write access"), body);
  }

  @Test
  void aLongListIsCutWithACount() {
    var many =
        IntStream.rangeClosed(1, VerdictDigest.NAMED_LIMIT + 2)
            .mapToObj(line -> finding(line, RiskLevel.HIGH, Confidence.HIGH))
            .toList();

    var body = VerdictDigest.changesRequested(many, NONE_POSTED);

    assertTrue(body.contains("- …and 2 more"), body);
    assertFalse(body.contains("Title " + (VerdictDigest.NAMED_LIMIT + 1)), body);
  }

  @Test
  void aChangeRequestWithNothingToNameStillSaysSo() {
    assertTrue(
        VerdictDigest.changesRequested(List.of(), NONE_POSTED)
            .contains("**Changes requested.** See the inline comments on the diff."));
  }

  @Test
  void aStandingChangeRequestIsMentionedOnlyWhenThereIsOne() {
    assertTrue(VerdictDigest.nothingBlocks(true).contains("earlier change request stays"));
    assertFalse(VerdictDigest.nothingBlocks(false).contains("earlier change request"));
  }

  @Test
  void aListingIsFoldedOnlyWhenNothingInItBlocks() {
    var medium = finding(1, RiskLevel.MEDIUM, Confidence.HIGH);
    var high = finding(2, RiskLevel.HIGH, Confidence.HIGH);

    var folded =
        VerdictDigest.foldUnlessBlocking("listing\n", List.of(medium), BlockingStrictness.BALANCED);

    assertTrue(folded.contains("Not blocking: 1 finding without an inline thread"), folded);
    assertTrue(folded.contains("\n\nlisting\n\n</details>"), folded);
    assertTrue(
        VerdictDigest.foldUnlessBlocking(
                "listing", List.of(medium, medium), BlockingStrictness.BALANCED)
            .contains("2 findings"));
    assertEquals(
        "listing",
        VerdictDigest.foldUnlessBlocking(
            "listing", List.of(medium, high), BlockingStrictness.BALANCED));
  }

  @Test
  void onlyABlockThatOpensTheBodyIsDropped() {
    var block = VerdictDigest.nothingBlocks(false);

    assertEquals("rest", VerdictDigest.without(block + "\n\nrest"));
    assertEquals("", VerdictDigest.without(block));
    assertEquals("quoted\n" + block, VerdictDigest.without("quoted\n" + block));
    assertEquals(VerdictDigest.START + " cut", VerdictDigest.without(VerdictDigest.START + " cut"));
  }

  @Test
  void theOpeningIsNeverReadBackAsAPreviousFinding() {
    var block =
        VerdictDigest.changesRequested(
            List.of(finding(1, RiskLevel.HIGH, Confidence.HIGH)), NONE_POSTED);

    assertTrue(FollowUpAnalyzer.isSelfAuthoredStatusBody(block));
    assertTrue(
        FollowUpAnalyzer.isSelfAuthoredStatusBody(
            block + "\n\n" + ReviewResult.unresolvedPreviousMessage(2)));
    assertFalse(FollowUpAnalyzer.isSelfAuthoredStatusBody(block + "\n\nA real finding."));
  }
}
