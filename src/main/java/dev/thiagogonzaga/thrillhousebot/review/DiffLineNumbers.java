package dev.thiagogonzaga.thrillhousebot.review;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Line numbers for the diff the review call reads (#1320).
 *
 * <p>The review prompt used to carry the raw GitHub patch, so the model worked out each finding's
 * {@code line} by counting from the hunk headers, which it does unreliably. {@link #annotate}
 * starts every added and context line inside a {@code ```diff} block with its new-side line number
 * and a space, ahead of the diff marker: {@code 842 +code}, {@code 843 code}. Removed lines, hunk
 * headers, "no newline" markers and a line with no marker at all are left as they are. GitHub
 * writes an empty context line as a single space, so it is numbered like any other; a completely
 * empty line only appears in a patch whose trailing spaces were trimmed. Only the prompt is
 * annotated: the formatted diff the deterministic stages parse ({@code FindingQuoteValidator},
 * {@code DiffLineResolver} and the rest) stays the plain patch.
 *
 * <p>A bare number costs about a tenth more diff tokens on the test corpus; a padded column with a
 * separator character cost four tenths. {@link CopiedLineNumbers} removes the number again when the
 * model copies it into a quote.
 */
public final class DiffLineNumbers {

  private static final String FENCE_OPEN = "```diff";
  private static final String FENCE = "```";

  /** Room per line for its number and space: up to seven digits, so large files rarely regrow. */
  private static final int NUMBER_ROOM = 8;

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
    var out = new StringBuilder(diff.length() + lines.size() * NUMBER_ROOM);
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
}
