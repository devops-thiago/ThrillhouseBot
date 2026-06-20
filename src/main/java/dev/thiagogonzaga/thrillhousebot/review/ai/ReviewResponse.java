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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;

@RegisterForReflection
public record ReviewResponse(
    List<Finding> findings,
    @JsonProperty("previous_findings_status") List<PreviousFindingStatus> previousFindingsStatus,
    Summary summary) {
  public ReviewResponse {
    findings = List.copyOf(findings != null ? findings : List.of());
    previousFindingsStatus =
        List.copyOf(previousFindingsStatus != null ? previousFindingsStatus : List.of());
  }

  @RegisterForReflection
  public record Finding(
      String risk, // critical, high, medium, low
      String confidence, // high, medium, low — null on pre-confidence responses
      String file,
      int line,
      String title,
      String description,
      @JsonProperty("suggestion_old") String suggestionOld,
      @JsonProperty("suggestion_new") String suggestionNew) {

    /** Convenience constructor for callers that predate the confidence field. */
    public Finding(
        String risk,
        String file,
        int line,
        String title,
        String description,
        String suggestionOld,
        String suggestionNew) {
      this(risk, null, file, line, title, description, suggestionOld, suggestionNew);
    }
  }

  @RegisterForReflection
  public record PreviousFindingStatus(
      int id,
      String status, // resolved, unresolved, justified
      String note) {}

  @RegisterForReflection
  public record Summary(
      @JsonProperty("total_findings") int totalFindings,
      int critical,
      int high,
      int medium,
      int low,
      @JsonProperty("overall_assessment") String overallAssessment,
      @JsonProperty("pr_purpose") String prPurpose,
      @JsonProperty("description_gaps") List<String> descriptionGaps,
      @JsonProperty("suggested_labels") List<String> suggestedLabels) {
    public Summary {
      // The AI may emit null elements inside these arrays (e.g. ["bug", null]); a bare List.copyOf
      // would throw an NPE that fails the whole review, even though both lists are best-effort
      // metadata. Drop the nulls first, then copy. List.copyOf is kept inline rather than folded
      // into the helper so SpotBugs still sees the defensive copy and does not flag EI_EXPOSE_REP.
      descriptionGaps = List.copyOf(withoutNulls(descriptionGaps));
      suggestedLabels = List.copyOf(withoutNulls(suggestedLabels));
    }

    private static List<String> withoutNulls(List<String> values) {
      return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    /** Convenience constructor for callers (and responses) that predate label suggestions. */
    public Summary(
        int totalFindings,
        int critical,
        int high,
        int medium,
        int low,
        String overallAssessment,
        String prPurpose,
        List<String> descriptionGaps) {
      this(
          totalFindings,
          critical,
          high,
          medium,
          low,
          overallAssessment,
          prPurpose,
          descriptionGaps,
          List.of());
    }
  }
}
