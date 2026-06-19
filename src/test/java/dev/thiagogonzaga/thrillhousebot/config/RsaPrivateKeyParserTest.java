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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RsaPrivateKeyParserTest {

  // Throwaway PKCS#1 RSA test key with \n escape sequences, as delivered through an env var.
  private static final String PRIVATE_KEY_WITH_ESCAPES =
      "-----BEGIN RSA PRIVATE KEY-----\\n"
          + "MIIEowIBAAKCAQEAqEcn0Wmzxjw+MTxPpYpetynJEC+u5dV87wWx2m6Xs8TJB3dn\\n"
          + "GRemwG/x1/DZMLk5WzMOIxYBBYUQ3HQlB+tobP3EyD9TwQDsR9/CMhvMbZ4IQlBD\\n"
          + "rbV4cyvcQkEPGP4ojqr4TU7+GQh8ZlcA6QxGgQrgyAEdGaiOPkBtrbw+oOUdvAMF\\n"
          + "kU0+lvd0BuhJ0vEkyLz66reMwfhRMoNZcGaLogVwE8aEAnabQhYw5Xgt5A9o/SOt\\n"
          + "1i+PqvjzUDP/XcJzuKS2Wak0LAjp/HuU2JnpPIjwE2qwZ9WcJC2YYbJWDBYP6rbh\\n"
          + "uFghflQxXOjmRQL8RAclma5AyjQvUk0VLOfMnwIDAQABAoIBAA3i0UZWkp8pGXi2\\n"
          + "oVvnrykuKYlMZgJRO539uk9sENikxHH5SGipqvj2Q96t4T5ECpeb/u6mQi9Sa7HF\\n"
          + "Y8jjhBp6VmKv1xl2GglUTZIU+SmSgNv4A52x+96FIAfXlodZcb9UHGlEu3MVYC6R\\n"
          + "v2F7wdWkMChQ7zXO0u4QIvzTK9fa7eVffnK99PgMAtPBHMwMhuPgRc+Ur0OWE+Xd\\n"
          + "7CppB+szpJAGmvuWyuoulfjp7ynPJGLCOds2r+FWvPwXFi80RCvoppaQ9oKUWxiB\\n"
          + "dQAtAwr0myjvsQJBsWVlav75tlayHDo1PdnPbTlp7n7hBlZBL5TYziOqK6V+TYsE\\n"
          + "/9uruAECgYEA3Yhqe0PcrGttn7OIWPqlZpjqocE0H4taZnd90mA/m9sM9Ans1pJ9\\n"
          + "3eAxzluqDZCCYFrVhAR82UeB8FMOt1w8wMvLGMOOa586FYNI16UzuJp5t7xWasoc\\n"
          + "zpnohKlfmDxYYGMzXjcU3KWhTeIkoJO/sIwVZ67s++XQwlqbHFzlOL8CgYEAwnWc\\n"
          + "51s+N8F03Wzwk9HLmpHxs0ydO5dPjQFfj8Hk7tHIx5q94921x/CXH4hLeMO5zjl4\\n"
          + "vOAQGr4KBqRWZ4TQhXkea6Kfd1dGm9E4KKx8wm7zqhDikgVbeGBKXDjblCKnlTHb\\n"
          + "v2JWoNSStBqZnHLM3ZxH8ZZKY/YYTLcyNuMnhCECgYBRCsGhfG7zKI2++aesnWz6\\n"
          + "voA/UnWmAI2+pIID/y/l7VmswSDCUm73RzgRPNlWAwKfCzvHAvlFZ3Jyn3/ntjeH\\n"
          + "dEZFNe0ZE/PkwNVaBlaIdwKGI8Edafjl38n/FhMhlxnhkQjOs6nPGkyLOGqbz9E1\\n"
          + "XdnKx2RstmMLZqgN1TIJ8wKBgC8dnhm0UtvhhLZNufCm6WUXLW/bBVG19LFefs/v\\n"
          + "E9AFhldOl+nJA01hbsxWEqs9CRz9cdKZm21PVFCNqt3EIV3lnchIi8i3ncUNKUU5\\n"
          + "nbTieylek/b7U1FUS1AS+qjmyKHuhabWZdTsDGuU8lkku5yKTCgt2PJlYzfbP1Br\\n"
          + "M1zhAoGBAMHT2Y3JiLkfUifKEytraOpIwjcKxX5x6qkVjZ0W8MqGUn0+B9IY9jpo\\n"
          + "yQIy96VtO8p+PIXh6FAVrGzDGU/FUd+vlKqo7u73bF6hdWps/t6CuZKG9vIQNjPs\\n"
          + "qK75ejcwlPmFYeQJscpQ2c8KNqcewsA53bJWYmroAc/na7JCfpf7\\n"
          + "-----END RSA PRIVATE KEY-----";

  @Test
  void parsesPkcs1KeyWithNewlineEscapes() {
    var key = RsaPrivateKeyParser.parse(PRIVATE_KEY_WITH_ESCAPES);

    assertNotNull(key);
    assertEquals("RSA", key.getAlgorithm());
    assertNotNull(key.getModulus());
  }

  @Test
  void parsesPkcs1KeyWithRealNewlines() {
    var key = RsaPrivateKeyParser.parse(PRIVATE_KEY_WITH_ESCAPES.replace("\\n", "\n"));

    assertNotNull(key);
    assertEquals("RSA", key.getAlgorithm());
  }

  @Test
  void rejectsNullKey() {
    assertThrows(IllegalArgumentException.class, () -> RsaPrivateKeyParser.parse(null));
  }

  @Test
  void rejectsBlankKey() {
    assertThrows(IllegalArgumentException.class, () -> RsaPrivateKeyParser.parse("   "));
  }

  @Test
  void rejectsNonBase64Garbage() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RsaPrivateKeyParser.parse("not-a-real-private-key!!!"));
  }

  @Test
  void rejectsValidBase64ThatIsNotAnRsaKey() {
    // Decodes cleanly as base64 but is not a PKCS#1 RSA key, so the KeyFactory must reject it.
    assertThrows(
        IllegalArgumentException.class,
        () -> RsaPrivateKeyParser.parse("aGVsbG8gd29ybGQgdGhpcyBpcyBub3QgYSBrZXk="));
  }
}
