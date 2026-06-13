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

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Derives the GenAI provider label (the OpenTelemetry {@code gen_ai.provider.name} attribute) from
 * the configured OpenAI-compatible base URL.
 *
 * <p>ThrillhouseBot talks to many providers (DeepSeek, OpenAI, Qwen, OpenRouter, Groq, Ollama, …)
 * through the same OpenAI-compatible client, so the provider cannot be assumed — it is inferred
 * from the endpoint host. Operators can always override the result with an explicit provider name.
 */
final class AiProviderResolver {

  /** Label used when the base URL is missing or cannot be parsed into a usable host. */
  static final String UNKNOWN_PROVIDER = "unknown";

  /**
   * Well-known host substrings mapped to canonical provider names. Keyed by a substring (not an
   * exact host) so regional and versioned subdomains — e.g. {@code dashscope-intl.aliyuncs.com} —
   * still match. Canonical names follow the OpenTelemetry GenAI conventions where one exists.
   */
  private static final Map<String, String> KNOWN_HOSTS =
      Map.ofEntries(
          Map.entry("deepseek.com", "deepseek"),
          Map.entry("openai.com", "openai"),
          Map.entry("groq.com", "groq"),
          Map.entry("openrouter.ai", "openrouter"),
          Map.entry("anthropic.com", "anthropic"),
          Map.entry("mistral.ai", "mistral_ai"),
          Map.entry("perplexity.ai", "perplexity"),
          Map.entry("x.ai", "xai"),
          Map.entry("aliyuncs.com", "alibaba"),
          Map.entry("dashscope", "alibaba"));

  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

  private AiProviderResolver() {}

  /**
   * Derives a provider name from {@code baseUrl}.
   *
   * <ul>
   *   <li>Known hosts map to their canonical name (e.g. {@code https://api.deepseek.com/v1} →
   *       {@code deepseek}).
   *   <li>Loopback hosts map to {@code ollama}, the most common local OpenAI-compatible server.
   *   <li>Other hosts fall back to the registrable domain label (e.g. {@code https://api.acme.com}
   *       → {@code acme}).
   *   <li>Blank, unparseable, or bare-IP endpoints map to {@link #UNKNOWN_PROVIDER}.
   * </ul>
   */
  static String fromBaseUrl(String baseUrl) {
    var host = hostOf(baseUrl);
    if (host == null || host.isBlank()) {
      return UNKNOWN_PROVIDER;
    }
    host = host.toLowerCase(Locale.ROOT);
    for (var entry : KNOWN_HOSTS.entrySet()) {
      if (host.contains(entry.getKey())) {
        return entry.getValue();
      }
    }
    if (isLoopback(host)) {
      return "ollama";
    }
    if (IPV4.matcher(host).matches() || host.startsWith("[")) {
      return UNKNOWN_PROVIDER;
    }
    return registrableLabel(host);
  }

  private static String hostOf(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return null;
    }
    var trimmed = baseUrl.trim();
    var host = parseHost(trimmed);
    // Tolerate scheme-less values like "api.deepseek.com/v1" by re-parsing as an authority.
    if (host == null && !trimmed.contains("://")) {
      host = parseHost("//" + trimmed);
    }
    return host;
  }

  private static String parseHost(String value) {
    try {
      return URI.create(value).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isLoopback(String host) {
    return host.equals("localhost")
        || host.endsWith(".localhost")
        || host.equals("127.0.0.1")
        || host.equals("[::1]")
        || host.equals("::1");
  }

  /** Returns the label just left of the TLD (e.g. {@code api.deepseek.com} → {@code deepseek}). */
  private static String registrableLabel(String host) {
    var labels = host.split("\\.");
    if (labels.length >= 2) {
      return labels[labels.length - 2];
    }
    return host;
  }
}
