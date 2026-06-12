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

import java.util.concurrent.ConcurrentHashMap;

/** Per-thread binding and per-session attempt tracking for AI observability callbacks. */
final class ReviewSessionContext {

  private static final ThreadLocal<Binding> BINDING = new ThreadLocal<>();
  private static final ConcurrentHashMap<Long, Integer> ACTIVE_ATTEMPT = new ConcurrentHashMap<>();

  private ReviewSessionContext() {}

  record Binding(long sessionId, int attempt) {}

  static void bind(long sessionId, int attempt) {
    BINDING.set(new Binding(sessionId, attempt));
    ACTIVE_ATTEMPT.put(sessionId, attempt);
  }

  static void clear() {
    BINDING.remove();
  }

  static void invalidate(long sessionId) {
    ACTIVE_ATTEMPT.remove(sessionId);
  }

  /** Test-only: clears the thread binding and every attempt registration. */
  static void reset() {
    BINDING.remove();
    ACTIVE_ATTEMPT.clear();
  }

  static Long currentSessionId() {
    var binding = BINDING.get();
    return binding != null ? binding.sessionId() : null;
  }

  static Integer currentAttempt() {
    var binding = BINDING.get();
    return binding != null ? binding.attempt() : null;
  }

  static boolean isActiveAttempt(long sessionId, int attempt) {
    var active = ACTIVE_ATTEMPT.get(sessionId);
    return active != null && active == attempt;
  }
}
