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
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code OTEL_JVM_METRICS_ENABLED=false} and {@code OTEL_HTTP_SERVER_METRICS_ENABLED=false} turn
 * off the metrics Quarkus records on its own (#1262), while the bot's metrics are still served.
 */
@QuarkusTest
@TestProfile(QuarkusMetricsSwitchedOffTest.QuarkusMetricsOffProfile.class)
class QuarkusMetricsSwitchedOffTest {

  @Inject ReviewOutcomeMetrics outcomeMetrics;

  @Test
  void jvmAndHttpServerMetricsAreNotRecorded() {
    outcomeMetrics.recordCompleted();
    // A request the HTTP server instrumentation would otherwise time.
    given().when().get("/q/health").then().statusCode(200);

    given()
        .when()
        .get("/metrics")
        .then()
        .statusCode(200)
        .body(containsString("thrillhouse_reviews_total"))
        .body(not(containsString("jvm_")))
        .body(not(containsString("http_server_request_duration")));
  }

  public static class QuarkusMetricsOffProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.otel.sdk.disabled",
          "false",
          "thrillhousebot.metrics.prometheus-enabled",
          "true",
          "OTEL_JVM_METRICS_ENABLED",
          "false",
          "OTEL_HTTP_SERVER_METRICS_ENABLED",
          "false");
    }
  }
}
