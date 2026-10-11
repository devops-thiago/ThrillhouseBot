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
  void documentationAndDataFilesDeclareNothing() {
    // A README's example code or a YAML key is not the file's own declaration.
    var example = "+```python\n+def example():\n+```\n+class Foo is described above.";
    var code = "+def example():";

    assertEquals("", ChangeSymbols.overviewSuffix("docs/CONFIG-PYTHON.md", example));
    assertEquals("", ChangeSymbols.overviewSuffix("ci.YML", "+type: module"));
    assertEquals(" — declares: example", ChangeSymbols.overviewSuffix("src/x.py", code));
  }
}
