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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;

@ApplicationScoped
public class ReviewResponseParser {

  private static final String PREVIOUS_FINDINGS_STATUS = "previous_findings_status";
  private static final String SUMMARY = "summary";
  private static final String DESCRIPTION_GAPS = "description_gaps";

  private final ObjectMapper mapper;

  @Inject
  public ReviewResponseParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ReviewResponse parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Model returned an empty response");
    }
    JsonNode root;
    try {
      root = mapper.readTree(extractJson(raw));
    } catch (IOException e) {
      throw new IllegalArgumentException("Model response is not valid review JSON", e);
    }
    normalizePreviousFindingsStatus(root);
    normalizeDescriptionGaps(root);
    try {
      return mapper.treeToValue(root, ReviewResponse.class);
    } catch (JsonProcessingException e) {
      return parseWithoutSummary(root, e);
    }
  }

  /**
   * Last-resort salvage for valid JSON that still fails schema mapping after normalization: a
   * mapping failure confined to the {@code summary} node must not discard findings (and previous
   * finding statuses) that mapped cleanly — that would throw away a fully paid review and force a
   * full-cost retry. Retry the mapping with {@code summary} removed; every consumer of {@link
   * ReviewResponse#summary()} null-guards it. If the failure was not confined to the summary,
   * report the original mapping error.
   */
  private ReviewResponse parseWithoutSummary(JsonNode root, JsonProcessingException cause) {
    if (root instanceof ObjectNode rootObject && rootObject.hasNonNull(SUMMARY)) {
      var withoutSummary = rootObject.deepCopy();
      withoutSummary.remove(SUMMARY);
      try {
        var salvaged = mapper.treeToValue(withoutSummary, ReviewResponse.class);
        Log.warnf(
            "Review response summary did not match the schema — dropped the 'summary' node and"
                + " kept %d finding(s). Mapping error: %s",
            salvaged.findings().size(), cause.getMessage());
        return salvaged;
      } catch (JsonProcessingException stillFailing) {
        // The failure was not confined to the summary — fall through to the original error.
      }
    }
    throw schemaMismatch(cause);
  }

  /**
   * A Jackson databind failure means the JSON itself was valid but did not fit the review schema —
   * a (near-)deterministic model-output shape problem, not a transient parse failure. Keep the
   * exception type callers catch, but say so in the message (with the failing path) so the two
   * failure classes are distinguishable in the logs.
   */
  static IllegalArgumentException schemaMismatch(JsonProcessingException cause) {
    if (cause instanceof JsonMappingException mapping) {
      return new IllegalArgumentException(
          "Model response was valid JSON but did not match the review schema at "
              + mapping.getPathReference(),
          cause);
    }
    return new IllegalArgumentException("Model response is not valid review JSON", cause);
  }

  /**
   * Models sometimes emit {@code previous_findings_status} as an object — a single status, or a map
   * keyed by finding id — instead of the array the schema asks for. Normalize it to the array form
   * so the shape mismatch does not fail the whole review (and force a full-cost retry).
   */
  private void normalizePreviousFindingsStatus(JsonNode root) {
    if (!(root instanceof ObjectNode rootObject)) {
      return;
    }
    var statuses = rootObject.get(PREVIOUS_FINDINGS_STATUS);
    if (statuses == null || statuses.isNull() || statuses.isArray()) {
      return;
    }
    var normalized = mapper.createArrayNode();
    if (statuses.isObject()) {
      if (statuses.has("status")) {
        // A bare single status object with id/status fields at the top level
        normalized.add(statuses);
      } else {
        // A map keyed by finding id, with string statuses or nested status objects
        for (var entry : statuses.properties()) {
          normalized.add(statusEntry(entry.getKey(), entry.getValue()));
        }
      }
    }
    rootObject.set(PREVIOUS_FINDINGS_STATUS, normalized);
  }

  private ObjectNode statusEntry(String key, JsonNode value) {
    var item = mapper.createObjectNode();
    if (value.isObject()) {
      item.setAll((ObjectNode) value);
    } else {
      item.put("status", value.asText());
    }
    if (!item.has("id")) {
      item.put("id", parseFindingId(key));
    }
    return item;
  }

  /** The map key is usually the finding number, possibly wrapped in text ("finding_2"). */
  private static int parseFindingId(String key) {
    var digits = key.replaceAll("\\D", "");
    return digits.isEmpty() ? 0 : Integer.parseInt(digits);
  }

  /**
   * Models sometimes emit {@code summary.description_gaps} elements as objects — e.g. {@code
   * {"claim": …, "code": …}} — instead of the plain strings the schema asks for. Normalize each
   * element to a string so the shape mismatch does not fail the whole review (and force a full-cost
   * retry). Elements that are already strings (or null, which the {@code Summary} constructor
   * drops) pass through unchanged; a bare string or single object in place of the array is wrapped
   * into a one-element array.
   */
  private void normalizeDescriptionGaps(JsonNode root) {
    if (!(root instanceof ObjectNode rootObject)
        || !(rootObject.get(SUMMARY) instanceof ObjectNode summary)) {
      return;
    }
    var gaps = summary.get(DESCRIPTION_GAPS);
    if (gaps == null || gaps.isNull()) {
      return;
    }
    var normalized = mapper.createArrayNode();
    if (gaps.isArray()) {
      var changed = false;
      for (var gap : gaps) {
        if (gap.isTextual() || gap.isNull()) {
          normalized.add(gap);
        } else {
          normalized.add(flattenGap(gap));
          changed = true;
        }
      }
      if (!changed) {
        // Already-conforming arrays stay untouched
        return;
      }
    } else if (gaps.isTextual()) {
      normalized.add(gaps);
    } else {
      normalized.add(flattenGap(gaps));
    }
    summary.set(DESCRIPTION_GAPS, normalized);
  }

  /**
   * Flattens one mis-shaped gap element to a string: objects join their string-valued fields {@code
   * ": "}-separated in a stable order — {@code "claim"} first when present (the field the
   * production shape led with), then the rest in emission order; non-string scalars flatten via
   * {@code asText()}; arrays, and objects without any string-valued field, degrade to their JSON
   * text so no content is silently lost.
   */
  private static String flattenGap(JsonNode gap) {
    if (!gap.isObject()) {
      return gap.isValueNode() ? gap.asText() : gap.toString();
    }
    var parts = new ArrayList<String>();
    var claim = gap.get("claim");
    if (claim != null && claim.isTextual()) {
      parts.add(claim.asText());
    }
    for (var entry : gap.properties()) {
      if (entry.getValue().isTextual() && !"claim".equals(entry.getKey())) {
        parts.add(entry.getValue().asText());
      }
    }
    return parts.isEmpty() ? gap.toString() : String.join(": ", parts);
  }

  /** Strips optional markdown fences and leading noise before the JSON object/array. */
  static String extractJson(String raw) {
    var trimmed = raw.strip();

    if (trimmed.startsWith("```")) {
      var start = trimmed.indexOf('\n');
      var end = trimmed.lastIndexOf("```");
      if (start >= 0 && end > start) {
        trimmed = trimmed.substring(start + 1, end).strip();
      }
    }

    var start = firstJsonStart(trimmed.indexOf('{'), trimmed.indexOf('['));
    if (start > 0) {
      trimmed = trimmed.substring(start);
    }

    return escapeControlCharsInStrings(trimmed);
  }

  /**
   * Escapes raw control characters (U+0000–U+001F) that appear inside JSON string literals. Models
   * sometimes emit a literal tab or newline inside a string field — for example verbatim source in
   * {@code suggestion_old}/{@code suggestion_new}, escaping {@code \n} but leaving a tab raw —
   * which strict JSON parsing rejects, failing the whole review and forcing a full-cost retry.
   * Control characters outside string literals are valid JSON whitespace and are left untouched, as
   * are already-escaped sequences.
   */
  static String escapeControlCharsInStrings(String json) {
    var out = new StringBuilder(json.length() + 16);
    var inString = false;
    var escaped = false;
    for (var i = 0; i < json.length(); i++) {
      var c = json.charAt(i);
      if (escaped) {
        out.append(c);
        escaped = false;
      } else if (c == '\\') {
        out.append(c);
        escaped = true;
      } else if (c == '"') {
        inString = !inString;
        out.append(c);
      } else if (inString && c < 0x20) {
        appendEscapedControlChar(out, c);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static void appendEscapedControlChar(StringBuilder out, char c) {
    switch (c) {
      case '\t' -> out.append("\\t");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      case '\b' -> out.append("\\b");
      case '\f' -> out.append("\\f");
      default -> out.append(String.format("\\u%04x", (int) c));
    }
  }

  /** Index of whichever JSON opener comes first; -1 when neither is present. */
  private static int firstJsonStart(int objectStart, int arrayStart) {
    if (objectStart < 0) {
      return arrayStart;
    }
    if (arrayStart < 0) {
      return objectStart;
    }
    return Math.min(objectStart, arrayStart);
  }
}
