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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.thiagogonzaga.thrillhousebot.LogSafe;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ReviewResponseParser {

  private static final String FINDINGS = "findings";
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

  /** How many of a dropped entry's field names one warning line lists before it stops. */
  private static final int MAX_LOGGED_FIELD_NAMES = 8;

  /** How long one listed field name may be before it is cut. */
  private static final int MAX_LOGGED_FIELD_NAME_CHARS = 40;

  private final ObjectMapper mapper;

  /**
   * The JSON names of {@link ReviewResponse.Summary}'s fields, read from the mapper that maps the
   * record, so the summary lane's fold (#850) follows the record's {@code @JsonProperty} names and
   * a field added to it is folded without a change here.
   */
  private final List<String> summaryFields;

  @Inject
  public ReviewResponseParser(ObjectMapper mapper) {
    this.mapper = mapper;
    this.summaryFields =
        mapper
            .getDeserializationConfig()
            .introspect(mapper.constructType(ReviewResponse.Summary.class))
            .findProperties()
            .stream()
            .map(BeanPropertyDefinition::getName)
            .toList();
  }

  /**
   * Reads one review batch's response. A root with no {@code findings} node is refused (#805): on
   * this lane the absence can be a set of findings that never arrived, and reading it as empty is
   * how a pull request was approved with nothing recorded.
   */
  public ReviewResponse parse(String raw) {
    return parse(raw, false);
  }

  /**
   * Reads the final summary call's response, which {@code FindingPipeline} takes only the summary
   * from: the merged review's findings come from the batches. Two shapes the batch lane refuses or
   * loses are read here (#850). A root with no {@code findings} node reads as an empty list, since
   * nothing is lost with it and a refusal only buys a full-price retry of a summary already in
   * hand. A root that writes the summary's fields with no {@code summary} object around them has
   * them folded into one by {@link #foldSummaryFields}.
   */
  public ReviewResponse parseSummary(String raw) {
    return parse(raw, true);
  }

  private ReviewResponse parse(String raw, boolean summaryLane) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Model returned an empty response");
    }
    var root = readDocuments(extractJson(raw, REVIEW_ROOT_KEYS));
    if (summaryLane) {
      foldSummaryFields(root);
    }
    normalizePreviousFindingsStatus(root);
    normalizeDescriptionGaps(root);
    normalizeFileSummaries(root);
    if (!root.hasNonNull(FINDINGS)) {
      if (!summaryLane) {
        // Absent is not the same as empty. A review that found nothing says "findings": [] — both
        // prompts require it — so a root without the node is a response that never delivered the
        // review, and the salvage below would have read it as a clean approval (#805).
        throw new IllegalArgumentException(
            "Model response has no findings node; a clean review states \"findings\": []");
      }
      Log.infof(
          "Summary response had no findings node among its %d top-level field(s); read it as an"
              + " empty list, since only the summary is taken from this call",
          root.size());
      root.set(FINDINGS, mapper.createArrayNode());
    }
    try {
      return mapper.treeToValue(root, ReviewResponse.class);
    } catch (JsonProcessingException e) {
      return parseWithoutSummary(root, e);
    }
  }

  /**
   * Reads every JSON document in the extracted text and folds them into one root. Jackson's {@code
   * readTree} returns after the first complete value and ignores whatever follows, so a response
   * that put its summary in one object and its findings in a second, fenced one parsed as a root
   * with no findings — an approval with nothing recorded about the 5,780 bytes it never read
   * (#805). Reasoning models split their output this way despite the "respond ONLY with valid JSON"
   * instruction, so the split is read rather than refused: each further document is merged by
   * {@link #mergeInto}, and one warning names how much of the response lay past the first document.
   *
   * <p>Between documents it skips whitespace, fence markers and any prose ahead of the next opening
   * brace — the same tolerance {@link #extractJson} extends to prose ahead of the first. Prose
   * after the last document that holds no brace is discarded with a warning rather than failed: the
   * document was read whole, prose carries no findings, and a format failure is a full-price retry
   * that a model which habitually signs off would spend every one of on the same sentence. What
   * must fail is a brace after the last document that does not parse as a complete object — a
   * truncated or malformed further document is content the parser could not read, and it raises the
   * same {@code IllegalArgumentException} a malformed response does, so the caller retries instead
   * of approving from a response it only partly read.
   */
  private ObjectNode readDocuments(String json) {
    var first = readObject(json, 0);
    var root = first.node();
    var position = first.end();
    var documents = 1;
    var tally = new MergeTally();
    for (var next = nextDocumentStart(json, position);
        next >= 0;
        next = nextDocumentStart(json, position)) {
      var document = readObject(json, next);
      mergeInto(root, document.node(), tally);
      position = document.end();
      documents++;
    }
    if (documents > 1) {
      Log.warnf(
          "Review response held %d JSON documents rather than one — read the %d characters past"
              + " the first document and merged the rest into it: %d finding(s) appended, %d"
              + " duplicate finding(s) dropped, %d conflicting top-level field(s) kept from the"
              + " earlier document",
          documents,
          json.length() - first.end(),
          tally.appended,
          tally.duplicates,
          tally.conflicts);
    }
    return root;
  }

  /** One document read out of the response text: its root and the index just past its close. */
  private record Document(ObjectNode node, int end) {}

  /** What merging the later documents did, for the one warning {@link #readDocuments} logs. */
  private static final class MergeTally {
    int appended;
    int duplicates;
    int conflicts;
  }

  /**
   * Reads the JSON object starting at {@code start}. The parser is asked for exactly one value, and
   * where it stopped is what tells the caller whether the response continues.
   */
  private Document readObject(String json, int start) {
    JsonNode node;
    int end;
    try (var parser = mapper.createParser(json.substring(start))) {
      node = mapper.readTree(parser);
      end = start + (int) parser.currentLocation().getCharOffset();
    } catch (IOException e) {
      throw new IllegalArgumentException("Model response is not valid review JSON", e);
    }
    if (!(node instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Model response is not a JSON object");
    }
    return new Document(object, end);
  }

  /**
   * The index of the next document's opening brace at or after {@code from}, or -1 when none
   * remains. Prose ahead of a further brace is skipped, as the prose ahead of the first document
   * is; prose with no brace after it is discarded, and the discard is logged with its size.
   */
  private static int nextDocumentStart(String json, int from) {
    return nextDocumentStart(json, from, true);
  }

  /** {@link #nextDocumentStart(String, int)}, logging the discard only when {@code log} is set. */
  private static int nextDocumentStart(String json, int from, boolean log) {
    var at = from;
    while (at < json.length()) {
      var c = json.charAt(at);
      if (c == '{') {
        return at;
      }
      if (json.startsWith("```", at)) {
        // The marker and its language tag only — a document opened on the same line still counts.
        at += 3;
        while (at < json.length() && Character.isLetterOrDigit(json.charAt(at))) {
          at++;
        }
      } else if (Character.isWhitespace(c)) {
        at++;
      } else {
        var brace = json.indexOf('{', at);
        if (brace < 0) {
          if (!log) {
            return -1;
          }
          Log.warnf(
              "Review response continued past its last JSON document with %d characters that hold"
                  + " no further document; discarded them",
              json.length() - at);
          return -1;
        }
        at = brace;
      }
    }
    return -1;
  }

  /**
   * Folds a later document into {@code root}. Findings append in document order, and a finding
   * object identical to one already held is kept once; every other top-level field keeps the first
   * non-null value it was given, so two documents that disagree resolve the same way every time.
   */
  private static void mergeInto(ObjectNode root, ObjectNode document, MergeTally tally) {
    for (var entry : document.properties()) {
      var value = entry.getValue();
      if (FINDINGS.equals(entry.getKey())
          && value.isArray()
          && root.get(FINDINGS) instanceof ArrayNode findings) {
        // JsonNode equality and hashing are structural, so the set is the "identical object" test.
        var held = new HashSet<JsonNode>();
        findings.forEach(held::add);
        for (var finding : value) {
          if (held.add(finding)) {
            findings.add(finding);
            tally.appended++;
          } else {
            tally.duplicates++;
          }
        }
      } else if (!root.hasNonNull(entry.getKey())) {
        root.set(entry.getKey(), value);
      } else {
        tally.conflicts++;
      }
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
   * Models sometimes write the summary's fields straight onto the root with no {@code summary}
   * object around them: 24 of the 34 summary responses production refused in one window (#850).
   * Jackson maps none of those fields onto {@link ReviewResponse}, so the summary reads as null and
   * the walkthrough degrades to counts only. When the root holds no {@code summary} object but does
   * carry fields {@link ReviewResponse.Summary} declares, move them into one. A root that already
   * holds a {@code summary} object keeps it whole: the fields beside it are a second answer that
   * may disagree with the first, so they are not merged in and are ignored like any other unknown
   * root field. Summary lane only, and run ahead of the other normalizers so a folded summary is
   * normalized exactly as a wrapped one is.
   */
  private void foldSummaryFields(ObjectNode root) {
    if (root.get(SUMMARY) instanceof ObjectNode) {
      return;
    }
    var summary = mapper.createObjectNode();
    for (var name : summaryFields) {
      if (root.has(name)) {
        summary.set(name, root.remove(name));
      }
    }
    if (summary.isEmpty()) {
      return;
    }
    Log.infof(
        "Summary response wrote %d summary field(s) on its root with no summary object; folded them"
            + " into one",
        summary.size());
    root.set(SUMMARY, summary);
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
   *
   * <p>When nothing is recovered the warning also carries the shape that arrived, because by then
   * nothing else does: the session row stores the response after this rewrite, so its {@code
   * file_summaries} reads {@code []} and a blank Changed Files table cannot be explained after the
   * fact (#872). The node itself stays out of the row — it is the whole walkthrough's worth of
   * model prose on a row already holding the response, for a question the node type and the first
   * entry's field names answer.
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
    logUnrecovered(fileSummaries, normalized, seen);
    summary.set(FILE_SUMMARIES, normalized);
  }

  /**
   * What the recovery made of the {@code seen} entries it walked. A recovery that saved some of
   * them logs the counts and no more: describing the miss would put a shape in the log of every
   * review one stray entry appears in. A recovery that saved none describes what arrived, because
   * by then this line is the only record of it (#872).
   */
  private static void logUnrecovered(JsonNode fileSummaries, ArrayNode normalized, int seen) {
    if (normalized.isEmpty()) {
      Log.warnf(
          "Review response file_summaries did not match the schema — recovered nothing from %d"
              + " entr%s, so the walkthrough renders with no summaries; it arrived as %s, first"
              + " entry %s",
          seen,
          seen == 1 ? "y" : "ies",
          fileSummaries.getNodeType(),
          describeEntry(firstEntry(fileSummaries)));
      return;
    }
    Log.warnf(
        "Review response file_summaries did not match the schema — recovered %d entr%s and dropped"
            + " %d; unrecovered entries render as blank walkthrough rows",
        normalized.size(), normalized.size() == 1 ? "y" : "ies", seen - normalized.size());
  }

  /**
   * The entry the drop diagnostic describes: element zero of an array, the first property's value
   * of the map form, and the node itself for a single entry or for a scalar. It is read only when
   * nothing was recovered, so whichever it returns is one of the dropped entries, and a
   * non-conforming array always has the element zero it reads (a conforming one — an empty array
   * among them — returns before this point).
   */
  private static JsonNode firstEntry(JsonNode fileSummaries) {
    if (fileSummaries.isArray()) {
      return fileSummaries.get(0);
    }
    if (looksLikeFileSummary(fileSummaries)) {
      return fileSummaries;
    }
    return fileSummaries.properties().stream()
        .findFirst()
        .map(Map.Entry::getValue)
        .orElse(fileSummaries);
  }

  /**
   * One dropped entry's node type and field names, which is what tells an unknown key apart from a
   * non-textual value or a level of nesting nobody expected — the three ways every entry can fall
   * outside {@code PATH_KEYS}/{@code SUMMARY_KEYS} at once (#872).
   *
   * <p>Names only, never values: a name is all it takes to decide whether the key lists should
   * grow, while a value carries the model's prose about the diff. The model chooses those names, so
   * each one is flattened by {@link LogSafe} on its way into the record, and both how many are
   * listed and how long each may be are bounded — an entry is free to carry a thousand keys or one
   * key a megabyte long.
   */
  private static String describeEntry(JsonNode entry) {
    var names = new ArrayList<String>();
    for (var property : entry.properties()) {
      if (names.size() == MAX_LOGGED_FIELD_NAMES) {
        break;
      }
      names.add(shortened(LogSafe.oneLine(property.getKey())));
    }
    if (names.isEmpty()) {
      return entry.getNodeType() + " with no fields";
    }
    var described = entry.getNodeType() + " with field(s) " + names;
    var unlisted = entry.size() - names.size();
    if (unlisted > 0) {
      described += " and " + unlisted + " more";
    }
    return described;
  }

  /** One field name cut to the length a log line carries it at. */
  private static String shortened(String name) {
    return name.length() <= MAX_LOGGED_FIELD_NAME_CHARS
        ? name
        : name.substring(0, MAX_LOGGED_FIELD_NAME_CHARS) + "…";
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

  /**
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
   * The root keys of a review response: an object that opens on one of them is the answer. Every
   * batch and summary response carries at least one, and the shapes it is up against — prose, a
   * severity tag, a quoted Swift or diff excerpt — carry none.
   */
  static final List<String> REVIEW_ROOT_KEYS = List.of(FINDINGS, PREVIOUS_FINDINGS_STATUS, SUMMARY);

  /** A fence marker, its optional language tag, and nothing else but whitespace. */
  private static final Pattern FENCE_OPENER = Pattern.compile("```[\\w+-]*\\s*");

  /** What a probe finds when the input ends inside the document it reads. */
  private static final int CUT = -1;

  /** What a probe finds when the document it reads does not parse. */
  private static final int BROKEN = -2;

  /** The start of a {@code true}, {@code false} or {@code null} literal, or nothing at all. */
  private static final Pattern LITERAL_PREFIX =
      Pattern.compile("(?:t(?:r(?:ue?)?)?|f(?:a(?:l(?:se?)?)?)?|n(?:u(?:ll?)?)?)?");

  /** Where a JSON object with at least one key may open: a brace, whitespace, then a quote. */
  private static final Pattern OBJECT_START = Pattern.compile("\\{\\s*\"");

  /**
   * Parser features for the answer probe in {@link #extractJson(String, List)}: a raw control
   * character inside a string is escaped before the real parse, so the probe must not fail on one.
   */
  private static final JsonFactory PROBE_FACTORY =
      JsonFactory.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build();

  /**
   * Like {@link #extractJson(String)}, but anchored on the answer rather than on the first bracket
   * in the body (#894). The first-bracket rule is a guess that gets worse the more prose a response
   * carries: a 256,302-character production response opened with deliberation whose first {@code [}
   * was a {@code [LOW]} severity tag at index 242, 252,138 characters ahead of the answer, so both
   * the parse and the truncation salvage started there and a complete findings array was discarded.
   * Deliberation quotes code, so a body with a brace or bracket ahead of its answer is the ordinary
   * case, not the odd one.
   *
   * <p>The answer is what the model wrote last, so it is recognized as the tail of the body: the
   * anchor is the earliest object from which the rest of the body reads as a run of JSON documents
   * to its end — separated only by whitespace, fence markers and brace-free prose, as {@link
   * #readDocuments} reads them, with trailing brace-free prose allowed and a cut inside the last
   * document counting as its end — and whose first document opens on, or holds, one of {@code
   * rootKeys}. That is decided by where the object sits and what it says, not by how it is wrapped,
   * which a fence cannot decide: deliberation fences its excerpts too, sometimes as {@code
   * ```json}; a split response (#805) spreads its answer over several fences, so the last fence
   * would lose all but the final document; a cut response has no closing fence; and an answer may
   * carry no fence at all. The earliest such object rather than the last, because a split
   * response's earlier documents are part of the answer.
   *
   * <p>What that rules out, and why each matters:
   *
   * <ul>
   *   <li>a {@code [LOW]} tag, a {@code {placeholder}} or a Swift closure in the prose: none of
   *       them opens a JSON object;
   *   <li>a fenced JSON excerpt of a file under review, or a contract-shaped recap of an earlier
   *       round quoted in the deliberation: the prose after it holds braces that do not parse, so
   *       the run breaks before the end — a recap taken for the answer would publish that round's
   *       findings again;
   *   <li>an object nested inside the answer's root, such as a {@code file_summaries} entry written
   *       {@code {"summary": "...", "path": "..."}} under the summary lane's unwrapped root (#850),
   *       or the {@code summary} under {@code {"analysis": {"summary": {...}}, "findings": [...]}}:
   *       the root opens earlier and reads to the end itself, whole or cut, so it is chosen first.
   * </ul>
   *
   * <p>When no object satisfies that — the answer is followed by prose holding a brace, say — the
   * anchor is the first object opening on a root key, so {@link #readDocuments} still reads the
   * answer and raises its usual error on what follows. When no object opens on a root key at all,
   * this is {@link #extractJson(String)} unchanged, so every other tolerated form reads as it
   * always has. A response wrapped whole in one fence whose content opens on the anchor keeps
   * having its closing fence cut as that method cuts it.
   *
   * <p>Each document start is walked at most once across all candidates, and a candidate inside a
   * complete object already probed is skipped, so the probe stays close to one pass over the body.
   */
  static String extractJson(String raw, List<String> rootKeys) {
    if (raw == null) {
      return "";
    }
    var trimmed = raw.strip();
    var rootKey = rootAnchorPattern(rootKeys).matcher(trimmed);
    if (!rootKey.find()) {
      return extractJson(raw);
    }
    var firstRootObject = rootKey.start();
    var anchor = answerStart(trimmed, rootKey);
    if (anchor < 0) {
      anchor = firstRootObject;
    }
    var end = trimmed.length();
    var close = trimmed.lastIndexOf("```");
    if (close > anchor && opensTheWholeFence(trimmed, anchor)) {
      end = close;
    }
    return escapeControlCharsInStrings(trimmed.substring(anchor, end).strip());
  }

  /**
   * The earliest object start from which {@code text} reads as the answer (see {@link
   * #extractJson(String, List)}), or -1 when none does.
   */
  private static int answerStart(String text, Matcher rootKey) {
    var dead = new HashSet<Integer>();
    var chars = text.toCharArray();
    var candidates = OBJECT_START.matcher(text);
    var skipUntil = 0;
    while (candidates.find()) {
      var start = candidates.start();
      if (start < skipUntil) {
        continue;
      }
      var probe = probeAnswer(text, chars, start, rootKey, dead);
      if (probe.answer()) {
        return start;
      }
      skipUntil = Math.max(skipUntil, probe.firstEnd());
    }
    return -1;
  }

  /**
   * What probing one candidate found: whether the body reads as the answer from it, and where its
   * first document closed (-1 when it did not parse).
   */
  private record Probe(boolean answer, int firstEnd) {}

  /**
   * Walks the documents from {@code start} to the end of {@code text}. Every document start the
   * walk passes through on a run that breaks is added to {@code dead}: any later candidate reaching
   * one follows the same run and breaks the same way.
   */
  private static Probe probeAnswer(
      String text, char[] chars, int start, Matcher rootKey, Set<Integer> dead) {
    var visited = new ArrayList<Integer>();
    var firstEnd = -1;
    var at = start;
    while (!dead.contains(at)) {
      visited.add(at);
      var end = documentEnd(chars, at);
      if (end == CUT) {
        // Cut inside this document: the run reached the end of the body.
        return new Probe(firstEnd >= 0 || holdsRootKey(rootKey, start, text.length()), firstEnd);
      }
      if (end == BROKEN) {
        break;
      }
      if (firstEnd < 0) {
        firstEnd = end;
        if (!holdsRootKey(rootKey, start, end)) {
          break;
        }
      }
      at = nextDocumentStart(text, end, false);
      if (at < 0) {
        return new Probe(true, firstEnd);
      }
    }
    dead.addAll(visited);
    return new Probe(false, firstEnd);
  }

  /**
   * The index just past the JSON object opening at {@code start}; {@link #CUT} when the input ends
   * inside it, {@link #BROKEN} when it does not parse.
   */
  private static int documentEnd(char[] chars, int start) {
    try (var parser = PROBE_FACTORY.createParser(chars, start, chars.length - start)) {
      parser.nextToken();
      parser.skipChildren();
      return start + (int) parser.currentLocation().getCharOffset();
    } catch (IOException e) {
      return endsAtTheCut(e, chars, start) ? CUT : BROKEN;
    }
  }

  /**
   * Whether a parse of the object at {@code start} failed only because the input ended. Jackson
   * says so in more than one way: an end inside a string or before a closing brace raises {@link
   * com.fasterxml.jackson.core.io.JsonEOFException} at the end of input, an end just after a comma
   * raises a plain parse error there, and an end inside {@code true}, {@code false} or {@code null}
   * raises one at the start of the partial literal. So the test is what lies past the error's
   * location: nothing, or the start of a literal. Anything but a parse error with a location is not
   * a cut; the probe's input is an in-memory array, so no other failure is expected.
   */
  static boolean endsAtTheCut(IOException failure, char[] chars, int start) {
    if (!(failure instanceof JsonProcessingException parseError)
        || parseError.getLocation() == null) {
      return false;
    }
    var at = start + (int) parseError.getLocation().getCharOffset();
    return LITERAL_PREFIX.matcher(CharBuffer.wrap(chars, at, chars.length - at)).matches();
  }

  /** Whether an object opening on a root key starts within {@code [from, to)}. */
  private static boolean holdsRootKey(Matcher rootKey, int from, int to) {
    return rootKey.region(from, to).find();
  }

  /**
   * Whether {@code text} is wrapped in a fence whose content opens at {@code anchor}, so its last
   * fence marker is the answer's closing one. A body that opens on a fenced excerpt (a {@code
   * ```swift} block quoted in deliberation) is not: its last marker may be the answer's opening
   * fence of a cut response, and cutting there would drop the answer.
   */
  private static boolean opensTheWholeFence(String text, int anchor) {
    return FENCE_OPENER.matcher(text.substring(0, anchor)).matches();
  }

  /** An object whose first key is one of {@code rootKeys}. */
  private static Pattern rootAnchorPattern(List<String> rootKeys) {
    var alternatives = new StringJoiner("|", "(?:", ")");
    rootKeys.forEach(key -> alternatives.add(Pattern.quote(key)));
    return Pattern.compile("\\{\\s*\"" + alternatives + "\"\\s*:");
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
