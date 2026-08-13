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

import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swaps an installation token GitHub has stopped accepting for a freshly minted one, so a call that
 * outlived its credential can be repeated instead of discarded.
 *
 * <p>#624: an installation token is read once, at the top of a review, and every write at the end
 * of that review reuses the string. A review on {@code AI_TIMEOUT=900s} runs longer than the margin
 * the cache leaves, so the token is dead by the time the findings are ready — and the review post,
 * the check-run conclusion, the mark-as-failed and the failure comment all drew {@code 401 Bad
 * credentials} off the same corpse. {@link GitHubWriteRetry} was already backing off and repeating
 * those calls, but a repeat that re-sends the dead token can only draw the same 401: a credential
 * fault is the one class of failure where retrying without refreshing first is guaranteed useless.
 *
 * <h2>Why a 401 cannot be a duplicate post</h2>
 *
 * GitHub authenticates before it routes, so a request it answers with 401 never reached the handler
 * that would have created the comment, the review or the check-run update. The response is the
 * rejection itself, not a report about a write that happened — the same argument {@link
 * GitHubWriteRetry} makes for a throttle, and a stronger one, because 401 leaves no room for the
 * request to have been partially applied. That is what makes repeating it safe when the ambiguous
 * failures (a reset connection, a read timeout, a 5xx) deliberately are not repeated.
 *
 * <h2>Why the seam is a process-wide binding</h2>
 *
 * The calls that need this are {@code default} methods on the REST-client interfaces, which have no
 * injection point — the same reason {@link GitHubWritePacer#DEFAULT} and {@link
 * GitHubLostWrites#SHARED} are process-wide. {@link GitHubAuthClient} is the only thing that can
 * mint a token, and it binds itself here when CDI constructs it; until then, and in any unit test
 * that never builds one, {@link #NONE} makes every refusal behave exactly as it did before.
 */
final class GitHubTokenRefresh {

  private static final Logger log = LoggerFactory.getLogger(GitHubTokenRefresh.class);

  /** Mints a replacement for an {@code Authorization} header GitHub has stopped accepting. */
  @FunctionalInterface
  interface Source {

    /**
     * The current header for whichever installation minted {@code deadAuthHeader}, or empty when
     * the header is not one this process issued and so cannot be traced to an installation.
     */
    Optional<String> replace(String deadAuthHeader);
  }

  /** Nothing can be replaced — the state before {@link GitHubAuthClient} binds itself. */
  static final Source NONE = _ -> Optional.empty();

  /** The process-wide seam: one binding, shared by every GitHub call in the instance. */
  static final GitHubTokenRefresh SHARED = new GitHubTokenRefresh();

  private final AtomicReference<Source> source = new AtomicReference<>(NONE);

  GitHubTokenRefresh() {}

  /** Points this seam at whatever can mint tokens; {@code null} restores {@link #NONE}. */
  void bind(Source replacement) {
    source.set(replacement == null ? NONE : replacement);
  }

  /**
   * The header {@code auth} should be repeated with after {@code failure}, or empty when the call
   * must not be repeated: the failure was not a rejected credential, the caller carries no
   * credential at all, or nothing fresher than the one that just failed can be minted.
   *
   * <p>A replacement equal to the header that failed is refused rather than returned. That is the
   * shape a genuinely revoked installation takes — the cache is already holding the newest token
   * GitHub will issue and it is still being turned away — and repeating the call with it would only
   * spend another request to be told the same thing.
   *
   * <p>Minting is a network call of its own, so a failure inside it is swallowed: the caller is
   * already handling {@code failure}, and replacing that with a token-endpoint error would hide the
   * 401 that actually explains the loss.
   */
  Optional<String> replacementFor(String operation, String auth, WebApplicationException failure) {
    if (auth == null
        || GitHubApiError.of(failure).filter(GitHubApiError::isExpiredCredential).isEmpty()) {
      return Optional.empty();
    }
    var fresh = mint(auth);
    if (fresh.isEmpty() || fresh.get().equals(auth)) {
      log.warn(
          "GitHub rejected the credential on {} and no fresher installation token is available —"
              + " the payload is lost",
          operation);
      return Optional.empty();
    }
    log.warn(
        "GitHub rejected the credential on {} — the installation token has expired, retrying once"
            + " with a freshly minted one",
        operation);
    return fresh;
  }

  private Optional<String> mint(String auth) {
    try {
      return source.get().replace(auth);
    } catch (RuntimeException e) {
      log.warn("Failed to mint a replacement installation token", e);
      return Optional.empty();
    }
  }

  /**
   * Runs a GitHub call with {@code auth} and, when GitHub rejects the credential, runs it exactly
   * once more with a fresh one. For the reads (#626) and the writes that have no backoff loop of
   * their own — {@link GitHubWriteRetry} folds the same refresh into its attempt loop instead, so
   * one call there can still both refresh and back off.
   */
  <T> T retrying(String operation, String auth, Function<String, T> call) {
    try {
      return call.apply(auth);
    } catch (WebApplicationException e) {
      var fresh = replacementFor(operation, auth, e);
      if (fresh.isEmpty()) {
        throw e;
      }
      return call.apply(fresh.get());
    }
  }
}
