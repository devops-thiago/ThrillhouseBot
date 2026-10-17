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
package dev.thiagogonzaga.thrillhousebot.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the registrar + resource pair against a real OpenTelemetry SDK built through the same
 * autoconfigure builder Quarkus uses, so the OTel→Prometheus naming conversion is covered without
 * booting the application.
 */
class PrometheusMetricsResourceTest {

  private static ThrillhouseConfig configWithPrometheusEnabled(boolean enabled) {
    var metrics = mock(ThrillhouseConfig.MetricsConfig.class);
    when(metrics.prometheusEnabled()).thenReturn(enabled);
    var config = mock(ThrillhouseConfig.class);
    when(config.metrics()).thenReturn(metrics);
    return config;
  }

  @Test
  void scrapeServesOtelMetricsInPrometheusTextFormat() throws IOException {
    var registrar = new PrometheusMetricsRegistrar(configWithPrometheusEnabled(true));
    var builder =
        AutoConfiguredOpenTelemetrySdk.builder()
            .disableShutdownHook()
            .addPropertiesSupplier(
                () ->
                    Map.of(
                        "otel.traces.exporter", "none",
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none"));
    registrar.customize(builder);
    var sdk = builder.build().getOpenTelemetrySdk();
    try {
      new ReviewOutcomeMetrics(sdk).recordCompleted();

      var response = new PrometheusMetricsResource(registrar).scrape();

      assertEquals(200, response.getStatus());
      assertTrue(response.getMediaType().toString().startsWith("text/plain"));
      var body = new String((byte[]) response.getEntity(), StandardCharsets.UTF_8);
      assertTrue(body.contains("thrillhouse_reviews_total"), body);
      assertTrue(body.contains("outcome=\"completed\""), body);
    } finally {
      sdk.close();
    }
  }

  @Test
  void scrapeReturns404WhenPrometheusDisabled() throws IOException {
    var registrar = new PrometheusMetricsRegistrar(configWithPrometheusEnabled(false));
    var builder = AutoConfiguredOpenTelemetrySdk.builder();
    registrar.customize(builder);

    assertTrue(registrar.reader().isEmpty());
    assertEquals(404, new PrometheusMetricsResource(registrar).scrape().getStatus());
  }
}
