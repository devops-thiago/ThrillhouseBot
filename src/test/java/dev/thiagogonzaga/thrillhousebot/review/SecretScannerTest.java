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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
    // The versioned shape: a one-digit segment after the prefix.
    var versioned = "xo" + "xa-2-" + fake.random("0123456789", 13) + "-" + fake.alnum(32);
    assertSingleHit(SecurityRule.SLACK_TOKEN, versioned, "slack: " + versioned);
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

  // --- typed and array declarations (#916) ---

  /** One generic hit whose literal is {@code value} and whose key is {@code key}. */
  private static void assertGenericHit(String key, String value, String line) {
    var hits = scan(line);
    assertEquals(1, hits.size(), () -> "expected one hit on: " + line + " got " + hits);
    assertEquals(SecurityRule.GENERIC_SECRET, hits.get(0).rule(), line);
    assertEquals(value, hits.get(0).literal(), line);
    assertEquals(key, hits.get(0).keyName(), line);
  }

  @Test
  void roundNineCArrayDeclaration() {
    var value = fake.genericSecret(40);
    assertGenericHit("API_TOKEN", value, "static const char API_TOKEN[] = \"" + value + "\";");
  }

  @Test
  void roundNineRustStrConstant() {
    var value = fake.genericSecret(40);
    assertGenericHit("API_TOKEN", value, "pub const API_TOKEN: &str = \"" + value + "\";");
  }

  @Test
  void cArrayWithASize() {
    var value = fake.genericSecret(40);
    assertGenericHit("API_TOKEN", value, "char API_TOKEN[41] = \"" + value + "\";");
    assertGenericHit("kApiKey", value, "constexpr char kApiKey[ ] = \"" + value + "\";");
  }

  @Test
  void cPointerDeclarations() {
    var value = fake.genericSecret(32);
    assertGenericHit("API_TOKEN", value, "const char *API_TOKEN = \"" + value + "\";");
    assertGenericHit("api_token", value, "char* api_token = \"" + value + "\";");
    assertGenericHit("api_token", value, "char * const api_token = \"" + value + "\";");
  }

  @Test
  void rustStaticWithALifetime() {
    var value = fake.genericSecret(32);
    assertGenericHit("API_TOKEN", value, "static API_TOKEN: &'static str = \"" + value + "\";");
  }

  @Test
  void typeScriptAnnotation() {
    var value = fake.genericSecret(32);
    assertGenericHit("apiToken", value, "const apiToken: string = '" + value + "';");
    assertGenericHit("apiToken", value, "  private apiToken?: string = \"" + value + "\";");
  }

  @Test
  void goVarWithATypeAndShortDeclaration() {
    var value = fake.genericSecret(32);
    assertGenericHit("apiToken", value, "var apiToken string = \"" + value + "\"");
    assertGenericHit("apiToken", value, "\tapiToken := \"" + value + "\"");
  }

  @Test
  void kotlinAndScalaValWithAType() {
    var value = fake.genericSecret(32);
    assertGenericHit("apiToken", value, "val apiToken: String = \"" + value + "\"");
    assertGenericHit("apiToken", value, "private val apiToken: String? = \"" + value + "\"");
  }

  @Test
  void swiftLetWithAType() {
    var value = fake.genericSecret(32);
    assertGenericHit("apiToken", value, "let apiToken: String = \"" + value + "\"");
  }

  @Test
  void pythonAnnotation() {
    var value = fake.genericSecret(32);
    assertGenericHit("API_TOKEN", value, "API_TOKEN: Final[str] = \"" + value + "\"");
  }

  @Test
  void zigSliceTypeFromTheCorpus() {
    // The round 12 and 13 shape (#962): zig/src/config.zig:4, with a fake literal.
    var value = fake.genericSecret(40);
    assertGenericHit("api_token", value, "const api_token: []const u8 = \"" + value + "\";");
  }

  @Test
  void sliceAndArrayAnnotations() {
    var value = fake.genericSecret(40);
    for (var type :
        List.of(
            "[] const u8",
            "[]u8",
            "[32]u8",
            "[_]u8",
            "[:0]const u8",
            "*const [40:0]u8",
            "?[]const u8",
            "const [32]u8")) {
      assertGenericHit("apiToken", value, "pub const apiToken: " + type + " = \"" + value + "\";");
    }
  }

  @Test
  void sliceAnnotationsOfAnUnquotedValueAreNotReported() {
    assertTrue(scan("const api_token: []const u8 = std.os.getenv(\"API_TOKEN\").?;").isEmpty());
    assertTrue(scan("const api_token: []const u8 = config.api_token;").isEmpty());
    // Two qualifiers in a row and an unclosed bracket are outside the prefix.
    var value = fake.genericSecret(40);
    assertTrue(scan("const api_token: const const u8 = \"" + value + "\";").isEmpty());
    assertTrue(scan("const api_token: [32 u8 = \"" + value + "\";").isEmpty());
  }

  @Test
  void javaAndCSharpTypeBeforeTheName() {
    var value = fake.genericSecret(32);
    assertGenericHit(
        "API_TOKEN", value, "private static final String API_TOKEN = \"" + value + "\";");
    assertGenericHit("ApiToken", value, "public const string ApiToken = \"" + value + "\";");
  }

  @Test
  void typedDeclarationsKeepThePlaceholderExclusions() {
    for (var value : List.of("changeme-9f!Q", "${API_TOKEN}", "<your-token>", "{{ vault_tok }}")) {
      assertTrue(scan("static const char API_TOKEN[] = \"" + value + "\";").isEmpty(), value);
      assertTrue(scan("pub const API_TOKEN: &str = \"" + value + "\";").isEmpty(), value);
      assertTrue(scan("val apiToken: String = \"" + value + "\"").isEmpty(), value);
      assertTrue(scan("var apiToken string = \"" + value + "\"").isEmpty(), value);
    }
  }

  @Test
  void typedDeclarationsOfAnUnquotedValueAreNotReported() {
    assertTrue(scan("val apiToken: String = BuildConfig.API_TOKEN").isEmpty());
    assertTrue(scan("var apiToken string = os.Getenv(\"API_TOKEN\")").isEmpty());
    assertTrue(scan("pub static API_TOKEN: &str = env!(\"API_TOKEN\");").isEmpty());
    // A comparison, not an assignment.
    assertTrue(scan("if apiToken string == \"" + fake.genericSecret(32) + "\" {").isEmpty());
  }

  @Test
  void theDeclarationSuffixStaysLinearOnAdversarialLines() {
    int length = SecretScanner.MAX_GENERIC_LINE_LENGTH;
    var lines =
        List.of(
            "token".repeat(length / 5),
            ("token: " + "a".repeat(45) + " ").repeat(length / 53),
            ("token[" + "a".repeat(45)).repeat(length / 51),
            ("token " + "a".repeat(45) + " ").repeat(length / 52),
            ("token: &'" + "a".repeat(25) + "    mut    ").repeat(length / 45),
            ("token: *    const    [" + "a".repeat(25) + "]    const    ").repeat(length / 60),
            ("token: [" + "a:".repeat(30)).repeat(length / 68),
            "a".repeat(40) + "token" + "a".repeat(length - 45));
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          for (int i = 0; i < 20; i++) {
            for (var line : lines) {
              assertTrue(line.length() <= length, "the line must reach the generic rule");
              assertTrue(scan(line).isEmpty());
              assertEquals(line, SecretScanner.redactAssignedLiterals(line, THRESHOLD));
            }
          }
        });
  }

  // --- the model-text scrub's assignment shape (#916) ---

  @Test
  void anAssignedLiteralIsRedactedWhateverTheDeclarationForm() {
    var value = fake.genericSecret(40);
    var redacted = "[redacted: " + value.substring(0, 4) + "…, 40 chars]";
    // A form the scan itself does not read.
    var line = "pub static API_TOKEN: Lazy<&str> = Lazy::new(|| \"" + value + "\");";
    assertTrue(scan(line).isEmpty());
    assertEquals(
        "pub static API_TOKEN: Lazy<&str> = Lazy::new(|| \"" + redacted + "\");",
        SecretScanner.redactAssignedLiterals(line, THRESHOLD));
    assertEquals(
        "static API_TOKEN: &'static str = \"" + redacted + "\";",
        SecretScanner.redactAssignedLiterals(
            "static API_TOKEN: &'static str = \"" + value + "\";", THRESHOLD));
    assertEquals(
        "const api_token: []const u8 = \"" + redacted + "\";",
        SecretScanner.redactAssignedLiterals(
            "const api_token: []const u8 = \"" + value + "\";", THRESHOLD));
    assertEquals(
        "Remove `api_key := `" + redacted + "`` and\nrotate it.",
        SecretScanner.redactAssignedLiterals(
            "Remove `api_key := `" + value + "`` and\nrotate it.", THRESHOLD));
  }

  @Test
  void theModelTextScrubLeavesEverythingElseAlone() {
    var value = fake.genericSecret(40);
    for (var text :
        List.of(
            // No credential name before the literal on its line.
            "checksum = \"" + value + "\" // token",
            "token handling\nchecksum = \"" + value + "\"",
            // No operator between the name and the literal.
            "The token is \"" + value + "\"",
            // Not secret-looking: a placeholder, an identifier, a URL, low entropy.
            "API_TOKEN: &str = \"<your-token-here>\"",
            "api_token = \"API_TOKEN_NAME\"",
            "token_url = \"https://auth.local/oauth2/t0ken\"",
            "password = \"hunter2hunter2\"")) {
      assertEquals(text, SecretScanner.redactAssignedLiterals(text, THRESHOLD), text);
    }
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
    // Separators alone do not make an identifier: a segment here is not a word.
    assertTrue(SecretScanner.looksLikeSecret("Zx9!-q8#Lm2-vRt", 3.5));
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
    assertEquals(1, SecretScanner.scan(line, null, 3.0).size());
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
