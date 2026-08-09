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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GitHubActionsClient}'s response records (#115). GitHub omits a collection
 * field entirely on some responses, so every accessor must hand back an empty list rather than the
 * {@code null} that would take a review down while merely looking for a coverage report.
 */
class GitHubActionsClientTest {

  @Test
  void anAbsentRunListReadsAsNoRuns() {
    assertTrue(new GitHubActionsClient.WorkflowRuns(0, null).workflowRuns().isEmpty());
    assertEquals(
        1,
        new GitHubActionsClient.WorkflowRuns(
                1,
                List.of(
                    new GitHubActionsClient.WorkflowRun(1L, "ci", "sha", "completed", "success")))
            .workflowRuns()
            .size());
  }

  @Test
  void anAbsentArtifactListReadsAsNoArtifacts() {
    assertTrue(new GitHubActionsClient.RunArtifacts(0, null).artifacts().isEmpty());
    assertEquals(
        1,
        new GitHubActionsClient.RunArtifacts(
                1, List.of(new GitHubActionsClient.Artifact(9L, "coverage", 10L, false)))
            .artifacts()
            .size());
  }
}
