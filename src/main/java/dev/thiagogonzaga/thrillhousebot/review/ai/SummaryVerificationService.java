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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The opt-in summary verification step (#1078, {@code REVIEW_SUMMARY_VERIFICATION_ENABLED}, off by
 * default): after the summary call, one call on the concise model reads the summary's statements
 * (purpose sentences, walkthrough rows, diagram lines) against the diff and the open findings and
 * names the ones they contradict; {@link SummaryStatements} then removes them. Findings are never
 * read back from this call or changed by it.
 *
 * <p>The summary call does not see the diff, so what it writes can only be as true as the file
 * list, the findings and the PR text. Round 17's summary errors were statements the diff or the
 * bot's own findings contradict: a "billing_runs summary row" nothing writes, rows "sorted" by code
 * that does not sort, a rack id called sanitised while a finding reports it is not, and a diagram
 * drawing a route that does not exist.
 *
 * <p>It fails open like the finding verifier: disabled, nothing to check, the spend ceiling
 * reached, an input over the per-call budget, an error, an empty, cut or malformed answer, each
 * keeps the summary as it was. Its usage is recorded on the review's {@link ReviewTokenLedger}
 * entry, and it reaches the model through {@link BoundedChatModel}, so it holds a {@link
 * ModelCallGate} slot like every other call.
 */
@ApplicationScoped
public class SummaryVerificationService {

  /** The JSON key the answer lists its contradicted statements under. */
  static final String ROOT_KEY = "contradicted";

  private final SummaryVerifier verifier;
  private final boolean enabled;
  private final boolean diagramEnabled;
  private final ObjectMapper mapper;
  private final ReviewTokenLedger tokenLedger;
  private final ResponseCaps responseCaps;
  private final TokenCounter tokenCounter;

  @Inject
  public SummaryVerificationService(
      SummaryVerifier verifier,
      ThrillhouseConfig config,
      ObjectMapper mapper,
      ReviewTokenLedger tokenLedger,
      ResponseCaps responseCaps,
      TokenCounter tokenCounter) {
    this(
        verifier,
        config.review().summaryVerificationEnabled(),
        config.review().diagram().enabled(),
        mapper,
        tokenLedger,
        responseCaps,
        tokenCounter);
  }

  private SummaryVerificationService(
      SummaryVerifier verifier,
      boolean enabled,
      boolean diagramEnabled,
      ObjectMapper mapper,
      ReviewTokenLedger tokenLedger,
      ResponseCaps responseCaps,
      TokenCounter tokenCounter) {
    this.verifier = verifier;
    this.enabled = enabled;
    this.diagramEnabled = diagramEnabled;
    this.mapper = mapper;
    this.tokenLedger = tokenLedger;
    this.responseCaps = responseCaps;
    this.tokenCounter = tokenCounter;
  }

  /** The step switched off, for callers that construct the pipeline without configuration. */
  public static SummaryVerificationService disabled() {
    return new SummaryVerificationService(null, false, false, null, null, null, null);
  }

  /**
   * What the call checks the statements against.
   *
   * @param prContext the PR title and description, fenced and bounded as the finding verifier gets
   *     them, or {@code ""}
   * @param diffParts the reviewed diff, unfenced, in the parts the review calls read it in (one per
   *     batch); the parts that fit the budget are sent, in order
   * @param projectStack the project stack section, escaped, or {@code ""}
   * @param findings the findings the round posts
   * @param inputBudget the per-call input budget the whole prompt must fit
   */
  public record Material(
      String prContext,
      List<String> diffParts,
      String projectStack,
      List<ReviewResponse.Finding> findings,
      int inputBudget) {
    public Material {
      prContext = Objects.requireNonNullElse(prContext, "");
      // copyOf stays inline so SpotBugs sees the defensive copy (EI_EXPOSE_REP).
      diffParts =
          List.copyOf(
              diffParts == null
                  ? List.of()
                  : diffParts.stream().filter(p -> p != null && !p.isBlank()).toList());
      projectStack = Objects.requireNonNullElse(projectStack, "");
      findings = findings == null ? List.of() : List.copyOf(findings);
    }
  }

  /** Whether the step is on ({@code REVIEW_SUMMARY_VERIFICATION_ENABLED}). */
  public boolean enabled() {
    return enabled;
  }

  /**
   * {@code summary} with every statement the call reports contradicted removed, or {@code summary}
   * itself whenever the step is off, has nothing to check or fails. {@code ledgerSessionId} is the
   * review's {@link ReviewTokenLedger#keyFor ledger key}.
   */
  public ReviewResponse.Summary verify(
      long ledgerSessionId, ReviewResponse.Summary summary, Material material) {
    if (!enabled() || summary == null) {
      return summary;
    }
    var statements = SummaryStatements.of(summary, diagramEnabled);
    if (statements.isEmpty() || (material.diffParts().isEmpty() && material.findings().isEmpty())) {
      return summary;
    }
    if (tokenLedger.ceilingReached(ledgerSessionId)) {
      Log.warnf(
          "Summary verification for review session %d skipped at the review's token spend ceiling"
              + " (REVIEW_MAX_TOKENS_PER_REVIEW); keeping the summary as written",
          ledgerSessionId);
      return summary;
    }
    try {
      var statementsSlot = PromptTemplateEscaper.fence(statements.render());
      var findingsSlot = PromptTemplateEscaper.escape(findingsJson(material.findings()));
      var diffSlot = packedDiff(material, statementsSlot, findingsSlot);
      if (diffSlot == null) {
        Log.warnf(
            "Summary verification for review session %d skipped: its statements and findings"
                + " alone exceed the per-call input budget; keeping the summary as written",
            ledgerSessionId);
        return summary;
      }
      if (diffSlot.isEmpty() && material.findings().isEmpty()) {
        return summary;
      }
      var result =
          verifier.verify(
              statementsSlot,
              findingsSlot,
              material.prContext(),
              diffSlot,
              material.projectStack());
      // Recorded before the answer is read: a cut response was still billed.
      recordUsage(ledgerSessionId, result);
      var raw =
          AiResponses.textOrThrowOnTruncation(
              result, "Summary verification", responseCaps.forLane(AiResponses.ModelLane.CONCISE));
      if (raw == null || raw.isBlank()) {
        Log.warnf(
            "Summary verification for review session %d returned no response body; keeping the"
                + " summary as written",
            ledgerSessionId);
        return summary;
      }
      var contradicted = contradictedIds(raw);
      // Only the ids that name a statement are logged: those are this class's own labels, while
      // anything else the answer put there is model text.
      var named =
          statements.statements().stream()
              .map(SummaryStatements.Statement::id)
              .filter(contradicted::contains)
              .toList();
      Log.infof(
          "Summary verification for review session %d checked %d statement(s); contradicted: %s",
          ledgerSessionId,
          statements.statements().size(),
          named.isEmpty() ? "none" : String.join(", ", named));
      return statements.without(contradicted);
    } catch (IOException | RuntimeException e) {
      // Only the type is logged: the message can carry provider or model text.
      Log.warnf(
          "Summary verification for review session %d failed (%s); keeping the summary as written",
          ledgerSessionId, e.getClass().getSimpleName());
      return summary;
    }
  }

  /**
   * The diff slot: the diff parts that fit what the budget leaves once the rest of the prompt is
   * counted, fenced; {@code ""} when none fits or there is none; {@code null} when the rest of the
   * prompt alone does not fit.
   */
  private String packedDiff(Material material, String statementsSlot, String findingsSlot) {
    var overhead =
        tokenCounter.estimateTokens(SummaryVerifierPrompts.templates())
            + tokenCounter.estimateTokens(statementsSlot)
            + tokenCounter.estimateTokens(findingsSlot)
            + tokenCounter.estimateTokens(material.prContext())
            + tokenCounter.estimateTokens(material.projectStack())
            + tokenCounter.estimateTokens(PromptTemplateEscaper.fenceForBudgeting());
    long left = (long) material.inputBudget() - overhead;
    if (left < 0) {
      return null;
    }
    var sent = new ArrayList<String>();
    for (var part : material.diffParts()) {
      long size = tokenCounter.estimateTokens(part) + 1L;
      if (size <= left) {
        sent.add(part);
        left -= size;
      }
    }
    return sent.isEmpty() ? "" : PromptTemplateEscaper.fence(String.join("\n", sent));
  }

  /** The findings as the call reads them: location, grade, title and description. */
  private String findingsJson(List<ReviewResponse.Finding> findings) throws IOException {
    var rows = new ArrayList<LinkedHashMap<String, Object>>();
    for (var finding : findings) {
      var row = new LinkedHashMap<String, Object>();
      row.put("file", finding.file());
      row.put("line", finding.line());
      row.put("risk", finding.risk());
      row.put("title", finding.title());
      row.put("description", finding.description());
      rows.add(row);
    }
    return mapper.writeValueAsString(rows);
  }

  /**
   * The ids the answer lists, upper-cased. An entry with no id or no evidence is ignored, so an
   * answer that names a statement without saying what refutes it cuts nothing; an answer that is
   * not the expected JSON throws, and the caller keeps the summary.
   */
  Set<String> contradictedIds(String raw) throws IOException {
    var json = ReviewResponseParser.extractJson(raw, List.of(ROOT_KEY));
    JsonNode root = mapper.readTree(json);
    if (!root.isObject() || !root.path(ROOT_KEY).isArray()) {
      throw new IOException("summary verification answer has no " + ROOT_KEY + " array");
    }
    var ids = new HashSet<String>();
    for (var entry : root.path(ROOT_KEY)) {
      var id = entry.path("id").asText("").strip();
      var evidence = entry.path("evidence").asText("").strip();
      if (!id.isEmpty() && !evidence.isEmpty()) {
        ids.add(id.toUpperCase(Locale.ROOT));
      }
    }
    return ids;
  }

  /** Records the call's provider-reported usage on the review's ledger entry. */
  private void recordUsage(long ledgerSessionId, Result<String> result) {
    var usage = result == null ? null : result.tokenUsage();
    if (usage == null) {
      return;
    }
    tokenLedger.recordUsage(ledgerSessionId, usage.inputTokenCount(), usage.outputTokenCount());
  }
}
