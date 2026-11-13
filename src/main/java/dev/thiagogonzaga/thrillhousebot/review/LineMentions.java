package dev.thiagogonzaga.thrillhousebot.review;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a finding's own {@code file:line} mentions in step with where the finding is posted
 * (#1320). When the pipeline moves a finding onto the code its quote occupies, or onto the nearest
 * line the diff can take a comment on, its description can still name the line the model cited: the
 * thread then says "at {@code Foo.java:42}" while sitting on line 45.
 *
 * <p>Only mentions of the finding's own file at exactly the old line are rewritten: the bare file
 * name, the full path or a tail of it, or an absolute path ending in it, followed by {@code :line}
 * and no further digit, range or list ({@code 42-50}, {@code 42..50}, {@code 42,44}, {@code 42 to
 * 50}). Mentions of other files and of ranges are left alone.
 */
final class LineMentions {

  private LineMentions() {}

  /** {@code text} with each mention of {@code file} at line {@code from} moved to {@code to}. */
  static String moved(String text, String file, int from, int to) {
    if (text == null || file == null || from == to || from <= 0 || to <= 0) {
      return text;
    }
    int slash = file.lastIndexOf('/');
    String name = file.substring(slash + 1);
    if (name.isEmpty() || !text.contains(name + ":" + from)) {
      return text;
    }
    var mention =
        Pattern.compile(
            "(?<![\\w./-])(/?(?:[\\w.-]+/)*"
                + Pattern.quote(name)
                + "):"
                + from
                + "(?!\\d|[-–—]\\d|\\.\\.\\d|,\\s?\\d|\\s+to\\s+\\d)");
    Matcher matcher = mention.matcher(text);
    var out = new StringBuilder(text.length());
    while (matcher.find()) {
      String path = matcher.group(1);
      String replacement =
          path.equals(name)
                  || file.endsWith("/" + path)
                  || file.equals(path)
                  || path.endsWith("/" + file)
              ? path + ":" + to
              : matcher.group();
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
