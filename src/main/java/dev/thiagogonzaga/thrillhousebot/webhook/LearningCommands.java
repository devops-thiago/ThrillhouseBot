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
package dev.thiagogonzaga.thrillhousebot.webhook;

import dev.thiagogonzaga.thrillhousebot.review.MarkdownSafe;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearning;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearnings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The replies to {@code /learnings}, {@code /forget <id>} and {@code /remember <text>} (#38). The
 * caller ({@link CommentCommandService}) has already confirmed write access and that the feature is
 * on; this class turns the command into exactly one reply.
 *
 * <p>Everything a reply echoes back — a learning's text, title and path — is maintainer or model
 * prose, so it is placed in inline code spans, where a mention does not notify anyone and markdown
 * cannot restructure the comment.
 */
@ApplicationScoped
public class LearningCommands {

  private static final Logger log = LoggerFactory.getLogger(LearningCommands.class);

  /** Learnings listed in one {@code /learnings} reply; the reply says when there are more. */
  static final int MAX_LISTED = 30;

  /** Characters of a learning's text shown per list row. */
  static final int LISTED_TEXT_CHARS = 200;

  /** The id argument of {@code /forget}: {@code 12}, {@code L12}, {@code #12} or {@code [L12]}. */
  private static final Pattern FORGET_ID =
      Pattern.compile("(?i)\\bforget\\s{1,8}\\[?(?:L|#)?(\\d{1,18})\\]?(?!\\w)");

  /** The command word of {@code /remember} or {@code @<bot> remember}; the text follows it. */
  private static final Pattern REMEMBER_WORD =
      Pattern.compile("(?i)(?:^|\\s)(?:/|@[\\w-]{1,64}(?:\\[bot\\])?\\s{1,8})remember\\b");

  static final String FORGET_USAGE =
      "Name the learning to retract by its id, e.g. `/forget 12`. `/learnings` lists the ids.";

  static final String REMEMBER_USAGE =
      "Write the convention after the command, e.g. `/remember GitHub review threads are flat:"
          + " every reply's in_reply_to_id is the thread root`.";

  private final ReviewLearnings learnings;

  @Inject
  public LearningCommands(ReviewLearnings learnings) {
    this.learnings = learnings;
  }

  /** Whether the learnings store is switched on for this deployment. */
  public boolean enabled() {
    return learnings.enabled();
  }

  /** The one reply for a learnings command. */
  public String reply(CommentCommandService.CommandContext ctx) {
    return switch (ctx.command()) {
      case FORGET -> forget(ctx);
      case REMEMBER -> remember(ctx);
      default -> list(ctx);
    };
  }

  private String list(CommentCommandService.CommandContext ctx) {
    var active = learnings.list(ctx.installationId(), ctx.owner(), ctx.repo());
    if (active.isEmpty()) {
      return "🧠 ThrillhouseBot has no active learnings for this repository.";
    }
    return renderList(active, learnings.maxPerRepo());
  }

  static String renderList(List<ReviewLearningService.LearningView> active, int cap) {
    var sb =
        new StringBuilder("## 🧠 ThrillhouseBot learnings for this repository\n\n")
            .append(active.size())
            .append(" of at most ")
            .append(cap)
            .append(" active. Reviews of the files they concern are told about them.")
            .append(" Retract one with `/forget <id>`.\n\n")
            .append("| Id | Kind | About | Learning | Taught by |\n")
            .append("|----|------|-------|----------|-----------|\n");
    active.stream().limit(MAX_LISTED).forEach(l -> sb.append(row(l)).append('\n'));
    if (active.size() > MAX_LISTED) {
      sb.append("\n")
          .append(active.size() - MAX_LISTED)
          .append(" older learning(s) not shown; the dashboard lists every one.");
    }
    return sb.toString().stripTrailing();
  }

  private static String row(ReviewLearningService.LearningView l) {
    var decline = ReviewLearning.KIND_DECLINE.equals(l.kind());
    var about =
        decline
            ? code(l.findingTitle()) + (l.path() == null ? "" : " in " + code(l.path()))
            : (l.path() == null ? "whole repository" : code(l.path()));
    return "| "
        + l.id()
        + " | "
        + (decline ? "declined finding" : "convention")
        + " | "
        + about
        + " | "
        + code(clip(l.text()))
        + " | "
        + code(l.author())
        + " ([PR #"
        + l.sourcePrNumber()
        + "]("
        + l.sourceUrl()
        + ")) |";
  }

  private static String clip(String text) {
    return text.length() <= LISTED_TEXT_CHARS
        ? text
        : text.substring(0, LISTED_TEXT_CHARS - 1).stripTrailing() + "…";
  }

  /** A value inside an inline code span, safe in a table cell. */
  private static String code(String value) {
    var inner = MarkdownSafe.inlineCode(value).replace("|", "\\|");
    return inner.isBlank() ? "-" : "`" + inner + "`";
  }

  private String forget(CommentCommandService.CommandContext ctx) {
    var matcher = FORGET_ID.matcher(TriggerDetector.stripQuotedContext(bodyOf(ctx)));
    if (!matcher.find()) {
      return FORGET_USAGE;
    }
    // At most 18 digits, so the id always fits a long.
    long id = Long.parseLong(matcher.group(1));
    var outcome = learnings.forget(ctx.installationId(), ctx.owner(), ctx.repo(), id, ctx.login());
    log.info("/forget {} on {}/{} by @{}: {}", id, ctx.owner(), ctx.repo(), ctx.login(), outcome);
    return switch (outcome) {
      case RETRACTED ->
          "🧹 Learning " + id + " is retracted; later reviews of this repository no longer see it.";
      case ALREADY_RETRACTED -> "Learning " + id + " was already retracted.";
      case NOT_FOUND -> "This repository has no learning " + id + ". `/learnings` lists the ids.";
    };
  }

  private String remember(CommentCommandService.CommandContext ctx) {
    var body = bodyOf(ctx);
    var matcher = REMEMBER_WORD.matcher(body);
    var text = matcher.find() ? body.substring(matcher.end()).strip() : "";
    if (text.isEmpty()) {
      return REMEMBER_USAGE;
    }
    var outcome =
        learnings.rememberConvention(
            ctx.installationId(),
            ctx.owner(),
            ctx.repo(),
            ctx.prNumber(),
            ctx.commentId(),
            ctx.login(),
            text);
    log.info("/remember on {}/{} by @{}: {}", ctx.owner(), ctx.repo(), ctx.login(), outcome);
    return switch (outcome) {
      case STORED ->
          "🧠 Remembered for this repository. `/learnings` lists it with its id, and `/forget"
              + " <id>` retracts it.";
      case DUPLICATE -> "That convention is already remembered.";
      case REFUSED_SECRET ->
          "Not remembered: the text contains something shaped like a credential, and learnings"
              + " are replayed into every later review. Restate it without the value.";
      case REFUSED_CAP ->
          "Not remembered: this repository already holds "
              + learnings.maxPerRepo()
              + " active learnings. Retract one with `/forget <id>` first.";
      case REFUSED_EMPTY -> REMEMBER_USAGE;
    };
  }

  private static String bodyOf(CommentCommandService.CommandContext ctx) {
    return ctx.body() == null ? "" : ctx.body();
  }
}
