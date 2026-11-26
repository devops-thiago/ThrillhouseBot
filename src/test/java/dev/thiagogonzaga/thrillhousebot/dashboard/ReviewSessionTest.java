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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewSessionTest {

  @Test
  void elapsedIsTheTimeSinceTheSessionStarted() {
    var session = new ReviewSession();
    session.setTimestamp(Instant.now().minusSeconds(5));

    var elapsed = session.elapsedMs();

    assertTrue(elapsed >= 5_000L && elapsed < 60_000L, "elapsed " + elapsed);
  }

  @Test
  void elapsedIsZeroWithoutAStartOrForAStartInTheFuture() {
    assertEquals(0L, new ReviewSession().elapsedMs());
    var skewed = new ReviewSession();
    skewed.setTimestamp(Instant.now().plusSeconds(60));
    assertEquals(0L, skewed.elapsedMs());
  }
}
