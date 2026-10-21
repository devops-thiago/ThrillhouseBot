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

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * The hard-required settings the shipped binary refuses to boot without (#671). A
 * {@code @QuarkusIntegrationTest} runs the built artifact under the {@code prod} profile, so the
 * test {@code application.properties} never reaches it; the values returned here are handed to the
 * launched process as system properties instead, under the same environment-variable names an
 * operator sets, so the binary resolves them through its own {@code ${...}} expressions.
 *
 * <p>The GitHub App key is generated per run, never written in the source. The database is not set
 * here: the binary is built for PostgreSQL, so the run supplies {@code DATABASE_URL} and {@code
 * QUARKUS_DATASOURCE_USERNAME} / {@code QUARKUS_DATASOURCE_PASSWORD} in its environment, which the
 * launched process inherits (the {@code native-build} CI job starts a PostgreSQL service for it).
 */
public class NativeRuntimeConfig implements QuarkusTestResourceLifecycleManager {

  /** The webhook secret the binary is started with, so a test can sign a delivery it accepts. */
  static final String WEBHOOK_SECRET = "native-it-webhook-secret";

  /**
   * Length of the PKCS#8 envelope Java puts in front of a 2048-bit RSA key's PKCS#1 body: the outer
   * sequence, the version and the rsaEncryption algorithm identifier, then the octet-string header.
   * GitHub hands out PKCS#1, and that is the format the validator parses.
   */
  private static final int PKCS8_RSA_2048_HEADER = 26;

  @Override
  public Map<String, String> start() {
    return Map.of(
        "GITHUB_APP_ID", "12345",
        "GITHUB_PRIVATE_KEY", pkcs1PrivateKeyPem(),
        "GITHUB_WEBHOOK_SECRET", WEBHOOK_SECRET,
        "AI_API_KEY", "native-it-api-key",
        // No collector runs beside the test; an enabled exporter would only log refusals.
        "quarkus.otel.sdk.disabled", "true");
  }

  @Override
  public void stop() {
    // Nothing to release: the values are plain strings.
  }

  static String pkcs1PrivateKeyPem() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();
      var pkcs1 = Arrays.copyOfRange(pkcs8, PKCS8_RSA_2048_HEADER, pkcs8.length);
      return "-----BEGIN RSA PRIVATE KEY-----"
          + Base64.getEncoder().encodeToString(pkcs1)
          + "-----END RSA PRIVATE KEY-----";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key generation is unavailable", e);
    }
  }
}
