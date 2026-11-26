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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.review.ReviewResult.CiCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in procedural subsection for agents reading the pull request summary (#1442). It is
 * deterministic, carries no titles in the machine-readable manifest, and never makes a model call.
 */
final class AgentNextSteps {

  /** Visible heading, placed directly under {@link PrSummaryGenerator#SUMMARY_HEADING}. */
  static final String SECTION_HEADING = "### 🤖 Agent next steps";

  /**
   * Hidden line fingerprinting the head this summary was written for; paired with the prior edit.
   */
  static final String HEAD_MARKER_PREFIX = "<!-- thrillhousebot:head=";

  private static final String MARKER_SUFFIX = " -->";

  private static final Pattern HEAD_MARKER =
      Pattern.compile(
          Pattern.quote(HEAD_MARKER_PREFIX) + "([0-9a-fA-F]+)" + Pattern.quote(MARKER_SUFFIX));

  private static final int SHORT_SHA_LEN = 7;

  /** Cap on file:line rows and {@code resolved} hints so the section stays small. */
  static final int MAX_LISTED_FINDINGS = 10;

  private AgentNextSteps() {}

  static String headMarker(String commitSha) {
    if (commitSha == null || commitSha.isBlank()) {
      return "";
    }
    return HEAD_MARKER_PREFIX + commitSha.strip().toLowerCase(Locale.ROOT) + MARKER_SUFFIX;
  }

  static String parsePriorHead(String summaryBody) {
    if (summaryBody == null || summaryBody.isBlank()) {
      return null;
    }
    Matcher m = HEAD_MARKER.matcher(summaryBody);
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
  }

  /**
   * Inserts {@code section} immediately after the summary heading so it precedes the overview
   * paragraphs. The heading is not necessarily the first line: coverage, degradation and confidence
   * banners are prepended above it and stay there.
   */
  static String embedAfterHeading(String summaryMarkdown, String section) {
    if (section == null || section.isBlank()) {
      return summaryMarkdown;
    }
    if (summaryMarkdown == null || summaryMarkdown.isBlank()) {
      return section;
    }
    var heading = PrSummaryGenerator.SUMMARY_HEADING + "\n\n";
    var headingStart = summaryMarkdown.indexOf(heading);
    if (headingStart < 0) {
      return section + "\n\n" + summaryMarkdown;
    }
    var headingEnd = headingStart + heading.length();
    return summaryMarkdown.substring(0, headingEnd)
        + section
        + "\n\n"
        + summaryMarkdown.substring(headingEnd);
  }

  static String render(
      ReviewResult round,
      String commitSha,
      String priorHeadSha,
      PrSummaryGenerator.CurrentState state,
      String manifestLine,
      BotIdentity botIdentity) {
    var findings = state.result().findings();
    var sb = new StringBuilder();
    sb.append(SECTION_HEADING).append("\n\n");
    appendHead(sb, commitSha, priorHeadSha);
    appendOpenWork(sb, findings.size(), manifestLine, findings);
    appendCi(sb, round);
    appendSuggestedCommands(sb, round, findings, botIdentity);
    appendInteractionCheatsheet(sb, botIdentity);
    return sb.toString().stripTrailing();
  }

  private static void appendHead(StringBuilder sb, String commitSha, String priorHeadSha) {
    sb.append("**Head:** ");
    if (commitSha == null || commitSha.isBlank()) {
      sb.append("unknown\n\n");
      return;
    }
    var normalized = commitSha.strip().toLowerCase(Locale.ROOT);
    var shortSha =
        normalized.length() <= SHORT_SHA_LEN ? normalized : normalized.substring(0, SHORT_SHA_LEN);
    sb.append('`').append(shortSha).append('`');
    if (priorHeadSha == null) {
      sb.append(" (no prior head fingerprint on this pull request)");
    } else if (priorHeadSha.equalsIgnoreCase(normalized)) {
      sb.append(" (unchanged since last summary)");
    } else {
      sb.append(" (new since last summary)");
    }
    sb.append("\n\n");
  }

  private static void appendOpenWork(
      StringBuilder sb, int openCount, String manifestLine, List<Finding> findings) {
    sb.append("**Open findings:** ").append(openCount);
    if (openCount == 0) {
      sb.append("\n\n");
      return;
    }
    sb.append("\n");
    if (manifestHasEntries(manifestLine)) {
      sb.append(
          "- Parse the `thrillhousebot:findings` line at the top of this same comment for"
              + " `key`, `file`, `line`, `thread` and `carried`.\n");
    } else {
      int shown = 0;
      for (Finding f : findings) {
        if (shown >= MAX_LISTED_FINDINGS) {
          sb.append("- … and ")
              .append(openCount - shown)
              .append(" more (re-run `/review` after fixes).\n");
          break;
        }
        var key = SuggestionFormatter.findingKey(f.file(), f.line(), f.title());
        sb.append("- `")
            .append(MarkdownSafe.inlineCode(f.file()))
            .append(':')
            .append(f.line())
            .append("` (`key ")
            .append(key)
            .append(')');
        if (!f.postsInline()) {
          sb.append(" — double-check only");
        }
        sb.append('\n');
        shown++;
      }
    }
    sb.append('\n');
  }

  private static boolean manifestHasEntries(String manifestLine) {
    return manifestLine != null && !manifestLine.isBlank() && manifestLine.contains("{\"key\"");
  }

  private static void appendCi(StringBuilder sb, ReviewResult result) {
    sb.append("**CI:** ");
    if (result.offendingCiChecks().isEmpty()) {
      if (result.ciUnreadable()) {
        sb.append("status could not be read — confirm locally before merging.\n\n");
      } else {
        sb.append("required checks green.\n\n");
      }
      return;
    }
    sb.append("pending or failing — ");
    var names = new ArrayList<String>();
    for (CiCheck check : result.offendingCiChecks()) {
      names.add('`' + MarkdownSafe.inlineCode(check.name()) + '`');
    }
    sb.append(String.join(", ", names));
    sb.append(".\n\n");
  }

  private static void appendSuggestedCommands(
      StringBuilder sb, ReviewResult result, List<Finding> findings, BotIdentity botIdentity) {
    boolean ciGreen = result.offendingCiChecks().isEmpty() && !result.ciUnreadable();
    if (findings.isEmpty() && ciGreen) {
      return;
    }
    sb.append("**Suggested commands:**\n");
    if (!ciGreen) {
      sb.append("- Fix failing CI, then `/review`.\n\n");
      return;
    }
    var mention = botIdentity.primaryMention();
    sb.append("- After addressing findings: `/review` or @").append(mention).append(" review\n");
    int hints = 0;
    for (Finding f : findings) {
      if (f.postsInline()) {
        continue;
      }
      if (hints >= MAX_LISTED_FINDINGS) {
        break;
      }
      sb.append("- @")
          .append(mention)
          .append(" resolved `")
          .append(MarkdownSafe.inlineCode(f.file()))
          .append(':')
          .append(f.line())
          .append("` — ")
          .append(MarkdownSafe.inline(f.title()))
          .append('\n');
      hints++;
    }
    if (hints == 0) {
      sb.append("- To clear a finding: `@")
          .append(mention)
          .append(" resolved <path>:<line> — <title>` on this pull request.\n");
    }
    sb.append('\n');
  }

  /**
   * Fixed maintainer and agent reference. Stays deterministic so consumers need no repository
   * AGENTS.md or README.
   */
  private static void appendInteractionCheatsheet(StringBuilder sb, BotIdentity botIdentity) {
    var mention = botIdentity.primaryMention();
    sb.append("**")
        .append(MarkdownSafe.inline(botIdentity.displayName()))
        .append(" on this pull request** (comment on the PR");
    sb.append(" — write access required except `/help`):\n");
    sb.append("- `/review` or @").append(mention).append(" review — run or re-run a full review");
    sb.append(" after fixes or when you need a fresh pass on the current head\n");
    sb.append(
        "- `/summary` — post the one-per-PR summary comment again if it was deleted (no-op when one"
            + " is already present)\n");
    sb.append("- `/help` — list every command (any reviewer may run this)\n");
    sb.append("- `@")
        .append(mention)
        .append(" resolved <path>:<line> — <title>` — ")
        .append(
            "mark a finding settled when it has no inline thread; takes effect on the **next**"
                + " review, not immediately\n");
    sb.append("- `@")
        .append(mention)
        .append(" declined <path>:<line> — <title>` plus your reason — ")
        .append("justify keeping a finding open; evaluated on the next review\n");
    sb.append("- `/resolve` — resolve the bot's **inline review threads** only (different from @")
        .append(mention)
        .append(" resolved above)\n");
    sb.append(
            "- Pushes can auto-review when the bot is not paused and label/author gates allow; a manual")
        .append(" `/review` always runs\n");
    sb.append(
            "- Machine-readable open findings: the hidden `thrillhousebot:findings` line at the top of")
        .append(" this comment (`key`, `file`, `line`, `thread`, `carried`)\n");
  }
}
