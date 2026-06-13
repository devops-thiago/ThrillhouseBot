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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives the GenAI provider label (the OpenTelemetry {@code gen_ai.provider.name} attribute) from
 * the configured OpenAI-compatible base URL.
 *
 * <p>ThrillhouseBot talks to many providers (DeepSeek, OpenAI, Qwen, OpenRouter, Groq, …) through
 * the same OpenAI-compatible client, so the provider cannot be assumed — it is inferred from the
 * endpoint host. Operators can always override the result with an explicit provider name.
 */
final class AiProviderResolver {

  /** Label used when the host is missing, a bare IP/loopback, or otherwise not identifying. */
  static final String UNKNOWN_PROVIDER = "unknown";

  /**
   * Known provider domains mapped to canonical names. Matched on host boundaries (exact host or a
   * subdomain), so regional/versioned subdomains like {@code dashscope-intl.aliyuncs.com} resolve
   * correctly while unrelated hosts that merely contain a domain as a substring (e.g. {@code
   * matrix.ai} vs {@code x.ai}) do not. The list has a fixed iteration order, so the result is
   * deterministic even if a future host could match more than one entry. Canonical names follow the
   * OpenTelemetry GenAI conventions where one exists.
   */
  private static final List<Map.Entry<String, String>> KNOWN_DOMAINS =
      List.of(
          Map.entry("deepseek.com", "deepseek"),
          Map.entry("openai.com", "openai"),
          Map.entry("groq.com", "groq"),
          Map.entry("openrouter.ai", "openrouter"),
          Map.entry("anthropic.com", "anthropic"),
          Map.entry("mistral.ai", "mistral_ai"),
          Map.entry("perplexity.ai", "perplexity"),
          Map.entry("x.ai", "xai"),
          Map.entry("aliyuncs.com", "alibaba"));

  /**
   * Two-label public suffixes (e.g. {@code co.uk}, {@code com.br}) so the registrable name is taken
   * from the correct label rather than the suffix component.
   */
  private static final Set<String> SECOND_LEVEL_SUFFIXES =
      Set.of("co", "com", "net", "org", "gov", "edu", "ac");

  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

  private AiProviderResolver() {}

  /**
   * Derives a provider name from {@code baseUrl}.
   *
   * <ul>
   *   <li>Known provider domains map to their canonical name (e.g. {@code
   *       https://api.deepseek.com/v1} → {@code deepseek}).
   *   <li>Other hosts fall back to the registrable domain label (e.g. {@code https://api.acme.com}
   *       → {@code acme}, {@code https://api.example.co.uk} → {@code example}).
   *   <li>Loopback, bare-IP, blank, or unparseable endpoints map to {@link #UNKNOWN_PROVIDER}; set
   *       an explicit provider name to label these (e.g. a local Ollama or vLLM server).
   * </ul>
   */
  static String fromBaseUrl(String baseUrl) {
    var host = hostOf(baseUrl);
    if (host == null || host.isBlank()) {
      return UNKNOWN_PROVIDER;
    }
    host = host.toLowerCase(Locale.ROOT);
    var known = matchKnownDomain(host);
    if (known != null) {
      return known;
    }
    if (isLocalhost(host) || isIpLiteral(host)) {
      return UNKNOWN_PROVIDER;
    }
    return registrableLabel(host);
  }

  private static String matchKnownDomain(String host) {
    for (var entry : KNOWN_DOMAINS) {
      var domain = entry.getKey();
      if (host.equals(domain) || host.endsWith("." + domain)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String hostOf(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return null;
    }
    var trimmed = baseUrl.trim();
    var host = hostFrom(trimmed);
    // Tolerate scheme-less values like "api.deepseek.com/v1" by re-parsing as an authority.
    if (host == null && !trimmed.contains("://")) {
      host = hostFrom("//" + trimmed);
    }
    return host;
  }

  private static String hostFrom(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
    var host = uri.getHost();
    if (host != null) {
      return host;
    }
    // URI.getHost() rejects otherwise-valid reg-names containing underscores (common for
    // Docker/Compose service names), so recover the host from the raw authority.
    return hostFromAuthority(uri.getAuthority());
  }

  private static String hostFromAuthority(String authority) {
    if (authority == null || authority.isBlank()) {
      return null;
    }
    var hostPort = authority.substring(authority.lastIndexOf('@') + 1);
    if (hostPort.startsWith("[")) {
      var close = hostPort.indexOf(']');
      return close > 0 ? hostPort.substring(0, close + 1) : null;
    }
    var colon = hostPort.indexOf(':');
    var host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
    return host.isBlank() ? null : host;
  }

  private static boolean isLocalhost(String host) {
    return host.equals("localhost") || host.endsWith(".localhost");
  }

  private static boolean isIpLiteral(String host) {
    return IPV4.matcher(host).matches() || host.startsWith("[");
  }

  /**
   * Returns the registrable label — the name just left of the public suffix (e.g. {@code
   * api.deepseek.com} → {@code deepseek}, {@code api.example.co.uk} → {@code example}).
   */
  private static String registrableLabel(String host) {
    var labels = host.split("\\.");
    if (labels.length < 2) {
      return host;
    }
    var tld = labels[labels.length - 1];
    var secondLevel = labels[labels.length - 2];
    if (labels.length >= 3 && tld.length() == 2 && SECOND_LEVEL_SUFFIXES.contains(secondLevel)) {
      return labels[labels.length - 3];
    }
    return secondLevel;
  }
}
