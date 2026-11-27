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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import java.util.List;

/**
 * The commenters the review tests treat as confirmed write-access holders: the logins their
 * fixtures use for a maintainer. A comment still needs a write-capable association to count, so a
 * fixture that gives one of these logins {@code CONTRIBUTOR} or no association stays unprivileged.
 */
final class KnownWriters {

  static final VerifiedWriters ALL =
      VerifiedWriters.of(
          List.of("maintainer", "alice", "bob", "octocat", "u", "m", "member", "dev", "reviewer"));

  private KnownWriters() {}

  /** A resolver that answers {@link #ALL} without asking GitHub, for a loader under test. */
  static VerifiedWritersResolver resolver() {
    return new VerifiedWritersResolver(null, BotIdentity.of("thrillhousebot")) {
      @Override
      public VerifiedWriters resolve(
          long installationId,
          String owner,
          String repo,
          List<GitHubReviewClient.PullRequestComment> inlineComments,
          List<GitHubCommentClient.IssueComment> conversationComments) {
        return ALL;
      }
    };
  }
}
