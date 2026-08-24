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
package dev.thiagogonzaga.thrillhousebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * #764. {@link LogSafe}'s class javadoc states an invariant over the whole code base — every value
 * a log statement splices in that the bot did not choose itself goes through it — and five separate
 * rounds have now found that invariant untrue: {@code MarkdownSafe.oneLine}'s ASCII-only class
 * (#742), {@code LogSafe}'s own {@code \p{IsZs}} gap, three {@code ReviewPublisher} DEBUG lines
 * waved through because debug is off by default, four rate-limit headers in {@code
 * GitHubApiError.diagnostics()}, and three INFO lines carrying a model-supplied finding title and
 * path (#764). Every round fixed the sites someone had enumerated, and the enumeration is what
 * keeps failing: it is a list a human maintains, so the next call site written is outside it by
 * default.
 *
 * <p>So this test states the invariant rather than a list of sites. It derives the untrusted
 * accessor names from the AI response records themselves — every {@code String}-typed component of
 * every record reachable from a {@code *Response} class in {@code review.ai}, read reflectively —
 * and then reads every log call in {@code src/main/java} and fails on any that interpolates one of
 * them without {@link LogSafe}. Both halves extend themselves: a field added to {@code
 * ReviewResponse.Finding} is covered the moment it exists, and a log line added anywhere in main is
 * scanned the moment it is written. Nothing here has to be kept in sync by hand, which is the
 * property the five previous rounds lacked.
 *
 * <p>Reading source text rather than bytecode is unusual for a unit test, and it is a deliberate
 * trade. A textual scan cannot resolve types, so it decides by accessor name and errs towards
 * reporting: a {@code file()} on some unrelated record is reported too, and the answer to a report
 * is to wrap the value, which is never wrong for a logged string. What it must not do is go blind,
 * because a structural test that silently matches nothing reads exactly like a clean code base — so
 * {@link #theScannerReportsTheShapesItClaimsTo()} pins the scanner against known-bad and known-good
 * sources, and the run below asserts the accessor set is non-empty before it trusts a clean result.
 *
 * <p>Numeric conversions are exempt on purpose. {@code %d} splices a number, which can neither
 * forge a record boundary nor smuggle an escape, and {@code LogSafe.oneLine} does not take one — a
 * finding's {@code line()} is the common case.
 */
class LogSafeInvariantTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  private static final String AI_PACKAGE = "dev.thiagogonzaga.thrillhousebot.review.ai";

  /**
   * JBoss {@code Log.warnf} and slf4j {@code log.warn} alike; group 2 marks the format variants.
   */
  private static final Pattern LOG_CALL =
      Pattern.compile("\\b(?:Log|log)\\.(debug|info|warn|error|trace|fatal)(f?)\\s*\\(");

  /** A {@code java.util.Formatter} conversion; group 1 is the conversion character. */
  private static final Pattern CONVERSION =
      Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])");

  private static final Pattern NO_ARG_CALL = Pattern.compile("\\.(\\w+)\\s*\\(\\s*\\)");

  /**
   * A local holding untrusted text: the shape {@code FollowUpAnalyzer}'s {@code locator} had, where
   * the value logged was {@code file() + ":" + line()} composed a few lines earlier, so a check
   * that only read the log call's own accessors saw a bare identifier and passed it (#764).
   */
  private static final Pattern LOCAL_DECLARATION =
      Pattern.compile("\\b(?:final\\s+)?(?:String|var)\\s+(\\w+)\\s*=\\s*([^;]+);", Pattern.DOTALL);

  private static final Pattern IDENTIFIER = Pattern.compile("\\b\\w+\\b");

  private static final Pattern STRING_LITERAL =
      Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);

  @Test
  void noLogStatementInterpolatesAModelSuppliedValueWithoutLogSafe() throws Exception {
    var untrusted = untrustedAccessors();
    assertTrue(
        untrusted.containsAll(Set.of("title", "file", "risk", "confidence", "reason")),
        "the untrusted accessor set lost its known members, so a clean run means nothing: "
            + untrusted);

    var violations = new ArrayList<String>();
    try (var sources = Files.walk(MAIN_SOURCES)) {
      for (var source : sources.filter(LogSafeInvariantTest::isJavaSource).toList()) {
        violations.addAll(
            violationsIn(
                Files.readString(source), MAIN_SOURCES.relativize(source).toString(), untrusted));
      }
    }

    assertEquals(
        List.of(),
        violations,
        "a log statement interpolates a model-supplied value without LogSafe.oneLine");
  }

  @Test
  void theScannerReportsTheShapesItClaimsTo() {
    var untrusted = Set.of("title", "file", "reason");

    assertEquals(
        1,
        scanned("Log.infof(\"finding %s\", finding.title());", untrusted).size(),
        "a raw accessor at a string conversion must be reported");
    assertEquals(
        List.of(),
        scanned("Log.infof(\"finding %s\", LogSafe.oneLine(finding.title()));", untrusted),
        "a sanitized accessor must not be reported");
    assertEquals(
        List.of(),
        scanned("Log.infof(\"line %d\", finding.line());", untrusted),
        "a numeric conversion cannot forge a record and must not be reported");
    assertEquals(
        1,
        scanned(
                "String at = finding.file() + \":\" + finding.line();\nLog.infof(\"at %s\", at);",
                untrusted)
            .size(),
        "a local composed from an accessor must be reported where it is logged");
    assertEquals(
        1,
        scanned("log.warn(\"rejected {}\", verdict.reason());", untrusted).size(),
        "the slf4j call shape must be scanned too");
    assertEquals(
        List.of(),
        scanned("// Log.infof(\"finding %s\", finding.title());", untrusted),
        "a commented-out call is not a call site");
    assertEquals(
        List.of(),
        scanned(
            "var title = finding.title();\nLog.infof(\"no title survived the batches\");",
            untrusted),
        "a message whose prose names a tainted local does not log it");
    assertEquals(
        1,
        scanned(
                "Log.infof(\"path %s\", LogSafe.oneLine(finding.title()) + finding.file());",
                untrusted)
            .size(),
        "one wrapped value is not proof the rest of the argument is sanitized");
    assertEquals(
        1,
        scanned(
                "var at = LogSafe.oneLine(finding.title()) + finding.file();\n"
                    + "Log.infof(\"at %s\", at);",
                untrusted)
            .size(),
        "a local composed from a wrapped value and a raw one is still tainted");
    assertEquals(
        List.of(),
        scanned("Log.infof(\"finding %s\", LogSafe.oneLine(shorten(finding.title())));", untrusted),
        "a nested call inside the sanitized span does not end it early");
  }

  private static List<String> scanned(String body, Set<String> untrusted) {
    return violationsIn(
        "class Sample {\n  void run() {\n" + body + "\n  }\n}\n", "Sample.java", untrusted);
  }

  /**
   * Every {@code String}-valued component name of every record reachable from a {@code *Response}
   * class in {@code review.ai}. These are the names a model's own text arrives under, and reading
   * them off the records means a new field extends the check without anyone remembering to.
   */
  private static Set<String> untrustedAccessors() throws IOException, ClassNotFoundException {
    var accessors = new TreeSet<String>();
    try (var sources = Files.list(MAIN_SOURCES.resolve(AI_PACKAGE.replace('.', '/')))) {
      for (var source : sources.toList()) {
        var name = source.getFileName().toString();
        if (name.endsWith("Response.java")) {
          collectComponents(Class.forName(AI_PACKAGE + "." + name.replace(".java", "")), accessors);
        }
      }
    }
    return accessors;
  }

  private static void collectComponents(Class<?> type, Set<String> accessors) {
    if (type.isRecord()) {
      for (var component : type.getRecordComponents()) {
        var declared = component.getGenericType().getTypeName();
        if (declared.equals(String.class.getName())
            || declared.equals("java.util.List<java.lang.String>")) {
          accessors.add(component.getName());
        }
      }
    }
    for (var nested : type.getDeclaredClasses()) {
      collectComponents(nested, accessors);
    }
  }

  private static boolean isJavaSource(Path path) {
    return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
  }

  /** Every unsanitized interpolation of an untrusted value in one source file, as report lines. */
  private static List<String> violationsIn(String rawSource, String name, Set<String> untrusted) {
    var source = withoutComments(rawSource);
    var tainted = taintedLocals(source, untrusted);
    var violations = new ArrayList<String>();
    var calls = LOG_CALL.matcher(source);
    while (calls.find()) {
      var arguments = argumentsAt(source, calls.end() - 1);
      var formatted = !calls.group(2).isEmpty();
      var formatIndex = formatted ? indexOfLiteral(arguments) : -1;
      var conversions =
          formatIndex < 0 ? List.<Character>of() : conversionsOf(arguments.get(formatIndex));
      for (var index = 0; index < arguments.size(); index++) {
        if (index == formatIndex) {
          continue;
        }
        var conversion = conversionAt(conversions, index - formatIndex - 1, formatIndex >= 0);
        if (conversion != null && Character.toLowerCase(conversion) != 's') {
          continue;
        }
        var argument = withoutSanitizedCalls(arguments.get(index));
        // A literal is text the bot chose, and its words are not identifiers: without this, a
        // message naming a tainted local ("no candidate survived") reads as logging it.
        var expression = withoutLiterals(argument);
        var reached = new TreeSet<String>();
        var accessors = NO_ARG_CALL.matcher(expression);
        while (accessors.find()) {
          if (untrusted.contains(accessors.group(1))) {
            reached.add(accessors.group(1) + "()");
          }
        }
        var identifiers = IDENTIFIER.matcher(expression);
        while (identifiers.find()) {
          if (tainted.contains(identifiers.group())) {
            reached.add(identifiers.group());
          }
        }
        if (!reached.isEmpty()) {
          violations.add(
              name
                  + ":"
                  + lineOf(source, calls.start())
                  + " logs "
                  + reached
                  + " raw in argument `"
                  + argument.replaceAll("\\s+", " ")
                  + "` — wrap it in LogSafe.oneLine");
        }
      }
    }
    return violations;
  }

  /** Locals initialised from an untrusted accessor without sanitizing it, by name. */
  private static Set<String> taintedLocals(String source, Set<String> untrusted) {
    var tainted = new TreeSet<String>();
    var declarations = LOCAL_DECLARATION.matcher(source);
    while (declarations.find()) {
      var initializer = withoutLiterals(withoutSanitizedCalls(declarations.group(2)));
      var accessors = NO_ARG_CALL.matcher(initializer);
      while (accessors.find()) {
        if (untrusted.contains(accessors.group(1))) {
          tainted.add(declarations.group(1));
          break;
        }
      }
    }
    return tainted;
  }

  private static Character conversionAt(List<Character> conversions, int position, boolean known) {
    if (!known || position < 0 || position >= conversions.size()) {
      return null;
    }
    return conversions.get(position);
  }

  private static List<Character> conversionsOf(String formatArgument) {
    var literal = new StringBuilder();
    var pieces = STRING_LITERAL.matcher(formatArgument);
    while (pieces.find()) {
      literal.append(pieces.group(1));
    }
    var conversions = new ArrayList<Character>();
    var found = CONVERSION.matcher(literal);
    while (found.find()) {
      var conversion = found.group(1).charAt(0);
      if (conversion != '%' && conversion != 'n') {
        conversions.add(conversion);
      }
    }
    return conversions;
  }

  private static int indexOfLiteral(List<String> arguments) {
    for (var index = 0; index < arguments.size(); index++) {
      if (arguments.get(index).startsWith("\"")) {
        return index;
      }
    }
    return -1;
  }

  private static int lineOf(String source, int offset) {
    return (int) source.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
  }

  /**
   * The argument expressions of the call whose opening parenthesis sits at {@code open}, split on
   * the commas that belong to this call: nesting and every literal form are tracked, so a comma
   * inside a nested call, a lambda body or a string is not a separator.
   */
  private static List<String> argumentsAt(String source, int open) {
    var arguments = new ArrayList<String>();
    var argument = new StringBuilder();
    var depth = 1;
    var index = open + 1;
    while (index < source.length() && depth > 0) {
      var character = source.charAt(index);
      if (character == '"' || character == '\'') {
        var end = endOfLiteral(source, index);
        argument.append(source, index, end);
        index = end;
        continue;
      }
      if (character == '(' || character == '[' || character == '{') {
        depth++;
      } else if (character == ')' || character == ']' || character == '}') {
        depth--;
        if (depth == 0) {
          break;
        }
      }
      if (character == ',' && depth == 1) {
        arguments.add(argument.toString().strip());
        argument.setLength(0);
      } else {
        argument.append(character);
      }
      index++;
    }
    if (!argument.toString().isBlank()) {
      arguments.add(argument.toString().strip());
    }
    return arguments;
  }

  /** The offset just past the string, text block or character literal starting at {@code start}. */
  private static int endOfLiteral(String source, int start) {
    var quote = source.charAt(start);
    var textBlock = source.startsWith("\"\"\"", start);
    var index = start + (textBlock ? 3 : 1);
    while (index < source.length()) {
      var character = source.charAt(index);
      if (character == '\\') {
        index += 2;
        continue;
      }
      if (textBlock && source.startsWith("\"\"\"", index)) {
        return index + 3;
      }
      if (!textBlock && character == quote) {
        return index + 1;
      }
      index++;
    }
    return source.length();
  }

  /** The expression with its string and character literals removed, so only code is read. */
  private static String withoutLiterals(String expression) {
    var code = new StringBuilder(expression.length());
    var index = 0;
    while (index < expression.length()) {
      var character = expression.charAt(index);
      if (character == '"' || character == '\'') {
        index = endOfLiteral(expression, index);
        continue;
      }
      code.append(character);
      index++;
    }
    return code.toString();
  }

  /**
   * The source with every comment blanked to spaces, newlines kept. A commented-out log call is not
   * a call site, and a stray quote or parenthesis in prose would otherwise desynchronise the
   * argument split; blanking rather than deleting keeps every offset, so reported line numbers
   * still point at the real line.
   */
  private static String withoutComments(String source) {
    var scrubbed = new StringBuilder(source.length());
    var index = 0;
    while (index < source.length()) {
      var character = source.charAt(index);
      if (character == '"' || character == '\'') {
        var end = endOfLiteral(source, index);
        scrubbed.append(source, index, end);
        index = end;
        continue;
      }
      if (source.startsWith("//", index)) {
        var end = source.indexOf('\n', index);
        end = end < 0 ? source.length() : end;
        scrubbed.append(" ".repeat(end - index));
        index = end;
        continue;
      }
      if (source.startsWith("/*", index)) {
        var end = source.indexOf("*/", index + 2);
        end = end < 0 ? source.length() : end + 2;
        source.substring(index, end).chars().forEach(c -> scrubbed.append(c == '\n' ? '\n' : ' '));
        index = end;
        continue;
      }
      scrubbed.append(character);
      index++;
    }
    return scrubbed.toString();
  }

  /**
   * The expression with every {@code LogSafe.…(…)} call and its arguments removed, so what remains
   * is only the part that reached the log line unsanitized.
   *
   * <p>Skipping the whole expression when it mentions {@code LogSafe.} anywhere — which is what
   * this did first — reads one wrapped value as proof the rest is safe. {@code
   * LogSafe.oneLine(finding.title()) + finding.file()} passed the scan with the path interpolated
   * raw at its own {@code %s}, which is the shape a partial fix produces: someone wraps the value a
   * finding names and leaves the one beside it. Removing the sanitized spans and scanning the
   * remainder cannot be satisfied that way.
   *
   * <p>Spans are matched by balancing parentheses rather than to the first {@code )}, so a nested
   * call inside the argument does not end the span early.
   */
  private static String withoutSanitizedCalls(String expression) {
    var out = new StringBuilder(expression.length());
    var at = 0;
    while (at < expression.length()) {
      var call = expression.indexOf("LogSafe.", at);
      if (call < 0) {
        out.append(expression, at, expression.length());
        break;
      }
      out.append(expression, at, call);
      var open = expression.indexOf('(', call);
      if (open < 0) {
        // Not a call: a bare mention cannot sanitize anything, so keep it for the scan.
        out.append(expression, call, expression.length());
        break;
      }
      var depth = 0;
      var scan = open;
      while (scan < expression.length()) {
        var c = expression.charAt(scan);
        if (c == '(') {
          depth++;
        } else if (c == ')' && --depth == 0) {
          break;
        }
        scan++;
      }
      at = scan < expression.length() ? scan + 1 : expression.length();
    }
    return out.toString();
  }
}
