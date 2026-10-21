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
package dev.thiagogonzaga.thrillhousebot.review.eval;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.FinishReason;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Records how each model call of an eval sample ended (#1169), so a sample run through the
 * production call path can be held to one call, plus the one repeat a call that reasoned its whole
 * cap away gets: a sample that needed a retry or a repair is not the prompt's answer, and passing
 * it would loosen the grade. Only the eval reads it; in any other test it records and is ignored.
 */
@ApplicationScoped
public class EvalCallRecorder implements ChatModelListener {

  /** How one call ended: its finish reason, output tokens and whether any answer text arrived. */
  record Call(FinishReason finishReason, Integer outputTokens, boolean answered) {

    @Override
    public String toString() {
      return finishReason + "/" + outputTokens + (answered ? "" : "/no-content");
    }
  }

  private final List<Call> calls = new ArrayList<>();

  @Override
  public void onResponse(ChatModelResponseContext ctx) {
    var response = ctx.chatResponse();
    var text = response.aiMessage() == null ? null : response.aiMessage().text();
    var usage = response.tokenUsage();
    synchronized (calls) {
      calls.add(
          new Call(
              response.finishReason(),
              usage == null ? null : usage.outputTokenCount(),
              text != null && !text.isBlank()));
    }
  }

  /** Forgets every call recorded so far, before a sample starts. */
  void reset() {
    synchronized (calls) {
      calls.clear();
    }
  }

  /** The calls recorded since the last {@link #reset}, in the order they ended. */
  List<Call> calls() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  /**
   * Whether {@code calls} is one call, or one call that stopped at its cap with no content followed
   * by its one repeat: the only shapes the eval grades as the prompt's own answer.
   */
  static boolean oneCallOrItsRepeat(List<Call> calls) {
    return calls.size() == 1
        || (calls.size() == 2
            && calls.get(0).finishReason() == FinishReason.LENGTH
            && !calls.get(0).answered());
  }
}
