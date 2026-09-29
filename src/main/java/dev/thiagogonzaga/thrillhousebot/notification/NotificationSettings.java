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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.NotificationsConfig.OutgoingWebhookConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The outgoing webhook's configuration, checked and resolved once (#73).
 *
 * <p>{@link #problems} is what {@code StartupConfigValidator} refuses a boot over, and {@link
 * #resolve} is what the notifier runs on; both read the same rules, so a value the validator let
 * through always resolves. Messages never quote the URL: a Slack or Discord incoming-webhook URL is
 * itself the credential.
 *
 * @param url the receiver
 * @param format the body format
 * @param secret the HMAC-SHA256 key, or empty for unsigned requests
 * @param events the outcomes that are sent
 * @param includeContent whether the PR title and finding titles are sent
 * @param timeout the per-attempt request timeout
 * @param maxAttempts attempts per notification, the first included
 */
public record NotificationSettings(
    URI url,
    NotificationFormat format,
    Optional<String> secret,
    Set<ReviewOutcome.Event> events,
    boolean includeContent,
    Duration timeout,
    int maxAttempts) {

  private static final String URL_VAR = "NOTIFICATIONS_WEBHOOK_URL";

  public NotificationSettings {
    events = Set.copyOf(events);
  }

  /**
   * The resolved settings, or empty when notifications are off (no URL) or the configuration is
   * invalid — the latter never reaches a running process, because the validator refuses the boot.
   */
  public static Optional<NotificationSettings> resolve(OutgoingWebhookConfig config) {
    if (!enabled(config) || !problems(config).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new NotificationSettings(
            URI.create(config.url().orElseThrow().strip()),
            NotificationFormat.from(config.format()).orElseThrow(),
            config.secret().filter(s -> !s.isBlank()),
            parseEvents(config.events()),
            config.includeContent(),
            config.timeout(),
            config.maxAttempts()));
  }

  /** Whether an operator switched notifications on, by setting a URL. */
  public static boolean enabled(OutgoingWebhookConfig config) {
    return config.url().filter(u -> !u.isBlank()).isPresent();
  }

  /**
   * Every problem with an enabled configuration, worded for the boot refusal. Empty when
   * notifications are off: nothing else is read then, so a stray value cannot block a boot.
   */
  public static List<String> problems(OutgoingWebhookConfig config) {
    var problems = new ArrayList<String>();
    if (!enabled(config)) {
      return problems;
    }
    urlProblem(config.url().orElseThrow().strip(), config.allowHttp()).ifPresent(problems::add);
    if (NotificationFormat.from(config.format()).isEmpty()) {
      problems.add(
          "NOTIFICATIONS_WEBHOOK_FORMAT must be one of "
              + String.join(", ", OutgoingWebhookConfig.ALLOWED_FORMATS)
              + " (thrillhousebot.notifications.webhook.format): "
              + config.format());
    }
    var events = config.events() == null ? List.<String>of() : config.events();
    var unknown =
        events.stream()
            .map(e -> e.strip().toLowerCase(Locale.ROOT))
            .filter(e -> !OutgoingWebhookConfig.ALLOWED_EVENTS.contains(e))
            .toList();
    if (!unknown.isEmpty() || parseEvents(events).isEmpty()) {
      problems.add(
          "NOTIFICATIONS_WEBHOOK_EVENTS must list one or more of "
              + String.join(", ", OutgoingWebhookConfig.ALLOWED_EVENTS)
              + " (thrillhousebot.notifications.webhook.events): "
              + String.join(",", events));
    }
    var timeout = config.timeout();
    if (timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(OutgoingWebhookConfig.MAX_TIMEOUT) > 0) {
      problems.add(
          "NOTIFICATIONS_WEBHOOK_TIMEOUT must be above 0 and at most "
              + OutgoingWebhookConfig.MAX_TIMEOUT.toSeconds()
              + "s (thrillhousebot.notifications.webhook.timeout): "
              + timeout);
    }
    if (config.maxAttempts() < 1
        || config.maxAttempts() > OutgoingWebhookConfig.MAX_ATTEMPTS_CEILING) {
      problems.add(
          "NOTIFICATIONS_WEBHOOK_MAX_ATTEMPTS must be between 1 and "
              + OutgoingWebhookConfig.MAX_ATTEMPTS_CEILING
              + " (thrillhousebot.notifications.webhook.max-attempts): "
              + config.maxAttempts());
    }
    return problems;
  }

  /**
   * What is wrong with the URL, without quoting it. An absolute https URL with a host is required;
   * plain http only when the operator opted in for local testing. Userinfo is refused because it
   * would put a credential where HTTP clients and proxies log it.
   */
  private static Optional<String> urlProblem(String raw, boolean allowHttp) {
    URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException _) {
      return Optional.of(
          URL_VAR + " is not a valid URL (its value is not shown: it may be secret)");
    }
    var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !("http".equals(scheme) && allowHttp)) {
      return Optional.of(
          URL_VAR
              + " must be an https:// URL; set NOTIFICATIONS_WEBHOOK_ALLOW_HTTP=true to accept"
              + " http:// for local testing");
    }
    if (uri.getHost() == null) {
      return Optional.of(URL_VAR + " must name a host");
    }
    if (uri.getRawUserInfo() != null) {
      return Optional.of(
          URL_VAR
              + " must not carry credentials before the host; use NOTIFICATIONS_WEBHOOK_SECRET");
    }
    return Optional.empty();
  }

  private static Set<ReviewOutcome.Event> parseEvents(List<String> raw) {
    var events = EnumSet.noneOf(ReviewOutcome.Event.class);
    for (var value : raw) {
      ReviewOutcome.Event.from(value).ifPresent(events::add);
    }
    return events;
  }

  /**
   * The receiver as it may be logged: scheme and host only. Everything after the host — where Slack
   * and Discord keep the webhook's credential — is withheld.
   */
  public String redactedUrl() {
    return redact(url);
  }

  /** Scheme and host of {@code uri}, with the rest withheld. Visible for tests. */
  static String redact(URI uri) {
    var port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
    return uri.getScheme() + "://" + uri.getHost() + port + "/…";
  }

  /** Whether requests are signed. */
  public boolean signed() {
    return secret.isPresent();
  }

  /** Never prints the secret or the full URL, should the record end up in a log line. */
  @Override
  public String toString() {
    return "NotificationSettings[url="
        + redactedUrl()
        + ", format="
        + format
        + ", signed="
        + signed()
        + ", events="
        + events
        + ", includeContent="
        + includeContent
        + ", timeout="
        + timeout
        + ", maxAttempts="
        + maxAttempts
        + "]";
  }
}
