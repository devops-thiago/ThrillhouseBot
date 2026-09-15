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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ModelCallGateTest {

  @Test
  void anUnboundedGateNeverMakesACallWait() {
    var gate = new ModelCallGate(0, Duration.ZERO, System::nanoTime);

    try (var first = gate.acquire("first");
        var second = gate.acquire("second")) {
      assertEquals(Duration.ZERO, first.waited());
      assertEquals(Duration.ZERO, second.waited());
    }
    assertEquals(Integer.MAX_VALUE, gate.availableSlots());
    assertEquals(0, gate.queuedCalls());
  }

  @Test
  void aCallPastTheCeilingWaitsForTheSlotAndNeverOverlaps() throws Exception {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);
    var first = gate.acquire("first");
    var secondHolds = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var second =
          executor.submit(
              () -> {
                try (var _ = gate.acquire("second")) {
                  secondHolds.countDown();
                }
                return null;
              });
      awaitCondition(() -> gate.queuedCalls() > 0 || secondHolds.getCount() == 0);
      assertEquals(
          1,
          secondHolds.getCount(),
          "the second call got a slot while the first still held the only one");

      first.close();
      second.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, gate.availableSlots());
  }

  @Test
  void closingASlotTwiceReturnsItOnce() {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);

    var slot = gate.acquire("call");
    assertEquals(0, gate.availableSlots(), "an acquired slot must be taken from the gate");
    slot.close();
    slot.close();

    assertEquals(1, gate.availableSlots(), "a second close must not mint a slot");
  }

  @Test
  void aWaitThatOutlastsItsBoundFailsTheCallAndNamesTheCeiling() {
    var gate = new ModelCallGate(1, Duration.ofMillis(20), System::nanoTime);

    try (var _ = gate.acquire("holder")) {
      var thrown =
          assertThrows(
              AiReviewException.class, () -> gate.acquire("AI review call for session 42"));

      assertTrue(
          thrown.getMessage().contains("AI review call for session 42"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("AI_MAX_CONCURRENT_CALLS=1"), thrown.getMessage());
    }
    assertEquals(1, gate.availableSlots(), "a call that never got a slot must not return one");
  }

  @Test
  void anInterruptWhileWaitingEndsTheWaitAndKeepsTheFlag() {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);

    try (var _ = gate.acquire("holder")) {
      Thread.currentThread().interrupt();
      try {
        var thrown = assertThrows(AiReviewException.class, () -> gate.acquire("waiting call"));

        assertTrue(thrown.getMessage().contains("interrupted"), thrown.getMessage());
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void aWaitLongerThanTheThresholdIsLoggedOnceAtInfoWithItsLength() {
    var logged = captureLog(() -> acquireAfter(Duration.ofMillis(6_250)));

    assertEquals(1, logged.size(), logged.toString());
    assertEquals(Level.INFO, logged.getFirst().getLevel());
    var line = formatted(logged.getFirst());
    assertTrue(line.contains("verifier call"), line);
    assertTrue(line.contains("6250 ms"), line);
    assertTrue(line.contains("AI_MAX_CONCURRENT_CALLS=1"), line);
  }

  @Test
  void aShortWaitIsNotLogged() {
    var logged = captureLog(() -> acquireAfter(Duration.ofSeconds(5)));

    assertEquals(List.of(), logged);
  }

  @Test
  void readsTheCeilingFromConfig() {
    var config = mock(ThrillhouseConfig.class);
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.ai()).thenReturn(ai);
    when(config.review()).thenReturn(review);
    when(ai.maxConcurrentCalls()).thenReturn(3);
    when(review.aiTimeoutSeconds()).thenReturn(300);

    assertEquals(3, new ModelCallGate(config).availableSlots());
  }

  /** Acquires a free slot on a clock that reads {@code elapsed} between the two readings. */
  private static void acquireAfter(Duration elapsed) {
    var readings = new ArrayDeque<>(List.of(0L, elapsed.toNanos()));
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), readings::pop);
    try (var slot = gate.acquire("verifier call")) {
      assertEquals(elapsed, slot.waited());
    }
  }

  private static List<LogRecord> captureLog(Runnable action) {
    var julLogger = Logger.getLogger(ModelCallGate.class.getName());
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var capture =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            logged.add(entry);
          }

          @Override
          public void flush() {
            // Nothing is buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    julLogger.addHandler(capture);
    try {
      action.run();
    } finally {
      julLogger.removeHandler(capture);
    }
    return logged;
  }

  private static String formatted(LogRecord entry) {
    var params = entry.getParameters();
    var message = entry.getMessage();
    if (params == null) {
      return message;
    }
    for (var param : params) {
      message = message.replaceFirst("\\{}", String.valueOf(param));
    }
    return message;
  }

  private static void awaitCondition(BooleanSupplier condition) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "condition not reached within 10 seconds");
      Thread.onSpinWait();
    }
  }
}
