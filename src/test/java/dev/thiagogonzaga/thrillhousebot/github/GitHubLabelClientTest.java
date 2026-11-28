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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.PrLabeler;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

  /**
   * The labels are written at the end of a review with the token read at its start, so a review
   * that outlives the token must still get them applied.
   */
  @Nested
  class ExpiredCredentials {

    private static final String DEAD = "Bearer expired-token";
    private static final String FRESH = "Bearer minted-token";
    private static final GitHubLabelClient.AddLabelsRequest BUG =
        new GitHubLabelClient.AddLabelsRequest(List.of("bug"));
    private static final GitHubLabelClient.CreateLabelRequest AREA =
        new GitHubLabelClient.CreateLabelRequest("area/api", "ededed", "Added by ThrillhouseBot");

    private final GitHubLabelClient client = mock(GitHubLabelClient.class);

    @BeforeEach
    void bindFresh() {
      GitHubTokenRefresh.SHARED.bind(_ -> Optional.of(FRESH));
      doCallRealMethod()
          .when(client)
          .addLabels(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      doCallRealMethod()
          .when(client)
          .createLabel(anyString(), anyString(), anyString(), anyString(), any());
    }

    @AfterEach
    void unbind() {
      GitHubTokenRefresh.SHARED.bind(null);
    }

    private static WebApplicationException badCredentials() {
      return new WebApplicationException(
          Response.status(401).entity("{\"message\":\"Bad credentials\"}").build());
    }

    @Test
    void labelsRejectedForTheirCredentialAreAddedWithAFreshOne() {
      var added = List.of(new GitHubLabelClient.Label("bug", null, "d73a4a"));
      when(client.addLabelsOnce(DEAD, "json", "o", "r", 7, BUG)).thenThrow(badCredentials());
      when(client.addLabelsOnce(FRESH, "json", "o", "r", 7, BUG)).thenReturn(added);

      assertSame(added, client.addLabels(DEAD, "json", "o", "r", 7, BUG));

      var order = inOrder(client);
      order.verify(client).addLabelsOnce(DEAD, "json", "o", "r", 7, BUG);
      order.verify(client).addLabelsOnce(FRESH, "json", "o", "r", 7, BUG);
      verify(client, times(2))
          .addLabelsOnce(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void aLabelCreationRejectedForItsCredentialIsRepeatedWithAFreshOne() {
      var created = new GitHubLabelClient.Label("area/api", null, "ededed");
      when(client.createLabelOnce(DEAD, "json", "o", "r", AREA)).thenThrow(badCredentials());
      when(client.createLabelOnce(FRESH, "json", "o", "r", AREA)).thenReturn(created);

      assertSame(created, client.createLabel(DEAD, "json", "o", "r", AREA));

      verify(client, times(2))
          .createLabelOnce(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aLabelThatAlreadyExistsIsRefusedAfterOneAttempt() {
      var exists =
          new WebApplicationException(
              Response.status(422).entity("{\"message\":\"Validation Failed\"}").build());
      when(client.createLabelOnce(DEAD, "json", "o", "r", AREA)).thenThrow(exists);

      var thrown =
          assertThrows(
              WebApplicationException.class,
              () -> client.createLabel(DEAD, "json", "o", "r", AREA));

      assertSame(exists, thrown);
      verify(client, times(1))
          .createLabelOnce(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aReviewThatOutlivedItsTokenStillGetsItsLabelsApplied() {
      var config = mock(ThrillhouseConfig.class, RETURNS_DEEP_STUBS);
      when(config.review().labels().enabled()).thenReturn(true);
      when(config.review().labels().apply()).thenReturn(true);
      when(config.review().labels().allowCreate()).thenReturn(true);
      when(config.review().labels().maxLabels()).thenReturn(3);
      var labeler =
          new PrLabeler(
              config, client, mock(GitHubTokenReads.class), mock(GitHubCommentClient.class));
      when(client.createLabelOnce(anyString(), anyString(), anyString(), anyString(), any()))
          .thenAnswer(
              call -> {
                if (DEAD.equals(call.getArgument(0))) {
                  throw badCredentials();
                }
                return new GitHubLabelClient.Label("area/api", null, "ededed");
              });
      when(client.addLabelsOnce(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
          .thenAnswer(
              call -> {
                if (DEAD.equals(call.getArgument(0))) {
                  throw badCredentials();
                }
                return List.of();
              });

      labeler.applyOrSuggest(
          new PrLabeler.LabelRequest(
              DEAD,
              "o",
              "r",
              7,
              false,
              List.of("bug", "area/api"),
              List.of(new GitHubLabelClient.Label("bug", null, "d73a4a"))));

      verify(client)
          .addLabelsOnce(
              FRESH,
              "application/vnd.github+json",
              "o",
              "r",
              7,
              new GitHubLabelClient.AddLabelsRequest(List.of("bug", "area/api")));
    }
  }
}
