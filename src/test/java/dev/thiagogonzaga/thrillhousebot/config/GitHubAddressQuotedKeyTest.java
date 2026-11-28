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

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * A REST client address can be written with the client's key in quotes ({@code
 * quarkus.rest-client."github-api".url}). {@link GitHubAddressCheck} reads the unquoted name and
 * relies on Quarkus to answer it with a value set under the quoted one. This runs the check against
 * the application's real configuration, so a Quarkus upgrade that stops doing so fails here instead
 * of letting such an address go unchecked.
 *
 * <p>The addresses are loopback ones, which the start-up check accepts in the test launch mode, so
 * the application starts; the check is then run as it runs in production, with loopback refused.
 */
@QuarkusTest
@TestProfile(GitHubAddressQuotedKeyTest.QuotedKeys.class)
class GitHubAddressQuotedKeyTest {

  private static final String REFUSED =
      " must be an https address, because credentials are sent to it: found the scheme http";

  public static class QuotedKeys implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.rest-client.\"github-api\".url", "http://127.0.0.1:9/quoted-rest",
          "quarkus.rest-client.\"github-graphql\".uri", "http://127.0.0.1:9/quoted-graphql");
    }
  }

  @Test
  void anAddressSetUnderTheQuotedClientKeyIsRefused() {
    var config = ConfigProvider.getConfig();

    var problems =
        GitHubAddressCheck.problems(name -> config.getOptionalValue(name, String.class), false);

    assertEquals(
        List.of(
            "quarkus.rest-client.github-api.url" + REFUSED,
            "quarkus.rest-client.github-graphql.uri" + REFUSED),
        problems);
  }
}
