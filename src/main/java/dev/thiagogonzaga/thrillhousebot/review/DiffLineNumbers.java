package dev.thiagogonzaga.thrillhousebot.review;

import java.util.regex.Matcher;
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

  /** A copied number: digits, one space, then the added or context marker. */
  private static final Pattern LEAKED_NUMBER = Pattern.compile("^(\\d{1,9}) [+ ]");

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
    var out = new StringBuilder(diff.length() + lines.length * 4);
    boolean inBlock = false;
    int next = -1;
    for (int i = 0; i < lines.length; i++) {
      var line = lines[i];
      var marker = line.strip();
      if (!inBlock) {
        inBlock = marker.equals(FENCE_OPEN);
        next = -1;
      } else if (marker.equals(FENCE)) {
        inBlock = false;
      } else {
        Matcher header = DiffLineResolver.HUNK_HEADER.matcher(line);
        if (line.startsWith("@@") && header.lookingAt()) {
          next = Integer.parseInt(header.group(1));
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

  /**
   * The quote without the line numbers and diff markers the model copied from the annotated diff.
   * Applied only when every non-blank line starts with a number, a space and a marker, and the
   * numbers run consecutively, which is what a copied run of the diff looks like and plain code
   * practically never does. Anything else is returned unchanged.
   */
  public static String stripLeaked(String quote) {
    if (quote == null || quote.isEmpty() || !Character.isDigit(quote.charAt(0))) {
      return quote;
    }
    var lines = quote.split("\n", -1);
    int expected = -1;
    for (var line : lines) {
      if (line.isBlank()) {
        continue;
      }
      var number = LEAKED_NUMBER.matcher(line);
      if (!number.lookingAt()) {
        return quote;
      }
      int value = Integer.parseInt(number.group(1));
      if (expected >= 0 && value != expected) {
        return quote;
      }
      expected = value + 1;
    }
    var out = new StringBuilder(quote.length());
    for (int i = 0; i < lines.length; i++) {
      var number = LEAKED_NUMBER.matcher(lines[i]);
      out.append(number.lookingAt() ? lines[i].substring(number.end()) : lines[i]);
      if (i < lines.length - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }
}
