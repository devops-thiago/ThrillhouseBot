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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;

@RegisterForReflection
public record ReviewResponse(
    List<Finding> findings,
    @JsonProperty("previous_findings_status") List<PreviousFindingStatus> previousFindingsStatus,
    Summary summary,
    // The findings of rounds older than the effective previous one that this round closed (their
    // code left the diff, or a maintainer cleared them), recorded so a later round does not close
    // and name them again (#1066). Never written by the model: ReviewResponseParser drops the field
    // from every answer, every lane rebuilds the response through the three-argument constructor
    // before it is persisted, and the orchestrator sets it only after the verdict.
    @JsonProperty("closed_earlier_round") List<Finding> closedEarlierRound,
    // The review call's verdict on each acceptance criterion of the linked issue (#1200). Read by
    // ReviewResponseParser on its own, leniently, and taken by FindingPipeline as soon as the call
    // returns. The steps that rebuild the response through the shorter constructors drop it, so
    // nothing downstream reads it here.
    @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("linked_issue_criteria")
        List<CriterionVerdict> linkedIssueCriteria) {
  public ReviewResponse {
    findings = List.copyOf(findings != null ? findings : List.of());
    previousFindingsStatus =
        List.copyOf(previousFindingsStatus != null ? previousFindingsStatus : List.of());
    closedEarlierRound = List.copyOf(closedEarlierRound != null ? closedEarlierRound : List.of());
    linkedIssueCriteria = List.copyOf(Summary.withoutNulls(linkedIssueCriteria));
  }

  /** A response that closed no earlier round's finding: every model answer and rebuilt round. */
  public ReviewResponse(
      List<Finding> findings, List<PreviousFindingStatus> previousFindingsStatus, Summary summary) {
    this(findings, previousFindingsStatus, summary, List.of());
  }

  /** A response with no criterion verdicts, which a rebuild through this constructor drops. */
  public ReviewResponse(
      List<Finding> findings,
      List<PreviousFindingStatus> previousFindingsStatus,
      Summary summary,
      List<Finding> closedEarlierRound) {
    this(findings, previousFindingsStatus, summary, closedEarlierRound, List.of());
  }

  /** This response recording {@code closed} as the earlier rounds' findings it closed. */
  public ReviewResponse withClosedEarlierRound(List<Finding> closed) {
    return new ReviewResponse(
        findings, previousFindingsStatus, summary, closed, linkedIssueCriteria);
  }

  /** This response carrying {@code verdicts} as its criterion verdicts. */
  public ReviewResponse withLinkedIssueCriteria(List<CriterionVerdict> verdicts) {
    return new ReviewResponse(
        findings, previousFindingsStatus, summary, closedEarlierRound, verdicts);
  }

  /**
   * The review call's verdict on one acceptance criterion of the linked issue (#1200).
   *
   * @param criterion the criterion as the call copied it from the issue's list
   * @param status {@code met}, {@code unmet} or {@code unclear}, as the call wrote it
   * @param note one line on what the call looked for, written as {@code evidence}, or {@code null}
   */
  @RegisterForReflection
  public record CriterionVerdict(
      String criterion, String status, @JsonProperty("evidence") String note) {}

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

  /** One changed file the model summarized: its path and a one-line description of the change. */
  @RegisterForReflection
  public record FileSummary(String path, String summary) {}

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
      @JsonProperty("suggested_labels") List<String> suggestedLabels,
      @JsonProperty("file_summaries") List<FileSummary> fileSummaries,
      // Optional Mermaid source (no ``` fences); null/blank unless the diagram feature is enabled
      // and the change is non-trivial.
      @JsonProperty("walkthrough_diagram") String walkthroughDiagram,
      // The already-reported description gaps (#923) the summary call judges resolved, each by its
      // "G<n>" label; written only when the prompt lists such gaps, and consumed before the
      // response is persisted.
      @JsonProperty("addressed_gaps") List<String> addressedGaps) {
    public Summary {
      // The AI may emit null elements inside these arrays; drop them before List.copyOf. copyOf
      // stays inline so SpotBugs sees the defensive copy (EI_EXPOSE_REP).
      // Every path that builds a summary — the parsed answer, the carried gaps, the scrubbers —
      // comes through here, so no carried-gap label the model echoed survives to a surface (#970).
      descriptionGaps = List.copyOf(DescriptionGapLabels.stripAll(withoutNulls(descriptionGaps)));
      suggestedLabels = List.copyOf(withoutNulls(suggestedLabels));
      fileSummaries = List.copyOf(withoutNulls(fileSummaries));
      addressedGaps = List.copyOf(withoutNulls(addressedGaps));
    }

    /** Convenience constructor for callers (and responses) that predate addressed gaps. */
    public Summary(
        int totalFindings,
        int critical,
        int high,
        int medium,
        int low,
        String overallAssessment,
        String prPurpose,
        List<String> descriptionGaps,
        List<String> suggestedLabels,
        List<FileSummary> fileSummaries,
        String walkthroughDiagram) {
      this(
          totalFindings,
          critical,
          high,
          medium,
          low,
          overallAssessment,
          prPurpose,
          descriptionGaps,
          suggestedLabels,
          fileSummaries,
          walkthroughDiagram,
          List.of());
    }

    /** This summary with its description gaps replaced and no addressed gaps left to apply. */
    public Summary withDescriptionGaps(List<String> gaps) {
      return new Summary(
          totalFindings,
          critical,
          high,
          medium,
          low,
          overallAssessment,
          prPurpose,
          gaps,
          suggestedLabels,
          fileSummaries,
          walkthroughDiagram,
          List.of());
    }

    /** This summary with its file summaries replaced; every other field is kept. */
    public Summary withFileSummaries(List<FileSummary> rows) {
      return new Summary(
          totalFindings,
          critical,
          high,
          medium,
          low,
          overallAssessment,
          prPurpose,
          descriptionGaps,
          suggestedLabels,
          rows,
          walkthroughDiagram,
          addressedGaps);
    }

    static <T> List<T> withoutNulls(List<T> values) {
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

    /** Convenience constructor for callers (and responses) that predate per-file summaries. */
    public Summary(
        int totalFindings,
        int critical,
        int high,
        int medium,
        int low,
        String overallAssessment,
        String prPurpose,
        List<String> descriptionGaps,
        List<String> suggestedLabels) {
      this(
          totalFindings,
          critical,
          high,
          medium,
          low,
          overallAssessment,
          prPurpose,
          descriptionGaps,
          suggestedLabels,
          List.of(),
          null);
    }

    /** Convenience constructor for callers (and responses) that predate the walkthrough diagram. */
    public Summary(
        int totalFindings,
        int critical,
        int high,
        int medium,
        int low,
        String overallAssessment,
        String prPurpose,
        List<String> descriptionGaps,
        List<String> suggestedLabels,
        List<FileSummary> fileSummaries) {
      this(
          totalFindings,
          critical,
          high,
          medium,
          low,
          overallAssessment,
          prPurpose,
          descriptionGaps,
          suggestedLabels,
          fileSummaries,
          null);
    }
  }
}
