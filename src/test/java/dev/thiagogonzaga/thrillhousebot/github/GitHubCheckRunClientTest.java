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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
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

  // GitHub's own limits for the check-run output fields, plus the notice the client appends when
  // it truncates. Kept as literals here (not references to the production constants) so these
  // tests pin the boundaries and wording independently of the code under test.
  private static final int MAX_TITLE = 255;
  private static final int MAX_SUMMARY = 65_535;
  private static final int MAX_TEXT = 65_535;
  private static final String TITLE_NOTICE = "\n\n… (truncated at GitHub's 255-character limit)";
  private static final String BODY_NOTICE = "\n\n… (truncated at GitHub's 65,535-character limit)";

  private static GitHubCheckRunClient.UpdateCheckRunRequest.Output output(
      String title, String summary, String text) {
    return new GitHubCheckRunClient.UpdateCheckRunRequest.Output(title, summary, text);
  }

  @Test
  void outputFieldsExactlyOnTheirLimitsPassThroughUnchanged() {
    var title = "t".repeat(MAX_TITLE);
    var summary = "s".repeat(MAX_SUMMARY);
    var text = "x".repeat(MAX_TEXT);

    var out = output(title, summary, text);

    assertEquals(title, out.title(), "a title exactly on the limit is valid and must not be cut");
    assertEquals(summary, out.summary(), "a summary exactly on the limit must not be cut");
    assertEquals(text, out.text(), "a text exactly on the limit must not be cut");
  }

  @Test
  void outputTitleOneOverItsLimitIsTruncatedWithAnHonestNotice() {
    var out = output("t".repeat(MAX_TITLE + 1), null, null);

    // Over the limit GitHub 422s the PATCH, and the fail-soft wrapper swallows it — the check run
    // would silently never update. Capping keeps the update going out.
    assertEquals(MAX_TITLE, out.title().length());
    assertTrue(out.title().endsWith(TITLE_NOTICE), "the notice must name the title limit");
    assertTrue(out.title().startsWith("tttt"), "the surviving prefix is the original content");
  }

  @Test
  void outputSummaryOneOverItsLimitIsTruncatedWithAnHonestNotice() {
    var out = output("ok", "s".repeat(MAX_SUMMARY + 1), null);

    assertEquals(MAX_SUMMARY, out.summary().length());
    assertTrue(out.summary().endsWith(BODY_NOTICE), "the notice must name the summary limit");
    assertTrue(out.summary().startsWith("ssss"));
  }

  @Test
  void outputTextOneOverItsLimitIsTruncatedWithAnHonestNotice() {
    var out = output("ok", "fine", "x".repeat(MAX_TEXT + 1));

    assertEquals(MAX_TEXT, out.text().length());
    assertTrue(out.text().endsWith(BODY_NOTICE), "the notice must name the text limit");
    assertTrue(out.text().startsWith("xxxx"));
  }

  @Test
  void outputFieldsAreCappedAtTheirOwnLimitNotASharedOne() {
    var out = output("t".repeat(MAX_TITLE + 1), "s".repeat(MAX_TITLE + 1), null);

    assertEquals(MAX_TITLE, out.title().length(), "the title is bounded at 255");
    assertEquals(
        MAX_TITLE + 1,
        out.summary().length(),
        "a summary far under its own 65,535 limit must not be cut to the title's limit");
  }

  @Test
  void outputTitleTruncationDoesNotSplitASurrogatePair() {
    // Place an astral code point (U+1F600, a high/low surrogate pair) so its HIGH surrogate lands
    // exactly on the last kept character; cutting there would emit a lone high surrogate.
    var keep = MAX_TITLE - TITLE_NOTICE.length();
    var title = "t".repeat(keep - 1) + "😀" + "y".repeat(MAX_TITLE);

    var capped = output(title, null, null).title();
    var kept = capped.substring(0, capped.length() - TITLE_NOTICE.length());

    assertFalse(
        Character.isHighSurrogate(kept.charAt(kept.length() - 1)),
        "the kept text must not end in a dangling high surrogate");
    assertEquals(keep - 1, kept.length(), "the cut steps back one character to keep the pair");
    assertTrue(capped.endsWith(TITLE_NOTICE));
  }

  @Test
  void nullOutputFieldsStayNull() {
    var out = output(null, null, null);

    assertNull(out.title());
    assertNull(out.summary());
    assertNull(out.text());
  }

  /**
   * #624: the check-run conclusion is written after the model work and after the review has been
   * posted — exactly where a token read at the top of a long review has already expired. Three of
   * that incident's 401s landed here, leaving the merge gate stuck in progress on a pull request
   * whose review had actually finished.
   */
  @Nested
  class ExpiredCredentials {

    private static final String DEAD = "Bearer expired-token";
    private static final String FRESH = "Bearer minted-token";
    private static final GitHubCheckRunClient.UpdateCheckRunRequest CONCLUSION =
        new GitHubCheckRunClient.UpdateCheckRunRequest(null, "success", null, null, null);

    @AfterEach
    void unbind() {
      GitHubTokenRefresh.SHARED.bind(null);
    }

    private GitHubCheckRunClient updatingClient() {
      var client = mock(GitHubCheckRunClient.class);
      doCallRealMethod()
          .when(client)
          .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
      return client;
    }

    private static WebApplicationException badCredentials() {
      return new WebApplicationException(
          Response.status(401).entity("{\"message\":\"Bad credentials\"}").build());
    }

    @Test
    void aConclusionRejectedForItsCredentialIsWrittenWithAFreshOne() {
      GitHubTokenRefresh.SHARED.bind(_ -> Optional.of(FRESH));
      var client = updatingClient();
      doThrow(badCredentials())
          .when(client)
          .updateCheckRunOnce(DEAD, "json", "o", "r", 94131141478L, CONCLUSION);

      client.updateCheckRun(DEAD, "json", "o", "r", 94131141478L, CONCLUSION);

      // The gate ends up green instead of stuck in progress for good.
      verify(client).updateCheckRunOnce(FRESH, "json", "o", "r", 94131141478L, CONCLUSION);
    }

    @Test
    void aRejectionThatNoFreshTokenCanFixStillPropagates() {
      var client = updatingClient();
      var rejection = badCredentials();
      doThrow(rejection).when(client).updateCheckRunOnce(DEAD, "json", "o", "r", 1L, CONCLUSION);

      var thrown =
          assertThrows(
              WebApplicationException.class,
              () -> client.updateCheckRun(DEAD, "json", "o", "r", 1L, CONCLUSION));

      // CheckRunManager's own fail-soft handling is left exactly as it was.
      assertSame(rejection, thrown);
      verify(client, times(1))
          .updateCheckRunOnce(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }
  }
}
