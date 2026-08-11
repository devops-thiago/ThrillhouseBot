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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ReviewResponseParser {

  private static final String PREVIOUS_FINDINGS_STATUS = "previous_findings_status";
  private static final String SUMMARY = "summary";
  private static final String DESCRIPTION_GAPS = "description_gaps";
  private static final String FILE_SUMMARIES = "file_summaries";
  private static final String PATH = "path";

  /**
   * Keys a mis-shaped {@code file_summaries} entry may carry the path under, most canonical first.
   * Jackson ignores unknown properties, so an entry keyed {@code file} maps to a FileSummary with a
   * null path — which the walkthrough renderer then drops without a word (#536).
   */
  private static final List<String> PATH_KEYS =
      List.of(PATH, "file", "filename", "file_path", "filepath", "file_name");

  /** Keys a mis-shaped {@code file_summaries} entry may carry the one-line summary under. */
  private static final List<String> SUMMARY_KEYS =
      List.of(SUMMARY, "description", "change", "changes", "note", "text");

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
    normalizeFileSummaries(root);
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
      } catch (JsonProcessingException _) {
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
      if (!flattenInto(normalized, gaps)) {
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
   * Copies {@code gaps} into {@code normalized}, flattening every non-string element. Returns
   * whether anything needed flattening — {@code false} means the array already conformed and the
   * caller should keep the original node untouched.
   */
  private static boolean flattenInto(ArrayNode normalized, JsonNode gaps) {
    var changed = false;
    for (var gap : gaps) {
      if (gap.isTextual() || gap.isNull()) {
        normalized.add(gap);
      } else {
        normalized.add(flattenGap(gap));
        changed = true;
      }
    }
    return changed;
  }

  /**
   * Flattens one mis-shaped gap element to a string: objects join their string-valued fields {@code
   * ": "}-separated in a stable order — {@code "claim"} first when present (the field the
   * production shape led with), then the rest in emission order; non-string scalars flatten via
   * {@code asText()}; arrays, and objects without any string-valued field, degrade to their JSON
   * text. Non-string members of an object that does have string-valued fields (e.g. {@code line:
   * 42}) are dropped by design — the gap list renders prose, and the string fields carry it.
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

  /**
   * Models sometimes emit {@code summary.file_summaries} in a shape the schema does not accept: a
   * map keyed by path ({@code {"src/A.java": "adds X"}}), entries keyed {@code file}/{@code
   * description} rather than {@code path}/{@code summary}, {@code "path: summary"} strings, or a
   * single object where the array belongs. Both ways that fails are silent. An unrecognized key is
   * ignored by Jackson, leaving a null {@code path} that the walkthrough renderer drops — so every
   * row renders "-" and nothing says why. A non-array node fails schema mapping instead, and the
   * salvage in {@link #parseWithoutSummary} then discards the WHOLE summary, taking pr_purpose and
   * the description gaps with it. Normalize to the array-of-{path, summary} form so a recoverable
   * shape still fills the walkthrough, and log what could not be recovered (#536).
   */
  private void normalizeFileSummaries(JsonNode root) {
    if (!(root instanceof ObjectNode rootObject)
        || !(rootObject.get(SUMMARY) instanceof ObjectNode summary)) {
      return;
    }
    var fileSummaries = summary.get(FILE_SUMMARIES);
    if (fileSummaries == null || fileSummaries.isNull()) {
      return;
    }
    var normalized = mapper.createArrayNode();
    var seen = 1;
    if (fileSummaries.isArray()) {
      if (conformsToFileSummarySchema(fileSummaries)) {
        // Already-conforming arrays stay untouched
        return;
      }
      seen = fileSummaries.size();
      for (var entry : fileSummaries) {
        addFileSummary(normalized, null, entry);
      }
    } else if (looksLikeFileSummary(fileSummaries)) {
      // A single entry emitted where the array belongs
      addFileSummary(normalized, null, fileSummaries);
    } else if (fileSummaries.isObject()) {
      // The map form: each property is one path and its summary
      seen = fileSummaries.size();
      for (var entry : fileSummaries.properties()) {
        addFileSummary(normalized, entry.getKey(), entry.getValue());
      }
    }
    // Anything else is a scalar where the walkthrough belongs: no (path, summary) pair at all.
    Log.warnf(
        "Review response file_summaries did not match the schema — recovered %d entr%s and dropped"
            + " %d; unrecovered entries render as blank walkthrough rows",
        normalized.size(), normalized.size() == 1 ? "y" : "ies", seen - normalized.size());
    summary.set(FILE_SUMMARIES, normalized);
  }

  /**
   * Whether every element already carries textual {@code path} and {@code summary} fields, so the
   * array maps cleanly and must be left exactly as the model sent it. An empty array conforms.
   */
  private static boolean conformsToFileSummarySchema(JsonNode fileSummaries) {
    for (var entry : fileSummaries) {
      if (!entry.isObject() || !entry.path(PATH).isTextual() || !entry.path(SUMMARY).isTextual()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the node is one walkthrough entry rather than the map form. The map form is keyed by
   * real repository paths, which are never spelled {@code summary}/{@code description}/…, so a
   * summary-ish field at the top level identifies a single entry unambiguously.
   */
  private static boolean looksLikeFileSummary(JsonNode node) {
    return node.isObject() && firstTextual(node, SUMMARY_KEYS, null) != null;
  }

  /**
   * Appends one recovered {@code {path, summary}} entry. {@code key} is the property name when the
   * model emitted the map form (so the path lives in the key), else {@code null}. A textual value
   * without such a key is read as {@code "path: summary"}. An element carrying no usable path or no
   * usable summary is dropped rather than mapped to a half-null entry the renderer would silently
   * skip.
   */
  private void addFileSummary(ArrayNode normalized, String key, JsonNode value) {
    var path = key;
    String summary = null;
    if (value.isObject()) {
      path = firstTextual(value, PATH_KEYS, path);
      summary = firstTextual(value, SUMMARY_KEYS, null);
    } else if (value.isTextual() && key != null) {
      summary = value.asText();
    } else if (value.isTextual()) {
      var separator = value.asText().indexOf(':');
      if (separator >= 0) {
        path = value.asText().substring(0, separator);
        summary = value.asText().substring(separator + 1);
      }
    }
    if (usable(path) && usable(summary)) {
      normalized.add(
          mapper.createObjectNode().put(PATH, path.strip()).put(SUMMARY, summary.strip()));
    }
  }

  /** Whether a recovered path or summary carries anything the walkthrough can render. */
  private static boolean usable(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * The first of {@code keys} present on {@code node} with a textual value, else {@code fallback}.
   */
  private static String firstTextual(JsonNode node, List<String> keys, String fallback) {
    for (var key : keys) {
      var value = node.get(key);
      if (value != null && value.isTextual()) {
        return value.asText();
      }
    }
    return fallback;
  }

   * Strips optional markdown fences and leading noise before the JSON object/array. An absent body
   * extracts to {@code ""}: "no response" is a soft failure every caller decides for itself (each
   * one guards the body before parsing), so this must not convert it into a {@code
   * NullPointerException} raised from inside the extraction.
   */
  static String extractJson(String raw) {
    if (raw == null) {
      return "";
    }
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
