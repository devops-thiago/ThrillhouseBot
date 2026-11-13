package dev.thiagogonzaga.thrillhousebot.review;

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
   * header (a clipped section) are left unnumbered rather than given a guessed number.
   */
  public static String annotate(String diff) {
    if (diff == null || !diff.contains(FENCE_OPEN)) {
      return diff;
    }
    var lines = diff.split("\n", -1);
    var out = new StringBuilder(diff.length() + lines.length * NUMBER_ROOM);
    boolean inBlock = false;
    int next = -1;
    for (int i = 0; i < lines.length; i++) {
      var line = lines[i];
      // Fences are matched whole: inside a block every patch line starts with its marker, so a
      // context line " ```" from a Markdown file is code, not the end of the block.
      if (!inBlock) {
        inBlock = line.equals(FENCE_OPEN);
        next = -1;
      } else if (line.equals(FENCE)) {
        inBlock = false;
      } else {
        var header = DiffLineResolver.hunkStart(line);
        if (header.isPresent()) {
          next = header.getAsInt();
        } else if (next >= 0 && isNewSide(line)) {
          out.append(next++).append(' ');
        }
      }
      out.append(line);
      if (i < lines.length - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  /**
   * Added and context lines exist on the new side. An empty line carries no marker and is not
   * counted, the same rule {@code DiffLineResolver} uses, so the two never disagree on a number.
   */
  private static boolean isNewSide(String line) {
    return !line.isEmpty() && (line.charAt(0) == '+' || line.charAt(0) == ' ');
  }
}
