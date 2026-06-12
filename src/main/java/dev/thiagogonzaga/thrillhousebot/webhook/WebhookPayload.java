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
package dev.thiagogonzaga.thrillhousebot.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(
    String action,
    @JsonProperty("pull_request") PullRequest pullRequest,
    Repository repository,
    Installation installation,
    Issue issue,
    Comment comment,
    @JsonProperty("check_run") CheckRun checkRun,
    @JsonProperty("check_suite") CheckSuite checkSuite) {
  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PullRequest(
      int number, String title, Head head, Base base, User user, String body) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Head(String sha, String ref) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Base(String sha, String ref) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record User(String login, long id) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Repository(
      @JsonProperty("full_name") String fullName,
      String name,
      @JsonProperty("default_branch") String defaultBranch,
      User owner) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Installation(long id) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Issue(
      int number,
      String title,
      String body,
      @JsonProperty("pull_request") PullRequestLink pullRequest) {}

  @RegisterForReflection
  public record PullRequestLink(
      String url,
      @JsonProperty("html_url") String htmlUrl,
      @JsonProperty("diff_url") String diffUrl,
      @JsonProperty("patch_url") String patchUrl) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Comment(long id, String body, User user) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CheckRun(
      long id,
      @JsonProperty("head_sha") String headSha,
      String status,
      String conclusion,
      App app,
      @JsonProperty("check_suite") CheckSuite checkSuite) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record App(long id) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CheckSuite(
      @JsonProperty("head_sha") String headSha, @JsonProperty("head_branch") String headBranch) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RequestedAction(String identifier) {}
}
