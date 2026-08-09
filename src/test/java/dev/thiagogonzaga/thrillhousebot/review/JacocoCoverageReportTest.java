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
      entries.put("a-surefire-report.xml", "<testsuite name=\"x\"/>");
      entries.put("jacoco.xml", REPORT);

      assertFalse(
          JacocoCoverageReport.fromArtifactZip(zipOf(entries)).isEmpty(),
          "an unrelated XML entry earlier in the archive must not end the search");
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
    void degradesToEmptyWhenTheArchiveIsTruncatedMidEntry() throws IOException {
      var whole = zipOf(Map.of("jacoco.xml", REPORT));
      var truncated = java.util.Arrays.copyOf(whole, whole.length / 2);

      assertTrue(
          JacocoCoverageReport.fromArtifactZip(truncated).isEmpty(),
          "a half-downloaded archive must degrade, not throw out of the review");
    }

    @Test
    void refusesAnArchiveThatInflatesPastTheAggregateCap() throws IOException {
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes)) {
        // A maximally-compressible entry whose inflated size alone blows the aggregate budget,
        // placed BEFORE the real report so reaching the report requires inflating the bomb. On the
        // unfixed code, skipping this non-XML entry inflates the whole of it to find the next
        // header, and the report behind it is then read as fact.
        zip.putNextEntry(new ZipEntry("bomb.bin"));
        var zeros = new byte[64 * 1024];
        var written = 0L;
        var target = JacocoCoverageReport.MAX_TOTAL_INFLATED_BYTES + zeros.length;
        while (written <= target) {
          zip.write(zeros);
          written += zeros.length;
        }
        zip.closeEntry();
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
