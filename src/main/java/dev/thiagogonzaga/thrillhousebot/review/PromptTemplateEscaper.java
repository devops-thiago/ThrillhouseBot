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

import java.security.SecureRandom;
import java.util.HexFormat;

/** Escapes user-provided prompt fragments before they are bound into a LangChain4j prompt. */
public final class PromptTemplateEscaper {

  // The prefix is fixed (the prompt names it); only the random suffix makes a fence unforgeable.
  private static final String FENCE_PREFIX = "[[THRILLHOUSEBOT-UNTRUSTED-DATA-";
  private static final String FENCE_SUFFIX = "]]";
  private static final SecureRandom RANDOM = new SecureRandom();

  private PromptTemplateEscaper() {}

  /** The fixed prefix of a diff fence line, named in the prompts so the model recognizes it. */
  public static String fencePrefix() {
    return FENCE_PREFIX;
  }

  /**
   * Wraps untrusted code (the diff) between two identical, per-call random fence lines so the model
   * can separate data from instructions. The fence token is drawn from a CSPRNG, so PR content
   * cannot reproduce the boundary; content between the fences is passed <em>byte exact</em> rather
   * than through {@link #neutralizeMarkers}, which would rewrite marker-like sequences in the
   * reviewed code. This is the "random sequence enclosure" / Microsoft "spotlighting" delimiting
   * defense.
   *
   * <p>Empty content is returned unchanged so a {@code {#if}} section around it stays falsy.
   */
  public static String fence(String content) {
    if (content == null || content.isEmpty()) {
      return content;
    }
    var bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    String fenceLine = FENCE_PREFIX + HexFormat.of().formatHex(bytes) + FENCE_SUFFIX;
    return fenceLine + "\n" + content + "\n" + fenceLine;
  }

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
