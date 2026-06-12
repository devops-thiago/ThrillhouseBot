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
package dev.thiagogonzaga.thrillhousebot.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReviewExecutorProducerTest {

  @Test
  void shouldProduceVirtualThreadExecutorAndShutdownCleanly() throws InterruptedException {
    var producer = new ReviewExecutorProducer();
    var executor = producer.reviewExecutor();

    assertNotNull(executor);
    executor.execute(() -> {});

    producer.shutdown(executor);

    assertTrue(executor.isShutdown());
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
  }

  @Test
  void shouldForceShutdownWhenTerminationTimesOut() throws InterruptedException {
    var producer = new ReviewExecutorProducer();
    ExecutorService blocking = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    var hold = new CountDownLatch(1);
    blocking.execute(
        () -> {
          started.countDown();
          try {
            hold.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        });
    started.await(2, TimeUnit.SECONDS);

    producer.shutdown(blocking);

    assertTrue(blocking.isShutdown());
  }

  @Test
  void shouldForceShutdownWhenAwaitIsInterrupted() throws InterruptedException {
    var producer = new ReviewExecutorProducer();
    ExecutorService blocking = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    var hold = new CountDownLatch(1);
    blocking.execute(
        () -> {
          started.countDown();
          try {
            hold.await(60, TimeUnit.SECONDS);
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        });
    started.await(2, TimeUnit.SECONDS);

    var shutdownThread = new Thread(() -> producer.shutdown(blocking));
    shutdownThread.start();
    shutdownThread.interrupt();
    shutdownThread.join(5_000);

    assertTrue(blocking.isShutdown());
  }
}
