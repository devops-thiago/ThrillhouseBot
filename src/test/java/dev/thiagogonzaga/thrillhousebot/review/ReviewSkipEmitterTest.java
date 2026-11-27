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

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewSkipEmitterTest {

  private ReviewSkipEmitter emitter;

  @BeforeEach
  void setUp() {
    emitter = new ReviewSkipEmitter(OpenTelemetry.noop());
  }

  @Test
  void shouldStartWithNoCounts() {
    assertTrue(emitter.countsByReason(java.util.List.of(1L)).isEmpty());
  }

  @Test
  void countsAreKeptPerInstallationAndSummedOverTheOnesAsked() {
    emitter.recordSkip(ReviewSkipReason.DRAFT, 1L, "acme", "app", 1, "draft");
    emitter.recordSkip(ReviewSkipReason.DRAFT, 2L, "globex", "app", 1, "draft");
    emitter.recordSkip(ReviewSkipReason.PAUSED, 2L, "globex", "app", 2, "paused");
    emitter.recordSkip(
        ReviewSkipReason.DUPLICATE_DELIVERY,
        ReviewSkipEmitter.UNKNOWN_INSTALLATION,
        "acme",
        "app",
        3,
        "redelivered");

    assertEquals(java.util.Map.of("DRAFT", 1L), emitter.countsByReason(java.util.List.of(1L)));
    assertEquals(
        java.util.Map.of("DRAFT", 2L, "PAUSED", 1L),
        emitter.countsByReason(java.util.List.of(1L, 2L, 3L)));
    assertTrue(emitter.countsByReason(java.util.List.of()).isEmpty());
  }

  @Test
  void shouldCountSkipsByReason() {
    emitter.recordSkip(ReviewSkipReason.DRAFT, 1L, "owner", "repo", 1, "draft");
    emitter.recordSkip(ReviewSkipReason.DRAFT, 1L, "owner", "repo", 2, "draft");
    emitter.recordSkip(ReviewSkipReason.PAUSED, 1L, "owner", "repo", 3, "paused");

    var counts = emitter.countsByReason(java.util.List.of(1L));
    assertEquals(2L, counts.get("DRAFT"));
    assertEquals(1L, counts.get("PAUSED"));
    assertNull(counts.get("RATE_LIMITED"));
  }

  @Test
  void shouldOrderCountsByEnumDeclaration() {
    emitter.recordSkip(ReviewSkipReason.RATE_LIMITED, 1L, "owner", "repo", 1, "throttled");
    emitter.recordSkip(ReviewSkipReason.PAUSED, 1L, "owner", "repo", 2, "paused");
    emitter.recordSkip(ReviewSkipReason.DRAFT, 1L, "owner", "repo", 3, "draft");

    assertEquals(
        List.of("PAUSED", "DRAFT", "RATE_LIMITED"),
        List.copyOf(emitter.countsByReason(java.util.List.of(1L)).keySet()));
  }

  @Test
  void theScrapedSkipCounterNamesNoRepository() throws IOException {
    var reader = PrometheusMetricReader.create();
    try (var meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        var sdk = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build()) {
      var scraped = new ReviewSkipEmitter(sdk);
      scraped.recordSkip(ReviewSkipReason.DRAFT, 1L, "acme", "unreleased-product", 7, "draft");
      scraped.recordSkip(ReviewSkipReason.DRAFT, 1L, "acme", "payroll", 8, "draft");
      scraped.recordSkip(ReviewSkipReason.PAUSED, 2L, "globex", "internal-tools", 9, "paused");

      var buffer = new ByteArrayOutputStream();
      PrometheusTextFormatWriter.create().write(buffer, reader.collect());
      var text = buffer.toString(StandardCharsets.UTF_8);

      var skipLines =
          text.lines().filter(line -> line.startsWith("thrillhouse_review_skips_total{")).toList();
      // One series per reason: the two DRAFT skips of different repositories add up.
      assertEquals(2, skipLines.size(), text);
      assertTrue(
          skipLines.stream().anyMatch(l -> l.contains("reason=\"DRAFT\"") && l.endsWith(" 2.0")),
          text);
      assertTrue(
          skipLines.stream().anyMatch(l -> l.contains("reason=\"PAUSED\"") && l.endsWith(" 1.0")),
          text);
      for (var leaked :
          List.of("repository", "acme", "globex", "unreleased-product", "payroll", "internal")) {
        assertFalse(text.contains(leaked), leaked + " in:\n" + text);
      }
    }
  }
}
