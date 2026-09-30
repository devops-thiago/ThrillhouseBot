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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-level secret detection for the deterministic security scan (#60): well-known credential
 * formats, private-key headers, JSON Web Tokens, and {@code password|secret|token|api_key = "..."}
 * assignments whose literal passes an entropy threshold. Regular expressions only, so it runs in
 * the native image with no extra dependency.
 *
 * <p>Every value is checked against a placeholder list before it is reported, because documentation
 * and sample configuration are full of token-shaped strings that are not credentials: AWS's own
 * sample key ends in {@code EXAMPLE}, and templates write {@code <your-token>}, {@code ${API_KEY}}
 * or {@code changeme}.
 *
 * <p>The matched value never leaves this class unredacted except through {@link Hit#literal()},
 * which the scan uses only to scrub the value out of other findings' text; titles and descriptions
 * are built from {@link #redact}.
 */
final class SecretScanner {

  /**
   * One detected value on a line.
   *
   * @param rule the rule that matched
   * @param literal the matched value — never logged, persisted or posted
   * @param keyName the assignment's key for {@link SecurityRule#GENERIC_SECRET}, otherwise {@code
   *     null}
   */
  record Hit(SecurityRule rule, String literal, String keyName) {}

  /** Characters of a known-format value shown before the ellipsis; its prefix names the format. */
  private static final int REDACTED_PREFIX = 4;

  /**
   * Lines longer than this are skipped by the generic assignment rule, which is the one expression
   * with a keyword search inside a character run; the known-format rules still read them.
   */
  static final int MAX_GENERIC_LINE_LENGTH = 1000;

  /** Shortest literal the generic assignment rule considers. */
  static final int MIN_GENERIC_LENGTH = 8;

  private static final String TOKEN_START = "(?<![A-Za-z0-9_-])";
  private static final String TOKEN_END = "(?![A-Za-z0-9_-])";

  private static final Map<SecurityRule, Pattern> KNOWN_FORMATS = knownFormats();

  private static Map<SecurityRule, Pattern> knownFormats() {
    var formats = new java.util.LinkedHashMap<SecurityRule, Pattern>();
    formats.put(
        SecurityRule.AWS_ACCESS_KEY,
        Pattern.compile(TOKEN_START + "((?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16})" + TOKEN_END));
    formats.put(
        SecurityRule.GITHUB_TOKEN,
        Pattern.compile(
            TOKEN_START
                + "(gh[pousr]_[A-Za-z0-9]{36,251}|github_pat_[A-Za-z0-9]{22}_[A-Za-z0-9]{59})"
                + TOKEN_END));
    formats.put(
        SecurityRule.SLACK_TOKEN,
        Pattern.compile(TOKEN_START + "(xox[abposr]-\\d{1,13}-[A-Za-z0-9-]{16,})" + TOKEN_END));
    formats.put(
        SecurityRule.GOOGLE_API_KEY,
        Pattern.compile(TOKEN_START + "(AIza[0-9A-Za-z_-]{35})" + TOKEN_END));
    formats.put(
        SecurityRule.STRIPE_LIVE_KEY,
        Pattern.compile(TOKEN_START + "((?:sk|rk)_live_[0-9A-Za-z]{24,99})" + TOKEN_END));
    formats.put(
        SecurityRule.PRIVATE_KEY,
        Pattern.compile("(-----BEGIN (?:[A-Z0-9]+ ){0,3}PRIVATE KEY(?: BLOCK)?-----)"));
    formats.put(
        SecurityRule.JWT,
        Pattern.compile(
            TOKEN_START
                + "(eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{16,})"
                + TOKEN_END));
    return formats;
  }

  /**
   * {@code <key> = "<literal>"} and its YAML/JSON/Go/Ruby spellings, where the key names a
   * credential. The literal must be quoted: an unquoted right-hand side is a variable or a call,
   * which is where the credential is supposed to come from.
   */
  private static final Pattern GENERIC_ASSIGNMENT =
      Pattern.compile(
          "(?i)([A-Za-z0-9_.-]{0,40}(?:password|passwd|secret|token|api[_-]?key|apikey"
              + "|access[_-]?key|private[_-]?key|client[_-]?secret)[A-Za-z0-9_.-]{0,40})"
              + "[\"']?\\s*(?::=|=>|=|:)\\s*([\"'])([^\"'\\s]{"
              + MIN_GENERIC_LENGTH
              + ",256})\\2");

  /**
   * Case-insensitive fragments that mark a value as a placeholder rather than a credential. Each is
   * a word no provider's random alphabet produces by chance at a rate that matters.
   */
  private static final List<String> PLACEHOLDER_FRAGMENTS =
      List.of(
          "example",
          "sample",
          "dummy",
          "placeholder",
          "changeme",
          "change_me",
          "change-me",
          "redacted",
          "your_",
          "your-",
          "yourkey",
          "yourtoken",
          "fake",
          "xxxx",
          "****",
          "<",
          ">",
          "${",
          "{{",
          "$(",
          "%s",
          "...",
          "…");

  /** A run of one character this long is filler ({@code AAAAAAAA}, {@code 00000000}). */
  private static final Pattern REPEATED_RUN = Pattern.compile("(.)\\1{5,}");

  /**
   * A value made of identifier words — {@code spring.datasource.password}, {@code DB_PASSWORD},
   * {@code my-app-secret-v2} — names a credential rather than being one.
   */
  private static final Pattern IDENTIFIER_WORD = Pattern.compile("[a-z]+\\d{0,2}|[A-Z]+\\d{0,2}");

  private static final Pattern IDENTIFIER_SEPARATOR = Pattern.compile("[._/-]");

  /**
   * The start of a PEM body: base64 of at least a line's worth, either on the line after the header
   * or after a literal {@code \n} on the header's own line (a key pasted into a JSON or YAML
   * string), or the {@code Proc-Type} header of a legacy encrypted key. A header with no body
   * behind it is code that parses or builds keys, not a key.
   */
  private static final Pattern PEM_BODY =
      Pattern.compile("^\\s*[\"']?(?:[A-Za-z0-9+/=]{40,}|Proc-Type:)");

  private static final Pattern PEM_BODY_ON_HEADER_LINE =
      Pattern.compile("-----(?:\\\\n|\\\\r\\\\n)[A-Za-z0-9+/=]{40,}");

  private SecretScanner() {}

  /**
   * Every secret on one added line; {@code nextLine} is the new-side line after it (or {@code
   * null}), read only to tell a private key from code that names the PEM header. A value one of the
   * known-format rules claimed is not reported again by the generic assignment rule, so a {@code
   * token = "ghp_..."} line raises one finding, graded by the specific rule.
   */
  static List<Hit> scan(String line, String nextLine, double entropyThreshold) {
    var hits = new ArrayList<Hit>();
    for (var format : KNOWN_FORMATS.entrySet()) {
      Matcher matcher = format.getValue().matcher(line);
      while (matcher.find()) {
        var literal = matcher.group(1);
        var rule = format.getKey();
        boolean reported =
            rule == SecurityRule.PRIVATE_KEY ? hasPemBody(line, nextLine) : !isPlaceholder(literal);
        if (reported) {
          hits.add(new Hit(rule, literal, null));
        }
      }
    }
    if (line.length() > MAX_GENERIC_LINE_LENGTH) {
      return hits;
    }
    Matcher generic = GENERIC_ASSIGNMENT.matcher(line);
    while (generic.find()) {
      var value = generic.group(3);
      if (claimed(hits, value) || !looksLikeSecret(value, entropyThreshold)) {
        continue;
      }
      hits.add(new Hit(SecurityRule.GENERIC_SECRET, value, generic.group(1)));
    }
    return hits;
  }

  /** Whether a private-key header is followed by a key body; see {@link #PEM_BODY}. */
  private static boolean hasPemBody(String headerLine, String nextLine) {
    return PEM_BODY_ON_HEADER_LINE.matcher(headerLine).find()
        || (nextLine != null && PEM_BODY.matcher(nextLine).find());
  }

  /** Whether a line reads as part of a PEM key body, for scrubbing the body out of other text. */
  static boolean isPemBodyLine(String line) {
    return PEM_BODY.matcher(line).find();
  }

  private static boolean claimed(List<Hit> hits, String value) {
    for (Hit hit : hits) {
      if (value.contains(hit.literal())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a generic assignment's literal reads as a credential: not a placeholder, not an
   * identifier, not a URL or path, at least one non-letter character and two character classes, and
   * a Shannon entropy at or above the threshold.
   */
  static boolean looksLikeSecret(String value, double entropyThreshold) {
    if (isPlaceholder(value)
        || value.startsWith("$")
        || value.startsWith("/")
        || value.startsWith("./")
        || value.startsWith("~/")
        || value.contains("://")
        || isIdentifier(value)) {
      return false;
    }
    return characterClasses(value) >= 2
        && value.chars().anyMatch(c -> !Character.isLetter(c))
        && entropy(value) >= entropyThreshold;
  }

  /**
   * Whether the value is two or more identifier words joined by separators. Split rather than one
   * repeated group, which would backtrack through every separator of a long value.
   */
  private static boolean isIdentifier(String value) {
    var words = IDENTIFIER_SEPARATOR.split(value, -1);
    return words.length >= 2
        && Arrays.stream(words).allMatch(word -> IDENTIFIER_WORD.matcher(word).matches());
  }

  /** Whether a matched value is a documentation or template stand-in. */
  static boolean isPlaceholder(String value) {
    var lower = value.toLowerCase(Locale.ROOT);
    for (String fragment : PLACEHOLDER_FRAGMENTS) {
      if (lower.contains(fragment)) {
        return true;
      }
    }
    return REPEATED_RUN.matcher(value).find();
  }

  private static int characterClasses(String value) {
    return (int) value.chars().map(SecretScanner::characterClass).distinct().count();
  }

  private static int characterClass(int c) {
    if (Character.isLowerCase(c)) {
      return 0;
    }
    if (Character.isUpperCase(c)) {
      return 1;
    }
    return Character.isDigit(c) ? 2 : 3;
  }

  /** Shannon entropy of the value's characters, in bits per character. */
  static double entropy(String value) {
    if (value.isEmpty()) {
      return 0.0;
    }
    var counts = new HashMap<Integer, Integer>();
    value.chars().forEach(c -> counts.merge(c, 1, Integer::sum));
    double entropy = 0.0;
    for (var count : counts.values()) {
      double p = (double) count / value.length();
      entropy -= p * (Math.log(p) / Math.log(2));
    }
    return entropy;
  }

  /**
   * The only form of a secret the bot ever shows: its first characters and its length. A
   * known-format value shows four, which is the provider prefix ({@code ghp_}, {@code AKIA}) and
   * says nothing about the credential. A generic value has no public prefix, so it shows at most
   * one character in four of its length — two of an eight-character password.
   */
  static String redact(Hit hit) {
    var literal = hit.literal();
    if (hit.rule() == SecurityRule.PRIVATE_KEY) {
      return "PEM block";
    }
    int shown =
        hit.rule() == SecurityRule.GENERIC_SECRET
            ? Math.min(REDACTED_PREFIX, literal.length() / 4)
            : REDACTED_PREFIX;
    return literal.substring(0, shown) + "…, " + literal.length() + " chars";
  }
}
