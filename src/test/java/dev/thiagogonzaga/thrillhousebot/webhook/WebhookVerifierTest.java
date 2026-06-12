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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {

  private final WebhookVerifier verifier = new WebhookVerifier();
  private static final String SECRET = "my-webhook-secret";
  private static final String PAYLOAD = "{\"action\":\"opened\"}";

  @Test
  void shouldVerifyValidSignature() {
    var signature = "sha256=" + computeHmac(PAYLOAD, SECRET);
    assertTrue(verifier.verify(signature, PAYLOAD.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  @Test
  void shouldRejectInvalidSignature() {
    var signature = "sha256=" + computeHmac("tampered-payload", SECRET);
    assertFalse(verifier.verify(signature, PAYLOAD.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  @Test
  void shouldRejectNullSignature() {
    assertFalse(verifier.verify(null, PAYLOAD.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  @Test
  void shouldRejectSignatureWithoutPrefix() {
    var signature = computeHmac(PAYLOAD, SECRET); // no "sha256=" prefix
    assertFalse(verifier.verify(signature, PAYLOAD.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  @Test
  void shouldRejectSignatureWithWrongSecret() {
    var signature = "sha256=" + computeHmac(PAYLOAD, "wrong-secret");
    assertFalse(verifier.verify(signature, PAYLOAD.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  @Test
  void shouldVerifyEmptyPayloadBody() {
    var payload = "";
    var signature = "sha256=" + computeHmac(payload, SECRET);
    assertTrue(verifier.verify(signature, payload.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  @Test
  void shouldVerifyPayloadWithSpecialCharacters() {
    var payload =
        "{\"action\":\"opened\",\"name\":\"Tést Üser 🎉\",\"emoji\":\"👍\",\"unicode\":\"\\u0041\"}";
    var signature = "sha256=" + computeHmac(payload, SECRET);
    assertTrue(verifier.verify(signature, payload.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  @Test
  void shouldRejectInvalidSignatureForSpecialCharPayload() {
    var payload = "{\"action\":\"opened\",\"name\":\"Tést Üser 🎉\"}";
    var signature = "sha256=" + computeHmac("different-payload", SECRET);
    assertFalse(verifier.verify(signature, payload.getBytes(StandardCharsets.UTF_8), SECRET));
  }

  private String computeHmac(String payload, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
