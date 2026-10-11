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
  static final int REDACTED_PREFIX = 4;

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

  /** The words that mark a key as naming a credential. */
  private static final String CREDENTIAL_WORD =
      "(?:password|passwd|secret|token|api[_-]?key"
          + "|access[_-]?key|private[_-]?key|client[_-]?secret)";

  /**
   * What a type annotation may put before its type name (#962): a pointer or optional mark ({@code
   * *}, {@code ?}), a {@code const} qualifier, then a slice or array bracket ({@code []}, {@code
   * [32]}, {@code [_]}, Zig's sentinel {@code [:0]} or {@code [40:0]}) with at most one more {@code
   * const} after it. Each part appears at most once, and the second {@code const} can only follow a
   * bracket, so no two parts compete for the same text.
   */
  private static final String TYPE_PREFIX =
      "(?:[*?]\\s{0,4})?(?:const\\s{1,4})?"
          + "(?:\\[[A-Za-z0-9_:]{0,20}+\\]\\s{0,4}(?:const\\s{1,4})?)?";

  /**
   * A type name after a {@code :} annotation: TypeScript {@code string}, Kotlin and Swift {@code
   * String} or {@code String?}, Python {@code str} or {@code Final[str]}, Rust {@code &str}, {@code
   * &'static str} or {@code &mut str}, and Zig {@code []const u8} or {@code *const [40:0]u8}
   * through {@link #TYPE_PREFIX}. One word at most 40 characters long, taken possessively like
   * every run in the rule, so a failed match gives nothing back and the rule stays linear.
   */
  private static final String ANNOTATED_TYPE =
      "&?(?:'[A-Za-z_]{1,20}\\s{1,4})?(?:mut\\s{1,4})?"
          + TYPE_PREFIX
          + "[A-Za-z_][A-Za-z0-9_.\\[\\]]{0,40}+\\??";

  /**
   * What a declaration may put between the key and the operator (#916): a C array suffix ({@code
   * API_TOKEN[]}, {@code API_TOKEN[41]}), a TypeScript optional mark, then either a {@code :} type
   * annotation (Rust, TypeScript, Kotlin, Scala, Swift, Python) or a whitespace-separated Go type
   * ({@code var apiToken string =}). A type written before the name — C {@code const char
   * *API_TOKEN}, Java {@code String apiToken} — needs nothing here, since the key is the last word
   * before the operator. Every part is optional and bounded.
   */
  private static final String DECLARATOR =
      "(?:\\[\\s{0,4}[A-Za-z0-9_]{0,40}+\\s{0,4}\\])?\\??[\"']?"
          + "(?:\\s{0,4}:\\s{0,4}"
          + ANNOTATED_TYPE
          + "|\\s{1,4}[A-Za-z_][A-Za-z0-9_.]{0,40}+)?";

  /**
   * {@code <key> = "<literal>"} and its YAML/JSON/Go/Ruby spellings, where the key names a
   * credential, with an optional {@link #DECLARATOR} between the key and the operator. The literal
   * must be quoted: an unquoted right-hand side is a variable or a call, which is where the
   * credential is supposed to come from.
   */
  private static final Pattern GENERIC_ASSIGNMENT =
      Pattern.compile(
          "(?i)([A-Za-z0-9_.-]{0,40}"
              + CREDENTIAL_WORD
              + "[A-Za-z0-9_.-]{0,40}+)"
              + DECLARATOR
              + "\\s*(?::=|=>|=|:)\\s*([\"'])([^\"'\\s]{"
              + MIN_GENERIC_LENGTH
              + ",256})\\2");

  /**
   * The looser shape the model-text scrub reads (#916): a credential word, then on the same line
   * the first {@code =} or {@code :} after it, then within 40 characters a quoted literal (double
   * or single quotes or backticks). It does not care what sits between the name and the operator or
   * between the operator and the quote ({@code &'static str = "…"}, {@code = b"…"}, {@code =
   * Some("…")}), so an assignment in a form the scan does not know still cannot be echoed. A
   * declaration with no {@code =} or {@code :} before the literal (Kotlin {@code by lazy { "…" }})
   * is outside it.
   */
  private static final Pattern ASSIGNED_LITERAL =
      Pattern.compile(
          "(?i)"
              + CREDENTIAL_WORD
              + "[^=:\\n]{0,60}+(?::=|=>|=|:)[^\"`\\n;]{0,40}?([\"'`])([^\"'`\\s]{"
              + MIN_GENERIC_LENGTH
              + ",256})\\1");

  /**
   * Every value a line assigns to a credential-named key, quoted or not (#926): the generic rule
   * reads only quoted literals, because an unquoted right-hand side is where the credential is
   * supposed to come from, but a model finding about {@code ENV API_TOKEN=${API_TOKEN}} is about
   * exactly that unquoted value. A {@code {{ template }}} value is read whole, spaces included. The
   * key (group 1) is any identifier; {@link #CREDENTIAL_KEY} decides whether it names a credential.
   */
  private static final Pattern ASSIGNED_VALUE =
      Pattern.compile(
          "([\\w.-]{1,80}+)[\"']?\\s{0,4}+(?::=|=>|=|:)\\s{0,4}+([\"'`]?)"
              + "(\\{\\{[^}\\n]{1,200}+}}|[^\\s\"'`,;]{1,256}+)\\2");

  /** A key that names a credential. */
  private static final Pattern CREDENTIAL_KEY = Pattern.compile("(?i)" + CREDENTIAL_WORD);

  /**
   * A value read from the environment or a template rather than written out (#926): {@code ${VAR}},
   * {@code ${VAR:-default}}, {@code $(command)} and {@code {{ template }}} here, and {@code $VAR}
   * and Windows {@code %VAR%} in {@link #VARIABLE_REFERENCE}. The whole value must be one such
   * reference.
   */
  private static final Pattern INTERPOLATION =
      Pattern.compile("\\$\\{[^}]{1,200}+}|\\$\\([^)]{1,200}+\\)|\\{\\{[^}]{1,200}+}}");

  private static final Pattern VARIABLE_REFERENCE =
      Pattern.compile("\\$[A-Za-z_]\\w{0,100}+|%[A-Za-z_]\\w{0,100}+%");

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
   * A construct only a regular expression writes (#981): a group or lookaround opener {@code (?}, a
   * character class holding a range ({@code [A-Za-z0-9_-]}), or a class escape ({@code \d}, {@code
   * \w}, {@code \s}, {@code \b}). Bounded, so a long value costs one linear pass.
   */
  private static final Pattern REGEX_CONSTRUCT =
      Pattern.compile("\\(\\?|\\[[^\\]]{0,64}?[A-Za-z0-9]-[A-Za-z0-9]|\\\\[dDwWsSbB]");

  /** The characters that carry a regular expression's structure. */
  private static final String REGEX_STRUCTURE = "()[]{}\\|?*+^$";

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
      if (claimed(hits, value)
          || insideStringLiteral(line, generic)
          || !looksLikeSecret(value, entropyThreshold)) {
        continue;
      }
      hits.add(new Hit(SecurityRule.GENERIC_SECRET, value, generic.group(1)));
    }
    return hits;
  }

  /**
   * Whether a generic match is sample text quoted inside another string literal (#981), such as
   * {@code "client_secret: 'abcdefgh12345'",} in a test's list of inputs: a quote opens right
   * before the key, is not closed before the value's own quote, and is a different quote character
   * from the value's. A quoted key in JSON, YAML or a dictionary ({@code "apiKey": "…"}, {@code
   * {"token": '…'}}) closes its quote before the operator, so it is still read as an assignment.
   * The rule reads only quoted values, so an unquoted value inside a string ({@code "password=…"})
   * is not an assignment to it either way.
   *
   * <p>The enclosing string must also sit where a list element or a call argument does: first on
   * its line, or right after {@code (}, {@code [}, {@code {} or {@code ,}. A string a command runs
   * ({@code sh -c 'DB_PASSWORD="…"'}, {@code ExecStart=… 'API_KEY="…"'}) follows a word or an
   * operator, so a real credential there is still reported. A command string passed as a list
   * element ({@code ["sh", "-c", "PASSWORD='…'"]}) is the case this cannot tell from sample text.
   */
  private static boolean insideStringLiteral(String line, Matcher generic) {
    int keyStart = generic.start(1);
    if (keyStart == 0) {
      return false;
    }
    char before = line.charAt(keyStart - 1);
    if (before != '"' && before != '\'' && before != '`') {
      return false;
    }
    return line.charAt(generic.start(2)) != before
        && line.substring(generic.end(1), generic.start(2)).indexOf(before) < 0
        && inElementPosition(line, keyStart - 1);
  }

  /**
   * Whether the string opening at {@code quote} is first on its line or follows {@code (}, {@code
   * [}, {@code {} or {@code ,}, ignoring whitespace.
   */
  private static boolean inElementPosition(String line, int quote) {
    int i = quote - 1;
    while (i >= 0 && Character.isWhitespace(line.charAt(i))) {
      i--;
    }
    return i < 0 || "([{,".indexOf(line.charAt(i)) >= 0;
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
        || isInterpolation(value)
        || value.startsWith("$")
        || value.startsWith("/")
        || value.startsWith("./")
        || value.startsWith("~/")
        || value.contains("://")
        || isIdentifier(value)
        || isRegex(value)) {
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

  /** Whether the whole value is a reference to the environment or a template; see INTERPOLATION. */
  static boolean isInterpolation(String value) {
    return INTERPOLATION.matcher(value).matches() || VARIABLE_REFERENCE.matcher(value).matches();
  }

  /**
   * Whether every credential-named key the line assigns gets an {@link #isInterpolation
   * interpolation} or a {@link #isPlaceholder placeholder}, at least one key is assigned, and no
   * rule of {@link #scan} matches the line (#926). Such a line names where a credential comes from,
   * or stands in for one in documentation: {@code ENV API_TOKEN=${API_TOKEN}}, {@code
   * FLEET_API_TOKEN=changeme}.
   */
  static boolean assignsOnlyStandIns(String line, double entropyThreshold) {
    if (line.length() > MAX_GENERIC_LINE_LENGTH) {
      return false;
    }
    Matcher assigned = ASSIGNED_VALUE.matcher(line);
    boolean any = false;
    while (assigned.find()) {
      if (!CREDENTIAL_KEY.matcher(assigned.group(1)).find()) {
        continue;
      }
      var value = assigned.group(3);
      if (!isInterpolation(value) && !isPlaceholder(value)) {
        return false;
      }
      any = true;
    }
    return any && scan(line, null, entropyThreshold).isEmpty();
  }

  /**
   * Whether the value is a regular expression rather than a credential (#981), such as {@code
   * TOKEN_END = "(?![A-Za-z0-9_-])"}: it holds a {@link #REGEX_CONSTRUCT} and at least a quarter of
   * its characters are {@link #REGEX_STRUCTURE}. A password with the odd bracket or question mark
   * has neither a construct nor that density.
   */
  private static boolean isRegex(String value) {
    if (!REGEX_CONSTRUCT.matcher(value).find()) {
      return false;
    }
    long structure = value.chars().filter(c -> REGEX_STRUCTURE.indexOf(c) >= 0).count();
    return structure * 4 >= value.length();
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
   * The text with every quoted literal that is assigned to a credential-named key on its line, and
   * that {@link #looksLikeSecret} at the threshold, replaced with its redacted form (#916). The
   * defence in depth for model text: the scan scrubs the values it matched, and this catches a
   * value it did not, so an assignment in a form the scan misses cannot become an echo.
   */
  static String redactAssignedLiterals(String text, double entropyThreshold) {
    return ASSIGNED_LITERAL
        .matcher(text)
        .replaceAll(
            match -> {
              var value = match.group(2);
              var whole = match.group();
              if (!looksLikeSecret(value, entropyThreshold)) {
                return Matcher.quoteReplacement(whole);
              }
              int start = match.start(2) - match.start();
              var redacted = redact(new Hit(SecurityRule.GENERIC_SECRET, value, null));
              return Matcher.quoteReplacement(
                  whole.substring(0, start)
                      + "[redacted: "
                      + redacted
                      + "]"
                      + whole.substring(start + value.length()));
            });
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
