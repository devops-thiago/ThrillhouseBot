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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitHubLabelClientTest {

  // Mirror the runtime mapper, which ignores the extra fields GitHub returns (id, url, default…).
  private final ObjectMapper mapper =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Test
  void addLabelsRequestShouldSerializeLabelsArray() throws Exception {
    var json =
        mapper.writeValueAsString(
            new GitHubLabelClient.AddLabelsRequest(List.of("bug", "enhancement")));
    assertEquals("{\"labels\":[\"bug\",\"enhancement\"]}", json);
  }

  @Test
  void addLabelsRequestShouldDefaultNullToEmptyList() {
    assertTrue(new GitHubLabelClient.AddLabelsRequest(null).labels().isEmpty());
  }

  @Test
  void createLabelRequestShouldSerializeAllFields() throws Exception {
    var json =
        mapper.writeValueAsString(
            new GitHubLabelClient.CreateLabelRequest(
                "area/api", "ededed", "Added by ThrillhouseBot"));
    assertTrue(json.contains("\"name\":\"area/api\""));
    assertTrue(json.contains("\"color\":\"ededed\""));
    assertTrue(json.contains("\"description\":\"Added by ThrillhouseBot\""));
  }

  @Test
  void labelShouldDeserializeFromGitHubPayload() throws Exception {
    var label =
        mapper.readValue(
            "{\"id\":1,\"name\":\"bug\",\"color\":\"d73a4a\",\"description\":\"Something is broken\"}",
            GitHubLabelClient.Label.class);
    assertEquals("bug", label.name());
    assertEquals("d73a4a", label.color());
    assertEquals("Something is broken", label.description());
  }
}
