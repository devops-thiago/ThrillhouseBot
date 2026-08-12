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

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes down what a failed GitHub call actually returned, on every GitHub REST client.
 *
 * <p>#568: the bot logged nothing but {@code status code 403}, which is the same line for "you are
 * creating content faster than GitHub accepts it" and for "this App has no permission to comment".
 * The two need opposite responses — wait and repost, versus fix the App installation — and neither
 * the maintainer nor an operator could tell which had happened from the instance's own output. The
 * response body and the rate-limit headers say which, so they are logged.
 *
 * <p>Registered as a {@link ResponseExceptionMapper} rather than wired into each call site so it
 * covers reads as well as writes and inherits any client added later. It deliberately returns
 * {@code null}: the mapper chain then falls through to the runtime's default mapper, so callers
 * keep the exact exception type and fail-soft handling they already have and this class changes
 * nothing but the log. Its {@linkplain #getPriority() priority} only has to beat the default
 * mapper's, which sits at {@link Integer#MAX_VALUE}.
 *
 * <p>Not every error deserves a warning: the bot probes for optional repository files ({@code
 * .github/instructions}, the settings file, the stack manifests) and reads a 404 as "absent", so a
 * 404 is ordinary traffic and is logged at debug. Auth, throttle and server failures are the ones
 * that mean something went wrong.
 */
@Priority(Priorities.USER)
public class GitHubErrorLogger implements ResponseExceptionMapper<Throwable> {

  private static final Logger log = LoggerFactory.getLogger(GitHubErrorLogger.class);

  @Override
  public boolean handles(int status, MultivaluedMap<String, Object> headers) {
    return status >= 400;
  }

  @Override
  public int getPriority() {
    return Priorities.USER;
  }

  /**
   * Logs the failure and returns {@code null} so the runtime's default mapper still builds the
   * exception the callers are written against.
   *
   * <p>Both lines are behind a level check because {@link GitHubApiError#diagnostics()} builds its
   * string eagerly — a parameter placeholder defers the {@code toString}, not the call that
   * produces the argument. The debug line is the one that pays: it runs on every optional-file
   * probe, which is ordinary traffic, and debug is off in production. The messages themselves are
   * unchanged; this is the diagnosis path that identified the {@code 401 Bad credentials} failure
   * in issue 624, so what it prints when it prints must stay exactly as it was.
   */
  @Override
  public Throwable toThrowable(Response response) {
    var error = GitHubApiError.from(response);
    if (error.isSevere()) {
      if (log.isWarnEnabled()) {
        log.warn("GitHub API call failed: {}", error.diagnostics());
      }
    } else if (log.isDebugEnabled()) {
      log.debug("GitHub API call returned an error: {}", error.diagnostics());
    }
    return null;
  }
}
