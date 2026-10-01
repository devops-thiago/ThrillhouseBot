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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes the summary prompt's carried-gap labels from description gaps (#970). The summary call
 * sees the gaps an earlier round listed as "G1: …", "G2: …" (#923) so it can name the resolved ones
 * in {@code addressed_gaps}; a model that echoes a label into {@code description_gaps} would put
 * "G1:" — or a bullet that is only "G1" — in front of a reviewer who never saw the prompt.
 *
 * <p>Only a leading label in the prompt's shapes is removed: "G1:", "G1 — …", "[G1]", "(G1)", or a
 * gap that is nothing but a label, which is dropped. A bare "G1" followed by more words is left
 * alone, because that is also how a gap about the G1 garbage collector starts, and a gap that
 * starts with an ordinary word ("Gateway retries …") never matches. {@code addressed_gaps} is not
 * touched: there the labels are the answer.
 */
public final class DescriptionGapLabels {

  private DescriptionGapLabels() {}

  /**
   * A leading carried-gap label: bracketed or parenthesized, with an optional colon or dash after
   * it; or bare, followed by a colon, a dash that ends the gap or is followed by a space, or the
   * end of the gap.
   */
  private static final Pattern LEADING_LABEL =
      Pattern.compile(
          "^\\s*(?:(?:\\[\\s*G\\d{1,3}\\s*]|\\(\\s*G\\d{1,3}\\s*\\))\\s*(?:[:\\-–—]\\s*)?"
              + "|G\\d{1,3}\\s*(?::\\s*|[-–—](?:\\s+|$)|$))");

  /**
   * {@code gaps} in order, each without its leading label; a gap that was only a label is dropped.
   * Every other gap, a blank one included, is kept as it is.
   */
  public static List<String> stripAll(List<String> gaps) {
    var stripped = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      var label = LEADING_LABEL.matcher(gap);
      if (!label.find()) {
        stripped.add(gap);
        continue;
      }
      var text = gap.substring(label.end()).strip();
      if (!text.isEmpty()) {
        stripped.add(text);
      }
    }
    return stripped;
  }
}
