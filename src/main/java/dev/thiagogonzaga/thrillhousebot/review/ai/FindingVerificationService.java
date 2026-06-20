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
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.Confidence;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import dev.thiagogonzaga.thrillhousebot.review.RiskLevel;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Second-pass audit of review findings: a skeptical verifier call re-evaluates each candidate
 * against the diff and project stack, dropping rejected findings and lowering inflated ones.
 *
 * <p>Fails open by design — any verifier error keeps the original findings, so a broken or slow
 * verification call can degrade quality but never lose a review.
 */
@ApplicationScoped
public class FindingVerificationService {

  private final FindingVerifier verifier;
  private final ThrillhouseConfig config;
  private final ObjectMapper mapper;

  @Inject
  public FindingVerificationService(
      FindingVerifier verifier, ThrillhouseConfig config, ObjectMapper mapper) {
    this.verifier = verifier;
    this.config = config;
    this.mapper = mapper;
  }

  private static final Pattern HEDGING =
      Pattern.compile("\\b(may|might|could|potentially|possibly)\\b", Pattern.CASE_INSENSITIVE);

  private static final String MEDIUM_LABEL = "medium";

  /**
   * Audits the response's findings; {@code diff}, {@code projectStack} and {@code previousFindings}
   * must already be escaped for prompt templating, the same values handed to the review call.
   * Previous findings let the verifier reject re-raises of answered findings.
   */
  public ReviewResponse verify(
      ReviewResponse response, String diff, String projectStack, String previousFindings) {
    ReviewResponse screened = demoteHedgedBlockingFindings(response);
    if (!config.review().verifierEnabled() || screened.findings().isEmpty()) {
      return screened;
    }
    try {
      var raw =
          verifier.verify(
              PromptTemplateEscaper.escape(renderCandidates(screened.findings())),
              diff,
              projectStack,
              previousFindings == null ? "" : previousFindings);
      var verdicts =
          mapper.readValue(ReviewResponseParser.extractJson(raw), VerificationResponse.class);
      return apply(screened, verdicts);
    } catch (IOException | RuntimeException e) {
      Log.warn("Finding verification failed — keeping unverified findings", e);
      return screened;
    }
  }

  /**
   * Deterministic guard that runs even when the AI verifier is disabled or fails: a
   * blocking-eligible finding whose own wording hedges ("may", "might", "could"...) is by
   * definition not a demonstrated failure, so its confidence drops to medium — it still posts, but
   * can no longer request changes on its own.
   */
  static ReviewResponse demoteHedgedBlockingFindings(ReviewResponse response) {
    if (response.findings().isEmpty()) {
      return response;
    }
    var adjusted = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      if (isBlockingEligible(finding) && containsHedging(finding)) {
        Log.infof(
            "Demoting hedged %s finding '%s' to medium confidence",
            finding.risk(), finding.title());
        adjusted.add(
            new ReviewResponse.Finding(
                finding.risk(),
                MEDIUM_LABEL,
                finding.file(),
                finding.line(),
                finding.title(),
                finding.description(),
                finding.suggestionOld(),
                finding.suggestionNew()));
        changed = true;
      } else {
        adjusted.add(finding);
      }
    }
    if (!changed) {
      return response;
    }
    return new ReviewResponse(adjusted, response.previousFindingsStatus(), response.summary());
  }

  private static boolean isBlockingEligible(ReviewResponse.Finding finding) {
    RiskLevel risk = RiskLevel.fromString(finding.risk());
    return (risk == RiskLevel.CRITICAL || risk == RiskLevel.HIGH)
        && Confidence.fromString(finding.confidence()) == Confidence.HIGH;
  }

  private static boolean containsHedging(ReviewResponse.Finding finding) {
    return (finding.title() != null && HEDGING.matcher(finding.title()).find())
        || (finding.description() != null && HEDGING.matcher(finding.description()).find());
  }

  /** The shape each candidate is presented in; ids are 1-based positions in the findings list. */
  @RegisterForReflection
  record Candidate(
      int id,
      String risk,
      String confidence,
      String file,
      int line,
      String title,
      String description,
      @JsonProperty("suggestion_old") String suggestionOld,
      @JsonProperty("suggestion_new") String suggestionNew) {

    static Candidate of(int id, ReviewResponse.Finding f) {
      return new Candidate(
          id,
          f.risk(),
          f.confidence(),
          f.file(),
          f.line(),
          f.title(),
          f.description(),
          f.suggestionOld(),
          f.suggestionNew());
    }
  }

  String renderCandidates(List<ReviewResponse.Finding> findings) throws IOException {
    var candidates = new ArrayList<Candidate>(findings.size());
    for (var i = 0; i < findings.size(); i++) {
      candidates.add(Candidate.of(i + 1, findings.get(i)));
    }
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(candidates);
  }

  ReviewResponse apply(ReviewResponse response, VerificationResponse verification) {
    var byId = new HashMap<Integer, VerificationResponse.Verdict>();
    for (VerificationResponse.Verdict verdict : verification.verdicts()) {
      byId.putIfAbsent(verdict.id(), verdict);
    }

    var kept = new ArrayList<ReviewResponse.Finding>();
    var rejected = 0;
    var downgraded = 0;
    for (var i = 0; i < response.findings().size(); i++) {
      var finding = response.findings().get(i);
      var verdict = byId.get(i + 1);
      String decision = verdict != null && verdict.verdict() != null ? verdict.verdict() : "";
      switch (decision.toLowerCase(Locale.ROOT)) {
        case "rejected" -> {
          rejected++;
          Log.infof(
              "Verifier rejected finding '%s' (%s:%d): %s",
              finding.title(), finding.file(), finding.line(), verdict.reason());
        }
        case "downgraded" -> {
          downgraded++;
          kept.add(downgrade(finding, verdict));
        }
        // confirmed, unknown decision, or no verdict at all — fail open and keep the finding
        default -> kept.add(finding);
      }
    }

    if (rejected == 0 && downgraded == 0) {
      return response;
    }
    Log.infof(
        "Finding verification: %d kept, %d downgraded, %d rejected",
        kept.size() - downgraded, downgraded, rejected);
    return new ReviewResponse(
        kept, response.previousFindingsStatus(), recount(response.summary(), kept));
  }

  /** Applies the verdict's risk/confidence, but only ever in the lowering direction. */
  static ReviewResponse.Finding downgrade(
      ReviewResponse.Finding finding, VerificationResponse.Verdict verdict) {
    RiskLevel risk = lowered(RiskLevel.fromString(finding.risk()), verdict.risk());
    Confidence confidence =
        loweredConfidence(Confidence.fromString(finding.confidence()), verdict.confidence());
    return new ReviewResponse.Finding(
        risk.name().toLowerCase(Locale.ROOT),
        confidence.name().toLowerCase(Locale.ROOT),
        finding.file(),
        finding.line(),
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  /**
   * Both enums declare constants from most to least severe, so higher ordinal = lower rating.
   * Proposed ratings are parsed strictly — the lenient {@code fromString} defaults would turn a
   * garbled verifier label into the lowest rating, silently collapsing a critical finding.
   */
  private static RiskLevel lowered(RiskLevel original, String proposed) {
    RiskLevel candidate = strictRisk(proposed);
    return candidate != null && candidate.ordinal() > original.ordinal() ? candidate : original;
  }

  private static Confidence loweredConfidence(Confidence original, String proposed) {
    Confidence candidate = strictConfidence(proposed);
    return candidate != null && candidate.ordinal() > original.ordinal() ? candidate : original;
  }

  /** Exact known labels only; null for anything else so the caller keeps the original rating. */
  private static RiskLevel strictRisk(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "critical" -> RiskLevel.CRITICAL;
      case "high" -> RiskLevel.HIGH;
      case MEDIUM_LABEL -> RiskLevel.MEDIUM;
      case "low" -> RiskLevel.LOW;
      default -> null;
    };
  }

  private static Confidence strictConfidence(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "high" -> Confidence.HIGH;
      case MEDIUM_LABEL -> Confidence.MEDIUM;
      case "low" -> Confidence.LOW;
      default -> null;
    };
  }

  /** Recomputes the summary counts after filtering, preserving the prose fields. */
  public static ReviewResponse.Summary recount(
      ReviewResponse.Summary original, List<ReviewResponse.Finding> findings) {
    if (original == null) {
      return null;
    }
    return new ReviewResponse.Summary(
        findings.size(),
        countRisk(findings, RiskLevel.CRITICAL),
        countRisk(findings, RiskLevel.HIGH),
        countRisk(findings, RiskLevel.MEDIUM),
        countRisk(findings, RiskLevel.LOW),
        original.overallAssessment(),
        original.prPurpose(),
        original.descriptionGaps(),
        original.suggestedLabels(),
        original.fileSummaries());
  }

  private static int countRisk(List<ReviewResponse.Finding> findings, RiskLevel risk) {
    return (int) findings.stream().filter(f -> RiskLevel.fromString(f.risk()) == risk).count();
  }
}
