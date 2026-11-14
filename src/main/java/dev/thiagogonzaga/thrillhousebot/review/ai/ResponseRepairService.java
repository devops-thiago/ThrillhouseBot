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
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * has titles, every finding title must be one of the failed answer's, each of those titles must
 * come back at least as often as the failed answer wrote it (#1176), and it may not hold more
 * statuses than the failed answer has. Those are read by {@link AnswerKeys} from the failed
 * answer's structure, so JSON quoted inside a field counts for nothing. The repair also cannot move
 * a finding or renumber a status (#1322): each finding keeps the file and line the failed answer
 * wrote for its title, and each status the id, key and status the failed answer wrote at its
 * position; a value the repair changed is restored. When the scan loses track of the failed
 * answer's structure none of that can be read, and the repair is rejected. A re-asked file summary
 * is kept only when its whole path is named in the string it came from.
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
   * the parser's refusal of {@code body}. A rejected API key is thrown, as by every call here.
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
      if (!summaryLane) {
        var keys = AnswerKeys.of(body);
        var rejection = rejection(repaired, keys);
        if (rejection.isPresent()) {
          Log.warnf(
              "Response repair for review session %d rejected: %s; the full retry runs",
              ledgerKey, rejection.get());
          return Optional.empty();
        }
        repaired = restored(ledgerKey, repaired, keys);
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
   * fails, or nothing usable comes back. A rejected API key is thrown.
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
   * lower effort (#1169). A rejected API key is thrown as {@link AiApiKeyRejectedException} instead
   * of read as a failed repair (#1329).
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
              () -> ReasoningRepeat.enableHint(config),
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
      // A rejected key is not a failed repair (#1329): the full retry would send the same key, so
      // the rejection ends the call the repair was for.
      var rejection = AiApiKeyRejectedException.classify(e);
      if (rejection.isPresent()) {
        Log.errorf(
            "Response repair for review session %d failed: %s",
            ledgerKey, rejection.get().getMessage());
        throw rejection.get();
      }
      // Only the type is logged: the message can carry provider or model text.
      Log.warnf(
          "Response repair for review session %d failed (%s)",
          ledgerKey, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Why a repaired review answer cannot stand for the failed answer {@code keys} were read from,
   * empty when it can: the scan lost the failed answer's structure, so the file, line and id each
   * finding and status must keep cannot be read from it (#1322); or the repair dropped findings,
   * holds a finding whose title is none of the failed answer's, repeats one finding's title in
   * place of another's, or holds more statuses than the failed answer wrote.
   */
  static Optional<String> rejection(ReviewResponse repaired, AnswerKeys keys) {
    if (!keys.readable()) {
      return Optional.of(
          "the failed answer's structure could not be read, so the file, line and id of each"
              + " finding and status cannot be checked");
    }
    var titles = keys.titleCount();
    if (repaired.findings().size() < titles) {
      return Optional.of(
          "it holds "
              + repaired.findings().size()
              + " finding(s) where the failed answer has "
              + titles
              + " title(s)");
    }
    var written =
        keys.titles().stream()
            .collect(Collectors.toMap(ResponseRepairService::plain, title -> 1, Integer::sum));
    var held = new HashMap<String, Integer>();
    for (var finding : repaired.findings()) {
      var title = finding.title() == null ? null : plain(finding.title());
      if (title == null || !written.containsKey(title)) {
        return Optional.of("it holds a finding whose title the failed answer does not have");
      }
      held.merge(title, 1, Integer::sum);
    }
    // Each title as often as the failed answer wrote it, so a copy of one finding cannot stand in
    // for another (#1176).
    var lost =
        written.entrySet().stream()
            .anyMatch(title -> held.getOrDefault(title.getKey(), 0) < title.getValue());
    if (lost) {
      return Optional.of(
          "it repeats a finding's title in place of another finding of the failed answer");
    }
    // Each status is restored from the failed answer's status at its position (#1322), so there
    // must be one: an element writing "status" twice counts once here.
    var statuses = Math.min(keys.statuses(), keys.statusEntries().size());
    if (repaired.previousFindingsStatus().size() > statuses) {
      return Optional.of(
          "it holds "
              + repaired.previousFindingsStatus().size()
              + " previous-finding status(es) where the failed answer has "
              + statuses);
    }
    return Optional.empty();
  }

  /**
   * {@code repaired} holding the file and line the failed answer wrote for each finding, and the
   * id, key and status it wrote for each status (#1322): the repair reshapes, so a value it changed
   * is put back, and the change is logged. A finding is paired with the failed answer's finding of
   * the same title, the n-th copy of a title with its n-th copy (an extra copy with the last one);
   * a status with the failed answer's status at the same position. A field the failed answer does
   * not write is absent from the restored one too, since the repair may not make a value up.
   */
  static ReviewResponse restored(long ledgerKey, ReviewResponse repaired, AnswerKeys keys) {
    var originals = new HashMap<String, List<Map<String, String>>>();
    for (var original : keys.findings()) {
      var title = original.get("title");
      if (title != null) {
        originals.computeIfAbsent(plain(title), t -> new ArrayList<>()).add(original);
      }
    }
    var seen = new HashMap<String, Integer>();
    var findings = new ArrayList<ReviewResponse.Finding>(repaired.findings().size());
    var changed = false;
    for (var finding : repaired.findings()) {
      // The rejection checks leave no finding without a title the failed answer wrote.
      var copies = originals.get(plain(finding.title()));
      var copy = seen.merge(plain(finding.title()), 1, Integer::sum) - 1;
      var original = copies.get(Math.min(copy, copies.size() - 1));
      var restored = withOriginalPlace(ledgerKey, finding, original);
      changed |= restored != finding;
      findings.add(restored);
    }
    var statuses =
        new ArrayList<ReviewResponse.PreviousFindingStatus>(
            repaired.previousFindingsStatus().size());
    for (var i = 0; i < repaired.previousFindingsStatus().size(); i++) {
      var status = repaired.previousFindingsStatus().get(i);
      // The rejection checks leave no status beyond the failed answer's.
      var restored = withOriginalId(ledgerKey, status, keys.statusEntries().get(i));
      changed |= restored != status;
      statuses.add(restored);
    }
    if (!changed) {
      return repaired;
    }
    return new ReviewResponse(
        findings,
        statuses,
        repaired.summary(),
        repaired.closedEarlierRound(),
        repaired.linkedIssueCriteria());
  }

  /** {@code finding} at the file and line {@code original} writes; itself when they agree. */
  private static ReviewResponse.Finding withOriginalPlace(
      long ledgerKey, ReviewResponse.Finding finding, Map<String, String> original) {
    var file = original.get("file");
    var line = lineOf(original.get("line"), finding.line());
    if (Objects.equals(file, finding.file()) && line == finding.line()) {
      return finding;
    }
    Log.infof(
        "Response repair for review session %d moved finding '%s' to %s:%d; restored the failed"
            + " answer's %s:%d",
        ledgerKey,
        LogSafe.oneLine(finding.title()),
        LogSafe.oneLine(finding.file()),
        finding.line(),
        LogSafe.oneLine(file),
        line);
    return new ReviewResponse.Finding(
        finding.risk(),
        finding.confidence(),
        file,
        line,
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  /**
   * The line {@code written} states, 0 when the failed answer wrote none (what the parser reads
   * then), or {@code fallback} when it wrote one that is no integer and so cannot be compared.
   */
  private static int lineOf(String written, int fallback) {
    if (written == null) {
      return 0;
    }
    try {
      return Integer.parseInt(written.strip());
    } catch (NumberFormatException _) {
      return fallback;
    }
  }

  /** {@code status} with the id, key and status {@code original} writes; itself when they agree. */
  private static ReviewResponse.PreviousFindingStatus withOriginalId(
      long ledgerKey, ReviewResponse.PreviousFindingStatus status, Map<String, String> original) {
    var id = lineOf(original.get("id"), status.id());
    var key = original.get("key");
    var value = original.getOrDefault("status", status.status());
    if (id == status.id()
        && Objects.equals(key, status.key())
        && Objects.equals(value, status.status())) {
      return status;
    }
    Log.infof(
        "Response repair for review session %d changed previous-finding status #%d (key '%s', %s);"
            + " restored the failed answer's #%d (key '%s', %s)",
        ledgerKey,
        status.id(),
        LogSafe.oneLine(status.key()),
        LogSafe.oneLine(status.status()),
        id,
        LogSafe.oneLine(key),
        LogSafe.oneLine(value));
    return new ReviewResponse.PreviousFindingStatus(id, value, status.note(), key);
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
