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
package dev.thiagogonzaga.thrillhousebot.config;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Parses a PEM-encoded PKCS#1 RSA private key (a GitHub App key) into an {@link RSAPrivateKey}.
 *
 * <p>Shared by {@code GitHubAuthClient}, which signs the App JWT, and the startup config validator,
 * which fails fast when {@code GITHUB_PRIVATE_KEY} is missing or malformed. Keeping a single parser
 * guarantees the value the validator accepts at boot is exactly the value the signer can use at
 * runtime.
 */
public final class RsaPrivateKeyParser {

  private RsaPrivateKeyParser() {}

  /**
   * Parses a PEM-encoded PKCS#1 RSA private key by converting it to PKCS#8 format. Handles keys
   * with {@code \n} escape sequences as delivered through environment variables.
   *
   * @param key the PEM key text (with or without the {@code -----BEGIN/END RSA PRIVATE KEY-----}
   *     armor)
   * @return the parsed RSA private key
   * @throws IllegalArgumentException if the key is null, blank, or cannot be parsed as an RSA
   *     private key
   */
  public static RSAPrivateKey parse(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("RSA private key is null or blank");
    }

    try {
      var pem =
          key.replace("\\n", "\n")
              .replace("-----BEGIN RSA PRIVATE KEY-----", "")
              .replace("-----END RSA PRIVATE KEY-----", "")
              .replaceAll("\\s", "");

      var pkcs1Bytes = Base64.getDecoder().decode(pem);
      byte[] pkcs8Bytes = pkcs1ToPkcs8(pkcs1Bytes);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Could not parse RSA private key: " + e.getMessage(), e);
    }
  }

  /**
   * Converts PKCS#1 RSA private key bytes to PKCS#8 format by wrapping with the PKCS#8 DER envelope
   * (version + AlgorithmIdentifier for rsaEncryption + OCTET STRING).
   */
  private static byte[] pkcs1ToPkcs8(byte[] pkcs1Key) {
    // AlgorithmIdentifier for rsaEncryption (DER-encoded OID 1.2.840.113549.1.1.1 + NULL)
    byte[] algId = {
      0x30,
      0x0d,
      0x06,
      0x09,
      0x2a,
      (byte) 0x86,
      0x48,
      (byte) 0x86,
      (byte) 0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00,
    };

    // OCTET STRING wrapping the PKCS#1 key (tag 0x04, long-form length 0x82 + 2 length bytes)
    var keyLen = pkcs1Key.length;
    var octetLen = keyLen + 4; // tag + 0x82 + 2-byte length + key bytes

    // Total content length = version(3) + algId + octetString
    var versionLen = 3; // INTEGER 0 -> 0x02 0x01 0x00
    var contentLen = versionLen + algId.length + octetLen;

    byte[] pkcs8 = new byte[4 + contentLen]; // SEQUENCE header(4) + content

    // SEQUENCE (tag 0x30, long-form length 0x82 + 2-byte length)
    pkcs8[0] = 0x30;
    pkcs8[1] = (byte) 0x82;
    pkcs8[2] = (byte) ((contentLen >> 8) & 0xFF);
    pkcs8[3] = (byte) (contentLen & 0xFF);

    // Version: INTEGER 0
    pkcs8[4] = 0x02;
    pkcs8[5] = 0x01;
    pkcs8[6] = 0x00;

    // AlgorithmIdentifier
    System.arraycopy(algId, 0, pkcs8, 7, algId.length);

    // OCTET STRING header
    var off = 7 + algId.length;
    pkcs8[off] = 0x04;
    pkcs8[off + 1] = (byte) 0x82;
    pkcs8[off + 2] = (byte) ((keyLen >> 8) & 0xFF);
    pkcs8[off + 3] = (byte) (keyLen & 0xFF);

    // PKCS#1 key bytes
    System.arraycopy(pkcs1Key, 0, pkcs8, off + 4, keyLen);

    return pkcs8;
  }
}
