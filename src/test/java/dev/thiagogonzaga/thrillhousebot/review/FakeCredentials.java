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
package dev.thiagogonzaga.thrillhousebot.review;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

/**
 * Credential-shaped test values for the security-scan tests (#60), built at run time from a seeded
 * generator. No value in the format of a real credential is written in the source, so the test
 * sources themselves never trip a secret scanner or push protection.
 */
final class FakeCredentials {

  private static final String ALNUM =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final String UPPER_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final String DIGITS = "0123456789";
  private static final String BASE64 = ALNUM + "+/";

  private final Random random;

  FakeCredentials(long seed) {
    this.random = new Random(seed);
  }

  /** Random characters from {@code alphabet} that no placeholder rule would skip. */
  String random(String alphabet, int length) {
    while (true) {
      var sb = new StringBuilder(length);
      for (int i = 0; i < length; i++) {
        sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      var value = sb.toString();
      if (!SecretScanner.isPlaceholder(value)) {
        return value;
      }
    }
  }

  String alnum(int length) {
    return random(ALNUM, length);
  }

  String awsAccessKey() {
    return "AK" + "IA" + random(UPPER_DIGITS, 16);
  }

  String githubToken() {
    return "gh" + "p_" + alnum(36);
  }

  String githubFineGrainedToken() {
    return "github" + "_pat_" + alnum(22) + "_" + alnum(59);
  }

  String slackToken() {
    return "xo" + "xb-" + random(DIGITS, 11) + "-" + random(DIGITS, 12) + "-" + alnum(24);
  }

  String googleApiKey() {
    return "AI" + "za" + alnum(35);
  }

  String stripeLiveKey() {
    return "sk" + "_live_" + alnum(24);
  }

  String jwt() {
    return base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")
        + "."
        + base64Url("{\"sub\":\"" + alnum(10) + "\",\"iat\":1700000000}")
        + "."
        + random(ALNUM + "-_", 43);
  }

  /** A PEM header, assembled so the source holds no header literal. */
  static String pemHeader(String type) {
    return "-----" + "BEGIN " + (type.isEmpty() ? "" : type + " ") + "PRIVATE" + " KEY-----";
  }

  String pemBodyLine() {
    return random(BASE64, 64);
  }

  private static String base64Url(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
