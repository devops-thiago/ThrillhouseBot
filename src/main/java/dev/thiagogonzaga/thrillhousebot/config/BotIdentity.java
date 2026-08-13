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
package dev.thiagogonzaga.thrillhousebot.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The GitHub login(s) this App's bot posts under, normalized for matching.
 *
 * <p>Built from {@link ThrillhouseConfig.GitHubConfig#botLogins()} and shared by every component
 * that must recognize the bot's own activity — comment-trigger filtering, summary dedup,
 * prior-review detection, and follow-up thread matching — so they can never disagree on who the bot
 * is. A single deployment runs under exactly one {@code <app-slug>[bot]} login, but the project
 * ships defaults for both slugs it may use; matching against the whole set (not one hardcoded slug)
 * is what keeps the bot from re-reviewing itself or re-raising resolved findings when deployed
 * under the alternate slug.
 */
public record BotIdentity(Set<String> logins) {

  /**
   * Built-in bot logins used when none are configured. Mirrors the {@code @WithDefault} on {@link
   * ThrillhouseConfig.GitHubConfig#botLogins()}.
   */
  public static final List<String> DEFAULT_LOGINS =
      List.of("thrillhousebot[bot]", "thrillhouse-bot[bot]");

  /** The suffix GitHub appends to an App's slug to form its bot login. */
  private static final String BOT_LOGIN_SUFFIX = "[bot]";

  /**
   * Normalizes the supplied logins (trimmed, lower-cased, blanks dropped). The stored set is never
   * empty: an empty set would make {@link #matches} always false and let the bot answer its own
   * comments in an infinite loop, so it falls back to {@link #DEFAULT_LOGINS}. Configured order is
   * preserved so {@link #primaryMention} is deterministic.
   */
  public BotIdentity {
    var normalized = normalize(logins);
    logins =
        Collections.unmodifiableSet(normalized.isEmpty() ? normalize(DEFAULT_LOGINS) : normalized);
  }

  /** Builds an identity from a configured list; a {@code null} list yields the defaults. */
  public static BotIdentity from(List<String> configuredLogins) {
    return new BotIdentity(
        configuredLogins == null ? Set.of() : new LinkedHashSet<>(configuredLogins));
  }

  /** Convenience factory for tests and fixed wiring. */
  public static BotIdentity of(String... logins) {
    return from(Arrays.asList(logins));
  }

  /** Whether {@code login} is one of the bot's own logins, compared case-insensitively. */
  public boolean matches(String login) {
    return login != null && logins.contains(login.toLowerCase(Locale.ROOT));
  }

  /**
   * The names a maintainer types to {@code @}-mention this bot, in login order. A GitHub App whose
   * login is {@code <app-slug>[bot]} is mentioned as {@code @<app-slug>} — the {@code [bot]} suffix
   * is not part of the mention — so it is stripped here; a login without the suffix (a PAT-driven
   * bot account) mentions as-is. Never empty: a pathological login that is nothing but the suffix
   * falls back to the raw logins rather than yielding a name no comment can contain.
   */
  public List<String> mentionNames() {
    var names =
        logins.stream().map(BotIdentity::mentionName).filter(s -> !s.isBlank()).distinct().toList();
    return names.isEmpty() ? List.copyOf(logins) : names;
  }

  /**
   * The mention name rendered in user-facing text that spells out the directive form — the first
   * configured login's, so a custom install's docs and acknowledgements name the bot the install
   * actually answers to.
   */
  public String primaryMention() {
    return mentionNames().get(0);
  }

  private static String mentionName(String login) {
    return login.endsWith(BOT_LOGIN_SUFFIX)
        ? login.substring(0, login.length() - BOT_LOGIN_SUFFIX.length())
        : login;
  }

  private static Set<String> normalize(Collection<String> logins) {
    return logins.stream()
        .filter(s -> s != null && !s.isBlank())
        .map(s -> s.strip().toLowerCase(Locale.ROOT))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
