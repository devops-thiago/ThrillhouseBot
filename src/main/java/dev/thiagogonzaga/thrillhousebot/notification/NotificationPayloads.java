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
package dev.thiagogonzaga.thrillhousebot.notification;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.thiagogonzaga.thrillhousebot.review.Finding;
import dev.thiagogonzaga.thrillhousebot.review.ReviewResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Renders a {@link ReviewOutcome} into the body of an outgoing webhook (#73).
 *
 * <p>Built on Jackson's tree model rather than bound records, so nothing here needs reflection
 * metadata in the native image and the wire shape is spelled out field by field in one place.
 *
 * <h2>What leaves the process</h2>
 *
 * By default only metadata: the event, a timestamp, the bot version, the repository, the pull
 * request number and head commit, the verdict and check conclusion, finding counts by severity, the
 * previous-round resolved/unresolved counts, whether the verdict is held on CI or covered only part
 * of the diff, the failure category, and the dashboard link. No code, diff text, finding prose,
 * model output or PR text is sent. {@link NotificationSettings#includeContent()} adds the PR title
 * and, per finding, its severity, file, line and title (at most {@value #MAX_CONTENT_FINDINGS}) —
 * never a finding's description or suggested code.
 */
public final class NotificationPayloads {

  /** Version of the JSON payload's shape; bumped only on a breaking change. */
  public static final int SCHEMA_VERSION = 1;

  /** Findings listed when content is opted in. */
  static final int MAX_CONTENT_FINDINGS = 25;

  /** Findings listed in a chat message when content is opted in. */
  static final int MAX_CHAT_FINDINGS = 5;

  /** Longest PR title or finding line a chat message carries. */
  static final int MAX_CHAT_LINE = 200;

  /** Discord's limit on a message's {@code content}. */
  static final int DISCORD_MAX_CONTENT = 2000;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final String FIELD_EVENT = "event";
  private static final String FIELD_TITLE = "title";

  private NotificationPayloads() {}

  /** The request body for {@code outcome} in the configured format, as UTF-8 JSON. */
  public static byte[] render(
      ReviewOutcome outcome,
      NotificationFormat format,
      boolean includeContent,
      Instant timestamp,
      String botVersion) {
    var node =
        switch (format) {
          case JSON -> json(outcome, includeContent, timestamp, botVersion);
          case SLACK -> slack(outcome, includeContent);
          case DISCORD -> discord(outcome, includeContent);
        };
    // JsonNode#toString renders valid JSON for a tree of plain nodes, with no checked exception.
    return node.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** The structured payload. Visible for tests. */
  static ObjectNode json(
      ReviewOutcome outcome, boolean includeContent, Instant timestamp, String botVersion) {
    var root = NODES.objectNode();
    root.put("schema_version", SCHEMA_VERSION);
    root.put(FIELD_EVENT, outcome.event().wireName());
    root.put("timestamp", timestamp.toString());
    var bot = root.putObject("bot");
    bot.put("name", "thrillhousebot");
    bot.put("version", botVersion);
    root.put("repository", outcome.repository());
    var pr = root.putObject("pull_request");
    pr.put("number", outcome.prNumber());
    pr.put("head_sha", outcome.headSha());
    pr.put("url", outcome.pullRequestUrl());
    if (includeContent && outcome.prTitle() != null) {
      pr.put(FIELD_TITLE, outcome.prTitle());
    }
    root.put("session_url", outcome.sessionUrl());
    if (outcome.result() != null) {
      root.set("review", review(outcome.result(), includeContent));
    }
    if (outcome.failure() != null) {
      root.putObject("failure").put("category", outcome.failure().wireName());
    }
    return root;
  }

  private static ObjectNode review(ReviewResult result, boolean includeContent) {
    var review = NODES.objectNode();
    review.put("verdict", result.reviewState().name());
    review.put("check_conclusion", result.reviewState().checkRunConclusion());
    review.put("highest_risk", Objects.toString(result.highestRisk(), null));
    review.put("held_on_ci", result.heldOnCiOnly());
    review.put("partial_coverage", result.truncated());
    review.put("first_review", result.isFirstReview());
    var counts = review.putObject("findings");
    counts.put("total", result.totalFindings());
    counts.put("critical", result.criticalCount());
    counts.put("high", result.highCount());
    counts.put("medium", result.mediumCount());
    counts.put("low", result.lowCount());
    var previous = review.putObject("previous_findings");
    previous.put("resolved", result.resolvedPreviousCount());
    previous.put("unresolved", result.unresolvedPreviousCount());
    if (includeContent) {
      var items = review.putArray("finding_list");
      for (var finding : listed(result, MAX_CONTENT_FINDINGS)) {
        var item = items.addObject();
        item.put("severity", Objects.toString(finding.risk(), null));
        item.put("file", finding.file());
        item.put("line", finding.line());
        item.put(FIELD_TITLE, finding.title());
      }
    }
    return review;
  }

  /** A Slack incoming-webhook message. Visible for tests. */
  static ObjectNode slack(ReviewOutcome outcome, boolean includeContent) {
    var text = new StringBuilder();
    var link = "<" + outcome.pullRequestUrl() + "|" + slackEscape(prLabel(outcome)) + ">";
    text.append(headline(outcome, link, "*"));
    for (var line : contentLines(outcome, includeContent)) {
      text.append("\n").append(slackEscape(line));
    }
    text.append("\n<").append(outcome.sessionUrl()).append("|Open the review session>");
    var root = NODES.objectNode();
    root.put("text", text.toString());
    return root;
  }

  /** A Discord webhook message, with every mention disabled. Visible for tests. */
  static ObjectNode discord(ReviewOutcome outcome, boolean includeContent) {
    var text = new StringBuilder();
    var link = "[" + prLabel(outcome) + "](<" + outcome.pullRequestUrl() + ">)";
    text.append(headline(outcome, link, "**"));
    for (var line : contentLines(outcome, includeContent)) {
      text.append("\n").append(discordEscape(line));
    }
    text.append("\n[Open the review session](<").append(outcome.sessionUrl()).append(">)");
    var root = NODES.objectNode();
    // Discord refuses content over 2000 characters. The clipped title and finding lines keep a
    // normal message well under it; escaping can double them, so the whole is clipped as well.
    root.put("content", clip(text.toString(), DISCORD_MAX_CONTENT));
    root.putObject("allowed_mentions").putArray("parse");
    return root;
  }

  /**
   * The opted-in lines a chat message adds under its headline, unescaped: the clipped PR title and
   * up to {@value #MAX_CHAT_FINDINGS} finding lines, most severe first. Empty unless content is
   * opted in.
   */
  private static List<String> contentLines(ReviewOutcome outcome, boolean includeContent) {
    var lines = new ArrayList<String>();
    if (!includeContent) {
      return lines;
    }
    if (outcome.prTitle() != null) {
      lines.add(clip(outcome.prTitle(), MAX_CHAT_LINE));
    }
    if (outcome.result() != null) {
      for (var finding : listed(outcome.result(), MAX_CHAT_FINDINGS)) {
        lines.add("• " + findingLine(finding));
      }
    }
    return lines;
  }

  /** The one-line summary both chat formats open with; {@code bold} is the format's marker. */
  private static String headline(ReviewOutcome outcome, String prLink, String bold) {
    if (outcome.failure() != null) {
      return "ThrillhouseBot review of "
          + prLink
          + " "
          + bold
          + "failed"
          + bold
          + " ("
          + outcome.failure().wireName()
          + ") at "
          + shortSha(outcome.headSha());
    }
    var result = outcome.result();
    return "ThrillhouseBot review of "
        + prLink
        + ": "
        + bold
        + verdictLabel(result)
        + bold
        + " — "
        + countsLabel(result)
        + " at "
        + shortSha(outcome.headSha());
  }

  /** The verdict as a reader would say it. Visible for tests. */
  static String verdictLabel(ReviewResult result) {
    if (result.heldOnCiOnly()) {
      return "approval held until CI passes";
    }
    return switch (result.reviewState()) {
      case APPROVE -> "approved";
      case REQUEST_CHANGES -> "changes requested";
      case COMMENT -> "commented";
    };
  }

  private static String countsLabel(ReviewResult result) {
    if (result.totalFindings() == 0) {
      return "no new findings";
    }
    return result.totalFindings()
        + (result.totalFindings() == 1 ? " finding" : " findings")
        + " (critical "
        + result.criticalCount()
        + ", high "
        + result.highCount()
        + ", medium "
        + result.mediumCount()
        + ", low "
        + result.lowCount()
        + ")";
  }

  private static String prLabel(ReviewOutcome outcome) {
    return outcome.repository() + "#" + outcome.prNumber();
  }

  private static String findingLine(Finding finding) {
    return clip(
        "["
            + Objects.toString(finding.risk(), "?")
            + "] "
            + finding.file()
            + ":"
            + finding.line()
            + " — "
            + finding.title(),
        MAX_CHAT_LINE);
  }

  private static List<Finding> listed(ReviewResult result, int limit) {
    return result.findings().stream()
        .sorted(
            Comparator.comparing(Finding::risk, Comparator.nullsLast(Comparator.naturalOrder())))
        .limit(limit)
        .toList();
  }

  private static String shortSha(String sha) {
    if (sha == null || sha.isBlank()) {
      return "an unknown commit";
    }
    return sha.length() > 7 ? sha.substring(0, 7) : sha;
  }

  /**
   * Slack's three control characters, escaped as Slack documents, so PR or model text cannot open a
   * link or an {@code <!channel>} mention. Visible for tests.
   */
  static String slackEscape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /**
   * Discord markdown control characters, backslash-escaped so PR or model text renders literally —
   * {@code <} included, so {@code <t:…>} timestamps and {@code <:emoji:…>} custom emoji stay text.
   * Mentions are disabled separately through {@code allowed_mentions}. Visible for tests.
   */
  static String discordEscape(String value) {
    var out = new StringBuilder(value.length());
    for (var i = 0; i < value.length(); i++) {
      var c = value.charAt(i);
      if ("\\*_~`|<>[]()#-".indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  /** {@code value} cut to {@code max} characters, marked with an ellipsis when it was cut. */
  static String clip(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }
}
