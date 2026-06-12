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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewResponseTest {

  @Test
  void compactConstructorShouldDefensivelyCopyFindings() {
    var findings = new ArrayList<ReviewResponse.Finding>();
    findings.add(new ReviewResponse.Finding("high", "f", 1, "t", "d", null, null));

    var response = new ReviewResponse(findings, null, null);

    // Modifying the original list should not affect the record's list
    findings.add(new ReviewResponse.Finding("low", "f2", 2, "t2", "d2", null, null));

    assertEquals(1, response.findings().size());
  }

  @Test
  void compactConstructorShouldDefensivelyCopyPreviousFindingsStatus() {
    var statuses = new ArrayList<ReviewResponse.PreviousFindingStatus>();
    statuses.add(new ReviewResponse.PreviousFindingStatus(1, "resolved", "done"));

    var response = new ReviewResponse(List.of(), statuses, null);

    // Modifying the original list should not affect the record's list
    statuses.add(new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still"));

    assertEquals(1, response.previousFindingsStatus().size());
  }

  @Test
  void compactConstructorShouldNotThrowNpeForNullFindings() {
    var response = new ReviewResponse(null, null, null);

    assertNotNull(response.findings());
    assertTrue(response.findings().isEmpty());
  }

  @Test
  void compactConstructorShouldNotThrowNpeForNullPreviousFindingsStatus() {
    var response = new ReviewResponse(List.of(), null, null);

    assertNotNull(response.previousFindingsStatus());
    assertTrue(response.previousFindingsStatus().isEmpty());
  }

  @Test
  void shouldStoreAllFields() {
    var findings =
        List.of(
            new ReviewResponse.Finding(
                "critical", "Secret.java", 10, "Key leak", "Hardcoded", "sk-123", "env var"));
    var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));
    var summary =
        new ReviewResponse.Summary(1, 1, 0, 0, 0, "needs fix", "adds endpoint", List.of());

    var response = new ReviewResponse(findings, statuses, summary);

    assertEquals(1, response.findings().size());
    assertEquals("critical", response.findings().get(0).risk());
    assertEquals(1, response.previousFindingsStatus().size());
    assertEquals("resolved", response.previousFindingsStatus().get(0).status());
    assertEquals("needs fix", response.summary().overallAssessment());
  }
}
