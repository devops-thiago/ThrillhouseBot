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

import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.Result;

/**
 * Builds the {@link Result} wrapper the blocking AI services now return, so a test stubs an
 * assistant with the response text it cares about and nothing else.
 *
 * <p>{@link #aiOk(String)} carries {@link FinishReason#STOP} — a model that finished answering —
 * which is what every pre-existing stub meant before the wrapper existed. {@link #aiTruncated} is
 * its counterpart for the cut-short case.
 */
public final class AiResults {

  private AiResults() {}

  /** A complete response: the model stopped because it was done. */
  public static Result<String> aiOk(String text) {
    return Result.<String>builder().content(text).finishReason(FinishReason.STOP).build();
  }

  /** A response cut short at the model's response-length cap. */
  public static Result<String> aiTruncated(String partialText) {
    return Result.<String>builder().content(partialText).finishReason(FinishReason.LENGTH).build();
  }

  /**
   * A completed response carrying no content body at all — the "no response" case a reasoning model
   * produces when its whole output budget went to reasoning tokens, with no length stop to show for
   * it (#534).
   */
  public static Result<String> aiNoContent() {
    return Result.<String>builder().finishReason(FinishReason.STOP).build();
  }
}
