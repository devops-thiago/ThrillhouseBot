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
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  /**
   * The credential-named-key assignment shape as one pattern, before its key names were split
   * across {@link LearningText#CREDENTIAL_ASSIGNMENTS}: the reference the split must agree with.
   */
  private static final Pattern ORIGINAL_ASSIGNMENT =
      Pattern.compile(
          "(?i)[\\w.-]{0,40}(?:passw(?:or)?d|pwd|secret|token|api[_-]?key|access[_-]?key"
              + "|private[_-]?key)[\\w.-]{0,40}\\s{0,4}(?::=|=>|[:=])\\s{0,4}['\"]?"
              + "(?=[^\\s'\"]*[^\\sa-z'\"])[^\\s'\"]{8,}");

  private static boolean splitAssignmentFound(String text) {
    return LearningText.CREDENTIAL_ASSIGNMENTS.stream().anyMatch(p -> p.matcher(text).find());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "password: hunter2!x",
        "password: required",
        "secret_key = 'abc123456'",
        "authToken=>abcdefg1",
        "api-key := 12345678",
        "MY_PRIVATE_KEY: \"xxxxxxxx9\"",
        "passwd=abcdefgh",
        "pwd     =  abc12345",
        "accesskey:abc.defgh1",
        "the token is fine",
        "Access_Key=ABCDEFGH",
        "private-key=>'0123456789'",
      })
  void credentialAssignmentsAreFoundAsTheSinglePatternFoundThem(String text) {
    assertEquals(ORIGINAL_ASSIGNMENT.matcher(text).find(), splitAssignmentFound(text), text);
  }

  @Test
  void generatedCredentialAssignmentsAreFoundAsTheSinglePatternFoundThem() {
    String[] parts = {
      "password",
      "passwd",
      "pwd",
      "secret",
      "token",
      "api_key",
      "api-key",
      "apikey",
      "accesskey",
      "access_key",
      "private-key",
      "privatekey",
      "PASSWORD",
      "Token",
      "my_",
      "auth",
      ".",
      "-",
      " ",
      "  ",
      ":",
      "=",
      ":=",
      "=>",
      "'",
      "\"",
      "abc12345",
      "abcdefgh",
      "x9",
      "value1!",
      "\t",
      "key",
      "api",
      "access",
      "private"
    };
    var random = new Random(38);
    for (var n = 0; n < 20_000; n++) {
      var text = new StringBuilder();
      for (var k = 1 + random.nextInt(8); k > 0; k--) {
        text.append(parts[random.nextInt(parts.length)]);
      }
      var t = text.toString();
      assertEquals(ORIGINAL_ASSIGNMENT.matcher(t).find(), splitAssignmentFound(t), t);
    }
  }
}
