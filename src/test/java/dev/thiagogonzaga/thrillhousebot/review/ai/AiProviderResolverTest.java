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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AiProviderResolverTest {

  @ParameterizedTest
  @CsvSource({
    "https://api.deepseek.com/v1, deepseek",
    "https://api.openai.com/v1, openai",
    "https://api.groq.com/openai/v1, groq",
    "https://openrouter.ai/api/v1, openrouter",
    "https://api.anthropic.com/v1, anthropic",
    "https://api.mistral.ai/v1, mistral_ai",
    "https://api.perplexity.ai, perplexity",
    "https://api.x.ai/v1, xai",
    "https://dashscope.aliyuncs.com/compatible-mode/v1, alibaba",
    "https://dashscope-intl.aliyuncs.com/compatible-mode/v1, alibaba",
  })
  void mapsWellKnownHostsToCanonicalProviders(String baseUrl, String expected) {
    assertEquals(expected, AiProviderResolver.fromBaseUrl(baseUrl));
  }

  @ParameterizedTest
  @CsvSource({
    // Hosts that merely contain a known domain as a substring must NOT match it.
    "https://api.matrix.ai/v1, matrix",
    "https://api.linux.ai/v1, linux",
    "https://api.phoenix.ai, phoenix",
    // Suffix spoofing: the real registrable domain wins, not the embedded provider domain.
    "https://api.openai.com.evil.com/v1, evil",
  })
  void anchorsMatchingToHostBoundaries(String baseUrl, String expected) {
    assertEquals(expected, AiProviderResolver.fromBaseUrl(baseUrl));
  }

  @ParameterizedTest
  @CsvSource({
    "https://api.example.com/v1, example",
    "https://llm.acme.io, acme",
    "https://gateway.internal.corp/v1, internal",
    // Multi-label public suffixes resolve to the registrable name, not the suffix component.
    "https://api.example.co.uk, example",
    "https://acme.com.br/v1, acme",
    "https://foo.ac.uk, foo",
  })
  void fallsBackToRegistrableDomainLabel(String baseUrl, String expected) {
    assertEquals(expected, AiProviderResolver.fromBaseUrl(baseUrl));
  }

  @ParameterizedTest
  @CsvSource({
    // Hosts with underscores: URI.getHost() returns null, but the host is recovered from authority.
    "http://ai_gateway:8080/v1, ai_gateway",
    "http://user:pass@ai_host:9000/v1, ai_host",
    // Single-label host has no registrable suffix; the host itself is the label.
    "http://myserver/v1, myserver",
  })
  void recoversUnconventionalButValidHosts(String baseUrl, String expected) {
    assertEquals(expected, AiProviderResolver.fromBaseUrl(baseUrl));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Loopback in every form, and bare IPs, are not provider-identifying.
        "http://localhost:11434/v1",
        "http://ollama.localhost/v1",
        "http://127.0.0.1:11434/v1",
        "http://[::1]:11434/v1",
        "http://[0:0:0:0:0:0:0:1]:11434/v1",
        "http://10.0.0.5:8000/v1",
        "https://192.168.1.10/v1",
        "https://[2001:db8::1]/v1",
      })
  void mapsLoopbackAndBareIpHostsToUnknown(String baseUrl) {
    assertEquals(AiProviderResolver.UNKNOWN_PROVIDER, AiProviderResolver.fromBaseUrl(baseUrl));
  }

  @Test
  void isCaseInsensitiveOnHost() {
    assertEquals("deepseek", AiProviderResolver.fromBaseUrl("https://API.DeepSeek.COM/v1"));
  }

  @Test
  void toleratesSchemelessBaseUrl() {
    assertEquals("deepseek", AiProviderResolver.fromBaseUrl("api.deepseek.com/v1"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "not a url"})
  void returnsUnknownForBlankOrUnparseableInput(String baseUrl) {
    assertEquals(AiProviderResolver.UNKNOWN_PROVIDER, AiProviderResolver.fromBaseUrl(baseUrl));
  }
}
