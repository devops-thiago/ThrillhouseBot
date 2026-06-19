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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class TriggerDetector {

  /** Default GitHub App bot logins used when none are configured. */
  static final List<String> DEFAULT_BOT_LOGINS =
      List.of("thrillhousebot[bot]", "thrillhouse-bot[bot]");

  /**
   * Command word → its matching patterns, in detection precedence order. A comment carrying more
   * than one command resolves to the first entry that matches, so {@code review} stays first to
   * preserve the original trigger behavior. Each command accepts both the {@code /word} slash form
   * and the {@code @Thrillhousebot word} mention form.
   */
  private static final Map<CommentCommand, List<Pattern>> COMMAND_PATTERNS = buildPatterns();

  /** Matches an {@code @thrillhousebot} mention with a word boundary, anywhere in the comment. */
  private static final Pattern MENTION_PATTERN =
      Pattern.compile(".*@thrillhousebot\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  // Fenced code (``` … ``` or ~~~ … ~~~), blockquote lines (> …) and inline code (`…`) are quoted
  // context, not instructions: a maintainer quoting or documenting a command must not execute it.
  private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```|~~~.*?~~~");
  private static final Pattern BLOCKQUOTE_LINE = Pattern.compile("(?m)^[ \\t]*>.*$");
  private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

  private final Set<String> botLogins;

  @Inject
  public TriggerDetector(ThrillhouseConfig config) {
    this(config.github().botLogins());
  }

  /** Default detector wired with the built-in bot logins (used in tests and as a fallback). */
  public TriggerDetector() {
    this(DEFAULT_BOT_LOGINS);
  }

  TriggerDetector(List<String> configuredBotLogins) {
    var normalized =
        (configuredBotLogins == null ? DEFAULT_BOT_LOGINS : configuredBotLogins)
            .stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    // Never end up with an empty set: that would make isBotComment always false and let the bot
    // answer its own replies (an infinite loop). Fall back to the built-in logins instead.
    this.botLogins =
        normalized.isEmpty()
            ? DEFAULT_BOT_LOGINS.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet())
            : normalized;
  }

  private static Map<CommentCommand, List<Pattern>> buildPatterns() {
    var patterns = new LinkedHashMap<CommentCommand, List<Pattern>>();
    patterns.put(CommentCommand.REVIEW, patternsFor("review"));
    patterns.put(CommentCommand.HELP, patternsFor("help"));
    patterns.put(CommentCommand.SUMMARY, patternsFor("summary"));
    patterns.put(CommentCommand.RESOLVE, patternsFor("resolve"));
    patterns.put(CommentCommand.PAUSE, patternsFor("pause"));
    patterns.put(CommentCommand.RESUME, patternsFor("resume"));
    return patterns;
  }

  private static List<Pattern> patternsFor(String word) {
    return List.of(
        Pattern.compile(
            ".*(?:^|\\s)/" + word + "(?:\\s|$).*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile(
            ".*@thrillhousebot\\s+" + word + "\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
  }

  /**
   * Strips quoted context — fenced code blocks, blockquotes, and inline code — so a command that
   * only appears inside them (e.g. when quoting another comment or documenting the command list) is
   * not mistaken for an instruction.
   */
  static String stripQuotedContext(String body) {
    var withoutFenced = FENCED_CODE.matcher(body).replaceAll(" ");
    var withoutQuotes = BLOCKQUOTE_LINE.matcher(withoutFenced).replaceAll(" ");
    return INLINE_CODE.matcher(withoutQuotes).replaceAll(" ");
  }

  /**
   * Parses the first recognized command from a comment body. Each command matches either its slash
   * form ("/review") or its mention form ("@Thrillhousebot review"). Returns {@link
   * CommentCommand#NONE} when the comment carries no command.
   */
  public CommentCommand detectCommand(String commentBody) {
    if (commentBody == null || commentBody.isBlank()) {
      return CommentCommand.NONE;
    }
    var body = stripQuotedContext(commentBody);
    for (var entry : COMMAND_PATTERNS.entrySet()) {
      if (entry.getValue().stream().anyMatch(p -> p.matcher(body).matches())) {
        return entry.getKey();
      }
    }
    return CommentCommand.NONE;
  }

  /**
   * Checks whether a comment body contains a review trigger keyword. Triggers: "/review" or
   * "@Thrillhousebot review"
   */
  public boolean isReviewTrigger(String commentBody) {
    return detectCommand(commentBody) == CommentCommand.REVIEW;
  }

  /**
   * Checks whether a comment @-mentions the bot ({@code @thrillhousebot}). Used to detect a
   * maintainer addressing the bot for a conversational reply, distinct from the {@code review}
   * command which {@link #isReviewTrigger} already routes to a full review.
   */
  public boolean containsBotMention(String commentBody) {
    if (commentBody == null || commentBody.isBlank()) {
      return false;
    }
    return MENTION_PATTERN.matcher(stripQuotedContext(commentBody)).matches();
  }

  /** Checks whether the comment author is the bot itself. Prevents infinite review loops. */
  public boolean isBotComment(String authorLogin) {
    if (authorLogin == null) {
      return false;
    }
    return botLogins.contains(authorLogin.toLowerCase(Locale.ROOT));
  }
}
