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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
