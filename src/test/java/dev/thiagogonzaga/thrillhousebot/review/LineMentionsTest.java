package dev.thiagogonzaga.thrillhousebot.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class LineMentionsTest {

  private static final String FILE = "src/main/Foo.java";

  @Test
  void movesMentionsOfTheFindingsOwnFileAtTheOldLine() {
    assertEquals(
        "See `Foo.java:45`, main/Foo.java:45 and src/main/Foo.java:45.",
        LineMentions.moved(
            "See `Foo.java:42`, main/Foo.java:42 and src/main/Foo.java:42.", FILE, 42, 45));
  }

  @Test
  void movesAnAbsolutePathEndingInTheFile() {
    assertEquals(
        "at /home/u/repo/src/main/Foo.java:45, not /other/Foo.java:42",
        LineMentions.moved(
            "at /home/u/repo/src/main/Foo.java:42, not /other/Foo.java:42", FILE, 42, 45));
  }

  @Test
  void leavesOtherFilesOtherLinesAndRangesAlone() {
    var text =
        "Bar.java:42, other/Foo.java:42, Foo.java:420, Foo.java:42-50, Foo.java:42–50, Foo.java:42—50, xFoo.java:42";
    var lists = "Foo.java:42..44, Foo.java:42,44, Foo.java:42, 44 and Foo.java:42 to 44";
    assertEquals(lists, LineMentions.moved(lists, FILE, 42, 45));
    assertEquals(text, LineMentions.moved(text, FILE, 42, 45));
    assertEquals(
        "at Foo.java:45—the crash", LineMentions.moved("at Foo.java:42—the crash", FILE, 42, 45));
  }

  @Test
  void returnsTheTextUnchangedWhenThereIsNothingToMove() {
    var text = "Foo.java:42";
    assertSame(text, LineMentions.moved(text, FILE, 42, 42));
    assertSame(text, LineMentions.moved(text, FILE, 0, 45));
    assertSame(text, LineMentions.moved(text, FILE, 42, 0));
    assertSame(text, LineMentions.moved(text, FILE, 7, 45));
    assertSame(text, LineMentions.moved(text, null, 42, 45));
    assertSame(text, LineMentions.moved(text, "src/", 42, 45));
    assertNull(LineMentions.moved(null, FILE, 42, 45));
  }

  @Test
  void aFileAtTheRepositoryRootMatchesItsBareName() {
    assertEquals("Dockerfile:9", LineMentions.moved("Dockerfile:8", "Dockerfile", 8, 9));
  }
}
