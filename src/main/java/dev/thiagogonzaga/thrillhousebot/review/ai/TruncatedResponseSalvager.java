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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Salvages the complete leading JSON elements out of a response the model cut mid-JSON — at its
 * length cap ({@link AiResponseTruncatedException#partialBody()}), or with no {@code
 * finish_reason=length} reported at all (#546). The body is well-formed up to the cut, so every
 * {@code findings} / {@code previous_findings_status} array element that closed before it — and a
 * {@code summary} object that did — is recoverable as-is; the trailing cut-off value is dropped by
 * construction. {@link #salvageArray} exposes the same pass for the other review-path responses
 * whose payload is a single named array, such as the finding verifier's {@code verdicts}.
 *
 * <p>Deliberately a separate class from {@link ReviewResponseParser}: the parser's contract is
 * all-or-nothing on a complete body, while salvage is best-effort on a known-cut one, and the two
 * must not blur (#508 is being fixed in the parser in parallel). The fence/noise stripping and
 * control-character escaping are shared through {@link ReviewResponseParser#extractJson}, and the
 * tokenization is Jackson's own streaming parser — no hand-rolled string slicing over model output.
 *
 * <p>Paranoid by construction, since the input is model output: the scan is a single bounded
 * forward pass ({@link #MAX_SALVAGED_BODY_CHARS}), each array keeps at most {@link
 * #MAX_ELEMENTS_PER_ARRAY} elements, an element that does not map onto the response schema is
 * skipped rather than failing the salvage, and any parse error simply ends the pass with what was
 * already collected. Salvage never throws.
 */
@ApplicationScoped
public class TruncatedResponseSalvager {

  /**
   * Upper bound on the body length a salvage pass will even look at. The buffered body is already
   * bounded by the model's output cap, so this is belt-and-braces against a runaway buffer — well
   * above the largest configured cap's worth of characters, so a real truncation is never refused.
   */
  static final int MAX_SALVAGED_BODY_CHARS = 10_000_000;

  /**
   * Upper bound on elements kept per array. A real batch response carries tens of findings; a body
   * repeating thousands of elements is runaway output, and the pass stops rather than ferrying it
   * all into the finding chain.
   */
  static final int MAX_ELEMENTS_PER_ARRAY = 500;

  private final ObjectMapper mapper;

  @Inject
  public TruncatedResponseSalvager(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * What a salvage pass recovered: the complete findings and previous-finding statuses (possibly
   * none), and the summary object when it closed before the cut ({@code null} otherwise).
   */
  public record Salvaged(
      List<ReviewResponse.Finding> findings,
      List<ReviewResponse.PreviousFindingStatus> previousFindingsStatus,
      ReviewResponse.Summary summary) {
    public Salvaged {
      findings = List.copyOf(findings);
      previousFindingsStatus = List.copyOf(previousFindingsStatus);
    }

    /** Whether anything a batch disclosure could honestly call "partially reviewed" came back. */
    public boolean hasFindingsOrStatuses() {
      return !findings.isEmpty() || !previousFindingsStatus.isEmpty();
    }
  }

  /**
   * Salvages the complete elements of {@code partialBody}. Everything comes back empty when there
   * is nothing to work with — no body (the blocking lanes do not buffer one), a body that does not
   * open a JSON object, or a cut that landed before the first element closed.
   */
  public Salvaged salvage(String partialBody) {
    var findings = new ArrayList<ReviewResponse.Finding>();
    var statuses = new ArrayList<ReviewResponse.PreviousFindingStatus>();
    // One-element holder: the summary is the pass's only scalar result, and the field handler
    // below is a lambda, which cannot assign a local.
    var summary = new ReviewResponse.Summary[1];
    scan(
        partialBody,
        (parser, field, value) -> {
          switch (field) {
            case "findings" ->
                salvageArrayElements(parser, value, ReviewResponse.Finding.class, findings);
            case "previous_findings_status" ->
                salvageArrayElements(
                    parser, value, ReviewResponse.PreviousFindingStatus.class, statuses);
            case "summary" ->
                summary[0] = objectOrNull(parser, value, ReviewResponse.Summary.class);
            default -> parser.skipChildren();
          }
        });
    return new Salvaged(findings, statuses, summary[0]);
  }

  /**
   * Salvages the complete leading elements of a single named top-level array — the shape of a
   * response whose whole payload is one array of records, such as the finding verifier's {@code
   * {"verdicts": [...]}} (#546). Same bounded, never-throwing pass as {@link #salvage}: the
   * elements that closed before the cut come back mapped onto {@code type}, the cut-off trailing
   * element is dropped, and an empty list means there was nothing recoverable — no body, no such
   * field, a non-array value, or a cut before the first element closed.
   */
  public <T> List<T> salvageArray(String partialBody, String field, Class<T> type) {
    var elements = new ArrayList<T>();
    scan(
        partialBody,
        (parser, name, value) -> {
          if (field.equals(name)) {
            salvageArrayElements(parser, value, type, elements);
          } else {
            parser.skipChildren();
          }
        });
    return List.copyOf(elements);
  }

  /** How a scan pass consumes one top-level field, positioned on its value token. */
  @FunctionalInterface
  private interface FieldHandler {
    void handle(JsonParser parser, String field, JsonToken value) throws IOException;
  }

  /**
   * The single bounded forward pass every salvage shares: hand each top-level field of the cut body
   * to {@code handler}, and end quietly at the cut. Does nothing when there is nothing to work with
   * — no body, an oversized one, or one that does not open a JSON object.
   */
  private void scan(String partialBody, FieldHandler handler) {
    if (partialBody == null
        || partialBody.isBlank()
        || partialBody.length() > MAX_SALVAGED_BODY_CHARS) {
      return;
    }
    var json = ReviewResponseParser.extractJson(partialBody);
    JsonParser parser = null;
    try {
      parser = mapper.createParser(json);
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        return;
      }
      while (parser.nextToken() == JsonToken.FIELD_NAME) {
        var field = parser.currentName();
        // Never null: inside the root object, Jackson raises JsonEOFException at the cut instead
        // of returning null, and the catch below ends the pass.
        var value = parser.nextToken();
        handler.handle(parser, field, value);
      }
    } catch (IOException e) {
      // The cut point (or trailing garbage): everything that closed before it is already
      // collected, and the partial trailing value was never added. This is the expected way for
      // a truncated body's pass to end.
      Log.debugf("Salvage pass ended at the cut: %s", e.getMessage());
    } finally {
      closeQuietly(parser);
    }
  }

  /**
   * Collects the array's complete elements into {@code out}, capped at {@link
   * #MAX_ELEMENTS_PER_ARRAY}. A non-array value is skipped whole (e.g. the object-form {@code
   * previous_findings_status} some models emit — salvage keeps only the unambiguous shape). An
   * element that is not an object, or does not map onto {@code type}, is skipped individually.
   */
  private <T> void salvageArrayElements(
      JsonParser parser, JsonToken value, Class<T> type, List<T> out) throws IOException {
    if (value != JsonToken.START_ARRAY) {
      parser.skipChildren();
      return;
    }
    while (out.size() < MAX_ELEMENTS_PER_ARRAY) {
      // Never null: inside the array, Jackson raises JsonEOFException at the cut instead of
      // returning null, ending the pass with the complete leading elements kept.
      var token = parser.nextToken();
      if (token == JsonToken.END_ARRAY) {
        return;
      }
      var element = objectOrNull(parser, token, type);
      if (element != null) {
        out.add(element);
      }
    }
    // Element cap reached: runaway output. Stop the whole pass (the outer loop ends on the next
    // non-FIELD_NAME token) rather than scanning the remainder.
  }

  /**
   * Reads the value at the current token completely and maps it onto {@code type}; {@code null} for
   * a non-object value or one that does not map. Reading always consumes the whole value, so the
   * parser stays element-aligned — and a value the cut split throws, ending the pass with the
   * partial value dropped.
   */
  /**
   * Closes the parser without letting a close-time failure mask the salvage result. A string-backed
   * parser cannot fail to close in practice — the null guard and the swallow exist for the
   * contract, and are exercised directly by tests because no production input reaches them.
   */
  static void closeQuietly(JsonParser parser) {
    if (parser == null) {
      return;
    }
    try {
      parser.close();
    } catch (IOException e) {
      Log.debugf("Ignoring a close failure on a string-backed parser: %s", e.getMessage());
    }
  }

  private <T> T objectOrNull(JsonParser parser, JsonToken token, Class<T> type) throws IOException {
    JsonNode node = parser.readValueAsTree();
    if (token != JsonToken.START_OBJECT) {
      return null;
    }
    try {
      return mapper.treeToValue(node, type);
    } catch (JsonProcessingException _) {
      Log.debugf("Skipping a salvaged element that does not map onto %s", type.getSimpleName());
      return null;
    }
  }
}
