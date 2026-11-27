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
package dev.thiagogonzaga.thrillhousebot.github;

import static dev.thiagogonzaga.thrillhousebot.github.GitHubStub.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.config.RsaPrivateKeyParser;
import io.quarkiverse.githubapp.GitHubCustomizer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.junit.jupiter.api.Test;

/**
 * App authentication through the real extension, with {@code GITHUB_API_URL} naming another address
 * than github.com: the extension picks up the interceptor that lets it read an armorless key and
 * the customizer that routes its application client through {@link GitHubAppConnector}, and every
 * call made as the App goes to that address, path included.
 */
@QuarkusTest
@TestProfile(GitHubAppAuthWiringTest.StubGitHubProfile.class)
class GitHubAppAuthWiringTest {

  private static final String API_PATH = "/api/v3";
  private static final String DEBUG_CATEGORY = "org.kohsuke.github";

  @Inject GitHubAuthClient authClient;

  @ConfigProperty(name = "thrillhousebot.github.api-url")
  String apiUrl;

  @Inject Instance<GitHubCustomizer> customizers;

  @Test
  void theExtensionFindsExactlyOneCustomizer() {
    assertTrue(
        customizers.isResolvable(), "a second GitHubCustomizer bean switches all of them off");
    assertInstanceOf(FailFastGitHubCustomizer.class, customizers.get());
  }

  @Test
  void theAnswerHoldingATokenIsNotLoggedWhenDebugIsOn() {
    assertTrue(Logger.getLogger(DEBUG_CATEGORY).isLoggable(Level.FINE));
    assertFalse(Logger.getLogger(DEBUG_CATEGORY + ".GitHubResponse").isLoggable(Level.FINE));
  }

  @Test
  void theAppsCallsGoToTheConfiguredAddressWithAJwtSignedByTheConfiguredKey() throws Exception {
    assertEquals("Bearer ghs_wiring", authClient.getAuthHeader(7));
    assertEquals(List.of(7L), authClient.listInstallationIds());

    var requests = GitHubStub.receivedBy(apiUrl.substring(0, apiUrl.length() - API_PATH.length()));
    assertEquals(
        List.of(
            "GET /api/v3/app",
            "GET /api/v3/app/installations/7",
            "POST /api/v3/app/installations/7/access_tokens",
            "GET /api/v3/app",
            "GET /api/v3/app/installations?per_page=100"),
        requests.stream().map(r -> r.substring(0, r.indexOf(" Bearer "))).toList());
    var jwt = requests.get(0).substring(requests.get(0).lastIndexOf(' ') + 1);
    var parts = jwt.split("\\.");
    var claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    assertTrue(claims.contains("\"iss\":\"12345\""), claims);
    var verifier = Signature.getInstance("SHA256withRSA");
    var key = (RSAPrivateCrtKey) RsaPrivateKeyParser.parse(testKey());
    verifier.initVerify(
        KeyFactory.getInstance("RSA")
            .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent())));
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));

    var refused =
        assertThrows(ClientWebApplicationException.class, () -> authClient.getAuthHeader(8));
    assertEquals(404, refused.getResponse().getStatus());
  }

  private static String testKey() {
    return ConfigProvider.getConfig().getValue("thrillhousebot.github.private-key", String.class);
  }

  public static class StubGitHubProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      var stub =
          GitHubStub.start()
              .on("GET /api/v3/app", json(200, "{\"id\":12345}"))
              .on("GET /api/v3/app/installations", json(200, "[{\"id\":7}]"))
              .on("GET /api/v3/app/installations/7", json(200, "{\"id\":7}"))
              .on(
                  "POST /api/v3/app/installations/7/access_tokens",
                  json(201, "{\"token\":\"ghs_wiring\"}"));
      // A test profile has no teardown, so the stub stops with the test JVM.
      Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
      var armorless =
          testKey()
              .replace("-----BEGIN RSA PRIVATE KEY-----", "")
              .replace("-----END RSA PRIVATE KEY-----", "");
      return Map.of(
          "GITHUB_API_URL",
          stub.url() + API_PATH,
          "GITHUB_APP_ID",
          "12345",
          "GITHUB_PRIVATE_KEY",
          armorless,
          "quarkus.log.category.\"org.kohsuke.github\".level",
          "DEBUG");
    }
  }
}
