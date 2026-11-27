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

import io.quarkiverse.githubapp.runtime.config.PrivateKeyConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GitHubAppCredentialInterceptorTest {

  private static final String HEADER = "-----BEGIN RSA PRIVATE KEY-----";
  private static final String FOOTER = "-----END RSA PRIVATE KEY-----";
  private static final String BASE64 = base64OfTestKey();

  private static String base64OfTestKey() {
    return RsaPrivateKeyParserTest.PRIVATE_KEY_WITH_ESCAPES
        .replace("\\n", "")
        .replace(HEADER, "")
        .replace(FOOTER, "")
        .replaceAll("\\s", "");
  }

  /** The configuration as application.properties declares it, with the variable set. */
  private static Config configWith(String variable) {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .addDiscoveredInterceptors()
        .withSources(
            new PropertiesConfigSource(
                Map.of(
                    GitHubAppCredentialInterceptor.PRIVATE_KEY,
                    "${KEY:}",
                    "KEY",
                    variable,
                    "other.property",
                    "left\\nalone"),
                "test",
                100))
        .build();
  }

  private static Optional<String> configured(String variable) {
    return configWith(variable)
        .getOptionalValue(GitHubAppCredentialInterceptor.PRIVATE_KEY, String.class);
  }

  static Stream<String> keysTheParserAccepts() {
    var wrapped = String.join("\n", BASE64.split("(?<=\\G.{64})"));
    var multiline = HEADER + "\n" + wrapped + "\n" + FOOTER + "\n";
    return Stream.of(
        multiline,
        multiline.replace("\n", "\r\n"),
        multiline.replace("\n", "\\n"),
        multiline.replace("\n", " "),
        HEADER + BASE64 + FOOTER,
        HEADER + BASE64,
        BASE64 + FOOTER,
        BASE64,
        RsaPrivateKeyParserTest.PRIVATE_KEY_WITH_ESCAPES);
  }

  @ParameterizedTest
  @MethodSource("keysTheParserAccepts")
  void theExtensionCanReadEveryKeyTheParserAccepts(String variable) {
    var expected = RsaPrivateKeyParser.parse(variable);

    var converted =
        (RSAPrivateKey) new PrivateKeyConverter().convert(configured(variable).orElseThrow());

    assertEquals(expected.getModulus(), converted.getModulus());
    assertEquals(expected.getPrivateExponent(), converted.getPrivateExponent());
  }

  static Stream<String> valuesTheParserRejects() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pkcs8 =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
    return Stream.of(pkcs8, "not a key", HEADER + "AAAA" + FOOTER);
  }

  @ParameterizedTest
  @MethodSource("valuesTheParserRejects")
  void aValueTheParserRejectsReachesTheExtensionUnchanged(String variable) {
    assertEquals(Optional.of(variable), configured(variable));
  }

  @Test
  void aPaddedAppIdReachesTheExtensionStripped() {
    var config =
        new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .addDiscoveredInterceptors()
            .withSources(
                new PropertiesConfigSource(
                    Map.of(GitHubAppCredentialInterceptor.APP_ID, "${ID:}", "ID", "  123456 \n"),
                    "test",
                    100))
            .build();

    assertEquals("123456", config.getValue(GitHubAppCredentialInterceptor.APP_ID, String.class));
  }

  @Test
  void anUnsetKeyStaysUnsetAndOtherPropertiesAreUntouched() {
    var config = configWith("");

    assertEquals(
        Optional.empty(),
        config.getOptionalValue(GitHubAppCredentialInterceptor.PRIVATE_KEY, String.class));
    assertEquals("left\\nalone", config.getValue("other.property", String.class));
    assertEquals(Optional.empty(), config.getOptionalValue("no.such.property", String.class));
  }
}
