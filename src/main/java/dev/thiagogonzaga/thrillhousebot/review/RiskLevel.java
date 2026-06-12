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

public enum RiskLevel {
  CRITICAL,
  HIGH,
  MEDIUM,
  LOW;

  public static RiskLevel fromString(String value) {
    if (value == null) return LOW;
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "critical" -> CRITICAL;
      case "high" -> HIGH;
      case "medium" -> MEDIUM;
      default -> LOW;
    };
  }

  public String toEmoji() {
    return switch (this) {
      case CRITICAL -> "🔴";
      case HIGH -> "🟠";
      case MEDIUM -> "🟡";
      case LOW -> "🔵";
    };
  }
}
