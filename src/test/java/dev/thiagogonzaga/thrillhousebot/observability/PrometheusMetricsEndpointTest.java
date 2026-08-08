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
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check that Quarkus invokes the registrar while building the OTel SDK and that the
 * scrape endpoint serves what the application records. Runs with the SDK enabled (the shared test
 * config disables it), so it pays a separate augmentation, which is exactly the wiring under test.
 */
@QuarkusTest
@TestProfile(PrometheusMetricsEndpointTest.OtelSdkEnabledProfile.class)
class PrometheusMetricsEndpointTest {

  @Inject ReviewOutcomeMetrics outcomeMetrics;

  @Test
  void metricsEndpointServesRecordedMetrics() {
    outcomeMetrics.recordCompleted();

    given()
        .when()
        .get("/metrics")
        .then()
        .statusCode(200)
        .header("Content-Type", containsString("text/plain"))
        .body(containsString("thrillhouse_reviews_total"))
        .body(containsString("outcome=\"completed\""));
  }

  public static class OtelSdkEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.otel.sdk.disabled", "false");
    }
  }
}
