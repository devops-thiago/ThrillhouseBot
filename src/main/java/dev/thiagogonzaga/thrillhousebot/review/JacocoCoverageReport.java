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

import io.quarkus.logging.Log;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Which source lines a JaCoCo XML report records as executable but never executed, keyed by the
 * source path the report names ({@code dev/thiagogonzaga/.../Foo.java} — the java package path plus
 * the source file name, which is a suffix of the repository path, not the repository path itself).
 *
 * <p>Only two facts are taken from the report, both per {@code <line>} element: {@code ci} (covered
 * instructions) and {@code mi} (missed instructions). A line with {@code ci + mi == 0} is not
 * executable — a blank line, a comment, a declaration the compiler emitted nothing for — and is
 * absent from the result entirely; a line with {@code ci == 0} and {@code mi > 0} is executable and
 * was never hit by any test. Nothing is inferred beyond that: this class reports what the report
 * says or reports nothing.
 *
 * <p>The bytes come from a workflow artifact uploaded by an arbitrary repository, so parsing is
 * defensive throughout — external entities and DTD loading are off, the archive's entry count and
 * uncompressed size are capped against a zip bomb, and every failure yields an {@link #EMPTY}
 * report rather than an exception.
 */
final class JacocoCoverageReport {

  /** Entries walked in the artifact archive before the rest are ignored. */
  static final int MAX_ZIP_ENTRIES = 512;

  /** Ceiling on one entry's decompressed size — a coverage report is not tens of megabytes. */
  static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

  /** Ceiling on how many source files one report may contribute. */
  static final int MAX_SOURCE_FILES = 5_000;

  /** Ceiling on uncovered lines recorded per source file. */
  static final int MAX_LINES_PER_FILE = 5_000;

  /**
   * Separator between a JaCoCo {@code <package name>} and a source file name. Not a filesystem
   * separator and never platform-dependent: the package name is the JVM internal binary name, which
   * the class-file format defines as '/'-separated on every platform, and the repository paths it
   * is matched against are git paths, also always '/'. A {@code File.separator} here would break
   * every report produced on Windows.
   */
  private static final String BINARY_PACKAGE_SEPARATOR = "/";

  /** No coverage data — the value every failure path degrades to. */
  static final JacocoCoverageReport EMPTY = new JacocoCoverageReport(Map.of());

  /** Uncovered lines per report source path, indexed by that path's file name for lookup. */
  private final Map<String, List<SourceFile>> byFileName;

  private record SourceFile(String path, NavigableSet<Integer> uncoveredLines) {}

  private JacocoCoverageReport(Map<String, NavigableSet<Integer>> uncoveredLinesByPath) {
    var index = new HashMap<String, List<SourceFile>>();
    uncoveredLinesByPath.forEach(
        (path, lines) ->
            index
                .computeIfAbsent(fileName(path), unused -> new ArrayList<>())
                .add(new SourceFile(path, lines)));
    this.byFileName = index;
  }

  boolean isEmpty() {
    return byFileName.isEmpty();
  }

  /**
   * The uncovered lines the report holds for a repository-relative path, or an empty set when the
   * report says nothing about that file.
   *
   * <p>The report names {@code dev/thiagogonzaga/x/Foo.java} while the diff names {@code
   * src/main/java/dev/thiagogonzaga/x/Foo.java}, so a path matches when the report's path is a
   * whole-segment suffix of the repository path. Two report entries matching the same repository
   * path (the same class compiled twice into different source roots) is genuine ambiguity: an empty
   * set is returned rather than a guess, because attributing another module's coverage to this file
   * would be the one failure mode that produces a wrong finding instead of no finding.
   */
  NavigableSet<Integer> uncoveredLines(String repositoryPath) {
    if (repositoryPath == null || repositoryPath.isBlank()) {
      return new TreeSet<>();
    }
    var candidates = byFileName.get(fileName(repositoryPath));
    if (candidates == null) {
      return new TreeSet<>();
    }
    SourceFile matched = null;
    for (var candidate : candidates) {
      if (isSuffixPath(repositoryPath, candidate.path())) {
        if (matched != null) {
          Log.debugf("Ambiguous coverage entries for %s; ignoring them", repositoryPath);
          return new TreeSet<>();
        }
        matched = candidate;
      }
    }
    return matched == null ? new TreeSet<>() : matched.uncoveredLines();
  }

  /** Whether {@code suffix} is {@code path} itself or a whole-segment tail of it. */
  private static boolean isSuffixPath(String path, String suffix) {
    return path.equals(suffix) || path.endsWith("/" + suffix);
  }

  private static String fileName(String path) {
    var slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  // ---------------------------------------------------------------- parsing

  /**
   * The coverage in a downloaded artifact archive: the first {@code .xml} entry that parses as a
   * JaCoCo report with at least one source file wins. {@link #EMPTY} when the bytes are not a
   * readable zip, hold no such entry, or hold nothing this parser understands.
   */
  static JacocoCoverageReport fromArtifactZip(byte[] zipBytes) {
    if (zipBytes == null || zipBytes.length == 0) {
      return EMPTY;
    }
    try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      var seen = 0;
      for (var entry = zip.getNextEntry();
          entry != null && seen < MAX_ZIP_ENTRIES;
          entry = zip.getNextEntry(), seen++) {
        if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
          // readNBytes bounds the decompressed size regardless of what the entry's header claims,
          // which is the only number a zip bomb cannot lie its way past.
          var report = parse(new ByteArrayInputStream(zip.readNBytes(MAX_ENTRY_BYTES)));
          if (!report.isEmpty()) {
            Log.infof("Read patch coverage from artifact entry %s", entry.getName());
            return report;
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      Log.debugf(e, "Could not read the coverage artifact archive");
    }
    return EMPTY;
  }

  /** Parses one JaCoCo XML document, or {@link #EMPTY} when it is not one / cannot be read. */
  static JacocoCoverageReport parse(InputStream xml) {
    XMLStreamReader reader = null;
    try {
      reader = secureInputFactory().createXMLStreamReader(xml);
      return new JacocoCoverageReport(readSourceFiles(reader));
    } catch (XMLStreamException | RuntimeException e) {
      Log.debugf(e, "Could not parse the coverage report as JaCoCo XML");
      return EMPTY;
    } finally {
      closeQuietly(reader);
    }
  }

  /**
   * Walks {@code <package>}/{@code <sourcefile>}/{@code <line>} and collects the uncovered lines of
   * each source file. The report's own path for a source file is its enclosing package name joined
   * to the file name, which is what {@link #uncoveredLines} suffix-matches against.
   */
  private static Map<String, NavigableSet<Integer>> readSourceFiles(XMLStreamReader reader)
      throws XMLStreamException {
    var result = new HashMap<String, NavigableSet<Integer>>();
    var packageName = "";
    NavigableSet<Integer> current = null;
    while (reader.hasNext()) {
      if (reader.next() != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      switch (reader.getLocalName()) {
        case "package" -> packageName = attribute(reader, "name", "");
        case "sourcefile" -> {
          var name = attribute(reader, "name", "");
          current = null;
          if (!name.isBlank() && result.size() < MAX_SOURCE_FILES) {
            var path = packageName.isBlank() ? name : packageName + BINARY_PACKAGE_SEPARATOR + name;
            current = result.computeIfAbsent(path, unused -> new TreeSet<>());
          }
        }
        case "line" -> recordLine(reader, current);
        default -> {
          // Every other element (report, class, method, counter) carries nothing we read.
        }
      }
    }
    result.values().removeIf(NavigableSet::isEmpty);
    return result;
  }

  /** Records the line when it is executable ({@code ci + mi > 0}) and was never hit. */
  private static void recordLine(XMLStreamReader reader, NavigableSet<Integer> uncovered) {
    if (uncovered == null || uncovered.size() >= MAX_LINES_PER_FILE) {
      return;
    }
    var number = intAttribute(reader, "nr");
    var covered = intAttribute(reader, "ci");
    var missed = intAttribute(reader, "mi");
    if (number > 0 && covered == 0 && missed > 0) {
      uncovered.add(number);
    }
  }

  private static String attribute(XMLStreamReader reader, String name, String fallback) {
    var value = reader.getAttributeValue(null, name);
    return value == null ? fallback : value;
  }

  /** An integer attribute, or 0 when absent or not a number — never an exception. */
  private static int intAttribute(XMLStreamReader reader, String name) {
    var raw = reader.getAttributeValue(null, name);
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException _) {
      return 0;
    }
  }

  /**
   * A parser that reads no DTD and resolves no external entity. A JaCoCo report carries a {@code
   * <!DOCTYPE report PUBLIC ... "report.dtd">} declaration, so the document must still parse with
   * the doctype present — it is the fetching of anything it references that is refused.
   */
  private static XMLInputFactory secureInputFactory() {
    var factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    setIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
    setIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory;
  }

  /** Applies a hardening property the running StAX implementation may not know about. */
  static void setIfSupported(XMLInputFactory factory, String property, Object value) {
    try {
      factory.setProperty(property, value);
    } catch (IllegalArgumentException _) {
      Log.debugf("StAX implementation does not support %s", property);
    }
  }

  /** Closes the reader, swallowing the failure the checked signature forces us to handle. */
  static void closeQuietly(XMLStreamReader reader) {
    if (reader == null) {
      return;
    }
    try {
      reader.close();
    } catch (XMLStreamException e) {
      Log.debug("Failed to close the coverage report reader", e);
    }
  }
}
