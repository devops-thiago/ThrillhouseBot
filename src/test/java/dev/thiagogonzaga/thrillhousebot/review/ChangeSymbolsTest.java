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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The names a file's added lines declare, as the summary call's evidence (#920). */
class ChangeSymbolsTest {

  @Test
  void theRoomBookingServiceDeclaresNoReminder() {
    // ThrillhouseBot-test#99: the description claimed reminder e-mails; the code declares none.
    var patch =
        """
        @@ -0,0 +1,9 @@
        +class BookingService:
        +    def __init__(self, store):
        +        self._store = store
        +
        +    def notify_organizer(self, booking):
        +        return self._mailer.send(booking.organizer, "Room booked", "booked")
        +def overlaps(a, b):
        + # def commented_out(): not a declaration of this line's shape
        +    def notify_organizer(self, again):
        """;

    assertEquals(
        List.of("BookingService", "__init__", "notify_organizer", "overlaps"),
        ChangeSymbols.declaredNames(patch));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "export default function render(props) {|render",
        "pub async fn fetch(url: &str) -> Result<()> {|fetch",
        "func (s *Store) Add(ctx context.Context) error {|Add",
        "func main() {|main",
        "data class Episode(val id: String)|Episode",
        "override suspend fun publish() {|publish",
        "public sealed interface Port {|Port",
        "type Config = { url: string }|Config",
        "public record Booking(int id) {}|Booking",
        "public static void main(String[] args) {|main",
        "private Map<String, List<Integer>> index(Path root) {|index",
        "public Foo(int x) {|''",
        "private int x = compute(y);|''",
        "public int count;|''",
        "public void a.b(int x) {|''",
        "return value;|''",
        "func (broken {|''"
      })
  void oneAddedLineDeclaresAtMostOneName(String line, String name) {
    assertEquals(name, ChangeSymbols.declaredName(line));
  }

  @Test
  void theOverviewSuffixNamesUpToItsCap() {
    var patch =
        IntStream.range(0, ChangeSymbols.MAX_NAMES + 1)
            .mapToObj(i -> "+def f" + i + "():")
            .collect(Collectors.joining("\n"));

    assertEquals(
        " — declares: f0, f1, f2, f3, f4, f5, f6, f7, f8, f9, …",
        ChangeSymbols.overviewSuffix("src/f.py", patch));
    assertEquals(" — declares: only", ChangeSymbols.overviewSuffix("f.py", "+def only():"));
    assertEquals("", ChangeSymbols.overviewSuffix("f.py", "+x = 1\n--- a\n+++ b"));
    assertEquals("", ChangeSymbols.overviewSuffix("f.py", null));
    assertEquals("", ChangeSymbols.overviewSuffix(null, " "));
    assertEquals(" — declares: only", ChangeSymbols.overviewSuffix("Makefile", "+def only():"));
  }

  @Test
  void aDefInsideADocstringIsNotTheFilesDeclaration() {
    // A usage example in a docstring is not a function the file declares.
    var patch =
        """
        @@ -0,0 +1,8 @@
        +def f():
        +    \"\"\"Usage:
        +    def usage_example():
        +    \"\"\"
        +    return '''inline''' + "def x(): # not a comment" + 'it\\'s'
        +class Real:
        +    pass
        """;

    assertEquals(List.of("f", "Real"), ChangeSymbols.declaredNames(patch));
  }

  @Test
  void aDocstringOpenedOnAContextLineStillCoversTheAddedLines() {
    var patch =
        """
        @@ -3,4 +3,6 @@ def f():
             '''Usage:
        +    class Example:
        -    removed line '''
             '''
        +def g():
        \\ No newline at end of file
        """;

    assertEquals(List.of("g"), ChangeSymbols.declaredNames(patch));
  }

  @Test
  void blockCommentsTextBlocksBacktickStringsAndHeredocsDeclareNothing() {
    var patch =
        """
        @@ -0,0 +1,20 @@
        +/* Example:
        +class InComment {}
        + */
        +public void after() {
        +String s = \"\"\"
        +    public void inTextBlock() {
        +    \"\"\";
        +const t = `
        +function inTemplate() {}
        +`;
        +cat <<-'EOF' > generated.py
        +class InHeredoc:
        +\tEOF
        +cat <<END
        +def in_unquoted_heredoc():
        +END
        +x = 1 << shift  // class InLineComment
        +y = 1 << 2
        +def last():
        """;

    assertEquals(List.of("after", "last"), ChangeSymbols.declaredNames(patch));
  }

  @Test
  void everyHeredocDelimiterShapeOpensAHeredoc() {
    // Quoted delimiters of any case, a quoted one after a space, a dash before an unquoted one, and
    // an unquoted upper-case one after a space; a shift by a lower-case name opens nothing.
    var patch =
        """
        @@ -0,0 +1,20 @@
        +cat <<'py' > generated.py
        +def build():
        +py
        +cat <<"end_marker"
        +class InDoubleQuoted:
        +end_marker
        +cat <<- "END"
        +def in_spaced_quoted():
        +END
        +cat <<-EOF
        +def in_dashed():
        +\tEOF
        +cat << EOF
        +def in_spaced_bare():
        +EOF
        +cat <<- END_X
        +def in_spaced_dashed():
        +END_X
        +y = x << y
        +def after_shift():
        +out << "end";
        +out << 'x';
        +def after_stream_insertion():
        """;

    assertEquals(
        List.of("after_shift", "after_stream_insertion"), ChangeSymbols.declaredNames(patch));
  }

  @Test
  void eachHunkStartsInCode() {
    // The second hunk's opening line closes nothing: what lies between hunks is unknown.
    var patch = "@@ -1 +1 @@\n+s = \"\"\"open\n@@ -9 +9 @@\n+def after_hunk():";

    assertEquals(List.of("after_hunk"), ChangeSymbols.declaredNames(patch));
  }

  @Test
  void anUnclosedQuoteEndsWithItsLine() {
    var patch = "+impl<'a> Foo for Bar {\n+    pub fn run(&self) {";

    assertEquals(List.of("run"), ChangeSymbols.declaredNames(patch));
  }

  @Test
  void addedLinesInsideAnExistingFunctionNameThatFunction() {
    // A retry added inside an existing send declares nothing new; the hunk header names send.
    var patch =
        """
        @@ -10,6 +10,9 @@ class Mailer:
             def __init__(self):
                 self.sent = 0
        @@ -20,4 +20,8 @@     def send(self, to, subject, body):
        +        for attempt in range(3):
        +            if self._deliver(to, subject, body):
        +                return True
        @@ -40,3 +44,4 @@ def helper():
        -        old()
        @@ -50,3 +55,5 @@ public void notify(Booking b) {
        +    audit(b);
        +def helper2():
        @@ -60,3 +66,4 @@
        +    plain();
        @@ -70,3 +77,4 @@ def helper2():
        +    more()
        @@ broken header with no close
        +    x()
        """;

    assertEquals(List.of("send", "notify"), ChangeSymbols.changedNames(patch));
    assertEquals(List.of(), ChangeSymbols.changedNames(null));
    assertEquals(List.of(), ChangeSymbols.changedNames(" "));
    assertEquals(
        " — declares: helper2 — changes: send, notify",
        ChangeSymbols.overviewSuffix("mailer.py", patch));
  }

  @Test
  void documentationAndDataFilesDeclareNothing() {
    // A README's example code or a YAML key is not the file's own declaration.
    var example = "+```python\n+def example():\n+```\n+class Foo is described above.";
    var code = "+def example():";

    assertEquals("", ChangeSymbols.overviewSuffix("docs/CONFIG-PYTHON.md", example));
    assertEquals("", ChangeSymbols.overviewSuffix("ci.YML", "+type: module"));
    assertEquals(" — declares: example", ChangeSymbols.overviewSuffix("src/x.py", code));
  }

  @Test
  void anAddedLineBelongsToTheNearestFunctionAboveItInItsHunk() {
    // The header names send, but the hunk's context crosses into cleanup before the second added
    // line; a declaration inside a docstring moves nothing.
    var patch =
        """
        @@ -20,6 +20,9 @@ def send(self, to):
        +        log(to)
                 return True
         def cleanup():
        +    os.remove(tmp_path)
        @@ -40,4 +43,5 @@ def outer():
             \"""
             def in_docstring():
             \"""
        +    work()
        """;

    assertEquals(List.of("send", "cleanup", "outer"), ChangeSymbols.changedNames(patch));
  }

  @Test
  void aKeywordPairDeclaresTheNameAfterBoth() {
    assertEquals("Mode", ChangeSymbols.declaredName("public enum class Mode {"));
    assertEquals("ClickListener", ChangeSymbols.declaredName("fun interface ClickListener {"));
    assertEquals("", ChangeSymbols.declaredName("enum class"));
  }

  @Test
  void aQuotedDelimiterOfAnyCaseOpensAHeredocWhereAShellCommandGoesOn() {
    // After the closing quote a shell line may go on with anything: nothing, a redirection, a pipe,
    // arguments, a dot path, a comment. Code that inserts a quoted string goes on with ';', ')',
    // ',', another '<<', a method call, or ends its line with ';'.
    var patch =
        """
        @@ -0,0 +1,30 @@
        +cat << 'eof'
        +def in_spaced_lower():
        +eof
        +cat << "end" > out.py
        +def in_redirected():
        +end
        +ruby <<~'eos' | sh
        +def in_piped():
        +eos
        +cat << 'x' 2> err.log
        +def in_stderr():
        +x
        +cat << 'z' && echo done
        +def in_chained():
        +z
        +cat << 'w' # generated
        +def in_commented():
        +w
        +kubectl apply -f - <<"END" --dry-run
        +def in_arguments():
        +END
        +ssh host bash -s <<'eof' arg
        +def in_trailing_argument():
        +eof
        +bash <<'eof' ./script.sh
        +def in_dot_path():
        +eof
        +env <<"X" .env
        +def in_dot_file():
        +X
        +out << "end";
        +def after_insertion():
        +out << 'x' << y;
        +def after_chained_insertion():
        +out << "n" 0;
        +def after_digit():
        +log(out << "s")
        +def after_call():
        +list << "x".freeze
        +def after_method_call():
        +fmt << "x" .format(y)
        +def after_spaced_method_call():
        """;

    assertEquals(
        List.of(
            "after_insertion",
            "after_chained_insertion",
            "after_digit",
            "after_call",
            "after_method_call",
            "after_spaced_method_call"),
        ChangeSymbols.declaredNames(patch));
  }
}
