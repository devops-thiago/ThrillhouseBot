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

/**
 * The hidden lines by which a later round recognizes what an earlier one posted, whatever language
 * its visible text is in (#1260). Comments posted before the markers existed carry only the English
 * text, so every reader accepts that too.
 *
 * <p>Model text cannot spell a marker outside code: every {@code <} it carries is escaped.
 */
final class ReadBackMarkers {

  /** The line above a part of a review body that is the bot's own verdict prose. */
  static final String STATUS = "<!-- thrillhousebot:status-body v=1 -->";

  /** Opens a block naming previous findings: the delta section and the closed and open lists. */
  static final String PREVIOUS_START = "<!-- thrillhousebot:section=previous v=1 -->";

  /** Closes the block {@link #PREVIOUS_START} opens. */
  static final String PREVIOUS_END = "<!-- thrillhousebot:section-end=previous v=1 -->";

  /** The line above the summary's Description vs. Implementation heading. */
  static final String GAPS = "<!-- thrillhousebot:section=gaps v=1 -->";

  /**
   * Before and after the finding a gap bullet of its own names. The closing one ends the bullet,
   * which is where a reader looks: a bullet quoting the marker in code ends with the code span.
   */
  static final String GAP_FINDING = "<!-- thrillhousebot:gap=finding v=1 -->";

  /** At the end of a linked-issue criterion line that points at a finding. */
  static final String GAP_CRITERION_FINDING = "<!-- thrillhousebot:gap=criterion-finding v=1 -->";

  private ReadBackMarkers() {}

  /**
   * {@code text} marked as the bot's own verdict prose, or {@code text} itself when it is blank or
   * already opens with the marker.
   */
  static String status(String text) {
    return text.isBlank() || text.startsWith(STATUS) ? text : STATUS + "\n" + text;
  }

  /** Whether a rendered gap bullet names a finding, so is not a gap of its own. */
  static boolean namesFinding(String bullet) {
    return bullet.endsWith(GAP_FINDING) || bullet.endsWith(GAP_CRITERION_FINDING);
  }
}
