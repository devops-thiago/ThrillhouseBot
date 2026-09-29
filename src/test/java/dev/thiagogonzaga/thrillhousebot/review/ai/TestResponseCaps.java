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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import java.util.Optional;

/**
 * Builds a {@link ResponseCaps} with fixed caps, for unit tests that construct services by hand.
 */
public final class TestResponseCaps {

  /** The model name {@link #of} reports, so a test can assert the active setting's full key. */
  public static final String MODEL = "test-model";

  private TestResponseCaps() {}

  /**
   * Caps for both lanes; {@code null} leaves that lane's setting unset (no {@code max_tokens}
   * sent).
   */
  public static ResponseCaps of(Integer activeCap, Integer conciseCap) {
    var activeModel = mock(ActiveModelSettings.class);
    when(activeModel.maxOutputTokens()).thenReturn(Optional.ofNullable(activeCap));
    when(activeModel.modelName()).thenReturn(MODEL);
    return new ResponseCaps(activeModel, Optional.ofNullable(conciseCap));
  }

  /** The shipped shape: no active-model cap configured, the concise lane at its 8192 default. */
  public static ResponseCaps defaults() {
    return of(null, 8192);
  }
}
