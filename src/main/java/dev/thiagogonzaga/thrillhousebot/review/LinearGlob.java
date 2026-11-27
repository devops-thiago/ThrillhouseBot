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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A glob with the syntax and the meaning of the JDK's {@code glob:} path matcher on a Unix file
 * system, matched in time proportional to the pattern's length times the path's length.
 *
 * <p>The JDK turns a glob into a backtracking regular expression: every {@code *} is a place the
 * match can be retried from, so a pattern such as {@code *a*a*a*a*a*b} takes time that grows with
 * the path's length to the power of the number of wildcards. A repository's settings file declares
 * globs and a pull request names the paths they are matched against, so that cost was set by people
 * who do not run the bot. This matcher follows every way the pattern can match at once, one path
 * character at a time, and never goes back. {@link #matchesFromASegment} does the same for a match
 * that may begin at any segment of the path, in one pass instead of one per segment.
 *
 * <p>What a glob means is unchanged: {@code *} matches within one path segment, {@code **} crosses
 * segments, {@code ?} is one character other than {@code /}, {@code [abc]}, {@code [a-z]} and
 * {@code [!a-z]} are one character of a set and never {@code /}, {@code {a,b}} is a choice (not
 * nested), and {@code \} takes the next character literally. A pattern the JDK rejects is rejected
 * here with the same {@link PatternSyntaxException}, and so is one longer than {@link
 * #MAX_PATTERN_CHARS}.
 */
final class LinearGlob {

  /** The longest pattern compiled. With the path's length it bounds the cost of one match. */
  static final int MAX_PATTERN_CHARS = 512;

  /** What {@link #peek} returns past the end of the pattern. */
  private static final char END = 0;

  private enum Kind {
    /** One given character. */
    LITERAL,
    /** {@code ?}: one character other than {@code /}. */
    ANY,
    /** {@code [...]}: one character of a set. */
    SET,
    /** {@code *}: any run of characters other than {@code /}. */
    STAR,
    /** {@code **}: any run of characters. */
    GLOBSTAR,
    /** The start of an alternative of a {@code {a,b}} group: this one, or the ones after it. */
    BRANCH,
    /** The end of an alternative: on to what follows the group. */
    JUMP,
    /** The end of the pattern. */
    ACCEPT
  }

  /** One step of the pattern. {@code first} and {@code second} are a character or step numbers. */
  private static final class Step {
    private final Kind kind;
    private final Pattern set;
    private int first;
    private int second;

    private Step(Kind kind, int first, Pattern set) {
      this.kind = kind;
      this.first = first;
      this.set = set;
    }
  }

  private final List<Step> steps;

  private LinearGlob(List<Step> steps) {
    this.steps = List.copyOf(steps);
  }

  /**
   * Compiles {@code glob}.
   *
   * @throws PatternSyntaxException when the glob is malformed or longer than {@link
   *     #MAX_PATTERN_CHARS}
   */
  static LinearGlob compile(String glob) {
    if (glob.length() > MAX_PATTERN_CHARS) {
      throw new PatternSyntaxException(
          "Glob longer than " + MAX_PATTERN_CHARS + " characters", glob, MAX_PATTERN_CHARS);
    }
    return new LinearGlob(new Parser(glob).parse());
  }

  /** Whether the whole of {@code path} matches the glob. */
  boolean matches(String path) {
    return matches(path, false);
  }

  /**
   * Whether the glob matches {@code path} from the start of one of its segments to its end: the
   * whole path, or what follows any of its {@code /}. One pass over the path, with the pattern
   * started again after every {@code /}, so the cost does not grow with the number of segments.
   */
  boolean matchesFromASegment(String path) {
    return matches(path, true);
  }

  private boolean matches(String text, boolean fromEverySegment) {
    var current = new BitSet(steps.size());
    var next = new BitSet(steps.size());
    if (!fromEverySegment || !text.startsWith("/")) {
      reach(current, 0);
    }
    int at = 0;
    while (at < text.length() && (fromEverySegment || !current.isEmpty())) {
      int character = text.codePointAt(at);
      at += Character.charCount(character);
      next.clear();
      for (int step = current.nextSetBit(0); step >= 0; step = current.nextSetBit(step + 1)) {
        advance(step, character, next);
      }
      if (fromEverySegment && character == '/' && at < text.length()) {
        reach(next, 0);
      }
      var reached = current;
      current = next;
      next = reached;
    }
    return current.get(steps.size() - 1);
  }

  /** Adds to {@code next} the steps reached by reading {@code character} at {@code step}. */
  private void advance(int step, int character, BitSet next) {
    var at = steps.get(step);
    switch (at.kind) {
      case LITERAL -> reachIf(character == at.first, next, step + 1);
      case ANY -> reachIf(character != '/', next, step + 1);
      case SET -> reachIf(at.set.matcher(Character.toString(character)).matches(), next, step + 1);
      case STAR -> reachIf(character != '/', next, step);
      case GLOBSTAR -> reachIf(!isLineTerminator(character), next, step);
      default -> {
        // A branch, a jump and the end read no character: reach() already followed them.
      }
    }
  }

  private void reachIf(boolean read, BitSet reached, int step) {
    if (read) {
      reach(reached, step);
    }
  }

  /** Marks {@code step} and every step that follows it without reading a character. */
  private void reach(BitSet reached, int step) {
    var pending = new ArrayDeque<Integer>();
    pending.push(step);
    while (!pending.isEmpty()) {
      int index = pending.pop();
      if (reached.get(index)) {
        continue;
      }
      reached.set(index);
      var at = steps.get(index);
      switch (at.kind) {
        case STAR, GLOBSTAR -> pending.push(index + 1);
        case BRANCH -> {
          pending.push(at.first);
          pending.push(at.second);
        }
        case JUMP -> pending.push(at.first);
        default -> {
          // A step that reads a character is left only by reading one.
        }
      }
    }
  }

  /** The characters {@code **} does not cross, as the {@code .} of a regular expression. */
  private static boolean isLineTerminator(int character) {
    return character == '\n'
        || character == '\r'
        || character == '\u0085'
        || character == ' '
        || character == ' ';
  }

  /** Reads a glob into its steps, by the JDK's rules for a Unix file system. */
  private static final class Parser {

    private final String glob;
    private final List<Step> steps = new ArrayList<>();
    private final List<Step> groupExits = new ArrayList<>();
    private Step groupBranch;
    private int at;

    private Parser(String glob) {
      this.glob = glob;
    }

    private List<Step> parse() {
      while (at < glob.length()) {
        char c = glob.charAt(at++);
        switch (c) {
          case '\\' -> escaped();
          case '[' -> set();
          case '{' -> openGroup();
          case '}' -> closeGroup();
          case ',' -> nextAlternative();
          case '*' -> star();
          case '?' -> add(Kind.ANY, 0, null);
          default -> literal(c);
        }
      }
      if (groupBranch != null) {
        throw error("Missing '}'", at - 1);
      }
      add(Kind.ACCEPT, 0, null);
      return steps;
    }

    private void escaped() {
      if (at == glob.length()) {
        throw error("No character to escape", at - 1);
      }
      literal(glob.charAt(at++));
    }

    /** One character, or the two halves of one outside the basic plane read as one. */
    private void literal(char c) {
      if (Character.isHighSurrogate(c) && Character.isLowSurrogate(peek(at))) {
        add(Kind.LITERAL, Character.toCodePoint(c, glob.charAt(at++)), null);
      } else {
        add(Kind.LITERAL, c, null);
      }
    }

    private void star() {
      if (peek(at) == '*') {
        at++;
        add(Kind.GLOBSTAR, 0, null);
      } else {
        add(Kind.STAR, 0, null);
      }
    }

    private void openGroup() {
      if (groupBranch != null) {
        throw error("Cannot nest groups", at - 1);
      }
      branch();
    }

    /** Opens an alternative: a branch to its first step, the next alternative filled in later. */
    private void branch() {
      groupBranch = add(Kind.BRANCH, steps.size() + 1, null);
      groupBranch.second = groupBranch.first;
    }

    private void nextAlternative() {
      if (groupBranch == null) {
        literal(',');
        return;
      }
      groupExits.add(add(Kind.JUMP, 0, null));
      var previous = groupBranch;
      previous.second = steps.size();
      branch();
    }

    private void closeGroup() {
      if (groupBranch == null) {
        literal('}');
        return;
      }
      groupExits.forEach(exit -> exit.first = steps.size());
      groupExits.clear();
      groupBranch = null;
    }

    /**
     * A {@code [...]} set. Its text is rewritten into a character class exactly as the JDK does,
     * and that class decides one character at a time, so every corner of the set syntax keeps the
     * meaning it has there.
     */
    private void set() {
      var regex = new StringBuilder("[[^/]&&[");
      if (peek(at) == '^') {
        regex.append("\\^");
        at++;
      } else {
        if (peek(at) == '!') {
          regex.append('^');
          at++;
        }
        if (peek(at) == '-') {
          regex.append('-');
          at++;
        }
      }
      if (!setBody(regex)) {
        throw error("Missing ']'", at - 1);
      }
      add(Kind.SET, 0, Pattern.compile(regex.append("]]").toString()));
    }

    /** Reads a set's members into {@code regex}; whether its closing {@code ]} was found. */
    private boolean setBody(StringBuilder regex) {
      boolean hasRangeStart = false;
      char last = 0;
      while (at < glob.length()) {
        char c = glob.charAt(at++);
        if (c == ']') {
          return true;
        }
        appendMember(regex, c);
        if (c != '-') {
          hasRangeStart = true;
          last = c;
          continue;
        }
        if (!hasRangeStart) {
          throw error("Invalid range", at - 1);
        }
        char end = peek(at++);
        if (end == END || end == ']') {
          return end == ']';
        }
        if (end < last) {
          throw error("Invalid range", at - 3);
        }
        regex.append(end);
        hasRangeStart = false;
      }
      return false;
    }

    private void appendMember(StringBuilder regex, char c) {
      if (c == '/') {
        throw error("Explicit 'name separator' in class", at - 1);
      }
      if (c == '\\' || c == '[' || (c == '&' && peek(at) == '&')) {
        regex.append('\\');
      }
      regex.append(c);
    }

    private Step add(Kind kind, int first, Pattern set) {
      var step = new Step(kind, first, set);
      steps.add(step);
      return step;
    }

    private char peek(int index) {
      return index < glob.length() ? glob.charAt(index) : END;
    }

    private PatternSyntaxException error(String description, int index) {
      return new PatternSyntaxException(description, glob, index);
    }
  }
}
