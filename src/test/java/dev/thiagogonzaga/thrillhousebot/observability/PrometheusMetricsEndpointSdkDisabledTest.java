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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * {@code OTEL_SDK_DISABLED=true} wins over {@code PROMETHEUS_METRICS_ENABLED=true} (#45): the SDK
 * is never built, so the registrar never registers a reader and the scrape endpoint answers 404.
 * The README, the configuration reference and the switch's {@code # doc:} line all state this.
 *
 * <p>The OTLP metric and log exporters are built into the image (#1262), so the same switch must
 * keep them from sending anything: with an OTLP receiver configured and log export switched on, the
 * disabled SDK has nothing to send, and nothing reaches the receiver even after a flush.
 */
@QuarkusTest
@TestProfile(PrometheusMetricsEndpointSdkDisabledTest.SdkDisabledEndpointEnabledProfile.class)
class PrometheusMetricsEndpointSdkDisabledTest {

  private static final Logger LOG =
      Logger.getLogger(PrometheusMetricsEndpointSdkDisabledTest.class);

  @Inject ReviewOutcomeMetrics outcomeMetrics;
  @Inject OpenTelemetry openTelemetry;

  @ConfigProperty(name = "OTEL_EXPORTER_ENDPOINT")
  String collector;

  @Test
  void metricsEndpointAnswers404WhenSdkIsDisabledAndEndpointEnabled() {
    given().when().get("/metrics").then().statusCode(404);
  }

  @Test
  void nothingIsExportedOverOtlpWhenSdkIsDisabled() {
    outcomeMetrics.recordCompleted();
    openTelemetry.getTracer("test").spanBuilder("otlp-disabled").startSpan().end();
    LOG.info("otlp-disabled");

    // The disabled SDK has no exporter, metric reader or log processor: flushing sends nothing.
    var sdk = (OpenTelemetrySdk) openTelemetry;
    sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS);
    sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
    sdk.getSdkLoggerProvider().forceFlush().join(10, TimeUnit.SECONDS);

    assertEquals("OTLP exports to " + collector + ": []", OtlpReceiver.describe(collector));
  }

  public static class SdkDisabledEndpointEnabledProfile implements QuarkusTestProfile {
    private static final OtlpReceiver COLLECTOR = OtlpReceiver.start();

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.otel.sdk.disabled",
          "true",
          "thrillhousebot.metrics.prometheus-enabled",
          "true",
          "OTEL_EXPORTER_ENDPOINT",
          COLLECTOR.endpoint(),
          "quarkus.otel.exporter.otlp.protocol",
          "http/protobuf",
          "OTEL_LOGS_ENABLED",
          "true");
    }
  }
}
