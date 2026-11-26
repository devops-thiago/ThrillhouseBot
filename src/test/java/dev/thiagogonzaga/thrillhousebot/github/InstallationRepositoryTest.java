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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkiverse.githubapp.GitHubClientProvider;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

class InstallationRepositoryTest {

  private final GitHubClientProvider clientProvider = mock(GitHubClientProvider.class);
  private final GitHub gitHub = mock(GitHub.class);
  private final GHRepository repository = mock(GHRepository.class);
  private final List<Exception> failures = new ArrayList<>();

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void theRepositoryIsReadThroughTheInstallationsClient() throws Exception {
    doReturn(gitHub).when(clientProvider).getInstallationClient(7L);
    when(gitHub.getRepository("o/r")).thenReturn(repository);

    assertEquals(
        Optional.of(repository),
        InstallationRepository.resolve(clientProvider, 7L, "o", "r", failures::add));
    assertTrue(failures.isEmpty());
  }

  @Test
  void aFailedReadIsHandedToTheCaller() throws Exception {
    doReturn(gitHub).when(clientProvider).getInstallationClient(7L);
    var refused = new IOException("not found");
    when(gitHub.getRepository("o/r")).thenThrow(refused);

    assertTrue(
        InstallationRepository.resolve(clientProvider, 7L, "o", "r", failures::add).isEmpty());
    assertEquals(List.of(refused), failures);
  }

  @Test
  void aClientThatCannotBeBuiltIsHandedToTheCaller() {
    var broken = new IllegalStateException("no token");
    when(clientProvider.getInstallationClient(7L)).thenThrow(broken);

    assertTrue(
        InstallationRepository.resolve(clientProvider, 7L, "o", "r", failures::add).isEmpty());
    assertEquals(List.of(broken), failures);
  }

  @Test
  void anInterruptedReadKeepsTheInterruptAndReportsNothing() throws Exception {
    doReturn(gitHub).when(clientProvider).getInstallationClient(7L);
    when(gitHub.getRepository("o/r")).thenThrow(new InterruptedIOException());

    assertTrue(
        InstallationRepository.resolve(clientProvider, 7L, "o", "r", failures::add).isEmpty());
    assertTrue(Thread.currentThread().isInterrupted());
    assertTrue(failures.isEmpty());
  }

  @Test
  void writeAndAdminMayAct() throws Exception {
    when(repository.getPermission("w")).thenReturn(GHPermissionType.WRITE);
    when(repository.getPermission("r")).thenReturn(GHPermissionType.READ);

    assertTrue(InstallationRepository.mayAct(repository, "w", failures::add));
    assertFalse(InstallationRepository.mayAct(repository, "r", failures::add));
    assertTrue(failures.isEmpty());
  }

  @Test
  void aFailedPermissionReadDeniesAndIsHandedToTheCaller() throws Exception {
    var refused = new IOException("403");
    when(repository.getPermission("w")).thenThrow(refused);
    var broken = new IllegalStateException("bad json");
    when(repository.getPermission("x")).thenThrow(broken);

    assertFalse(InstallationRepository.mayAct(repository, "w", failures::add));
    assertFalse(InstallationRepository.mayAct(repository, "x", failures::add));
    assertEquals(List.of(refused, broken), failures);
  }

  @Test
  void aTimedOutReadIsReportedAndLeavesTheThreadUninterrupted() throws Exception {
    doReturn(gitHub).when(clientProvider).getInstallationClient(7L);
    var timeout = new SocketTimeoutException("Read timed out");
    when(gitHub.getRepository("o/r")).thenThrow(timeout);

    assertTrue(
        InstallationRepository.resolve(clientProvider, 7L, "o", "r", failures::add).isEmpty());
    assertFalse(Thread.currentThread().isInterrupted());
    assertEquals(List.of(timeout), failures);
  }

  @Test
  void aTimedOutPermissionReadDeniesAndIsReported() throws Exception {
    var timeout = new SocketTimeoutException("Read timed out");
    when(repository.getPermission("w")).thenThrow(timeout);

    assertFalse(InstallationRepository.mayAct(repository, "w", failures::add));
    assertFalse(Thread.currentThread().isInterrupted());
    assertEquals(List.of(timeout), failures);
  }

  @Test
  void anInterruptedPermissionReadDeniesAndKeepsTheInterrupt() throws Exception {
    when(repository.getPermission("w")).thenThrow(new InterruptedIOException());

    assertFalse(InstallationRepository.mayAct(repository, "w", failures::add));
    assertTrue(Thread.currentThread().isInterrupted());
    assertTrue(failures.isEmpty());
  }
}
