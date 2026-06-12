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
package dev.thiagogonzaga.thrillhousebot.review;

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic guard against findings that quote code which is not in the diff. The model
 * occasionally reviews a paraphrase of the change instead of the change itself (historically also a
 * symptom of prompt-pipeline corruption) and then flags defects in code nobody wrote. Dogfooding
 * examples: a critical "broken type inference" finding whose suggested fix was byte-identical to
 * the committed line, and two critical "invalid syntax" findings quoting single-brace expressions
 * where the file has double braces.
 *
 * <p>The check is conservative: a finding is dropped only when none of its quoted lines exist in
 * the diff (pure phantom). When the quote is partially wrong, the unreliable suggestion block is
 * removed and confidence is capped at "low" so the finding can still surface but cannot block.
 */
@ApplicationScoped
public class FindingQuoteValidator {

  private static final String LOW_CONFIDENCE = "low";

  /** Validates each finding's quoted code against the raw (unescaped) diff text. */
  public ReviewResponse validate(ReviewResponse response, String diff) {
    if (response.findings().isEmpty() || diff == null || diff.isBlank()) {
      return response;
    }
    DiffIndex index = indexDiff(diff);
    var kept = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      QuoteMatch match = matchQuote(finding.suggestionOld(), index.linesFor(finding.file()));
      switch (match) {
        case FULL, NO_QUOTE -> kept.add(finding);
        case PARTIAL -> {
          Log.infof(
              "Finding '%s' (%s:%d) quotes code only partially present in the diff —"
                  + " dropping its suggestion and capping confidence",
              finding.title(), finding.file(), finding.line());
          kept.add(withoutSuggestion(finding));
          changed = true;
        }
        case NONE -> {
          Log.infof(
              "Dropping finding '%s' (%s:%d) — the code it quotes does not appear in the diff",
              finding.title(), finding.file(), finding.line());
          changed = true;
        }
      }
    }
    if (!changed) {
      return response;
    }
    return new ReviewResponse(
        kept,
        response.previousFindingsStatus(),
        FindingVerificationService.recount(response.summary(), kept));
  }

  enum QuoteMatch {
    /** Finding has no suggestion_old to check. */
    NO_QUOTE,
    /** Every quoted line is present in the diff. */
    FULL,
    /** Some quoted lines are present, others are not. */
    PARTIAL,
    /** No quoted line is present in the diff. */
    NONE
  }

  static QuoteMatch matchQuote(String suggestionOld, Set<String> diffLines) {
    List<String> quoted = normalizedLines(suggestionOld);
    if (quoted.isEmpty()) {
      return QuoteMatch.NO_QUOTE;
    }
    long present = quoted.stream().filter(diffLines::contains).count();
    if (present == quoted.size()) {
      return QuoteMatch.FULL;
    }
    return present == 0 ? QuoteMatch.NONE : QuoteMatch.PARTIAL;
  }

  /**
   * The diff's normalized body lines, both as one union and scoped per file. A quote is checked
   * against its finding's own file when that file is identifiable in the diff — the same text
   * appearing in some other file's hunk must not validate a misattributed finding. The union is the
   * conservative fallback for findings whose file cannot be matched to a diff section.
   */
  record DiffIndex(Map<String, Set<String>> byFile, Set<String> all) {

    Set<String> linesFor(String file) {
      if (file == null || file.isBlank()) {
        return all;
      }
      Set<String> exact = byFile.get(file);
      if (exact != null) {
        return exact;
      }
      // The model sometimes shortens or expands paths; suffix matches still scope. An
      // ambiguous name (Handler.java in several modules) scopes to the union of its
      // candidates, never to the whole diff — a quote from an unrelated file must not
      // validate just because the finding's path was ambiguous.
      var candidates =
          byFile.keySet().stream()
              .filter(k -> k.endsWith("/" + file) || file.endsWith("/" + k))
              .toList();
      if (candidates.isEmpty()) {
        return all;
      }
      var scoped = new HashSet<String>();
      for (String candidate : candidates) {
        scoped.addAll(byFile.get(candidate));
      }
      return scoped;
    }
  }

  /**
   * Diff body lines normalized for comparison: the unified-diff marker column (+, -, space) is
   * removed and surrounding whitespace trimmed, so quoted code matches regardless of indentation
   * shifts or which side of the change it sits on. The lines also get the same marker
   * neutralization the prompt pipeline applies, since the model quotes from the neutralized text.
   * File scoping follows the formatter's "### filename (status, +A -D)" section headers.
   */
  static DiffIndex indexDiff(String diff) {
    var byFile = new HashMap<String, Set<String>>();
    var all = new HashSet<String>();
    Set<String> current = null;
    for (String raw : PromptTemplateEscaper.neutralizeMarkers(diff).split("\n", -1)) {
      if (raw.startsWith("### ")) {
        current = byFile.computeIfAbsent(sectionFilename(raw), k -> new HashSet<>());
      } else if (!isDiffStructureLine(raw)) {
        String normalized = bodyOf(raw).strip();
        if (!normalized.isEmpty()) {
          all.add(normalized);
          if (current != null) {
            current.add(normalized);
          }
        }
      }
    }
    return new DiffIndex(byFile, all);
  }

  /** Hunk headers, file headers, and code fences are diff plumbing, never quotable content. */
  private static boolean isDiffStructureLine(String raw) {
    return raw.startsWith("+++")
        || raw.startsWith("---")
        || raw.startsWith("@@")
        || raw.startsWith("```");
  }

  /** The line without its unified-diff marker column (+, -, space). */
  private static String bodyOf(String raw) {
    if (!raw.isEmpty() && (raw.charAt(0) == '+' || raw.charAt(0) == '-' || raw.charAt(0) == ' ')) {
      return raw.substring(1);
    }
    return raw;
  }

  private static String sectionFilename(String headerLine) {
    String name = headerLine.substring(4);
    int suffix = name.lastIndexOf(" (");
    return (suffix > 0 ? name.substring(0, suffix) : name).strip();
  }

  private static List<String> normalizedLines(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    var lines = new ArrayList<String>();
    for (String raw : text.split("\n", -1)) {
      String normalized = raw.strip();
      if (!normalized.isEmpty()) {
        lines.add(normalized);
      }
    }
    return lines;
  }

  private static ReviewResponse.Finding withoutSuggestion(ReviewResponse.Finding finding) {
    return new ReviewResponse.Finding(
        finding.risk(),
        LOW_CONFIDENCE,
        finding.file(),
        finding.line(),
        finding.title(),
        finding.description(),
        null,
        null);
  }
}
