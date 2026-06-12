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
package dev.thiagogonzaga.thrillhousebot;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Wall-clock waits for test doubles that simulate slow upstreams. */
public final class TestDelays {

  private TestDelays() {}

  /**
   * Blocks for {@code millis}; returns false (with the interrupt flag restored) when interrupted
   * before the delay elapses.
   */
  public static boolean simulateLatency(long millis) {
    try {
      // The latch is never counted down — await is a timed wait for the full delay
      new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
