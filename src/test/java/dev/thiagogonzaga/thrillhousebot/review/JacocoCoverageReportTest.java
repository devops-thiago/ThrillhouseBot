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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link JacocoCoverageReport} — reading a repository's uploaded JaCoCo XML report
 * and answering which lines it records as executable but never executed (#115).
 */
class JacocoCoverageReportTest {

  /**
   * Line 12 is fully covered, 13 and 14 are executable but never hit, 15 is not executable at all,
   * and 16 is PARTIALLY covered — some instructions missed, but the line did execute.
   */
  private static final String REPORT =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
      <report name="thrillhousebot">
        <package name="dev/thiagogonzaga/thrillhousebot/review">
          <sourcefile name="CiStatusEvaluator.java">
            <line nr="12" mi="0" ci="4" mb="0" cb="0"/>
            <line nr="13" mi="3" ci="0" mb="0" cb="0"/>
            <line nr="14" mi="6" ci="0" mb="2" cb="0"/>
            <line nr="15" mi="0" ci="0" mb="0" cb="0"/>
            <line nr="16" mi="2" ci="5" mb="1" cb="1"/>
          </sourcefile>
        </package>
      </report>
      """;

  private static JacocoCoverageReport parse(String xml) {
    return JacocoCoverageReport.parse(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] zipOf(Map<String, String> entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  @Nested
  class Parsing {

    @Test
    void reportsOnlyExecutableLinesWithZeroHits() {
      var report = parse(REPORT);

      assertEquals(
          java.util.List.of(13, 14),
          java.util.List.copyOf(
              report.uncoveredLines(
                  "src/main/java/dev/thiagogonzaga/thrillhousebot/review/CiStatusEvaluator.java")),
          "only ci=0: a fully covered line, a partially covered one (mi>0 but ci>0), and a"
              + " non-executable line (ci=mi=0) are all excluded");
    }

    @Test
    void matchesTheRepositoryPathBySourceRootSuffix() {
      var report = parse(REPORT);

      assertFalse(
          report
              .uncoveredLines(
                  "src/main/java/dev/thiagogonzaga/thrillhousebot/review/CiStatusEvaluator.java")
              .isEmpty(),
          "the report names the package path; the diff names the repository path");
      assertTrue(
          report.uncoveredLines("other/dev/thiagogonzaga/CiStatusEvaluator.java").isEmpty(),
          "a same-named file under a different package must not match");
    }

    @Test
    void refusesToGuessBetweenTwoMatchingSourceEntries() {
      var report =
          parse(
              """
              <report name="multi">
                <package name="a/b">
                  <sourcefile name="Foo.java"><line nr="1" mi="1" ci="0"/></sourcefile>
                </package>
                <package name="x/a/b">
                  <sourcefile name="Foo.java"><line nr="2" mi="1" ci="0"/></sourcefile>
                </package>
              </report>
              """);

      assertTrue(
          report.uncoveredLines("mod/x/a/b/Foo.java").isEmpty(),
          "two report entries suffix-match this path; guessing would attribute the wrong lines");
      assertFalse(
          report.uncoveredLines("mod/src/a/b/Foo.java").isEmpty(), "an unambiguous path resolves");
    }

    /**
     * A guard, not a proof: the JDK's StAX also refuses an external entity inside an attribute
     * value on its own (XML forbids it), so flipping the hardening flags alone does not break this
     * assertion. It still pins the observable property — an uploaded report cannot smuggle file
     * content into a source path — and the hardening itself is load-bearing for a different reason:
     * with DTD support left on, every real JaCoCo report fails to parse because the parser goes
     * looking for the {@code report.dtd} its DOCTYPE names.
     */
    @Test
    void anUploadedReportCannotSmuggleFileContentIntoASourcePath(@TempDir Path tempDir)
        throws IOException {
      var secret = tempDir.resolve("secret.txt");
      Files.writeString(secret, "TOP-SECRET");
      var xml =
          "<?xml version=\"1.0\"?>"
              + "<!DOCTYPE report [<!ENTITY xxe SYSTEM \""
              + secret.toUri()
              + "\">]>"
              + "<report><package name=\"a\"><sourcefile name=\"&xxe;\">"
              + "<line nr=\"1\" mi=\"1\" ci=\"0\"/></sourcefile></package></report>";

      var report = parse(xml);

      assertTrue(
          report.uncoveredLines("a/TOP-SECRET").isEmpty(),
          "an external entity must never be resolved out of an uploaded artifact");
    }

    @Test
    void saysNothingAboutAPathItWasNotAsked() {
      var report = parse(REPORT);

      assertTrue(report.uncoveredLines(null).isEmpty(), "a null path resolves to nothing");
      assertTrue(report.uncoveredLines("   ").isEmpty(), "a blank path resolves to nothing");
      assertTrue(
          report.uncoveredLines("src/main/java/dev/other/Unrelated.java").isEmpty(),
          "a file the report never mentions is not 'uncovered' — it is unmeasured");
    }

    @Test
    void readsASourceFileInTheDefaultPackage() {
      var report =
          parse(
              """
              <report name="r">
                <package name="">
                  <sourcefile name="Root.java"><line nr="7" mi="2" ci="0"/></sourcefile>
                </package>
              </report>
              """);

      assertEquals(
          List.of(7),
          List.copyOf(report.uncoveredLines("Root.java")),
          "an empty package name must not produce a leading-slash path that matches nothing");
    }

    @Test
    void ignoresLineElementsThatCarryNoUsableNumbers() {
      var report =
          parse(
              """
              <report name="r">
                <line nr="1" mi="9" ci="0"/>
                <package>
                  <sourcefile name="Nameless.java"><line nr="2" mi="1" ci="0"/></sourcefile>
                </package>
                <package name="a">
                  <sourcefile name="Odd.java">
                    <line nr="0" mi="9" ci="0"/>
                    <line nr="-3" mi="9" ci="0"/>
                    <line nr="not-a-number" mi="9" ci="0"/>
                    <line nr="4" mi="" ci="0"/>
                    <line nr="5" ci="0"/>
                    <line nr="6" mi="9" ci="0"/>
                  </sourcefile>
                  <sourcefile name="">
                    <line nr="8" mi="9" ci="0"/>
                  </sourcefile>
                </package>
              </report>
              """);

      assertEquals(
          List.of(6),
          List.copyOf(report.uncoveredLines("a/Odd.java")),
          "a non-positive, unparseable, or missing-counter line is dropped, not guessed at");
      assertTrue(
          report.uncoveredLines("a/").isEmpty(), "a sourcefile with no name contributes nothing");
      assertEquals(
          List.of(2),
          List.copyOf(report.uncoveredLines("Nameless.java")),
          "a package element with no name attribute falls back to the default package");
    }

    @Test
    void capsHowManyLinesOneSourceFileMayContribute() {
      var xml = new StringBuilder("<report name=\"r\"><package name=\"a\">");
      xml.append("<sourcefile name=\"Huge.java\">");
      for (var nr = 1; nr <= JacocoCoverageReport.MAX_LINES_PER_FILE + 50; nr++) {
        xml.append("<line nr=\"").append(nr).append("\" mi=\"1\" ci=\"0\"/>");
      }
      xml.append("</sourcefile></package></report>");

      assertEquals(
          JacocoCoverageReport.MAX_LINES_PER_FILE,
          parse(xml.toString()).uncoveredLines("a/Huge.java").size(),
          "a pathological report cannot make one file's line list unbounded");
    }

    @Test
    void capsHowManySourceFilesOneReportMayContribute() {
      var xml = new StringBuilder("<report name=\"r\"><package name=\"a\">");
      var overflow = JacocoCoverageReport.MAX_SOURCE_FILES + 5;
      for (var i = 0; i < overflow; i++) {
        xml.append("<sourcefile name=\"F")
            .append(i)
            .append(".java\"><line nr=\"1\" mi=\"1\" ci=\"0\"/></sourcefile>");
      }
      xml.append("</package></report>");
      var report = parse(xml.toString());

      assertFalse(report.uncoveredLines("a/F0.java").isEmpty(), "files under the cap are kept");
      assertTrue(
          report.uncoveredLines("a/F" + (overflow - 1) + ".java").isEmpty(),
          "files past the cap are dropped rather than growing the map without bound");
    }

    @Test
    void degradesToEmptyWhenTheDocumentCannotEvenBeOpened() {
      InputStream failing =
          new InputStream() {
            @Override
            public int read() throws IOException {
              throw new IOException("stream died before the prolog");
            }
          };

      assertTrue(
          JacocoCoverageReport.parse(failing).isEmpty(),
          "a reader that was never constructed must still be closed safely");
    }

    @Test
    void swallowsAFailureToCloseTheReader() throws XMLStreamException {
      var reader = mock(XMLStreamReader.class);
      doThrow(new XMLStreamException("close failed")).when(reader).close();

      assertDoesNotThrow(() -> JacocoCoverageReport.closeQuietly(reader));
    }

    @Test
    void skipsAHardeningPropertyTheParserDoesNotKnow() {
      var factory = XMLInputFactory.newInstance();

      assertDoesNotThrow(
          () -> JacocoCoverageReport.setIfSupported(factory, "urn:no-such-stax-property", ""),
          "an implementation that rejects a hardening property must not fail the parse");
    }

    @Test
    void degradesToEmptyForContentThatIsNotAJacocoReport() {
      assertTrue(parse("not xml at all <<<").isEmpty(), "malformed XML must not throw");
      assertTrue(
          parse("<checkstyle><file name=\"A.java\"/></checkstyle>").isEmpty(),
          "a well-formed XML document of another shape carries no coverage");
    }
  }

  @Nested
  class ArtifactArchive {

    @Test
    void readsTheReportOutOfTheUploadedZip() throws IOException {
      var entries = new LinkedHashMap<String, String>();
      entries.put("build.log", "irrelevant");
      entries.put("site/jacoco/jacoco.xml", REPORT);

      var report = JacocoCoverageReport.fromArtifactZip(zipOf(entries));

      assertFalse(
          report
              .uncoveredLines(
                  "src/main/java/dev/thiagogonzaga/thrillhousebot/review/CiStatusEvaluator.java")
              .isEmpty(),
          "the report entry must be found regardless of its path inside the archive");
    }

    @Test
    void skipsXmlEntriesThatCarryNoCoverage() throws IOException {
      var entries = new LinkedHashMap<String, String>();
      entries.put("empty.xml", "");
      entries.put("a-surefire-report.xml", "<testsuite name=\"x\"/>");
      entries.put("jacoco.xml", REPORT);

      assertFalse(
          JacocoCoverageReport.fromArtifactZip(zipOf(entries)).isEmpty(),
          "an empty or unrelated XML entry earlier in the archive must not end the search");
    }

    @Test
    void ignoresDirectoryEntriesAndStopsAtTheEntryCap() throws IOException {
      var withDirectory = new LinkedHashMap<String, String>();
      withDirectory.put("site/", "");
      withDirectory.put("site/jacoco.xml", REPORT);
      assertFalse(
          JacocoCoverageReport.fromArtifactZip(zipOf(withDirectory)).isEmpty(),
          "a directory entry is skipped rather than parsed");

      var padded = new LinkedHashMap<String, String>();
      for (var i = 0; i < JacocoCoverageReport.MAX_ZIP_ENTRIES + 5; i++) {
        padded.put("pad" + i + ".txt", "x");
      }
      padded.put("jacoco.xml", REPORT);
      assertTrue(
          JacocoCoverageReport.fromArtifactZip(zipOf(padded)).isEmpty(),
          "the walk stops at the entry cap instead of reading an unbounded archive");
    }

    @Test
    void mergesEveryXmlReportNotJustTheFirst() throws IOException {
      var entries = new LinkedHashMap<String, String>();
      entries.put(
          "module-a/jacoco.xml",
          """
          <report name="a">
            <package name="com/example/a">
              <sourcefile name="Alpha.java"><line nr="1" mi="1" ci="0"/></sourcefile>
            </package>
          </report>
          """);
      entries.put(
          "module-b/jacoco.xml",
          """
          <report name="b">
            <package name="com/example/b">
              <sourcefile name="Beta.java"><line nr="2" mi="1" ci="0"/></sourcefile>
            </package>
          </report>
          """);

      var report = JacocoCoverageReport.fromArtifactZip(zipOf(entries));

      assertEquals(
          List.of(1),
          List.copyOf(report.uncoveredLines("module-a/src/main/java/com/example/a/Alpha.java")),
          "the first module's report is read");
      assertEquals(
          List.of(2),
          List.copyOf(report.uncoveredLines("module-b/src/main/java/com/example/b/Beta.java")),
          "a second module's report must not be lost to a first-match-wins walk");
    }

    @Test
    void degradesToEmptyWhenTheArchiveIsTruncatedMidEntry() throws IOException {
      var whole = zipOf(Map.of("jacoco.xml", REPORT));
      var truncated = java.util.Arrays.copyOf(whole, whole.length / 2);

      assertTrue(
          JacocoCoverageReport.fromArtifactZip(truncated).isEmpty(),
          "a half-downloaded archive must degrade, not throw out of the review");
    }

    @Test
    void refusesAnArchiveThatInflatesPastTheAggregateCap() throws IOException {
      // Every bomb entry stays WELL under the per-entry ceiling, so only their running sum can
      // refuse this archive: a per-entry bound alone — what the reader had before — lets all of it
      // through. Nothing here is XML either, which is the whole point: the unfixed walk skipped a
      // non-XML entry without reading it, and the next getNextEntry() then inflated the whole of it
      // to reach the following header. The real report sits BEHIND the bomb, so a reader that
      // reaches it is a reader that paid the full inflation cost first.
      var perEntry = JacocoCoverageReport.MAX_ENTRY_BYTES / 2;
      assertTrue(
          perEntry < JacocoCoverageReport.MAX_TOTAL_INFLATED_BYTES,
          "no single entry may blow the budget on its own, or it is not the aggregate bound this"
              + " archive is refused by");
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes)) {
        var zeros = new byte[64 * 1024];
        var inflated = 0L;
        for (var i = 0; inflated <= JacocoCoverageReport.MAX_TOTAL_INFLATED_BYTES; i++) {
          zip.putNextEntry(new ZipEntry("pad" + i + ".bin"));
          for (var written = 0; written < perEntry; written += zeros.length) {
            zip.write(zeros);
          }
          zip.closeEntry();
          inflated += perEntry;
        }
        zip.putNextEntry(new ZipEntry("jacoco.xml"));
        zip.write(REPORT.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }

      assertTrue(
          JacocoCoverageReport.fromArtifactZip(bytes.toByteArray()).isEmpty(),
          "an archive inflating past the aggregate cap must be refused, not walked to the report"
              + " hidden behind the bomb");
    }

    @Test
    void refusesAnArchiveWithMoreEntriesThanItWalks() throws IOException {
      // Same prefix-choosing power as the bomb abort, reached by padding instead: the report is
      // read, the cap is hit, and the merge that fit would otherwise be returned as this build's
      // coverage with every line the unread reports cover reported uncovered.
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes)) {
        zip.putNextEntry(new ZipEntry("jacoco.xml"));
        zip.write(REPORT.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        for (var i = 0; i <= JacocoCoverageReport.MAX_ZIP_ENTRIES; i++) {
          zip.putNextEntry(new ZipEntry("pad" + i + ".txt"));
          zip.write(new byte[] {'x'});
          zip.closeEntry();
        }
      }

      assertTrue(
          JacocoCoverageReport.fromArtifactZip(bytes.toByteArray()).isEmpty(),
          "an archive longer than the entry cap yields EMPTY, not the prefix that fit");
    }

    @Test
    void readsAnArchiveThatExactlyFillsTheEntryCap() throws IOException {
      // The boundary the refusal must not swallow: an archive using every entry it is allowed is
      // read in full, so the cap refuses only what it cannot walk.
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes)) {
        zip.putNextEntry(new ZipEntry("jacoco.xml"));
        zip.write(REPORT.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        for (var i = 0; i < JacocoCoverageReport.MAX_ZIP_ENTRIES - 1; i++) {
          zip.putNextEntry(new ZipEntry("pad" + i + ".txt"));
          zip.write(new byte[] {'x'});
          zip.closeEntry();
        }
      }

      assertFalse(
          JacocoCoverageReport.fromArtifactZip(bytes.toByteArray()).isEmpty(),
          "an archive that fits inside the cap is still read");
    }

    @Test
    void carriesNoPartialAnswerOutOfAnArchiveItRefused() throws IOException {
      // The report sits BEFORE the bomb this time, so the walk has merged real coverage by the
      // moment it gives up. Returning that prefix would let whoever built the archive choose which
      // reports the merge sees: append a bomb after a benign report and the truncated result reads
      // as complete coverage, so every line the rest of the artifact covers comes back uncovered.
      var perEntry = JacocoCoverageReport.MAX_ENTRY_BYTES / 2;
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes)) {
        zip.putNextEntry(new ZipEntry("jacoco.xml"));
        zip.write(REPORT.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        var zeros = new byte[64 * 1024];
        var inflated = 0L;
        for (var i = 0; inflated <= JacocoCoverageReport.MAX_TOTAL_INFLATED_BYTES; i++) {
          zip.putNextEntry(new ZipEntry("pad" + i + ".bin"));
          for (var written = 0; written < perEntry; written += zeros.length) {
            zip.write(zeros);
          }
          zip.closeEntry();
          inflated += perEntry;
        }
      }

      assertTrue(
          JacocoCoverageReport.fromArtifactZip(bytes.toByteArray()).isEmpty(),
          "a refused archive yields EMPTY, not the reports that happened to precede the bomb");
    }

    @Test
    void drainsButCollectsNothingFromAnEntryPastThePerEntryCeiling() throws IOException {
      var sink = new ByteArrayOutputStream();
      try (var zip =
          new ZipInputStream(new ByteArrayInputStream(zipOf(Map.of("jacoco.xml", REPORT))))) {
        zip.getNextEntry();

        var read = JacocoCoverageReport.inflateEntry(zip, Long.MAX_VALUE, 8, sink);

        assertEquals(
            REPORT.getBytes(StandardCharsets.UTF_8).length,
            read,
            "the entry is drained in full even when nothing is kept, so the walk can reach the"
                + " next header without the implicit inflation");
        assertEquals(
            0,
            sink.size(),
            "a report past the per-entry ceiling contributes nothing — half a report is not a"
                + " report");
      }
    }

    @Test
    void capsTheSourceFilesMergedAcrossReports() throws IOException {
      var first = new StringBuilder("<report name=\"a\"><package name=\"a\">");
      for (var i = 0; i < JacocoCoverageReport.MAX_SOURCE_FILES; i++) {
        first
            .append("<sourcefile name=\"F")
            .append(i)
            .append(".java\"><line nr=\"1\" mi=\"1\" ci=\"0\"/></sourcefile>");
      }
      first.append("</package></report>");
      var entries = new LinkedHashMap<String, String>();
      entries.put("module-a/jacoco.xml", first.toString());
      entries.put(
          "module-b/jacoco.xml",
          """
          <report name="b">
            <package name="b">
              <sourcefile name="Extra.java"><line nr="1" mi="1" ci="0"/></sourcefile>
            </package>
          </report>
          """);

      var report = JacocoCoverageReport.fromArtifactZip(zipOf(entries));

      assertFalse(
          report.uncoveredLines("mod/a/F0.java").isEmpty(), "the first report's files are merged");
      assertTrue(
          report.uncoveredLines("mod/b/Extra.java").isEmpty(),
          "merging a second report cannot grow the map past the source-file cap");
    }

    @Test
    void keepsOnlyWhatTwoReportsOfTheSamePathAgreeOn() throws IOException {
      // Two reports in one artifact both claiming com/example/Foo.java, which is what a
      // **/jacoco.xml upload collects when a class exists in two modules, or when a per-module
      // report sits next to an aggregate report of the same build. Unioning their misses charges
      // one build's misses to the other's file — and the by-path ambiguity guard cannot catch it
      // afterwards, because the merge has collapsed the two into one entry that exactly one
      // repository file matches.
      var entries = new LinkedHashMap<String, String>();
      entries.put(
          "module-a/jacoco.xml",
          """
          <report name="a">
            <package name="com/example">
              <sourcefile name="Foo.java">
                <line nr="1" mi="1" ci="0"/>
                <line nr="2" mi="1" ci="0"/>
              </sourcefile>
            </package>
          </report>
          """);
      entries.put(
          "module-b/jacoco.xml",
          """
          <report name="b">
            <package name="com/example">
              <sourcefile name="Foo.java">
                <line nr="2" mi="1" ci="0"/>
                <line nr="3" mi="1" ci="0"/>
              </sourcefile>
              <sourcefile name="Bar.java"><line nr="7" mi="1" ci="0"/></sourcefile>
            </package>
          </report>
          """);

      var report = JacocoCoverageReport.fromArtifactZip(zipOf(entries));

      assertEquals(
          List.of(2),
          List.copyOf(report.uncoveredLines("module-a/src/main/java/com/example/Foo.java")),
          "only a line every report recorded as missed may be reported; a line one of them saw"
              + " executed was executed");
      assertEquals(
          List.of(7),
          List.copyOf(report.uncoveredLines("module-b/src/main/java/com/example/Bar.java")),
          "a path only one report describes is untouched by that intersection");
    }

    @Test
    void dropsAPathTwoReportsAgreeOnNothingAbout() throws IOException {
      var entries = new LinkedHashMap<String, String>();
      entries.put(
          "module-a/jacoco.xml",
          """
          <report name="a">
            <package name="com/example">
              <sourcefile name="Foo.java"><line nr="1" mi="1" ci="0"/></sourcefile>
            </package>
          </report>
          """);
      entries.put(
          "module-b/jacoco.xml",
          """
          <report name="b">
            <package name="com/example">
              <sourcefile name="Foo.java"><line nr="2" mi="1" ci="0"/></sourcefile>
            </package>
          </report>
          """);

      assertTrue(
          JacocoCoverageReport.fromArtifactZip(zipOf(entries)).isEmpty(),
          "two reports that agree on nothing leave no coverage behind, not an empty entry the"
              + " rest of the reader would have to special-case");
    }

    @Test
    void degradesToEmptyWhenTheArchiveIsUnusable() throws IOException {
      assertTrue(JacocoCoverageReport.fromArtifactZip(null).isEmpty(), "no bytes, no coverage");
      assertTrue(JacocoCoverageReport.fromArtifactZip(new byte[0]).isEmpty(), "empty bytes");
      assertTrue(
          JacocoCoverageReport.fromArtifactZip("this is not a zip".getBytes(StandardCharsets.UTF_8))
              .isEmpty(),
          "a body that is not a zip must degrade, not throw");
      assertTrue(
          JacocoCoverageReport.fromArtifactZip(zipOf(Map.of("README.md", "hi"))).isEmpty(),
          "an archive with no XML entry carries no coverage");
    }
  }
}
