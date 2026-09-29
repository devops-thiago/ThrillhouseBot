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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** #60: the secret half of the deterministic security scan, one rule at a time. */
class SecretScannerTest {

  private static final double THRESHOLD = 3.5;

  private final FakeCredentials fake = new FakeCredentials(60);

  private static List<SecretScanner.Hit> scan(String line) {
    return SecretScanner.scan(line, null, THRESHOLD);
  }

  private static void assertSingleHit(SecurityRule rule, String literal, String line) {
    var hits = scan(line);
    assertEquals(1, hits.size(), () -> "expected one hit on: " + line + " got " + hits);
    assertEquals(rule, hits.get(0).rule());
    assertEquals(literal, hits.get(0).literal());
  }

  // --- known formats: true positives ---

  @Test
  void awsAccessKeyId() {
    var key = fake.awsAccessKey();
    assertSingleHit(SecurityRule.AWS_ACCESS_KEY, key, "aws_access_key_id = " + key);
  }

  @Test
  void githubClassicAndFineGrainedTokens() {
    var classic = fake.githubToken();
    assertSingleHit(SecurityRule.GITHUB_TOKEN, classic, "export GH=" + classic);
    var fineGrained = fake.githubFineGrainedToken();
    assertSingleHit(SecurityRule.GITHUB_TOKEN, fineGrained, "GH_TOKEN: " + fineGrained);
    for (var prefix : List.of("gho_", "ghs_", "ghu_", "ghr_")) {
      var token = prefix + fake.alnum(36);
      assertSingleHit(SecurityRule.GITHUB_TOKEN, token, "t=" + token);
    }
  }

  @Test
  void slackToken() {
    var token = fake.slackToken();
    assertSingleHit(SecurityRule.SLACK_TOKEN, token, "slack: " + token);
  }

  @Test
  void googleApiKey() {
    var key = fake.googleApiKey();
    assertSingleHit(SecurityRule.GOOGLE_API_KEY, key, "const maps = '" + key + "';");
  }

  @Test
  void stripeLiveKey() {
    var key = fake.stripeLiveKey();
    assertSingleHit(SecurityRule.STRIPE_LIVE_KEY, key, "Stripe.apiKey = \"" + key + "\";");
  }

  @Test
  void jwt() {
    var token = fake.jwt();
    assertSingleHit(SecurityRule.JWT, token, "Authorization: Bearer " + token);
  }

  @Test
  void privateKeyHeaderFollowedByABodyOnTheNextLine() {
    var header = FakeCredentials.pemHeader("RSA");
    var hits = SecretScanner.scan(header, fake.pemBodyLine(), THRESHOLD);
    assertEquals(1, hits.size());
    assertEquals(SecurityRule.PRIVATE_KEY, hits.get(0).rule());
  }

  @Test
  void privateKeyPastedIntoAStringWithAnEscapedNewline() {
    var line = "\"key\": \"" + FakeCredentials.pemHeader("") + "\\n" + fake.pemBodyLine() + "\"";
    var hits = SecretScanner.scan(line, null, THRESHOLD);
    assertEquals(SecurityRule.PRIVATE_KEY, hits.get(0).rule());
  }

  @Test
  void legacyEncryptedPrivateKey() {
    var hits =
        SecretScanner.scan(FakeCredentials.pemHeader("RSA"), "Proc-Type: 4,ENCRYPTED", THRESHOLD);
    assertEquals(1, hits.size());
  }

  // --- known formats: near misses ---

  @Test
  void aValueOneCharacterShortOfTheFormatIsNotReported() {
    assertTrue(scan("k = " + fake.awsAccessKey().substring(0, 19)).isEmpty());
    assertTrue(scan("k = " + fake.githubToken().substring(0, 39)).isEmpty());
    assertTrue(scan("k = " + fake.googleApiKey().substring(0, 38)).isEmpty());
  }

  @Test
  void aFormatEmbeddedInALongerWordIsNotReported() {
    assertTrue(scan("id = X" + fake.awsAccessKey()).isEmpty());
    assertTrue(scan("id = " + fake.awsAccessKey() + "Z").isEmpty());
  }

  @Test
  void stripeTestKeysAreNotLiveKeys() {
    assertTrue(scan("k = sk" + "_test_" + fake.alnum(24)).isEmpty());
  }

  @Test
  void aPrivateKeyHeaderWithNoBodyIsCodeThatNamesTheHeader() {
    var line = "private static final String HEADER = \"" + FakeCredentials.pemHeader("RSA") + "\";";
    assertTrue(SecretScanner.scan(line, "    // strip it before decoding", THRESHOLD).isEmpty());
    assertTrue(SecretScanner.scan(line, null, THRESHOLD).isEmpty());
  }

  @Test
  void aTwoSegmentJwtLookalikeIsNotReported() {
    var parts = fake.jwt().split("\\.");
    assertTrue(scan("t = " + parts[0] + "." + parts[1]).isEmpty());
  }

  // --- placeholders ---

  @Test
  void documentationPlaceholdersAreSkipped() {
    assertTrue(scan("aws_access_key_id = AK" + "IAIOSFODNN7" + "EXAMPLE").isEmpty());
    assertTrue(scan("token: gh" + "p_" + "x".repeat(36)).isEmpty());
    assertTrue(scan("password = \"${DB_PASSWORD}\"").isEmpty());
    assertTrue(scan("api_key: \"<your-api-key-here>\"").isEmpty());
    assertTrue(scan("secret = \"changeme-9f!Q\"").isEmpty());
    assertTrue(scan("client_secret = \"{{ vault_client_secret }}\"").isEmpty());
    assertTrue(scan("token = \"" + fake.alnum(4) + "000000" + fake.alnum(6) + "\"").isEmpty());
  }

  // --- generic assignments ---

  @Test
  void aHighEntropyLiteralAssignedToACredentialNameIsReported() {
    var hits = scan("db_password = \"q8#Lm2!vRt9Z\"");
    assertEquals(1, hits.size());
    assertEquals(SecurityRule.GENERIC_SECRET, hits.get(0).rule());
    assertEquals("q8#Lm2!vRt9Z", hits.get(0).literal());
    assertEquals("db_password", hits.get(0).keyName());
  }

  @Test
  void genericAssignmentSpellingsAcrossFormats() {
    var value = "Zx9!" + fake.alnum(12);
    for (var line :
        List.of(
            "  \"apiKey\": \"" + value + "\",",
            "API_KEY: '" + value + "'",
            "clientSecret := \"" + value + "\"",
            ":access_key => \"" + value + "\"",
            "export AUTH_TOKEN=\"" + value + "\"")) {
      var hits = scan(line);
      assertEquals(1, hits.size(), line);
      assertEquals(SecurityRule.GENERIC_SECRET, hits.get(0).rule(), line);
    }
  }

  @Test
  void genericNearMissesAreNotReported() {
    // Low entropy.
    assertTrue(scan("password = \"hunter2hunter2\"").isEmpty());
    // Names a credential rather than being one.
    assertTrue(scan("password_env = \"DB_PASSWORD\"").isEmpty());
    assertTrue(scan("secretName: \"my-app-secret-v2\"").isEmpty());
    assertTrue(scan("key: \"spring.datasource.password\"").isEmpty());
    // A URL or a path.
    assertTrue(scan("token_url = \"https://auth.example.org/oauth2/t0ken\"").isEmpty());
    assertTrue(scan("secret_file = \"/run/secrets/db-Pa55\"").isEmpty());
    // Unquoted: a variable or a call, which is where a credential belongs.
    assertTrue(scan("password = System.getenv(\"DB_PASS\")").isEmpty());
    // Letters only.
    assertTrue(scan("token = \"CorrectHorseBatteryStaple\"").isEmpty());
    // Too short.
    assertTrue(scan("secret = \"a1!B\"").isEmpty());
    // Not a credential name.
    assertTrue(scan("checksum = \"q8#Lm2!vRt9Z\"").isEmpty());
  }

  @Test
  void aKnownFormatAndASeparateAssignmentOnOneLineAreBothReported() {
    var key = fake.googleApiKey();
    var hits = scan("maps = \"" + key + "\"; db_password = \"q8#Lm2!vRt9Z\"");
    assertEquals(
        List.of(SecurityRule.GOOGLE_API_KEY, SecurityRule.GENERIC_SECRET),
        hits.stream().map(SecretScanner.Hit::rule).toList());
  }

  @Test
  void variablesPathsUrlsAndSingleClassValuesAreNotCredentials() {
    for (var value :
        List.of(
            "$HOME/.Pa55w0rd!",
            "./conf/Pa55w0rd!",
            "~/conf/Pa55w0rd!",
            "ftp://h0st.local/Pa55",
            "83749201748392017")) {
      assertFalse(SecretScanner.looksLikeSecret(value, 1.0), value);
    }
    assertTrue(SecretScanner.looksLikeSecret("q8#Lm2!vRt9Z", 3.5));
  }

  @Test
  void aKnownFormatAssignedToACredentialNameIsReportedOnceByTheSpecificRule() {
    var token = fake.githubToken();
    assertSingleHit(SecurityRule.GITHUB_TOKEN, token, "github_token = \"" + token + "\"");
  }

  @Test
  void aVeryLongLineSkipsOnlyTheGenericRule() {
    var padding = "a".repeat(SecretScanner.MAX_GENERIC_LINE_LENGTH);
    assertTrue(scan(padding + " password = \"q8#Lm2!vRt9Z\"").isEmpty());
    var key = fake.googleApiKey();
    assertSingleHit(SecurityRule.GOOGLE_API_KEY, key, padding + " " + key);
  }

  @Test
  void theEntropyThresholdGatesTheGenericRule() {
    var line = "password = \"q8#Lm2!vRt9Z\"";
    assertTrue(SecretScanner.scan(line, null, 3.0).size() == 1);
    assertTrue(SecretScanner.scan(line, null, 4.5).isEmpty());
  }

  @Test
  void entropyIsShannonEntropyInBitsPerCharacter() {
    assertEquals(0.0, SecretScanner.entropy(""));
    assertEquals(0.0, SecretScanner.entropy("aaaa"));
    assertEquals(2.0, SecretScanner.entropy("abcd"), 1e-9);
  }

  // --- redaction ---

  @Test
  void aKnownFormatShowsItsProviderPrefixAndLengthOnly() {
    var token = fake.githubToken();
    var redacted =
        SecretScanner.redact(new SecretScanner.Hit(SecurityRule.GITHUB_TOKEN, token, null));
    assertEquals("ghp_…, 40 chars", redacted);
    assertFalse(redacted.contains(token.substring(4, 8)));
  }

  @Test
  void aGenericValueShowsAtMostOneCharacterInFour() {
    assertEquals(
        "q8…, 8 chars",
        SecretScanner.redact(new SecretScanner.Hit(SecurityRule.GENERIC_SECRET, "q8#Lm2!v", "k")));
    assertEquals(
        "q8#L…, 24 chars",
        SecretScanner.redact(
            new SecretScanner.Hit(SecurityRule.GENERIC_SECRET, "q8#L" + "m".repeat(20), "k")));
  }

  @Test
  void aPrivateKeyShowsNoneOfItsMaterial() {
    var header = FakeCredentials.pemHeader("RSA");
    assertEquals(
        "PEM block",
        SecretScanner.redact(new SecretScanner.Hit(SecurityRule.PRIVATE_KEY, header, null)));
  }
}
