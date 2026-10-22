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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The accounts whose installations the bot serves ({@code thrillhousebot.github.allowed-accounts},
 * #1235). A public GitHub App can be installed by anyone, so without a list every installation's
 * webhooks would run reviews on the operator's budget. Logins match ignoring case; an empty list
 * serves every account.
 */
@ApplicationScoped
public class AllowedAccounts {

  private static final Logger log = LoggerFactory.getLogger(AllowedAccounts.class);

  private final Set<String> logins;

  @Inject
  public AllowedAccounts(ThrillhouseConfig config) {
    this(config.github().allowedAccounts().orElse(List.of()));
  }

  /** Visible for tests. */
  public AllowedAccounts(List<String> logins) {
    this.logins =
        logins.stream()
            .map(String::strip)
            .filter(login -> !login.isEmpty())
            .map(login -> login.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
  }

  /** Whether the installation on {@code accountLogin} is served; a missing login never is. */
  public boolean allows(String accountLogin) {
    if (logins.isEmpty()) {
      return true;
    }
    return accountLogin != null && logins.contains(accountLogin.strip().toLowerCase(Locale.ROOT));
  }

  void onStart(@Observes StartupEvent event) {
    if (logins.isEmpty()) {
      log.info(
          "Serving installations on every account; set GITHUB_ALLOWED_ACCOUNTS when the App is"
              + " public");
    } else {
      log.info(
          "Serving installations on these accounts only: {}", logins.stream().sorted().toList());
    }
  }
}
