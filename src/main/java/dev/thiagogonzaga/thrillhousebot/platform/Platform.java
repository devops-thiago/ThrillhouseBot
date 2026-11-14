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
package dev.thiagogonzaga.thrillhousebot.platform;

import java.util.Locale;
import java.util.Optional;

/** The platform hosting the repositories this deployment reviews (#37). One per deployment. */
public enum Platform {
  GITHUB,
  GITLAB;

  /** The platform a {@code GIT_PLATFORM} value names, whatever its case; empty for any other. */
  public static Optional<Platform> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    var name = value.strip().toUpperCase(Locale.ROOT);
    for (var platform : values()) {
      if (platform.name().equals(name)) {
        return Optional.of(platform);
      }
    }
    return Optional.empty();
  }
}
