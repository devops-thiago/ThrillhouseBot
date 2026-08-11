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
import dev.langchain4j.service.Result;
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
 *
 * <p>The verifier is a billed review-path call, so it participates in the {@code
 * REVIEW_MAX_TOKENS_PER_REVIEW} spend ceiling like every other call the review makes: its {@link
 * Result#tokenUsage() provider-reported usage} is recorded straight into the {@link
 * ReviewTokenLedger} (the blocking call never passes through the streaming path's {@link
 * ReviewSessionContext} bind, so the observability listener cannot correlate it), and once the
 * ceiling is reached the call is skipped fail-open — the unverified findings are kept, which is
 * exactly this service's error contract.
 */
@ApplicationScoped
public class FindingVerificationService {

  private final FindingVerifier verifier;
  private final ThrillhouseConfig config;
  private final ObjectMapper mapper;
  private final ReviewTokenLedger tokenLedger;

  @Inject
  public FindingVerificationService(
      FindingVerifier verifier,
      ThrillhouseConfig config,
      ObjectMapper mapper,
      ReviewTokenLedger tokenLedger) {
    this.verifier = verifier;
    this.config = config;
    this.mapper = mapper;
    this.tokenLedger = tokenLedger;
  }

  private static final Pattern HEDGING =
      Pattern.compile("\\b(may|might|could|potentially|possibly)\\b", Pattern.CASE_INSENSITIVE);

  private static final String MEDIUM_LABEL = "medium";

  /**
   * Audits the response's findings; {@code diff}, {@code projectStack} and {@code previousFindings}
   * must already be escaped for prompt templating, the same values handed to the review call.
   * Previous findings let the verifier reject re-raises of answered findings. {@code
   * ledgerSessionId} is the review's {@link ReviewTokenLedger} key ({@link
   * ReviewTokenLedger#keyFor}): the call's usage is recorded against it, and once the review's
   * spend ceiling is reached the call is skipped fail-open, keeping the unverified findings.
   */
  public ReviewResponse verify(
      long ledgerSessionId,
      ReviewResponse response,
      String diff,
      String projectStack,
      String previousFindings) {
    ReviewResponse screened = demoteHedgedBlockingFindings(response);
    if (!config.review().verifierEnabled() || screened.findings().isEmpty()) {
      return screened;
    }
    if (tokenLedger.ceilingReached(ledgerSessionId)) {
      // The verifier is a fresh billed call; once the ceiling is reached it is not made. Skipping
      // fail-open keeps the unverified findings — degraded quality, never a lost review.
      Log.warnf(
          "Finding verification for review session %d skipped at the review's token spend ceiling"
              + " (%d tokens spent, ceiling %d — REVIEW_MAX_TOKENS_PER_REVIEW); keeping the %d"
              + " unverified finding(s)",
          ledgerSessionId,
          tokenLedger.tokensSpent(ledgerSessionId),
          tokenLedger.ceiling(),
          screened.findings().size());
      return screened;
    }
    try {
      var result =
          verifier.verify(
              PromptTemplateEscaper.escape(renderCandidates(screened.findings())),
              diff,
              projectStack,
              previousFindings == null ? "" : previousFindings);
      // Meter before unwrapping: a truncated response was still billed, so its spend counts.
      recordVerifierUsage(ledgerSessionId, result);
      var raw =
          AiResponses.textOrThrowOnTruncation(
              result, "Finding verification", AiResponses.ModelLane.CONCISE);
      if (raw == null || raw.isBlank()) {
        // The unwrap helper's documented "no response" soft failure, which this caller has to
        // honour like every other one: a reasoning model can spend the whole output budget on
        // reasoning tokens and return an empty body with no length stop. Keeping the unverified
        // findings is this service's error contract; handing the absent body to the JSON
        // extraction instead reported an expected outcome as a verification fault.
        Log.warnf(
            "Finding verification returned no response body — keeping the %d unverified finding(s)",
            screened.findings().size());
        return screened;
      }
      var verdicts =
          mapper.readValue(ReviewResponseParser.extractJson(raw), VerificationResponse.class);
      return apply(screened, verdicts);
    } catch (AiResponseTruncatedException e) {
      // The verifier fails open by design; say why so a cap is not mistaken for a provider fault.
      Log.warnf("Finding verification — keeping unverified findings. %s", e.getMessage());
      return screened;
    } catch (IOException | RuntimeException e) {
      Log.warn("Finding verification failed — keeping unverified findings", e);
      return screened;
    }
  }

  /**
   * Records the verifier call's provider-reported usage in the review's ledger. The blocking AI
   * service returns usage on its {@link Result}, so it is recorded here directly — the
   * observability listener only correlates calls made under the streaming path's session bind,
   * which this call never is. A missing result or usage (a provider that omits it) records nothing;
   * the ledger itself tolerates a null side of the count.
   */
  private void recordVerifierUsage(long ledgerSessionId, Result<String> result) {
    var usage = result == null ? null : result.tokenUsage();
    if (usage == null) {
      return;
    }
    tokenLedger.recordUsage(ledgerSessionId, usage.inputTokenCount(), usage.outputTokenCount());
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
        original.fileSummaries(),
        original.walkthroughDiagram());
  }

  private static int countRisk(List<ReviewResponse.Finding> findings, RiskLevel risk) {
    return (int) findings.stream().filter(f -> RiskLevel.fromString(f.risk()) == risk).count();
  }
}
