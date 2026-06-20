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

/** Escapes user-provided prompt fragments before they are bound into a LangChain4j prompt. */
public final class PromptTemplateEscaper {

  private PromptTemplateEscaper() {}

  /**
   * Prepares untrusted content (a diff, a PR description, a maintainer's question, a prior finding)
   * for a prompt. The AI-service templates reference it through a Qute {@code @V} variable, and
   * quarkus-langchain4j binds {@code @V} values as <em>template data</em>: their string value is
   * inserted as-is and is <strong>not</strong> re-parsed as Qute. So the content already reaches
   * the model byte-exact — braces, backslashes, {@code {#if}} / {@code {config:x}} expression
   * syntax, everything renders verbatim and is never interpreted. (Verified end-to-end against the
   * real engine by AiServicePromptRenderingTest.)
   *
   * <p>The one transformation still required is neutralizing spoofed copies of the {@code
   * <<<DIFF_START>>>} / {@code <<<DIFF_END>>>} delimiters the prompts wrap the diff in, so PR
   * content can never fake the end of the diff section and smuggle in instructions after it.
   *
   * <p>This used to additionally wrap the value in a Qute unparsed section ({@code {|...|}}) to
   * survive a second Qute pass. That pass does not happen for data-bound variables — the wrapper
   * leaked into the prompt literally and corrupted any content containing {@code |}} — so it was
   * removed once the templates moved their {@code @UserMessage} to the method (where {@code @V}
   * variables are interpolated rather than the first parameter being rendered as a template
   * itself).
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
    return neutralizeMarkers(value);
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
