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
   * Legacy marker neutralization for untrusted content that is <em>not</em> individually {@link
   * #fence(String) fenced}. The AI-service templates reference such content through a Qute
   * {@code @V} variable, and quarkus-langchain4j binds {@code @V} values as <em>template data</em>:
   * their string value is inserted as-is and is <strong>not</strong> re-parsed as Qute. So the
   * content already reaches the model byte-exact — braces, backslashes, {@code {#if}} / {@code
   * {config:x}} expression syntax, everything renders verbatim and is never interpreted. (Verified
   * end-to-end against the real engine by AiServicePromptRenderingTest.)
   *
   * <p>This method is <strong>not</strong> the primary injection defense and is not sufficient on
   * its own: the data/instruction boundary for an untrusted block is {@link #fence(String)}, whose
   * per-call CSPRNG token PR content cannot forge. The review path now fences every untrusted prose
   * slot (see {@code ReviewPromptAssembler} / {@code PromptSections}); this method survives for the
   * remaining inline slots and for the on-request command generators that splice their prose
   * directly, where it only rewrites the fixed legacy {@code <<<DIFF_START>>>} / {@code
   * <<<DIFF_END>>>} marker strings — a residue of the older delimiter scheme — so those strings
   * cannot be mistaken for a boundary. No current prompt delimits anything with those markers.
   *
   * <p>Self-referential edge: code that itself contains the three-bracket marker strings — this
   * class, its tests, the prompt templates — renders with them neutralized when it flows through an
   * un-fenced slot, so the model reviews a slightly altered copy of exactly this one pattern. That
   * corruption is the cost of the legacy neutralization and is intentionally accepted.
   */
  public static String escape(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    return neutralizeMarkers(value);
  }

  /**
   * Rewrites the fixed legacy {@code <<<DIFF_START>>>} / {@code <<<DIFF_END>>>} marker strings so
   * un-fenced content cannot present them as a section boundary. This is the transform {@link
   * #escape(String)} applies.
   *
   * <p>It is deliberately <em>not</em> wired into the quote validator's raw side. The diff the
   * model reviews reaches it through {@link #fence(String)} <em>byte-exact</em> (never
   * neutralized), so {@code FindingQuoteValidator} indexes that same raw diff directly and the two
   * sides already agree; neutralizing the raw diff there would instead desynchronize a byte-exact
   * quote from its source. Only un-fenced prose/candidate slots pass through this method.
   */
  public static String neutralizeMarkers(String value) {
    return value
        .replace("<<<DIFF_START>>>", "<<DIFF_START>>")
        .replace("<<<DIFF_END>>>", "<<DIFF_END>>");
  }
}
