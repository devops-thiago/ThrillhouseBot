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

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class WebhookVerifier {

  private static final Logger log = LoggerFactory.getLogger(WebhookVerifier.class);
  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final String SIGNATURE_PREFIX = "sha256=";

  /**
   * Verifies the X-Hub-Signature-256 header against the request body.
   *
   * @param signatureHeader The value of the X-Hub-Signature-256 header
   * @param payloadBody The raw request body bytes
   * @param secret The webhook secret configured in the GitHub App
   * @return true if the signature matches, false otherwise
   */
  public boolean verify(String signatureHeader, byte[] payloadBody, String secret) {
    if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
      log.warn("Missing or invalid signature header");
      return false;
    }

    if (secret == null || secret.isBlank()) {
      log.error("Webhook secret is null or blank — cannot verify signature");
      return false;
    }

    var theirSignature = signatureHeader.substring(SIGNATURE_PREFIX.length());

    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      var keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
      mac.init(keySpec);
      var computedHash = mac.doFinal(payloadBody);
      var ourSignature = HexFormat.of().formatHex(computedHash);

      var match =
          MessageDigest.isEqual(
              theirSignature.getBytes(StandardCharsets.UTF_8),
              ourSignature.getBytes(StandardCharsets.UTF_8));
      if (!match) {
        log.warn("Webhook signature mismatch");
      }
      return match;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      log.error("Failed to verify webhook signature", e);
      return false;
    }
  }
}
