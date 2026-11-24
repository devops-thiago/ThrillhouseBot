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

import io.quarkiverse.githubapp.GitHubClientProvider;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Optional;
import java.util.function.Consumer;
import org.kohsuke.github.GHRepository;

/**
 * Reads made through an installation's hub4j client that several callers share: the repository
 * itself and a collaborator's right to act on it. A failure is handed to the caller, which logs it
 * in its own words; an interrupt is never handed over, it is restored on the thread instead.
 */
public final class InstallationRepository {

  private InstallationRepository() {}

  /**
   * {@code owner/repo} through installation {@code installationId}'s client, if GitHub gives it.
   */
  public static Optional<GHRepository> resolve(
      GitHubClientProvider clientProvider,
      long installationId,
      String owner,
      String repo,
      Consumer<Exception> onFailure) {
    try {
      return Optional.of(
          clientProvider.getInstallationClient(installationId).getRepository(owner + "/" + repo));
    } catch (InterruptedIOException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException e) {
      onFailure.accept(e);
    }
    return Optional.empty();
  }

  /** Whether {@code login} may act on {@code repository} ({@link RepositoryPermissions#canAct}). */
  public static boolean mayAct(
      GHRepository repository, String login, Consumer<Exception> onFailure) {
    try {
      return RepositoryPermissions.canAct(repository.getPermission(login));
    } catch (InterruptedIOException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException e) {
      onFailure.accept(e);
    }
    return false;
  }
}
