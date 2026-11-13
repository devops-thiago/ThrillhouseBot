package dev.thiagogonzaga.thrillhousebot.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Line numbers for the diff the review call reads (#1320).
 *
 * <p>The review prompt used to carry the raw GitHub patch, so the model worked out each finding's
 * {@code line} by counting from the hunk headers, which it does unreliably. {@link #annotate}
 * starts every added and context line inside a {@code ```diff} block with its new-side line number
 * and a space, ahead of the diff marker: {@code 842 +code}, {@code 843 code}. Removed lines, hunk
 * headers and "no newline" markers are left as they are. Only the prompt is annotated: the
 * formatted diff the deterministic stages parse ({@code FindingQuoteValidator}, {@code
 * DiffLineResolver} and the rest) stays the plain patch.
 *
 * <p>A bare number costs about a tenth more diff tokens on the test corpus; a padded column with a
 * separator character cost four tenths. {@link #stripLeaked} removes the number again when the
 * model copies it into a quote.
 */
public final class DiffLineNumbers {

  private static final String FENCE_OPEN = "```diff";
  private static final String FENCE = "```";

  /** How far a copied run's first number may be from the line the finding cites. */
  private static final int RUN_SLACK = 3;

  /** A copied number: digits, one space, then the added or context marker. */
  private static final Pattern LEAKED_NUMBER = Pattern.compile("^(\\d{1,9}) [+ ]");

  private DiffLineNumbers() {}

  /**
   * The diff with each added and context line of its {@code ```diff} blocks prefixed by its
   * new-side line number. Text outside those blocks is unchanged. Lines that precede any hunk
   * header (a clipped section) are left unnumbered rather than given a guessed number. The numbers
   * come from {@link PatchIndex}, the parser every deterministic stage reads (#1321), so a number
   * the model copies is the one those stages place the finding by.
   */
  public static String annotate(String diff) {
    if (diff == null || !diff.contains(FENCE_OPEN)) {
      return diff;
    }
    var lines = Arrays.asList(diff.split("\n", -1));
    var out = new StringBuilder(diff.length() + lines.size() * 4);
    int i = 0;
    while (i < lines.size()) {
      appendLine(out, lines, i, null);
      if (lines.get(i).equals(FENCE_OPEN)) {
        // Fences are matched whole: inside a block every patch line starts with its marker, so a
        // context line " ```" from a Markdown file is code, not the end of the block.
        int end = i + 1;
        while (end < lines.size() && !lines.get(end).equals(FENCE)) {
          end++;
        }
        var numbers = rightLineNumbers(lines.subList(i + 1, end));
        for (int j = i + 1; j < end; j++) {
          appendLine(out, lines, j, numbers.get(j - i - 1));
        }
        i = end;
      } else {
        i++;
      }
    }
    return out.toString();
  }

  /** The new-side number of each added and context line of a block, by its index in the block. */
  private static Map<Integer, Integer> rightLineNumbers(List<String> block) {
    var numbers = new HashMap<Integer, Integer>();
    PatchIndex.parse(block)
        .rightSide()
        .forEach(line -> numbers.put(line.position(), line.rightLine()));
    return numbers;
  }

  /** Appends line {@code index}, after {@code number} when there is one, and its line break. */
  private static void appendLine(StringBuilder out, List<String> lines, int index, Integer number) {
    if (number != null) {
      out.append(number).append(' ');
    }
    out.append(lines.get(index));
    if (index < lines.size() - 1) {
      out.append('\n');
    }
  }

  /**
   * The quote without the line numbers and diff markers the model copied from the annotated diff.
   * Applied only when every non-blank line starts with a number, a space and a marker, or is an
   * unnumbered removed line or "no newline" marker of the annotated diff, and the numbers run
   * consecutively, which is what a copied run of the diff looks like and plain code practically
   * never does. The removed lines and markers are dropped, so a block copied across a change keeps
   * only its new-side code. Blank lines before or inside the run are kept as they are. The run must
   * also reach where the finding points: a single numbered line only at {@code citedLine}, since on
   * its own {@code 1 + 2} is as likely to be code, and a longer run when its span, widened by
   * {@link #RUN_SLACK} lines, holds it, so a quote of a numbered list elsewhere in a data file is
   * left alone. Anything else is returned unchanged.
   */
  public static String stripLeaked(String quote, int citedLine) {
    if (quote == null || quote.isBlank() || !Character.isDigit(quote.stripLeading().charAt(0))) {
      return quote;
    }
    var lines = quote.split("\n", -1);
    if (!isCopiedRun(lines, citedLine)) {
      return quote;
    }
    var kept = new ArrayList<String>(lines.length);
    for (var line : lines) {
      var number = LEAKED_NUMBER.matcher(line);
      if (number.lookingAt()) {
        kept.add(line.substring(number.end()));
      } else if (!isUnnumberedDiffLine(line)) {
        kept.add(line);
      }
    }
    return String.join("\n", kept);
  }

  /** A removed line or a "no newline" marker, which the annotated diff leaves unnumbered. */
  private static boolean isUnnumberedDiffLine(String line) {
    return line.startsWith("-") || line.startsWith("\\");
  }

  /**
   * Whether every non-blank line carries a copied number and the numbers run on consecutively, with
   * a lone numbered line accepted only at {@code citedLine}.
   */
  private static boolean isCopiedRun(String[] lines, int citedLine) {
    int expected = -1;
    int numbered = 0;
    int first = -1;
    for (var line : lines) {
      if (line.isBlank() || isUnnumberedDiffLine(line)) {
        continue;
      }
      var number = LEAKED_NUMBER.matcher(line);
      if (!number.lookingAt()) {
        return false;
      }
      int value = Integer.parseInt(number.group(1));
      if (expected >= 0 && value != expected) {
        return false;
      }
      if (numbered++ == 0) {
        first = value;
      }
      expected = value + 1;
    }
    var slack = numbered > 1 ? RUN_SLACK : 0;
    var last = expected - 1;
    return citedLine > 0 && citedLine >= first - slack && citedLine <= last + slack;
  }
}
