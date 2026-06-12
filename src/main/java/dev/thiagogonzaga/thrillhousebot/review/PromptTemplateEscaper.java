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

/** Escapes user-provided prompt fragments before LangChain4j/Qute template rendering. */
public final class PromptTemplateEscaper {

  private PromptTemplateEscaper() {}

  /**
   * Wraps user-provided content in a Qute unparsed section ({@code {|...|}}) so the model receives
   * it byte-exact after LangChain4j substitutes it into the prompt template and Qute renders the
   * result. Also neutralizes spoofed copies of the section delimiters the prompts wrap the diff in
   * — PR content must never be able to fake the end of the diff section and inject instructions.
   *
   * <p>Character escaping is unsalvageable here: Qute consumes one backslash before a brace but
   * passes other backslashes through verbatim, so the previous brace-and-backslash escaping showed
   * the model {@code "\\n"} wherever the source said {@code "\n"} (it kept "correcting" it), and
   * odd-length backslash runs before a brace cannot be represented at all. Unparsed sections
   * round-trip everything; internal {@code |}} terminators are emitted as plain text between two
   * sections. Pinned against the real Qute engine by PromptTemplateEscaperQuteTest.
   *
   * <p>Self-referential edge: code that itself contains the three-bracket marker strings — this
   * class, its tests, the prompt templates — necessarily renders with them neutralized, so the
   * model reviews a slightly altered copy of exactly this one pattern and may misread the
   * replacement below as an identity operation. That corruption is the cost of the injection
   * defense and is intentionally accepted.
   */
  public static String escape(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    return "{|" + neutralizeMarkers(value).replace("|}", "|}|}{|") + "|}";
  }

  /**
   * The marker neutralization applied to all prompt content. The model only ever sees content in
   * this form, so anything comparing model output against raw content (the quote validator) must
   * pass the raw side through the same transformation.
   */
  public static String neutralizeMarkers(String value) {
    // Must match the markers in PrReviewPrompts.USER and FindingVerifierPrompts.USER
    return value
        .replace("<<<DIFF_START>>>", "<<DIFF_START>>")
        .replace("<<<DIFF_END>>>", "<<DIFF_END>>");
  }
}
