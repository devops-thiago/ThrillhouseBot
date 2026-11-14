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
import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The selection call of the opt-in related context (#55, {@code REVIEW_RELATED_CONTEXT_ENABLED},
 * off by default): one call on the concise model reads the changed files' paths and hunk headers
 * and the candidate files the diff refers to, and names the candidates worth reading, each with a
 * reason.
 *
 * <p>The answer names candidates by their number in the list, so nothing the model writes becomes a
 * path: a number outside the list selects nothing.
 *
 * <p>It fails open: the spend ceiling reached, an input over the per-call budget, an error, an
 * empty, cut or malformed answer each select nothing, and the review proceeds as it does with the
 * feature off. A call that stops at its cap with no answer is first repeated once at a lower effort
 * ({@link ReasoningRepeat}). Its usage is recorded on the review's {@link ReviewTokenLedger} entry,
 * which it opens: the call runs before the pipeline does, and the entry has to exist for the spend
 * to count against the review's ceiling. It reaches the model through {@link BoundedChatModel}, so
 * it holds a {@link ModelCallGate} slot like every other call.
 */
@ApplicationScoped
public class RelatedContextSelectionService {

  /** The JSON key the answer lists its picks under. */
  static final String ROOT_KEY = "related";

  /** Why a file was picked, as the selection prompt names the reasons. */
  public enum Reason {
    CALLER("caller"),
    INTERFACE("interface"),
    SANITIZER_OR_VALIDATOR("sanitizer-or-validator"),
    CONFIG_CONSUMER("config-consumer"),
    /** The answer gave no reason, or one the prompt does not list. */
    RELATED("related");

    private final String label;

    Reason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    static Reason of(String text) {
      var wanted = text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replace('_', '-');
      for (var reason : values()) {
        if (reason.label.equals(wanted)) {
          return reason;
        }
      }
      return RELATED;
    }
  }

  /**
   * One candidate as the call reads it.
   *
   * @param path the file's repository path
   * @param how how the diff refers to it, in a word or two
   * @param referencedBy the changed files that refer to it
   * @param symbols the names they use for it
   */
  public record Candidate(
      String path, String how, List<String> referencedBy, List<String> symbols) {
    public Candidate {
      referencedBy = List.copyOf(referencedBy);
      symbols = List.copyOf(symbols);
    }
  }

  /**
   * What the call is asked.
   *
   * @param changedFiles the changed files, one rendered block each: the path and its hunk headers
   * @param candidates the candidates, in rank order
   * @param maxFiles the most candidates the call may pick
   * @param inputBudget the per-call input budget the whole prompt must fit
   */
  public record Request(
      List<String> changedFiles, List<Candidate> candidates, int maxFiles, int inputBudget) {
    public Request {
      changedFiles = List.copyOf(changedFiles);
      candidates = List.copyOf(candidates);
    }
  }

  /** One picked candidate. */
  public record Selected(String path, Reason reason) {}

  /**
   * The call's outcome.
   *
   * @param selected the picked candidates, in the answer's order; empty when none or on a failure
   * @param called whether a model call was made, and so counts in {@code REVIEW_MAX_AI_CALLS}
   */
  public record Outcome(List<Selected> selected, boolean called) {
    static final Outcome NOT_CALLED = new Outcome(List.of(), false);

    public Outcome {
      selected = List.copyOf(selected);
    }
  }

  private final RelatedContextSelector selector;
  private final ThrillhouseConfig config;
  private final ObjectMapper mapper;
  private final ReviewTokenLedger tokenLedger;
  private final ResponseCaps responseCaps;
  private final TokenCounter tokenCounter;

  @Inject
  public RelatedContextSelectionService(
      RelatedContextSelector selector,
      ThrillhouseConfig config,
      ObjectMapper mapper,
      ReviewTokenLedger tokenLedger,
      ResponseCaps responseCaps,
      TokenCounter tokenCounter) {
    this.selector = selector;
    this.config = config;
    this.mapper = mapper;
    this.tokenLedger = tokenLedger;
    this.responseCaps = responseCaps;
    this.tokenCounter = tokenCounter;
  }

  /**
   * The candidates of {@code request} worth reading, at most {@code request.maxFiles()}. {@code
   * ledgerSessionId} is the review's {@link ReviewTokenLedger#keyFor ledger key}; its entry is
   * opened here and left open for the pipeline, and the caller releases it with {@link #release}
   * when the review ends without reaching the pipeline.
   */
  public Outcome select(long ledgerSessionId, Request request) {
    if (request.candidates().isEmpty() || request.maxFiles() < 1) {
      return Outcome.NOT_CALLED;
    }
    tokenLedger.open(ledgerSessionId);
    if (tokenLedger.ceilingReached(ledgerSessionId)) {
      Log.warnf(
          "Related-context selection for review session %d skipped at the review's token spend"
              + " ceiling (REVIEW_MAX_TOKENS_PER_REVIEW)",
          ledgerSessionId);
      return Outcome.NOT_CALLED;
    }
    var changedSlot = PromptTemplateEscaper.fence(String.join("\n", request.changedFiles()));
    var candidatesSlot = PromptTemplateEscaper.fence(renderCandidates(request.candidates()));
    var size =
        (long) tokenCounter.estimateTokens(RelatedContextPrompts.templates())
            + tokenCounter.estimateTokens(changedSlot)
            + tokenCounter.estimateTokens(candidatesSlot);
    if (size > request.inputBudget()) {
      Log.warnf(
          "Related-context selection for review session %d skipped: its file lists exceed the"
              + " per-call input budget",
          ledgerSessionId);
      return Outcome.NOT_CALLED;
    }
    try {
      // Each call's usage is recorded before the answer is read: a cut response was still billed.
      var result =
          ReasoningRepeat.blocking(
              "Related-context selection for review session " + ledgerSessionId,
              () -> selector.select(changedSlot, candidatesSlot, request.maxFiles()),
              () -> ReasoningRepeat.effortFor(config, true),
              () -> ReasoningRepeat.enableHint(config),
              () -> tokenLedger.ceilingReached(ledgerSessionId),
              answer -> recordUsage(ledgerSessionId, answer),
              () -> tokenLedger.recordReasoningStepDown(ledgerSessionId));
      var raw =
          AiResponses.textOrThrowOnTruncation(
              result,
              "Related-context selection",
              responseCaps.forLane(AiResponses.ModelLane.CONCISE));
      if (raw == null || raw.isBlank()) {
        Log.warnf(
            "Related-context selection for review session %d returned no response body; the"
                + " review proceeds without related context",
            ledgerSessionId);
        return new Outcome(List.of(), true);
      }
      var selected = selected(raw, request);
      Log.infof(
          "Related-context selection for review session %d picked %d of %d candidate(s): %s",
          ledgerSessionId,
          selected.size(),
          request.candidates().size(),
          selected.isEmpty()
              ? "none"
              : LogSafe.oneLine(
                  String.join(
                      ", ",
                      selected.stream()
                          .map(s -> s.path() + " (" + s.reason().label() + ")")
                          .toList())));
      return new Outcome(selected, true);
    } catch (IOException | RuntimeException e) {
      // Only the type is logged: the message can carry provider or model text.
      Log.warnf(
          "Related-context selection for review session %d failed (%s); the review proceeds"
              + " without related context",
          ledgerSessionId, e.getClass().getSimpleName());
      return new Outcome(List.of(), true);
    }
  }

  /** Drops the ledger entry {@link #select} opened, for a review that ends before its pipeline. */
  public void release(long ledgerSessionId) {
    tokenLedger.clear(ledgerSessionId);
  }

  /** The numbered list the call reads; a candidate's number is its position, from 1. */
  static String renderCandidates(List<Candidate> candidates) {
    var sb = new StringBuilder();
    for (var i = 0; i < candidates.size(); i++) {
      var candidate = candidates.get(i);
      sb.append(i + 1).append(". ").append(candidate.path()).append(" (").append(candidate.how());
      if (!candidate.referencedBy().isEmpty()) {
        sb.append("; referenced by ").append(String.join(", ", candidate.referencedBy()));
      }
      if (!candidate.symbols().isEmpty()) {
        sb.append("; as ").append(String.join(", ", candidate.symbols()));
      }
      sb.append(")\n");
    }
    return sb.toString().stripTrailing();
  }

  /**
   * The candidates the answer names, each once, in its order, at most {@code maxFiles}. An entry is
   * read by its {@code id}, the candidate's number; one with no number is read by a {@code path}
   * that is exactly a candidate's. Anything else selects nothing. An answer that is not the
   * expected JSON throws, and the caller selects nothing.
   */
  List<Selected> selected(String raw, Request request) throws IOException {
    var json = ReviewResponseParser.extractJson(raw, List.of(ROOT_KEY));
    JsonNode root = mapper.readTree(json);
    if (!root.path(ROOT_KEY).isArray()) {
      throw new IOException("related-context selection answer has no " + ROOT_KEY + " array");
    }
    var candidates = request.candidates();
    var picked = new LinkedHashMap<String, Reason>();
    for (var entry : root.path(ROOT_KEY)) {
      var path = candidatePath(entry, candidates);
      if (path != null && picked.size() < request.maxFiles()) {
        picked.putIfAbsent(path, Reason.of(entry.path("reason").asText(null)));
      }
    }
    var selected = new ArrayList<Selected>();
    picked.forEach((path, reason) -> selected.add(new Selected(path, reason)));
    return selected;
  }

  private static String candidatePath(JsonNode entry, List<Candidate> candidates) {
    var id = entry.path("id");
    var number = id.isInt() ? id.asInt() : numberIn(id.asText(""));
    if (number >= 1 && number <= candidates.size()) {
      return candidates.get(number - 1).path();
    }
    if (number != 0) {
      return null;
    }
    var path = entry.path("path").asText("").strip();
    return candidates.stream().map(Candidate::path).filter(path::equals).findFirst().orElse(null);
  }

  /** The number a textual id holds ({@code "3"}, {@code "#3"}); 0 when it holds none. */
  private static int numberIn(String text) {
    var digits = text.replaceAll("\\D", "");
    if (digits.isEmpty() || digits.length() > 4) {
      return 0;
    }
    return Integer.parseInt(digits);
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
