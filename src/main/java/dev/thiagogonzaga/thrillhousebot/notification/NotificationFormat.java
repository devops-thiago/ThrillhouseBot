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
package dev.thiagogonzaga.thrillhousebot.notification;

import java.util.Locale;
import java.util.Optional;

/** The body shape an outgoing webhook is sent in (#73). */
public enum NotificationFormat {
  /** The structured payload, for pipelines and custom receivers. */
  JSON,
  /** A Slack incoming-webhook message ({@code text} in Slack mrkdwn). */
  SLACK,
  /** A Discord webhook message ({@code content} in Discord markdown, mentions disabled). */
  DISCORD;

  /** The format named by a configured value, compared case-insensitively. */
  public static Optional<NotificationFormat> from(String value) {
    if (value == null) {
      return Optional.empty();
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "json" -> Optional.of(JSON);
      case "slack" -> Optional.of(SLACK);
      case "discord" -> Optional.of(DISCORD);
      default -> Optional.empty();
    };
  }
}
