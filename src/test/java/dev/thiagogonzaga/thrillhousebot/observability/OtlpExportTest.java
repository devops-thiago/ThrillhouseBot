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
 * The OTLP export to the endpoint {@code OTEL_EXPORTER_ENDPOINT} names, with log export switched on
 * (#1262). Before the fix the image had no OTLP metric exporter: a recorded metric never reached
 * the collector, though {@code GET /metrics} served it.
 */
@QuarkusTest
@TestProfile(OtlpExportTest.LogsOnProfile.class)
class OtlpExportTest {

  private static final Logger LOG = Logger.getLogger(OtlpExportTest.class);

  @Inject ReviewOutcomeMetrics outcomeMetrics;
  @Inject OpenTelemetry openTelemetry;

  @ConfigProperty(name = "OTEL_EXPORTER_ENDPOINT")
  String collector;

  @Test
  void recordedMetricsAreExportedToTheConfiguredEndpoint() {
    outcomeMetrics.recordCompleted();

    sdk().getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS);

    assertTrue(
        OtlpReceiver.received(collector, "/v1/metrics", "thrillhouse.reviews.total"),
        () -> OtlpReceiver.describe(collector));
  }

  @Test
  void logRecordsAreExportedWhenSwitchedOn() {
    var marker = "otlp-log-export-" + UUID.randomUUID();
    LOG.info(marker);

    sdk().getSdkLoggerProvider().forceFlush().join(10, TimeUnit.SECONDS);

    assertTrue(
        OtlpReceiver.received(collector, "/v1/logs", marker),
        () -> OtlpReceiver.describe(collector));
  }

  private OpenTelemetrySdk sdk() {
    return (OpenTelemetrySdk) openTelemetry;
  }

  public static class LogsOnProfile implements QuarkusTestProfile {
    private static final OtlpReceiver COLLECTOR = OtlpReceiver.start();

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.otel.sdk.disabled", "false",
          "OTEL_EXPORTER_ENDPOINT", COLLECTOR.endpoint(),
          "quarkus.otel.exporter.otlp.protocol", "http/protobuf",
          "OTEL_LOGS_ENABLED", "true");
    }
  }
}
