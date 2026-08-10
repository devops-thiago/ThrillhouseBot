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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AiResponseTruncatedException}'s carried state and cause-chain finder. */
class AiResponseTruncatedExceptionTest {

  @Test
  void theSingleArgumentConstructorCarriesNoBodyAndNoConciseMark() {
    var e = new AiResponseTruncatedException("cap");

    assertNull(e.partialBody());
    assertFalse(e.conciseModelImplicated());
  }

  @Test
  void implicatingConciseModelKeepsMessageAndBodyAndIsIdempotent() {
    var e = new AiResponseTruncatedException("cap", "{\"findings\":[", false);

    var marked = e.implicatingConciseModel();

    assertTrue(marked.conciseModelImplicated());
    assertEquals("cap", marked.getMessage());
    assertEquals("{\"findings\":[", marked.partialBody());
    // Already marked: no fresh copy, so the identity a caller logged stays stable.
    assertSame(marked, marked.implicatingConciseModel());
  }

  @Test
  void findInWalksTheCauseChainAndReturnsTheTruncation() {
    var truncation = new AiResponseTruncatedException("cap", "body", false);
    var wrapped = new CompletionException(new IllegalStateException("wrap", truncation));

    var found = AiResponseTruncatedException.findIn(wrapped);

    assertTrue(found.isPresent());
    assertSame(truncation, found.get());
    assertEquals("body", found.get().partialBody());
  }

  @Test
  void findInReturnsEmptyForANonTruncationFailure() {
    assertTrue(
        AiResponseTruncatedException.findIn(new RuntimeException("boom", new Exception("t")))
            .isEmpty());
  }

  @Test
  void findInSurvivesACyclicCauseChain() {
    // A caused-by B caused-by A: an unbounded walk would spin forever on the review thread.
    var forward = new AtomicReference<Throwable>();
    var a =
        new RuntimeException("a") {
          @Override
          public synchronized Throwable getCause() {
            return forward.get();
          }
        };
    var b =
        new RuntimeException("b") {
          @Override
          public synchronized Throwable getCause() {
            return a;
          }
        };
    forward.set(b);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5), () -> assertTrue(AiResponseTruncatedException.findIn(a).isEmpty()));
  }
}
