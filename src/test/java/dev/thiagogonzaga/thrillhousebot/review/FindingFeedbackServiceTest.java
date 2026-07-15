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

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FindingFeedbackServiceTest {

  @Inject FindingFeedbackService service;
  @Inject UserTransaction tx;

  @AfterEach
  void cleanup() throws Exception {
    tx.begin();
    FindingFeedback.deleteAll();
    tx.commit();
  }

  @Test
  void recordPersistsReactionAndIsIdempotentByReactionId() {
    assertTrue(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "owner/repo",
                7,
                100L,
                1,
                FindingFeedback.SIGNAL_NOT_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "octocat",
                55L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "owner/repo",
                7,
                100L,
                1,
                FindingFeedback.SIGNAL_NOT_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "octocat",
                55L)));

    assertEquals(1, FindingFeedback.count());
    var summary = service.summarize("owner/repo");
    assertEquals(0, summary.usefulCount());
    assertEquals(1, summary.notUsefulCount());
    assertEquals(1, summary.totalEvents());
  }

  @Test
  void recordNormalizesLoginAndIgnoresDuplicateCompositeKey() {
    assertTrue(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "owner/repo",
                3,
                200L,
                2,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "OctoCat",
                1L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "owner/repo",
                3,
                200L,
                2,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "octocat",
                99L)));

    assertEquals(1, FindingFeedback.count());
    FindingFeedback saved = FindingFeedback.<FindingFeedback>listAll().get(0);
    assertEquals("octocat", saved.reactorLogin);

    var recent = service.listRecent("owner/repo", 10);
    assertEquals(1, recent.size());
    assertEquals(2, recent.get(0).findingIndex());
    assertEquals(1L, recent.get(0).githubReactionId());
  }

  @Test
  void summarizeAllOrdersByTotalEvents() {
    service.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "a/one",
            1,
            1L,
            1,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "u1",
            10L));
    service.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "b/two",
            1,
            2L,
            1,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "u1",
            11L));
    service.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "b/two",
            1,
            2L,
            1,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "u2",
            12L));

    var all = service.summarizeAll();
    assertEquals(2, all.size());
    assertEquals("b/two", all.get(0).repository());
    assertEquals(2, all.get(0).totalEvents());
    assertEquals("a/one", all.get(1).repository());
  }

  @Test
  void summarizeAndListRecentHandleBlankKeys() {
    assertEquals(0, service.summarize(null).totalEvents());
    assertEquals(0, service.summarize(" ").totalEvents());
    assertTrue(service.listRecent(null, 10).isEmpty());
    assertTrue(service.listRecent(" ", 10).isEmpty());
    assertTrue(service.listRecent("owner/repo", 0).isEmpty());
    assertTrue(service.listRecent("owner/repo", -1).isEmpty());
  }

  @Test
  void recordAcceptsNullReactionIdAndRejectsNullReactor() {
    assertTrue(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "owner/repo",
                1,
                50L,
                1,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REPLY_HEURISTIC,
                "carol",
                null)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "owner/repo",
                1,
                51L,
                1,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                null,
                77L)));
  }

  @Test
  void recordRejectsBlankInputs() {
    assertFalse(service.recordFeedback(null));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "",
                1,
                1L,
                1,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "u",
                1L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "o/r",
                1,
                1L,
                1,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                " ",
                1L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "o/r", 1, 1L, 1, null, FindingFeedback.SOURCE_REACTION, "u", 1L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                "o/r", 1, 1L, 1, FindingFeedback.SIGNAL_USEFUL, null, "u", 1L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                null,
                1,
                1L,
                1,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "u",
                1L)));
  }
}
