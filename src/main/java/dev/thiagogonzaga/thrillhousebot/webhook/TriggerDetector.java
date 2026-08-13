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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class TriggerDetector {

  /**
   * Command word → its matching patterns, in detection precedence order. A comment carrying more
   * than one command resolves to the first entry that matches, so {@code review} stays first to
   * preserve the original trigger behavior. Each command accepts both the {@code /word} slash form
   * and the {@code @Thrillhousebot word} mention form.
   */
  private static final Map<CommentCommand, List<Pattern>> COMMAND_PATTERNS = buildPatterns();

  private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```|~~~.*?~~~");
  private static final Pattern BLOCKQUOTE_LINE = Pattern.compile("(?m)^[ \\t]*>.*$");
  private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

  private final BotIdentity botIdentity;

  /**
   * Matches an {@code @}-mention of any configured bot login with a word boundary, anywhere in the
   * comment. Built from {@link BotIdentity#mentionNames()} (each name {@link Pattern#quote}d, a
   * login is data, never regex) rather than a hardcoded slug, so a custom-login install's
   * maintainers can address their bot — including the {@code resolved} clear directive, whose
   * acknowledgement path this gate fronts (#679). Compiled once per detector, not per comment.
   */
  private final Pattern mentionPattern;

  @Inject
  public TriggerDetector(ThrillhouseConfig config) {
    this(config.github().botLogins());
  }

  /** Default detector wired with the built-in bot logins (used in tests and as a fallback). */
  public TriggerDetector() {
    this((List<String>) null);
  }

  TriggerDetector(List<String> configuredBotLogins) {
    this.botIdentity = BotIdentity.from(configuredBotLogins);
    String mentions =
        botIdentity.mentionNames().stream().map(Pattern::quote).collect(Collectors.joining("|"));
    this.mentionPattern =
        Pattern.compile(".*@(?:" + mentions + ")\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  private static Map<CommentCommand, List<Pattern>> buildPatterns() {
    var patterns = new LinkedHashMap<CommentCommand, List<Pattern>>();
    patterns.put(CommentCommand.REVIEW, patternsFor("review"));
    patterns.put(CommentCommand.HELP, patternsFor("help"));
    patterns.put(CommentCommand.SUMMARY, patternsFor("summary"));
    patterns.put(CommentCommand.DESCRIBE, patternsFor("describe"));
    patterns.put(CommentCommand.CHANGELOG, patternsFor("changelog"));
    patterns.put(CommentCommand.ADD_DOCS, patternsFor("add-docs"));
    patterns.put(CommentCommand.IMPROVE, patternsFor("improve"));
    patterns.put(CommentCommand.GENERATE_TESTS, patternsFor("generate-tests"));
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
    return mentionPattern.matcher(stripQuotedContext(commentBody)).matches();
  }

  /** Checks whether the comment author is the bot itself. Prevents infinite review loops. */
  public boolean isBotComment(String authorLogin) {
    return botIdentity.matches(authorLogin);
  }
}
