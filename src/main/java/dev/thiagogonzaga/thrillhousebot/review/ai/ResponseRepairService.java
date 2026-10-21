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
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The repair step (#1079, {@code REVIEW_RESPONSE_REPAIR_ENABLED}, on by default): when a review or
 * summary answer is not review JSON, one bounded call gets only that answer, the parser's error and
 * the response contract, and is asked for the same content in the required shape. The result is
 * read by the normal parser. Before this, the identical prompt (the whole diff and context) was
 * sent again and the model regenerated the whole review, while the failed answer's content was
 * usually fine and only its shape was wrong: round 17 saw four reviews take that full retry on
 * their first attempt, and round 18 six more.
 *
 * <p>The same step re-asks for {@code file_summaries} alone when the summary answer wrote that
 * field as a string the parser cannot read entries from: react ThrillhouseBot-test#273 rendered 19
 * of 20 walkthrough rows "Not summarized" that way.
 *
 * <p>A repaired review answer is held to the failed one, so the repair cannot turn a broken answer
 * into an approval or add a finding: it must keep at least as many findings as the failed answer
 * has titles, every finding title must be one of the failed answer's, and it may not hold more
 * statuses than the failed answer has. Those are read by {@link AnswerKeys} from the failed
 * answer's structure, so JSON quoted inside a field counts for nothing; when the scan loses track
 * of the structure, every {@code "title"} and {@code "status"} key in the text counts instead. A
 * re-asked file summary is kept only when its whole path is named in the string it came from.
 *
 * <p>It fails open: disabled, the spend ceiling reached, an error, an empty or cut answer, an
 * answer the parser still refuses or one the checks above reject, each returns nothing, and the
 * caller goes on as it did without the step. The usage of every call is recorded on the review's
 * {@link ReviewTokenLedger} entry, and both repairers reach the model through {@link
 * BoundedChatModel}, so each call holds a {@link ModelCallGate} slot.
 */
@ApplicationScoped
public class ResponseRepairService {

  /** The longest parser detail the repair prompt states. */
  static final int MAX_ERROR_DETAIL = 300;

  private static final Pattern TITLE_KEY = Pattern.compile("\"title\"\\s*:");
  private static final Pattern STATUS_KEY = Pattern.compile("\"status\"\\s*:");
  private static final Pattern ESCAPES_AND_SPACE = Pattern.compile("\\\\|\\s+");

  private final ResponseRepairer reviewRepairer;
  private final SummaryResponseRepairer summaryRepairer;
  private final ReviewResponseParser parser;
  private final ObjectMapper mapper;
  private final ReviewTokenLedger tokenLedger;
  private final ResponseCaps responseCaps;
  private final ThrillhouseConfig config;
  private final boolean enabled;

  @Inject
  public ResponseRepairService(
      ResponseRepairer reviewRepairer,
      SummaryResponseRepairer summaryRepairer,
      ReviewResponseParser parser,
      ObjectMapper mapper,
      ReviewTokenLedger tokenLedger,
      ResponseCaps responseCaps,
      ThrillhouseConfig config) {
    this.reviewRepairer = reviewRepairer;
    this.summaryRepairer = summaryRepairer;
    this.parser = parser;
    this.mapper = mapper;
    this.tokenLedger = tokenLedger;
    this.responseCaps = responseCaps;
    this.config = config;
    this.enabled = config.review().responseRepairEnabled();
  }

  /**
   * A repaired answer and the text it was read from, so the caller handles it as it handles an
   * answer the parser accepted the first time (the {@code file_summaries} re-ask reads the text).
   */
  public record Repair(ReviewResponse response, String body) {}

  /** Whether the step is on ({@code REVIEW_RESPONSE_REPAIR_ENABLED}). */
  public boolean enabled() {
    return enabled;
  }

  /**
   * The failed answer {@code body} in the required shape, read by the lane's parser, or empty
   * whenever the step is off, the ceiling is reached, or the repair fails or is rejected. {@code
   * ledgerKey} is the review's {@link ReviewTokenLedger#keyFor ledger key}; {@code parseFailure} is
   * the parser's refusal of {@code body}.
   */
  public Optional<Repair> repair(
      long ledgerKey, ModelLane lane, String body, IllegalArgumentException parseFailure) {
    if (!enabled || body == null || body.isBlank()) {
      return Optional.empty();
    }
    var summaryLane = lane == ModelLane.CONCISE;
    var contract =
        summaryLane
            ? ResponseRepairPrompts.SUMMARY_CONTRACT
            : ResponseRepairPrompts.REVIEW_CONTRACT;
    var raw = call(ledgerKey, lane, contract, errorText(parseFailure, summaryLane), body);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      var repaired = summaryLane ? parser.parseSummary(raw.get()) : parser.parse(raw.get());
      var rejection = summaryLane ? Optional.<String>empty() : rejection(repaired, body);
      if (rejection.isPresent()) {
        Log.warnf(
            "Response repair for review session %d rejected: %s; the full retry runs",
            ledgerKey, rejection.get());
        return Optional.empty();
      }
      Log.infof(
          "Response repair for review session %d reshaped the answer the parser refused (%s);"
              + " no full retry needed",
          ledgerKey, parseFailure.getMessage());
      return Optional.of(new Repair(repaired, raw.get()));
    } catch (RuntimeException e) {
      Log.warnf(
          "Response repair for review session %d returned an answer the parser also refused (%s);"
              + " the full retry runs",
          ledgerKey, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * The file summaries read from {@code text}, the string a summary answer wrote where the {@code
   * file_summaries} array belongs, by one re-ask for that field alone; only the entries whose path
   * is in {@code text} are kept. Empty whenever the step is off, the ceiling is reached, the call
   * fails, or nothing usable comes back.
   */
  public Optional<List<ReviewResponse.FileSummary>> fileSummaries(long ledgerKey, String text) {
    if (!enabled || text == null || text.isBlank()) {
      return Optional.empty();
    }
    var raw =
        call(
            ledgerKey,
            ModelLane.CONCISE,
            ResponseRepairPrompts.FILE_SUMMARIES_CONTRACT,
            ResponseRepairPrompts.FILE_SUMMARIES_ERROR,
            text);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      var rows = readFileSummaries(raw.get(), text);
      Log.infof(
          "Response repair for review session %d read %d file summar%s from the string the summary"
              + " answer wrote",
          ledgerKey, rows.size(), rows.size() == 1 ? "y" : "ies");
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
    } catch (IOException | RuntimeException e) {
      Log.warnf(
          "Response repair for review session %d could not read the file summaries it asked for"
              + " (%s); the walkthrough keeps its rows unsummarized",
          ledgerKey, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * The rows of the re-ask's answer whose path is non-blank and in {@code text}, so a row the
   * re-ask made up is never rendered.
   */
  private List<ReviewResponse.FileSummary> readFileSummaries(String raw, String text)
      throws IOException {
    JsonNode root =
        mapper.readTree(
            ReviewResponseParser.extractJson(raw, List.of(ReviewResponseParser.FILE_SUMMARIES)));
    var rows = new ArrayList<ReviewResponse.FileSummary>();
    for (var entry : root.path(ReviewResponseParser.FILE_SUMMARIES)) {
      var path = entry.path("path").asText("").strip();
      var summary = entry.path("summary").asText("").strip();
      if (!path.isEmpty() && !summary.isEmpty() && namesPath(text, path)) {
        rows.add(new ReviewResponse.FileSummary(path, summary));
      }
    }
    return List.copyOf(rows);
  }

  /**
   * Whether {@code text} names {@code path} whole: an occurrence not run on into a longer path or
   * name ({@code data.py} does not name {@code a.py}, nor {@code a.pyc} {@code a.py}); a period
   * after it ends a sentence only when no name character follows.
   */
  static boolean namesPath(String text, String path) {
    for (var at = text.indexOf(path); at >= 0; at = text.indexOf(path, at + 1)) {
      var end = at + path.length();
      var startsClean = at == 0 || !isPathChar(text.charAt(at - 1));
      var endsClean =
          end == text.length()
              || !isPathChar(text.charAt(end))
              || (text.charAt(end) == '.'
                  && (end + 1 == text.length() || !isPathChar(text.charAt(end + 1))));
      if (startsClean && endsClean) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPathChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/' || c == '.';
  }

  /**
   * One repair call's answer text, or empty when the ceiling is reached, the call fails, or the
   * answer is empty or cut. The usage is recorded before the answer is read: a cut answer was still
   * billed. A call whose reasoning spent the whole cap before any answer is repeated once at a
   * lower effort (#1169).
   */
  private Optional<String> call(
      long ledgerKey, ModelLane lane, String contract, String error, String answer) {
    if (tokenLedger.ceilingReached(ledgerKey)) {
      Log.warnf(
          "Response repair for review session %d skipped at the review's token spend ceiling"
              + " (REVIEW_MAX_TOKENS_PER_REVIEW)",
          ledgerKey);
      return Optional.empty();
    }
    try {
      var fenced = PromptTemplateEscaper.fence(answer);
      var concise = lane == ModelLane.CONCISE;
      var result =
          ReasoningRepeat.blocking(
              "Response repair for review session " + ledgerKey,
              () ->
                  concise
                      ? summaryRepairer.repair(contract, error, fenced)
                      : reviewRepairer.repair(contract, error, fenced),
              () -> ReasoningRepeat.effortFor(config, concise),
              () -> tokenLedger.ceilingReached(ledgerKey),
              repaired -> recordUsage(ledgerKey, repaired),
              () -> tokenLedger.recordReasoningStepDown(ledgerKey));
      var raw =
          AiResponses.textOrThrowOnTruncation(
              result, "Response repair", responseCaps.forLane(lane));
      if (raw == null || raw.isBlank()) {
        Log.warnf("Response repair for review session %d returned no response body", ledgerKey);
        return Optional.empty();
      }
      return Optional.of(raw);
    } catch (RuntimeException e) {
      // Only the type is logged: the message can carry provider or model text.
      Log.warnf(
          "Response repair for review session %d failed (%s)",
          ledgerKey, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Why a repaired review answer cannot stand for the failed {@code body}, empty when it can: it
   * dropped findings, holds a finding whose title is none of the failed answer's, or holds more
   * statuses than the failed answer wrote.
   */
  static Optional<String> rejection(ReviewResponse repaired, String body) {
    // The structure the scan read, or, when it lost track of it, every key in the raw text: the
    // stricter count, so a scan that went astray can only cost a full retry.
    var keys = AnswerKeys.of(body);
    var titles = keys.readable() ? keys.titleCount() : count(TITLE_KEY, body);
    if (repaired.findings().size() < titles) {
      return Optional.of(
          "it holds "
              + repaired.findings().size()
              + " finding(s) where the failed answer has "
              + titles
              + " title(s)");
    }
    var known =
        keys.readable()
            ? keys.titles().stream().map(ResponseRepairService::plain).collect(Collectors.toSet())
            : Set.<String>of();
    var plainBody = plain(body);
    for (var finding : repaired.findings()) {
      var title = finding.title() == null ? null : plain(finding.title());
      var held =
          title != null && (keys.readable() ? known.contains(title) : plainBody.contains(title));
      if (!held) {
        return Optional.of("it holds a finding whose title the failed answer does not have");
      }
    }
    var statuses = keys.readable() ? keys.statuses() : count(STATUS_KEY, body);
    if (repaired.previousFindingsStatus().size() > statuses) {
      return Optional.of(
          "it holds "
              + repaired.previousFindingsStatus().size()
              + " previous-finding status(es) where the failed answer has "
              + statuses);
    }
    return Optional.empty();
  }

  private static int count(Pattern key, String text) {
    var matcher = key.matcher(text);
    var found = 0;
    while (matcher.find()) {
      found++;
    }
    return found;
  }

  /** {@code text} without backslashes and whitespace, so an escape the repair resolved matches. */
  private static String plain(String text) {
    return ESCAPES_AND_SPACE.matcher(text).replaceAll("");
  }

  /**
   * What the repair prompt says went wrong: the contract's root in one line, then the parser's own
   * message and the first line of its cause (a JSON parser's location and expectation), clipped.
   */
  static String errorText(IllegalArgumentException parseFailure, boolean summaryLane) {
    var expected =
        summaryLane
            ? "Expected one JSON object with a \"summary\" object."
            : "Expected one JSON object with a \"findings\" array.";
    var detail = new StringBuilder(String.valueOf(parseFailure.getMessage()));
    var cause = parseFailure.getCause();
    if (cause != null && cause.getMessage() != null) {
      detail.append(": ").append(cause.getMessage().lines().findFirst().orElse(""));
    }
    var text = detail.toString();
    if (text.length() > MAX_ERROR_DETAIL) {
      text = text.substring(0, MAX_ERROR_DETAIL) + "…";
    }
    return expected + " The parser said: " + text;
  }

  private void recordUsage(long ledgerKey, Result<String> result) {
    var usage = result == null ? null : result.tokenUsage();
    if (usage == null) {
      return;
    }
    tokenLedger.recordUsage(ledgerKey, usage.inputTokenCount(), usage.outputTokenCount());
  }
}
