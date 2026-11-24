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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #1433 review: the manifest's {@code thread} follows the rules the review posts by, so a consumer
 * is not sent to reply on a thread the round never opens.
 */
class FindingsManifestThreadTest {

  private static final Pattern LINE =
      Pattern.compile("^<!-- thrillhousebot:findings v=1 (.*) -->$", Pattern.MULTILINE);
  private static final String PATCH = "@@ -1,2 +1,3 @@\n a\n+b\n+c\n";

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);
  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);
  private final ReviewPublisher publisher;

  FindingsManifestThreadTest() {
    var config = mock(ThrillhouseConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(2);
    publisher =
        new ReviewPublisher(
            mock(GitHubReviewClient.class),
            commentClient,
            mock(ReviewThreadService.class),
            mock(SuggestionFormatter.class),
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BotIdentity.of("thrillhousebot"));
  }

  private static Finding finding(RiskLevel risk, Confidence confidence, String file, int line) {
    return new Finding(risk, confidence, file, line, "T" + line, "d", null, null);
  }

  private static ReviewResult result(List<Finding> findings, List<Finding> openPrevious) {
    return new ReviewResult(
        findings,
        0,
        0,
        0,
        0,
        null,
        ReviewState.COMMENT,
        true,
        "summary",
        List.of(),
        List.of(),
        0,
        false,
        true,
        null,
        0,
        openPrevious);
  }

  /** {@code thread} by entry line. */
  private Map<Integer, Boolean> threads(ReviewResult result, DiffLineResolver resolver)
      throws Exception {
    publisher.publishSummary(
        new ReviewPublisher.SummaryRequest("auth", "o", "r", 1, result, false, null, resolver));
    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    var matcher = LINE.matcher(body.getValue().body());
    assertTrue(matcher.find(), body.getValue().body());
    var byLine = new HashMap<Integer, Boolean>();
    for (JsonNode entry : new ObjectMapper().readTree(matcher.group(1))) {
      byLine.put(entry.get("line").asInt(), entry.get("thread").asBoolean());
    }
    return byLine;
  }

  @Test
  void onlyInlineFindingsOnAChangedFileWithinTheCapExpectAThread() throws Exception {
    var changed = new DiffLineResolver(Map.of("src/A.java", PATCH));
    var threads =
        threads(
            result(
                List.of(
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/Other.java", 1),
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 2),
                    finding(RiskLevel.MEDIUM, Confidence.LOW, "src/A.java", 3),
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 4),
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 5)),
                List.of(finding(RiskLevel.HIGH, Confidence.HIGH, "src/Gone.java", 9))),
            changed);

    assertEquals(
        Map.of(1, false, 2, true, 3, false, 4, true, 5, false, 9, true),
        threads,
        "outside the diff, listed only and past the cap of two expect no thread; a carried"
            + " finding keeps its inline routing");
  }

  @Test
  void withoutTheDiffEveryInlineFindingExpectsAThread() throws Exception {
    var threads =
        threads(
            result(
                List.of(
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/Other.java", 1),
                    finding(RiskLevel.MEDIUM, Confidence.LOW, "src/A.java", 3)),
                List.of()),
            null);

    assertEquals(Map.of(1, true, 3, false), threads);
  }
}
