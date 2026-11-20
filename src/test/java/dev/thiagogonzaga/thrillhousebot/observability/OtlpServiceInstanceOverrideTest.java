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
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * A {@code service.instance.id} set through {@code OTEL_RESOURCE_ATTRIBUTES} replaces the default
 * one taken from {@code HOSTNAME} (#1373), and the other attributes listed there are exported too.
 */
@QuarkusTest
@TestProfile(OtlpServiceInstanceOverrideTest.OverrideProfile.class)
class OtlpServiceInstanceOverrideTest {

  private static final String HOSTNAME = "default-host-name";
  private static final String CONFIGURED = "configured-instance-3";

  @Inject ReviewOutcomeMetrics outcomeMetrics;
  @Inject OpenTelemetry openTelemetry;

  @ConfigProperty(name = "OTEL_EXPORTER_ENDPOINT")
  String collector;

  @Test
  void theConfiguredInstanceIdReplacesTheHostname() {
    outcomeMetrics.recordCompleted();

    ((OpenTelemetrySdk) openTelemetry)
        .getSdkMeterProvider()
        .forceFlush()
        .join(10, TimeUnit.SECONDS);

    assertTrue(
        OtlpReceiver.received(collector, "/v1/metrics", CONFIGURED),
        () -> OtlpReceiver.describe(collector));
    assertTrue(
        OtlpReceiver.received(collector, "/v1/metrics", "deployment.environment.name"),
        () -> OtlpReceiver.describe(collector));
    assertTrue(
        OtlpReceiver.received(collector, "/v1/metrics", "service.version"),
        () -> OtlpReceiver.describe(collector));
    assertFalse(
        OtlpReceiver.received(collector, "/v1/metrics", HOSTNAME),
        () -> OtlpReceiver.describe(collector));
  }

  public static class OverrideProfile implements QuarkusTestProfile {
    private static final OtlpReceiver COLLECTOR = OtlpReceiver.start();

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.otel.sdk.disabled",
          "false",
          "OTEL_EXPORTER_ENDPOINT",
          COLLECTOR.endpoint(),
          "quarkus.otel.exporter.otlp.protocol",
          "http/protobuf",
          "HOSTNAME",
          HOSTNAME,
          "OTEL_RESOURCE_ATTRIBUTES",
          "service.instance.id=" + CONFIGURED + ",deployment.environment.name=staging");
    }
  }
}
