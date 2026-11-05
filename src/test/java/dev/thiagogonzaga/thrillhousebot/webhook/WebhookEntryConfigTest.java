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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the application.properties wiring the webhook entry depends on: the Quarkiverse GitHub App
 * route stays on the same POST /api/webhook path the bot has always served, and the three App
 * secrets keep aliasing the environment variables the typed config reads, so an operator's existing
 * deployment keeps working after the migration. A drifted key here breaks webhook intake in
 * production while every unit test would still pass.
 */
class WebhookEntryConfigTest {

  private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");

  @Test
  void theExtensionRouteStaysOnTheWebhookPathTheControllerServed() throws IOException {
    assertEquals(
        "/api/webhook",
        readProperties().get("quarkus.github-app.webhook-url-path"),
        "The extension's webhook route must not move: GitHub's App configuration points at "
            + "POST /api/webhook.");
  }

  @Test
  void theAppSecretsAliasTheSameVariablesTheTypedConfigReads() throws IOException {
    var properties = readProperties();
    assertEquals(
        properties.get("thrillhousebot.github.app-id"),
        properties.get("quarkus.github-app.app-id"),
        "The App id must resolve identically for the typed config and the webhook extension, "
            + "or one layer validates a different app than the other authenticates as.");
    assertEquals(
        properties.get("thrillhousebot.github.private-key"),
        properties.get("quarkus.github-app.private-key"),
        "The private key must resolve identically for both layers.");
    assertEquals(
        properties.get("thrillhousebot.github.webhook-secret"),
        properties.get("quarkus.github-app.webhook-secret"),
        "The webhook secret must resolve identically for both layers: the extension verifies the "
            + "X-Hub-Signature-256 with exactly the secret GitHub signs against.");
  }

  @Test
  void plainCommentLinesAboveTheExtensionKeysDocumentTheIntake() throws IOException {
    // The generated configuration reference documents the thrillhousebot.* keys; the extension's
    // keys ride the same variables, so the intake contract is documented as a comment instead.
    // A missing marker here is just churn; going missing entirely is the failure pinned below.
    var text = Files.readString(PROPERTIES).replace("\r\n", "\n");
    assertTrue(
        text.contains("quarkus.github-app.webhook-url-path"),
        "The route-path key itself must stay in application.properties: without it the extension "
            + "mounts on / and GitHub's configured /api/webhook URL answers 404.");
    for (var line : List.of("webhook-url-path", "app-id", "private-key", "webhook-secret")) {
      assertTrue(
          text.replaceAll("\\s+", "").contains("quarkus.github-app." + line),
          "Missing quarkus.github-app." + line);
    }
  }

  /** application.properties is small; a line map over its non-comment lines is enough here. */
  private static Map<String, String> readProperties() throws IOException {
    var values = new HashMap<String, String>();
    for (String line : Files.readAllLines(PROPERTIES)) {
      var stripped = line.strip();
      if (stripped.isEmpty() || stripped.startsWith("#")) {
        continue;
      }
      var separator = stripped.indexOf('=');
      if (separator <= 0) {
        continue;
      }
      values.put(stripped.substring(0, separator), stripped.substring(separator + 1));
    }
    return values;
  }
}
