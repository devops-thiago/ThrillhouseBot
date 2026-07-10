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

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Estimates the token count of prompt text so diff budgeting can size each model call by tokens
 * instead of lines — lines are a poor proxy (5000 lines of dense code ≈ 128k tokens).
 *
 * <p>Uses a single OpenAI BPE encoding ({@code cl100k_base}) as a provider-agnostic estimate: exact
 * for OpenAI models, close for most others (BPE vocabularies are broadly similar on code/English).
 * Callers apply their own safety margin on top so an under-estimate never blows the budget. Exact
 * per-model encodings and self-calibration against the API's real {@code usage.prompt_tokens} are a
 * follow-up.
 *
 * <p>The {@code cl100k_base.tiktoken} table ships inside the jtokkit jar; it is registered for the
 * native image via {@code quarkus.native.resources.includes} in {@code application.properties}.
 */
@ApplicationScoped
public class TokenCounter {

  private final Encoding encoding;

  public TokenCounter() {
    // Lazy registry so only cl100k_base is loaded (1.6 MB), not every bundled encoding.
    this.encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
  }

  /** Estimated token count of {@code text}; 0 for null/empty. */
  public int estimateTokens(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return encoding.countTokens(text);
  }
}
