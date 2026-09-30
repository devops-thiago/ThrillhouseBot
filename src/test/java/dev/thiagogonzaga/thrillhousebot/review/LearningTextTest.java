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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LearningTextTest {

  /** Credential-shaped values built at run time, so no real-format literal sits in the source. */
  private static final FakeCredentials FAKE = new FakeCredentials(38);

  @Test
  void credentialShapedValuesAreRecognized() {
    var secrets =
        List.of(
            "use " + FAKE.githubToken() + " for the bot",
            FAKE.githubFineGrainedToken(),
            "key " + FAKE.awsAccessKey() + " in the env",
            FakeCredentials.pemHeader("RSA"),
            "token " + FAKE.jwt(),
            "slack " + FAKE.slackToken(),
            "sk-" + "proj-" + FAKE.alnum(22),
            FAKE.googleApiKey(),
            // Only the diff scan's rules (#60) know this one.
            "stripe " + FAKE.stripeLiveKey(),
            "Authorization: Bearer abcdef1234567890abcd",
            "password = hunter22hunter",
            "client_secret: 'abcdefgh12345'",
            "secret_key = hunter22hunter",
            "token = hunter22hunter",
            "authToken: hunter22hunter",
            "secret_key: ABCD1234EFGH in prod config",
            "DB_PASSWORD=\"s3cr3t!pass\"");
    for (var secret : secrets) {
      assertTrue(LearningText.containsCredential(secret), secret);
    }
  }

  @Test
  void ordinaryProseIsNotACredential() {
    for (var prose :
        List.of(
            "GitHub PR review threads are flat",
            "the password: yes",
            "a bearer token is read from the env",
            "Endpoints use Bearer authentication with env-provided tokens",
            "password: required here",
            "api_key: mandatory",
            "ask-for-review is fine",
            "")) {
      assertFalse(LearningText.containsCredential(prose), prose);
    }
    assertFalse(LearningText.containsCredential(null));
  }

  @Test
  void normalizeDropsQuotesAndInvisiblesAndCollapsesToOneLine() {
    var reply =
        """
        > **MEDIUM — renderThread misses deeper nested replies**
        > the bot's text

        Threads are\u202E flat:\u200B every reply's `in_reply_to_id` is the root.\u001B[31m
        ```
        in_reply_to_id == rootId
        ```
        """;

    var text = LearningText.normalize(reply, 1000);

    assertEquals(
        "Threads are flat: every reply's `in_reply_to_id` is the root. ``` in_reply_to_id =="
            + " rootId ```",
        text);
  }

  @Test
  void normalizeOfNothingIsEmpty() {
    assertEquals("", LearningText.normalize(null, 10));
    assertEquals("", LearningText.normalize("  \n ", 10));
    assertEquals("", LearningText.normalize("> only quoted", 10));
  }

  @Test
  void oneLineFlattensWithoutDroppingQuotes() {
    assertEquals("> a b", LearningText.oneLine("> a\n\tb", 10));
    assertEquals("", LearningText.oneLine(null, 10));
  }

  @Test
  void clipMarksTheCutAndNeverSplitsASurrogatePair() {
    assertEquals("abc", LearningText.clip("abc", 3));
    assertEquals("ab…", LearningText.clip("abcd", 3));
    assertEquals("a…", LearningText.clip("a😀bc", 3));
    assertEquals("…", LearningText.clip("abc", 1));
  }
}
