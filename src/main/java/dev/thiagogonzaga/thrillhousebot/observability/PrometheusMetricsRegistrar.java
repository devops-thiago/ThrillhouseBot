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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.quarkus.opentelemetry.runtime.AutoConfiguredOpenTelemetrySdkBuilderCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers a {@link PrometheusMetricReader} on the OpenTelemetry SDK so the metrics exported over
 * OTLP can also be scraped in Prometheus text format from {@code GET /metrics} (served by {@link
 * PrometheusMetricsResource}). Quarkus invokes every CDI bean implementing {@link
 * AutoConfiguredOpenTelemetrySdkBuilderCustomizer} while building the SDK; the reader stays unset —
 * and the endpoint answers 404 — when {@code thrillhousebot.metrics.prometheus-enabled=false} or
 * the OTel SDK itself is disabled.
 */
@ApplicationScoped
public class PrometheusMetricsRegistrar implements AutoConfiguredOpenTelemetrySdkBuilderCustomizer {

  private final boolean enabled;
  private final AtomicReference<PrometheusMetricReader> reader = new AtomicReference<>();

  @Inject
  public PrometheusMetricsRegistrar(ThrillhouseConfig config) {
    this.enabled = config.metrics().prometheusEnabled();
  }

  @Override
  public void customize(AutoConfiguredOpenTelemetrySdkBuilder builder) {
    if (!enabled) {
      return;
    }
    builder.addMeterProviderCustomizer(
        (meterProviderBuilder, configProperties) -> {
          var prometheusReader = PrometheusMetricReader.create();
          reader.set(prometheusReader);
          return meterProviderBuilder.registerMetricReader(prometheusReader);
        });
  }

  /** The registered reader, or empty when the endpoint is disabled or the SDK never started. */
  Optional<PrometheusMetricReader> reader() {
    return Optional.ofNullable(reader.get());
  }
}
