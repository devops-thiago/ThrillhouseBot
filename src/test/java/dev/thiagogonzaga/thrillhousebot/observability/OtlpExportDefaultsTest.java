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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The OTLP export with log export left at its default (#1262): metrics follow {@code
 * OTEL_EXPORTER_ENDPOINT}, a per-signal Quarkus endpoint still sends its signal elsewhere, and no
 * log record is exported.
 */
@QuarkusTest
@TestProfile(OtlpExportDefaultsTest.DefaultsProfile.class)
class OtlpExportDefaultsTest {

  private static final Logger LOG = Logger.getLogger(OtlpExportDefaultsTest.class);

  @Inject ReviewOutcomeMetrics outcomeMetrics;
  @Inject OpenTelemetry openTelemetry;

  @ConfigProperty(name = "OTEL_EXPORTER_ENDPOINT")
  String collector;

  @ConfigProperty(name = "quarkus.otel.exporter.otlp.traces.endpoint")
  String tracesCollector;

  @Test
  void metricsFollowTheGenericEndpointAndTracesTheirOwn() {
    outcomeMetrics.recordFailed();
    openTelemetry.getTracer("test").spanBuilder("otlp-trace-export").startSpan().end();

    sdk().getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS);
    sdk().getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);

    assertTrue(
        OtlpReceiver.received(collector, "/v1/metrics", "thrillhouse.reviews.total"),
        () -> OtlpReceiver.describe(collector));
    assertTrue(
        OtlpReceiver.received(tracesCollector, "/v1/traces", "otlp-trace-export"),
        () -> OtlpReceiver.describe(tracesCollector));
    assertFalse(
        OtlpReceiver.received(collector, "/v1/traces", "otlp-trace-export"),
        () -> OtlpReceiver.describe(collector));
  }

  @Test
  void logRecordsAreNotExportedByDefault() {
    var marker = "otlp-log-export-" + UUID.randomUUID();
    LOG.info(marker);

    sdk().getSdkLoggerProvider().forceFlush().join(10, TimeUnit.SECONDS);

    assertFalse(
        OtlpReceiver.received(collector, "/v1/logs", marker),
        () -> OtlpReceiver.describe(collector));
  }

  private OpenTelemetrySdk sdk() {
    return (OpenTelemetrySdk) openTelemetry;
  }

  public static class DefaultsProfile implements QuarkusTestProfile {
    private static final OtlpReceiver COLLECTOR = OtlpReceiver.start();
    private static final OtlpReceiver TRACES_COLLECTOR = OtlpReceiver.start();

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.otel.sdk.disabled",
          "false",
          "OTEL_EXPORTER_ENDPOINT",
          COLLECTOR.endpoint(),
          "quarkus.otel.exporter.otlp.traces.endpoint",
          TRACES_COLLECTOR.endpoint(),
          "quarkus.otel.exporter.otlp.protocol",
          "http/protobuf");
    }
  }
}
