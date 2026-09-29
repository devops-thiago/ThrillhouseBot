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

import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import java.io.Serializable;
import java.util.function.UnaryOperator;

/**
 * What a length stop actually was, in the figures an operator can compare with their settings: the
 * {@linkplain ResponseCap cap the request licensed} and the setting that supplied it, the
 * completion tokens the provider billed, and the prompt tokens beside them (#895).
 *
 * <p>The figures decide the advice. Raising the cap only helps when the billed completion reached
 * it; a provider can stop a call well short of what the request licensed (production billed exactly
 * 65,536 completion tokens against {@code max_tokens=96000}, on a model that finished another call
 * the same day at 68,321), and a higher setting does not move a bound the provider imposes. So the
 * knob is named as the remedy only when the stop reached the cap, and a stop that fell short says
 * it was a provider-side bound instead. Without usage from the provider there is nothing to
 * compare, and the report falls back to the lane's standing advice while saying the usage was not
 * reported.
 *
 * <p>Every rendering takes a {@code code} formatter so the same sentences serve the log (plain) and
 * the posted comment (setting names in backticks) without the wording drifting between them.
 *
 * @param lane the model binding the cut call ran on
 * @param cap the cap the request licensed, or {@code null} when the raising site did not record one
 * @param promptTokens the provider-reported prompt tokens, or {@code null} when not reported
 * @param completionTokens the provider-reported completion tokens, or {@code null} when not
 *     reported
 */
public record TruncationReport(
    ModelLane lane, ResponseCap cap, Integer promptTokens, Integer completionTokens)
    implements Serializable {

  /** Formats nothing: the log line's rendering. */
  public static final UnaryOperator<String> PLAIN = UnaryOperator.identity();

  /** Wraps setting names as inline code: the posted comment's rendering. */
  public static final UnaryOperator<String> MARKDOWN = s -> "`" + s + "`";

  /** How the stop relates to the licensed cap, as far as the figures on hand can tell. */
  public enum Stop {
    /** The provider reported no usage, so the stop cannot be compared with the cap. */
    USAGE_UNREPORTED,
    /** Usage was reported but the raising site recorded no cap to compare it with. */
    CAP_UNRECORDED,
    /** The request carried no {@code max_tokens}; the provider's own default bounded the call. */
    NO_CAP_SENT,
    /** The billed completion reached the licensed cap: raising it is the remedy. */
    AT_CAP,
    /** The provider stopped short of the licensed cap: a higher setting will not move it. */
    SHORT_OF_CAP
  }

  /** Classifies the stop from the figures on hand. */
  public Stop stop() {
    if (completionTokens == null) {
      return Stop.USAGE_UNREPORTED;
    }
    if (cap == null) {
      return Stop.CAP_UNRECORDED;
    }
    if (cap.tokens() == null) {
      return Stop.NO_CAP_SENT;
    }
    return completionTokens >= cap.tokens() ? Stop.AT_CAP : Stop.SHORT_OF_CAP;
  }

  /**
   * Whether the report tells the operator to change a setting — every stop except one that fell
   * short of the licensed cap, where no setting on this side moves the bound. Renderers use it to
   * decide whether "then run /review again" follows the remedy.
   */
  public boolean advisesASettingChange() {
    return stop() != Stop.SHORT_OF_CAP;
  }

  /**
   * The figures as one clause: the licensed cap and its setting, then the billed usage — for
   * example {@code licensed max_tokens=96000 (from X); billed 65536 completion tokens, 163342
   * prompt tokens}.
   */
  public String figures(UnaryOperator<String> code) {
    return capFigure(code) + "; " + usageFigure();
  }

  private String capFigure(UnaryOperator<String> code) {
    if (cap == null) {
      return "licensed cap not recorded";
    }
    if (cap.tokens() == null) {
      return "no max_tokens sent ("
          + code.apply(cap.setting())
          + " "
          + lane.noCapState()
          + ", so the provider default applied)";
    }
    return "licensed max_tokens=" + cap.tokens() + " (from " + code.apply(cap.setting()) + ")";
  }

  private String usageFigure() {
    if (completionTokens == null) {
      return "usage not reported by the provider";
    }
    return "billed "
        + completionTokens
        + " completion tokens, "
        + (promptTokens == null ? "prompt tokens not reported" : promptTokens + " prompt tokens");
  }

  /**
   * What the operator can do about it, as full sentences: raise the setting only when the stop
   * reached it, and say plainly when it was a provider-side bound that no setting moves.
   */
  public String remedy(UnaryOperator<String> code) {
    return switch (stop()) {
      case USAGE_UNREPORTED, CAP_UNRECORDED -> lane.remedy(code);
      case NO_CAP_SENT ->
          "No cap was sent, so the provider's own default stopped the call at "
              + completionTokens
              + " completion tokens; setting "
              + code.apply(cap.setting())
              + " above that licenses more, if the model allows it.";
      case AT_CAP ->
          "The stop reached the licensed cap, so raise "
              + code.apply(cap.setting())
              + lane.capNote(code)
              + ", or "
              + lane.dropCapClause()
              + ".";
      case SHORT_OF_CAP ->
          "The provider stopped "
              + (cap.tokens() - completionTokens)
              + " tokens short of the licensed cap: that is a provider-side bound, and raising "
              + code.apply(cap.setting())
              + " will not move it.";
    };
  }

  /** The figures and the remedy together, as the sentences every surface appends. */
  public String describe(UnaryOperator<String> code) {
    return "Cap and usage: " + figures(code) + ". " + remedy(code);
  }
}
