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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.runtime.StartupEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AllowedAccountsTest {

  @Test
  void anEmptyListServesEveryAccount() {
    var all = new AllowedAccounts(List.of(" ", ""));
    assertTrue(all.allows("anyone"));
    assertTrue(all.allows(null));
    assertDoesNotThrow(() -> all.onStart(mock(StartupEvent.class)));
  }

  @Test
  void aListServesOnlyItsAccountsIgnoringCaseAndSpace() {
    var listed = new AllowedAccounts(List.of(" Devops-Thiago ", "acme"));
    assertTrue(listed.allows("devops-thiago"));
    assertTrue(listed.allows(" ACME "));
    assertFalse(listed.allows("stranger"));
    assertFalse(listed.allows(null));
    assertDoesNotThrow(() -> listed.onStart(mock(StartupEvent.class)));
  }

  @Test
  void theListIsReadFromConfiguration() {
    var config = mock(ThrillhouseConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(github);
    when(github.allowedAccounts()).thenReturn(Optional.of(List.of("acme")));
    assertFalse(new AllowedAccounts(config).allows("stranger"));

    when(github.allowedAccounts()).thenReturn(Optional.empty());
    assertTrue(new AllowedAccounts(config).allows("stranger"));
  }
}
