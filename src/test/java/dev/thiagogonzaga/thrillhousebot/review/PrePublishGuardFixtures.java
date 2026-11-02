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

import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;

/** The finished run the {@link PrePublishGuard} tests hand to a guard or to the stand-down. */
final class PrePublishGuardFixtures {

  static final String AUTH = "Bearer test";
  static final String DETAILS = "https://bot.example/s";

  /** A finding the run verified and, if it stands down for a moved head, hands on (#806). */
  static final ReviewResponse.Finding VERIFIED =
      new ReviewResponse.Finding(
          "high",
          "high",
          "src/Main.java",
          10,
          "Carried null dereference",
          "the guard was removed",
          "new",
          "fixed");

  private PrePublishGuardFixtures() {}

  static ReviewOrchestrator.ReviewRequest request(boolean manual) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner",
        "repo",
        42,
        "abcdefgh",
        "T",
        "",
        "base",
        "main",
        1L,
        TestScopes.REPOSITORY_ID,
        manual);
  }

  static PrePublishGuard.Review review(boolean manual, long checkRunId) {
    return new PrePublishGuard.Review(
        AUTH,
        request(manual),
        checkRunId,
        DETAILS,
        context(List.of(), SupersededFindingsCarryover.Carried.NONE),
        new ReviewResponse(List.of(VERIFIED), List.of(), null),
        result(List.of()));
  }

  static ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> previousResponses, SupersededFindingsCarryover.Carried carried) {
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        "diff",
        "",
        0,
        List.of(),
        List.of("{}"),
        previousResponses,
        false,
        true,
        "{}",
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        List.of(),
        () -> new DiffLineResolver(Map.of()),
        null,
        List.of(),
        List.of(),
        carried);
  }

  static ReviewResult result(List<ReviewResult.PreviousFindingStatus> statuses) {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses, List.of(), 0);
  }
}
