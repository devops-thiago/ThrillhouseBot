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
package dev.thiagogonzaga.thrillhousebot.nativeimage;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Runs against the shipped artifact, not the JVM test application (#671). Under {@code -Pnative}
 * failsafe launches the native binary, so a reflection, resource or build-time-initialization gap
 * that every {@code @QuarkusTest} passes fails here: the binary must boot past {@code
 * StartupConfigValidator} (which parses the App key), reach PostgreSQL through Hibernate, and bind
 * a GitHub delivery with Jackson.
 *
 * <p>The cases stay off every path that calls GitHub or the model, so the run needs no network.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(NativeRuntimeConfig.class)
class NativeImageIT {

  private static final String WEBHOOK = "/api/webhook";

  @Test
  void bootsAndReportsLive() {
    given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void reachesTheDatabase() {
    given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void rejectsAnUnsignedDelivery() {
    given()
        .contentType("application/json")
        .header("X-GitHub-Event", "ping")
        .header("X-GitHub-Delivery", UUID.randomUUID().toString())
        .body("{\"zen\":\"unsigned\"}")
        .when()
        .post(WEBHOOK)
        .then()
        .statusCode(401);
  }

  @Test
  void bindsASignedPing() throws GeneralSecurityException {
    var body =
        "{\"zen\":\"Keep it logically awesome.\",\"hook_id\":1,"
            + "\"repository\":{\"id\":7,\"full_name\":\"octo/repo\",\"name\":\"repo\","
            + "\"owner\":{\"login\":\"octo\"}},"
            + "\"installation\":{\"id\":42},\"sender\":{\"login\":\"octo\"}}";
    delivery("ping", body).then().statusCode(200).body("status", equalTo("ok"));
  }

  @Test
  void refusesASignedBodyThatIsNotJson() throws GeneralSecurityException {
    delivery("ping", "not json").then().statusCode(400);
  }

  private static Response delivery(String event, String body) throws GeneralSecurityException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    return given()
        .contentType("application/json")
        .header("X-GitHub-Event", event)
        .header("X-GitHub-Delivery", UUID.randomUUID().toString())
        .header("X-Hub-Signature-256", "sha256=" + hmac(bytes))
        .body(bytes)
        .when()
        .post(WEBHOOK);
  }

  private static String hmac(byte[] body) throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            NativeRuntimeConfig.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body));
  }
}
