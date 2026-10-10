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

import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.AUTH;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.DETAILS;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.review;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ReviewStandDown}: the guards run in order, the first stand-down wins, and
 * its shared consequences (the skip counted, the check run concluded as skipped) are applied once.
 */
class ReviewStandDownTest {

  private static final PrePublishGuard.StandDown SUPERSEDED =
      new PrePublishGuard.StandDown(
          ReviewSkipReason.HEAD_MOVED,
          "head moved",
          "Superseded title",
          "Superseded summary",
          "Superseded",
          "superseded");

  private static final PrePublishGuard.StandDown LABEL_GATED =
      new PrePublishGuard.StandDown(
          ReviewSkipReason.EXCLUDED_LABEL,
          "excluded label",
          "Label title",
          "Label summary",
          "Skipped",
          "label-gated");

  private final PrePublishGuard first = mock(PrePublishGuard.class);
  private final PrePublishGuard second = mock(PrePublishGuard.class);
  private final CheckRunManager checkRunManager = mock(CheckRunManager.class);
  private final ReviewSkipEmitter skipEmitter =
      new ReviewSkipEmitter(io.opentelemetry.api.OpenTelemetry.noop());
  private final ReviewStandDown standDown =
      new ReviewStandDown(List.of(first, second), skipEmitter, checkRunManager);

  @Test
  void theFirstGuardThatStandsTheRunDownWinsAndLaterOnesAreNotAsked() {
    var run = review(false, 9L);
    when(first.check(run)).thenReturn(Optional.of(SUPERSEDED));
    when(second.check(run)).thenReturn(Optional.of(LABEL_GATED));

    assertSame(SUPERSEDED, standDown.beforeFirstWrite(run).orElseThrow());

    verify(second, never()).check(any());
    assertEquals(1L, skipEmitter.countsByReason().get("HEAD_MOVED"));
    assertFalse(skipEmitter.countsByReason().containsKey("EXCLUDED_LABEL"));
    var update = ArgumentCaptor.forClass(CheckRunManager.CheckRunUpdate.class);
    verify(checkRunManager).updateCheckRun(update.capture());
    assertEquals(
        new CheckRunManager.CheckRunUpdate(
            AUTH,
            "owner",
            "repo",
            9L,
            "completed",
            "skipped",
            "Superseded title",
            "Superseded summary",
            DETAILS),
        update.getValue());
  }

  @Test
  void guardsRunInListOrderUntilOneStandsTheRunDown() {
    var run = review(false, 9L);
    when(first.check(run)).thenReturn(Optional.empty());
    when(second.check(run)).thenReturn(Optional.of(LABEL_GATED));

    assertSame(LABEL_GATED, standDown.beforeFirstWrite(run).orElseThrow());

    var order = inOrder(first, second);
    order.verify(first).check(run);
    order.verify(second).check(run);
    assertEquals(1L, skipEmitter.countsByReason().get("EXCLUDED_LABEL"));
  }

  @Test
  void aRunNoGuardRejectsPostsWithNoConsequence() {
    var run = review(false, 9L);
    when(first.check(run)).thenReturn(Optional.empty());
    when(second.check(run)).thenReturn(Optional.empty());

    assertTrue(standDown.beforeFirstWrite(run).isEmpty());

    verifyNoInteractions(checkRunManager);
    assertTrue(skipEmitter.countsByReason().isEmpty());
  }

  @Test
  void withoutACheckRunOrWithAFailingOneTheSkipIsStillCounted() {
    when(first.check(any())).thenReturn(Optional.of(SUPERSEDED));

    assertTrue(standDown.beforeFirstWrite(review(false, -1L)).isPresent());
    verifyNoInteractions(checkRunManager);

    doThrow(new RuntimeException("check run API down")).when(checkRunManager).updateCheckRun(any());
    assertDoesNotThrow(() -> standDown.beforeFirstWrite(review(false, 1L)));

    assertEquals(2L, skipEmitter.countsByReason().get("HEAD_MOVED"));
  }

  @Test
  void theHeadIsGuardedBeforeTheLabels() {
    var headMoved = mock(HeadMovedGuard.class);
    var labelGate = mock(LabelGateGuard.class);
    var run = review(false, 9L);
    when(headMoved.check(run)).thenReturn(Optional.of(SUPERSEDED));
    when(labelGate.check(run)).thenReturn(Optional.of(LABEL_GATED));

    var wired = new ReviewStandDown(headMoved, labelGate, skipEmitter, checkRunManager);

    assertSame(SUPERSEDED, wired.beforeFirstWrite(run).orElseThrow());
    verify(labelGate, never()).check(any());
  }
}
