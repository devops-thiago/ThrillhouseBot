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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardResourceSummaryTest {

  @Test
  void nullSafeCountShouldDefaultNullToZero() {
    assertEquals(0L, DashboardResource.nullSafeCount(null));
  }

  @Test
  void nullSafeCountShouldReturnValueWhenPresent() {
    assertEquals(42L, DashboardResource.nullSafeCount(42L));
  }

  @Test
  void extractTotalCostShouldReturnZeroForNullRow() {
    assertEquals(0.0, DashboardResource.extractTotalCost(null, "totalCost"));
  }

  @Test
  void extractTotalCostShouldReadNumericValue() {
    assertEquals(
        0.05, DashboardResource.extractTotalCost(Map.of("totalCost", 0.05), "totalCost"), 1e-9);
  }

  @Test
  void extractTotalCostShouldReturnZeroForMissingOrNonNumericValue() {
    assertEquals(0.0, DashboardResource.extractTotalCost(Map.of(), "totalCost"));
    assertEquals(0.0, DashboardResource.extractTotalCost(Map.of("totalCost", "n/a"), "totalCost"));
  }

  @Test
  void extractTopModelShouldDefaultWhenMissingOrBlank() {
    assertEquals("N/A", DashboardResource.extractTopModel(null));
    assertEquals("N/A", DashboardResource.extractTopModel(Map.of()));
    var nullModelRow = new java.util.HashMap<String, Object>();
    nullModelRow.put("model", null);
    assertEquals("N/A", DashboardResource.extractTopModel(nullModelRow));
    assertEquals("N/A", DashboardResource.extractTopModel(Map.of("model", "  ")));
  }

  @Test
  void extractTopModelShouldReturnModelName() {
    assertEquals(
        "deepseek-chat", DashboardResource.extractTopModel(Map.of("model", "deepseek-chat")));
  }

  @Test
  void toSessionDetailShouldIncludeAiResponseJson() {
    var session = new ReviewSession();
    session.id = 7L;
    session.setRepository("owner/repo");
    session.setPrNumber(1);
    session.setPrTitle("Test");
    session.setCommitSha("abc");
    session.setTimestamp(java.time.Instant.parse("2025-06-01T12:00:00Z"));
    session.setStatus(ReviewSession.STATUS_FAILED);
    session.setErrorMessage("422");
    session.setAiResponseJson("{\"findings\":[]}");

    var detail = DashboardResource.toSessionDetail(session);

    assertEquals(7L, detail.get("id"));
    assertEquals("422", detail.get("errorMessage"));
    assertEquals("{\"findings\":[]}", detail.get("aiResponseJson"));
  }
}
