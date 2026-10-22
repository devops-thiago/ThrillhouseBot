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

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.time.Instant;
import java.util.List;
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
                TestScopes.scope("owner/repo"),
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
                TestScopes.scope("owner/repo"),
                "owner/repo",
                7,
                100L,
                1,
                FindingFeedback.SIGNAL_NOT_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "octocat",
                55L)));

    assertEquals(1, FindingFeedback.count());
    var summary = service.summarize(TestScopes.scope("owner/repo"), "owner/repo");
    assertEquals(0, summary.usefulCount());
    assertEquals(1, summary.notUsefulCount());
    assertEquals(1, summary.totalEvents());
  }

  @Test
  void recordNormalizesLoginAndIgnoresDuplicateCompositeKey() {
    assertTrue(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                TestScopes.scope("owner/repo"),
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
                TestScopes.scope("owner/repo"),
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

    var recent = service.listRecent(TestScopes.scope("owner/repo"), 10);
    assertEquals(1, recent.size());
    assertEquals(2, recent.get(0).findingIndex());
    assertEquals(1L, recent.get(0).githubReactionId());
  }

  @Test
  void countsByRepositoryOrderByTotalEventsWithinTheInstallation() {
    service.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            TestScopes.scope("a/one"),
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
            TestScopes.scope("b/two"),
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
            TestScopes.scope("b/two"),
            "b/two",
            1,
            2L,
            1,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "u2",
            12L));

    var one = TestScopes.repositoryId("a/one");
    var two = TestScopes.repositoryId("b/two");
    var counts = service.countsByRepository(TestScopes.INSTALLATION_ID, List.of(one, two, 999L));
    assertEquals(List.of(two, one), List.copyOf(counts.keySet()));
    assertEquals(new FindingFeedbackService.FeedbackCounts(1, 1, 2), counts.get(two));
    assertEquals(new FindingFeedbackService.FeedbackCounts(1, 0, 1), counts.get(one));
    assertTrue(service.countsByRepository(TestScopes.INSTALLATION_ID, List.of()).isEmpty());
    assertTrue(service.countsByRepository(2L, List.of(one, two)).isEmpty());
  }

  @Test
  void listRecentNeedsAPositiveLimit() {
    var scope = TestScopes.scope("owner/repo");
    assertTrue(service.listRecent(scope, 0).isEmpty());
    assertTrue(service.listRecent(scope, -1).isEmpty());
  }

  @Test
  void rowsAreReadByRepositoryIdAndLabelledByTheCaller() {
    // Stored under an older casing; the id, not the name, finds it.
    assertTrue(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                TestScopes.scope("owner/repo"),
                "Owner/Repo",
                1,
                10L,
                1,
                FindingFeedback.SIGNAL_USEFUL,
                FindingFeedback.SOURCE_REACTION,
                "octocat",
                301L)));

    var summary = service.summarize(TestScopes.scope("owner/repo"), "owner/renamed");
    assertEquals("owner/renamed", summary.repository());
    assertEquals(1, summary.usefulCount());
    assertEquals(1, summary.totalEvents());

    var recent = service.listRecent(TestScopes.scope("owner/repo"), 10);
    assertEquals(1, recent.size());
    assertEquals("Owner/Repo", recent.get(0).repository());
  }

  @Test
  void theSameNameUnderAnotherInstallationIsNeverCrossed() throws Exception {
    var ours = TestScopes.scope("owner/repo");
    var theirs = new RepoScope(2L, ours.repositoryId());
    service.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            theirs,
            "owner/repo",
            1,
            10L,
            1,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "mallory",
            401L));
    // A row stored before #1220 that the backfill could not match.
    tx.begin();
    var legacy = new FindingFeedback();
    legacy.repository = "owner/repo";
    legacy.prNumber = 1;
    legacy.githubCommentId = 11L;
    legacy.signal = FindingFeedback.SIGNAL_NOT_USEFUL;
    legacy.source = FindingFeedback.SOURCE_REACTION;
    legacy.reactorLogin = "legacy";
    legacy.createdAt = Instant.now();
    legacy.persist();
    tx.commit();

    assertEquals(0, service.summarize(ours, "owner/repo").totalEvents());
    assertTrue(service.listRecent(ours, 10).isEmpty());
    assertTrue(
        service.countsByRepository(ours.installationId(), List.of(ours.repositoryId())).isEmpty());
    assertEquals(1, service.summarize(theirs, "owner/repo").totalEvents());
  }

  @Test
  void recordAcceptsNullReactionIdAndRejectsNullReactor() {
    assertTrue(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                TestScopes.scope("owner/repo"),
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
                TestScopes.scope("owner/repo"),
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
                null,
                "o/r",
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
                TestScopes.scope(""),
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
                TestScopes.scope("o/r"),
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
                TestScopes.scope("o/r"),
                "o/r",
                1,
                1L,
                1,
                null,
                FindingFeedback.SOURCE_REACTION,
                "u",
                1L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                TestScopes.scope("o/r"),
                "o/r",
                1,
                1L,
                1,
                FindingFeedback.SIGNAL_USEFUL,
                null,
                "u",
                1L)));
    assertFalse(
        service.recordFeedback(
            new FindingFeedbackService.FeedbackInput(
                TestScopes.scope("o/r"),
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
