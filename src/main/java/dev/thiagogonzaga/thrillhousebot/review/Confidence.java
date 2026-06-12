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
package dev.thiagogonzaga.thrillhousebot.review;

import java.util.Locale;

/**
 * Model-reported certainty that a finding is real. Ordered from most to least certain so callers
 * can compare with {@link #ordinal()} the same way {@link RiskLevel} severities compare.
 */
public enum Confidence {
  HIGH,
  MEDIUM,
  LOW;

  /**
   * Absent values map to HIGH so responses predating the confidence field keep their blocking
   * semantics; unrecognized labels map to MEDIUM so a garbled value never blocks a merge.
   */
  public static Confidence fromString(String value) {
    if (value == null || value.isBlank()) {
      return HIGH;
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "high" -> HIGH;
      case "low" -> LOW;
      default -> MEDIUM;
    };
  }
}
