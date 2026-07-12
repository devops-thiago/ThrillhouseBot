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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-thread binding and per-session in-flight call tracking for AI observability callbacks.
 * Multiple concurrent streams (parallel map-reduce batches, or a retry overlapping a late callback)
 * each get a unique {@code callId}; invalidating one does not drop the others.
 */
final class ReviewSessionContext {

  private static final ThreadLocal<Binding> BINDING = new ThreadLocal<>();
  private static final ConcurrentHashMap<Long, Set<Long>> ACTIVE_CALLS = new ConcurrentHashMap<>();
  private static final AtomicLong NEXT_CALL_ID = new AtomicLong();

  private ReviewSessionContext() {}

  /**
   * @param attempt retry attempt within one logical AI call (1-based), for dashboard/logging
   * @param callId unique id for this stream invocation, used to gate stale callbacks
   */
  record Binding(long sessionId, int attempt, long callId) {}

  static void bind(long sessionId, int attempt) {
    var callId = NEXT_CALL_ID.incrementAndGet();
    BINDING.set(new Binding(sessionId, attempt, callId));
    ACTIVE_CALLS.computeIfAbsent(sessionId, id -> ConcurrentHashMap.newKeySet()).add(callId);
  }

  static void clear() {
    BINDING.remove();
  }

  /**
   * Drops every in-flight call for {@code sessionId}. Prefer {@link #invalidate(long, long)} when
   * only one concurrent stream finished.
   */
  static void invalidate(long sessionId) {
    ACTIVE_CALLS.remove(sessionId);
  }

  /** Marks one stream invocation finished so late callbacks for it are ignored. */
  static void invalidate(long sessionId, long callId) {
    ACTIVE_CALLS.computeIfPresent(
        sessionId,
        (id, calls) -> {
          calls.remove(callId);
          return calls.isEmpty() ? null : calls;
        });
  }

  /** Test-only: clears the thread binding and every in-flight registration. */
  static void reset() {
    BINDING.remove();
    ACTIVE_CALLS.clear();
  }

  static Long currentSessionId() {
    var binding = BINDING.get();
    return binding != null ? binding.sessionId() : null;
  }

  static Integer currentAttempt() {
    var binding = BINDING.get();
    return binding != null ? binding.attempt() : null;
  }

  static Long currentCallId() {
    var binding = BINDING.get();
    return binding != null ? binding.callId() : null;
  }

  static boolean isActiveCall(long sessionId, long callId) {
    var calls = ACTIVE_CALLS.get(sessionId);
    return calls != null && calls.contains(callId);
  }
}
