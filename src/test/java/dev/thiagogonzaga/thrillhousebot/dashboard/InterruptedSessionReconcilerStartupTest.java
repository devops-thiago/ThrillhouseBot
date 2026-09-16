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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

class InterruptedSessionReconcilerStartupTest {

  @Test
  void onStartShouldNotLogWhenNoSessionWasStranded() {
    ReviewSessionRepository repository = mock(ReviewSessionRepository.class);
    when(repository.update(anyString(), any(), any(), any())).thenReturn(0);

    var reconciler = new InterruptedSessionReconciler(repository);

    assertDoesNotThrow(() -> reconciler.onStart(mock(StartupEvent.class)));
  }

  @Test
  void onStartShouldSwallowAReconciliationFailure() {
    ReviewSessionRepository repository = mock(ReviewSessionRepository.class);
    when(repository.update(anyString(), any(), any(), any()))
        .thenThrow(new RuntimeException("db down"));

    var reconciler = new InterruptedSessionReconciler(repository);

    assertDoesNotThrow(() -> reconciler.onStart(mock(StartupEvent.class)));
  }
}
