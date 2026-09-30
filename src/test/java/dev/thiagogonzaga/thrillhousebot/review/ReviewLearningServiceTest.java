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

import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.LearningInput;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.RecordOutcome;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.RetractOutcome;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The learnings store against the real schema (#38): the table Hibernate creates from {@link
 * ReviewLearning}, idempotency, the refusal paths, the per-repository cap, and the installation and
 * repository scoping that keeps one repository's learnings out of another's reviews.
 */
@QuarkusTest
class ReviewLearningServiceTest {

  private static final String FLAT =
      "GitHub PR review threads are flat: every reply's in_reply_to_id is the thread root.";

  @Inject ReviewLearningService service;
  @Inject EntityManager em;
  @Inject UserTransaction tx;

  @AfterEach
  void cleanup() throws Exception {
    tx.begin();
    ReviewLearning.deleteAll();
    tx.commit();
  }

  private static LearningInput decline(long installationId, String repo, String url, String text) {
    return new LearningInput(
        installationId,
        repo,
        ReviewLearning.KIND_DECLINE,
        "renderThread misses deeper nested replies",
        "medium",
        "src/main/java/MaintainerReplyService.java",
        text,
        159,
        url,
        "Maintainer");
  }

  @Test
  void theTableExistsWithItsColumns() throws Exception {
    tx.begin();
    var columns =
        em.createNativeQuery(
                "select lower(column_name) from information_schema.columns"
                    + " where lower(table_name) = 'review_learning'")
            .getResultList();
    tx.commit();
    for (var column :
        java.util.List.of(
            "installationid",
            "repository",
            "kind",
            "findingtitle",
            "findingrisk",
            "path",
            "text",
            "sourceprnumber",
            "sourceurl",
            "author",
            "dedupkey",
            "createdat",
            "active",
            "retractedby",
            "retractedat")) {
      assertTrue(columns.contains(column), column + " in " + columns);
    }
  }

  @Test
  void aDeclineIsStoredOnceAndReadBackNormalized() {
    var input = decline(1L, "Owner/Repo", "https://github.com/o/r/pull/159#discussion_r1", FLAT);

    assertEquals(RecordOutcome.STORED, service.save(input, 10));
    assertEquals(RecordOutcome.DUPLICATE, service.save(input, 10));

    var active = service.listActive(1L, "owner/repo", 10);
    assertEquals(1, active.size());
    var learning = active.get(0);
    assertEquals(ReviewLearning.KIND_DECLINE, learning.kind());
    assertEquals("renderThread misses deeper nested replies", learning.findingTitle());
    assertEquals("medium", learning.findingRisk());
    assertEquals("src/main/java/MaintainerReplyService.java", learning.path());
    assertEquals(FLAT, learning.text());
    assertEquals(159, learning.sourcePrNumber());
    assertEquals("maintainer", learning.author());
    assertTrue(learning.active());
    assertNotNull(learning.createdAt());
    assertEquals(1, service.countActive(1L, "OWNER/REPO"));
  }

  @Test
  void aConventionHasNoFindingOrPath() {
    var input =
        new LearningInput(
            1L,
            "o/r",
            ReviewLearning.KIND_CONVENTION,
            null,
            null,
            null,
            "  Prefer records for DTOs.  ",
            3,
            "https://github.com/o/r/pull/3#issuecomment-9",
            "m");

    assertEquals(RecordOutcome.STORED, service.save(input, 10));

    var learning = service.listActive(1L, "o/r", 10).get(0);
    assertNull(learning.findingTitle());
    assertNull(learning.findingRisk());
    assertNull(learning.path());
    assertEquals("Prefer records for DTOs.", learning.text());
  }

  @Test
  void aCredentialIsNeverStored() {
    assertEquals(
        RecordOutcome.REFUSED_SECRET,
        service.save(decline(1L, "o/r", "u1", "use ghp_abcdefghijklmnop1234 here"), 10));
    var titled =
        new LearningInput(
            1L,
            "o/r",
            ReviewLearning.KIND_DECLINE,
            "leaks AKIAABCDEFGHIJKLMNOP",
            "high",
            "a.tf",
            "fine",
            1,
            "u2",
            "m");
    assertEquals(RecordOutcome.REFUSED_SECRET, service.save(titled, 10));
    var pathed =
        new LearningInput(
            1L,
            "o/r",
            ReviewLearning.KIND_DECLINE,
            "t",
            "high",
            "keys/ghp_abcdefghijklmnop1234.txt",
            "fine",
            1,
            "u3",
            "m");
    assertEquals(RecordOutcome.REFUSED_SECRET, service.save(pathed, 10));
    assertEquals(0, service.countActive(1L, "o/r"));
  }

  @Test
  void incompleteOrEmptyInputIsRefused() {
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(null, 10));
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(decline(1L, " ", "u", FLAT), 10));
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(decline(1L, "o/r", "u", "> x"), 10));
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(decline(1L, "o/r", " ", FLAT), 10));
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(decline(1L, "o/r", null, FLAT), 10));
    var noKind = new LearningInput(1L, "o/r", null, null, null, null, FLAT, 1, "u", "m");
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(noKind, 10));
    var noAuthor =
        new LearningInput(
            1L, "o/r", ReviewLearning.KIND_CONVENTION, null, null, null, FLAT, 1, "u", " ");
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(noAuthor, 10));
    var nullAuthor =
        new LearningInput(
            1L, "o/r", ReviewLearning.KIND_CONVENTION, null, null, null, FLAT, 1, "u", null);
    assertEquals(RecordOutcome.REFUSED_EMPTY, service.save(nullAuthor, 10));
  }

  @Test
  void theCapRefusesInsteadOfEvictingAndRetractionFreesRoom() {
    assertEquals(RecordOutcome.STORED, service.save(decline(1L, "o/r", "u1", FLAT), 2));
    assertEquals(RecordOutcome.STORED, service.save(decline(1L, "o/r", "u2", FLAT), 2));
    assertEquals(RecordOutcome.REFUSED_CAP, service.save(decline(1L, "o/r", "u3", FLAT), 2));
    assertEquals(
        RecordOutcome.STORED,
        service.save(decline(1L, "other/repo", "u3", FLAT), 2),
        "the cap is per repository");

    var oldest = service.listActive(1L, "o/r", 10).get(1);
    assertEquals(RetractOutcome.RETRACTED, service.retract(1L, "o/r", oldest.id(), "Admin"));
    assertEquals(RecordOutcome.STORED, service.save(decline(1L, "o/r", "u3", FLAT), 2));
  }

  @Test
  void learningsNeverCrossRepositoriesOrInstallations() {
    service.save(decline(1L, "o/r", "u1", FLAT), 10);

    assertEquals(1, service.listActive(1L, "o/r", 10).size());
    assertTrue(service.listActive(2L, "o/r", 10).isEmpty(), "another installation");
    assertTrue(service.listActive(1L, "o/other", 10).isEmpty(), "another repository");
    assertTrue(service.listActive(1L, " ", 10).isEmpty());
    assertTrue(service.listActive(1L, null, 10).isEmpty());
    assertTrue(service.listActive(1L, "o/r", 0).isEmpty());
    assertEquals(0, service.countActive(1L, null));
    assertEquals(0, service.countActive(1L, " "));
  }

  @Test
  void retractIsScopedAndAudited() {
    service.save(decline(1L, "o/r", "u1", FLAT), 10);
    var id = service.listActive(1L, "o/r", 10).get(0).id();

    assertEquals(RetractOutcome.NOT_FOUND, service.retract(1L, "o/other", id, "x"));
    assertEquals(RetractOutcome.NOT_FOUND, service.retract(2L, "o/r", id, "x"));
    assertEquals(RetractOutcome.NOT_FOUND, service.retract(1L, "o/r", id + 1000, "x"));
    assertEquals(RetractOutcome.NOT_FOUND, service.retract(1L, null, id, "x"));
    assertEquals(RetractOutcome.RETRACTED, service.retract(1L, "O/R", id, " Admin "));
    assertEquals(RetractOutcome.ALREADY_RETRACTED, service.retract(1L, "o/r", id, "admin"));

    assertTrue(service.listActive(1L, "o/r", 10).isEmpty(), "a retracted learning is not active");
    var audit = service.listForAudit("o/r", 10);
    assertEquals(1, audit.size());
    assertFalse(audit.get(0).active());
    assertEquals("admin", audit.get(0).retractedBy());
    assertNotNull(audit.get(0).retractedAt());
  }

  @Test
  void aRetractionWithoutALoginIsStillRecorded() {
    service.save(decline(1L, "o/r", "u1", FLAT), 10);
    var id = service.listActive(1L, "o/r", 10).get(0).id();

    assertEquals(RetractOutcome.RETRACTED, service.retract(1L, "o/r", id, null));
    assertNull(service.listForAudit("o/r", 10).get(0).retractedBy());
  }

  @Test
  void theAuditListNeedsARepository() {
    assertTrue(service.listForAudit(null, 10).isEmpty());
    assertTrue(service.listForAudit(" ", 10).isEmpty());
    assertTrue(service.listForAudit("o/r", 0).isEmpty());
  }

  @Test
  void theDedupKeyDependsOnEveryIdentifyingField() {
    var base = ReviewLearningService.dedupKey("decline", 1L, "o/r", "u", "t");
    assertEquals(base, ReviewLearningService.dedupKey("decline", 1L, "o/r", "u", "t"));
    assertNotEquals(base, ReviewLearningService.dedupKey("convention", 1L, "o/r", "u", "t"));
    assertNotEquals(base, ReviewLearningService.dedupKey("decline", 2L, "o/r", "u", "t"));
    assertNotEquals(base, ReviewLearningService.dedupKey("decline", 1L, "o/r", "u", null));
  }
}
