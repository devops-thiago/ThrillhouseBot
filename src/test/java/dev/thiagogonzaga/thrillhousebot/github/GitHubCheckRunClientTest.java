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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GitHubCheckRunClientTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void completedUpdateShouldOmitStatusAndNullOutputFields() throws Exception {
    var request =
        new GitHubCheckRunClient.UpdateCheckRunRequest(
            null,
            "success",
            "2026-06-08T20:28:11Z",
            "https://bot.example/session/7",
            new GitHubCheckRunClient.UpdateCheckRunRequest.Output(
                "ThrillhouseBot Review ✅", "No issues found.", null));

    var json = mapper.writeValueAsString(request);

    assertFalse(json.contains("\"status\""));
    assertTrue(json.contains("\"conclusion\":\"success\""));
    assertTrue(json.contains("\"completed_at\":\"2026-06-08T20:28:11Z\""));
    assertTrue(json.contains("\"details_url\":\"https://bot.example/session/7\""));
    assertFalse(json.contains("\"text\""));
  }

  @Test
  void inProgressUpdateShouldOmitConclusionAndCompletedAt() throws Exception {
    var request =
        new GitHubCheckRunClient.UpdateCheckRunRequest("in_progress", null, null, null, null);

    var json = mapper.writeValueAsString(request);

    assertTrue(json.contains("\"status\":\"in_progress\""));
    assertFalse(json.contains("\"conclusion\""));
    assertFalse(json.contains("\"completed_at\""));
    assertFalse(json.contains("\"details_url\""));
    assertFalse(json.contains("\"output\""));
  }

  @Test
  void conclusionOnlyUpdateShouldSerializeMinimalPayload() throws Exception {
    var request = new GitHubCheckRunClient.UpdateCheckRunRequest(null, "failure", null, null, null);

    var json = mapper.writeValueAsString(request);

    assertFalse(json.contains("\"status\""));
    assertTrue(json.contains("\"conclusion\":\"failure\""));
    assertFalse(json.contains("\"completed_at\""));
    assertFalse(json.contains("\"output\""));
  }

  @Test
  void createRequestShouldCarryDetailsUrlAndOmitItWhenNull() throws Exception {
    var withUrl =
        new GitHubCheckRunClient.CreateCheckRunRequest(
            "ThrillhouseBot Review", "abc123", "in_progress", "https://bot.example/session/7");
    var withoutUrl =
        new GitHubCheckRunClient.CreateCheckRunRequest(
            "ThrillhouseBot Review", "abc123", "in_progress", null);

    assertTrue(
        mapper
            .writeValueAsString(withUrl)
            .contains("\"details_url\":\"https://bot.example/session/7\""));
    assertFalse(mapper.writeValueAsString(withoutUrl).contains("\"details_url\""));
  }
}
