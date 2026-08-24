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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * its <em>aggregate</em> inflated size are both capped against a zip bomb (see {@link
 * #MAX_TOTAL_INFLATED_BYTES}), and every failure yields an {@link #EMPTY} report rather than an
 * exception.
 */
final class JacocoCoverageReport {

  /** Entries walked in the artifact archive before the rest are ignored. */
  static final int MAX_ZIP_ENTRIES = 512;

  /** Ceiling on one entry's decompressed size — a coverage report is not tens of megabytes. */
  static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

  /**
   * Aggregate ceiling on bytes inflated across <em>all</em> entries of one archive — the zip-bomb
   * guard. A per-entry cap alone is not enough: skipping to the next entry inflates the whole of
   * the current one, so a maximally-compressed 16&nbsp;MB artifact could otherwise drive gigabytes
   * of decompression. Every entry is read through a counting copy that charges this running budget,
   * and the walk aborts the moment the budget is blown. Generous enough for the largest legitimate
   * multi-module report (the download itself is capped far lower, at {@code
   * ArtifactZipFetcher.MAX_BYTES}), tight enough that inflation stays well under a second of CPU.
   */
  static final long MAX_TOTAL_INFLATED_BYTES = 128L * 1024 * 1024;

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
   *
   * <p>The one-path case of {@link #uncoveredLinesByPath}, and deliberately routed through it so
   * there is a single matching policy to reason about. It can only see the ambiguity one path
   * exposes; the mirror image — one report entry that matches several repository files — is
   * invisible from here, which is why the review's intersection resolves its whole file list at
   * once instead of calling this per file.
   */
  NavigableSet<Integer> uncoveredLines(String repositoryPath) {
    // singletonList, not List.of: a null path is a case the by-path resolver already answers, and
    // List.of would turn it into a NullPointerException out of a best-effort review enrichment.
    var resolved =
        uncoveredLinesByPath(Collections.singletonList(repositoryPath)).get(repositoryPath);
    return resolved == null ? new TreeSet<>() : resolved;
  }

  /**
   * Uncovered lines for a whole set of repository paths at once, dropping any attribution that is
   * ambiguous from <em>either</em> side. A repository path two report entries suffix-match is the
   * ambiguity {@link #uncoveredLines} already refuses. Its symmetric twin — one report entry that
   * suffix-matches two repository paths — is refused here too: in a multi-module or multi-variant
   * build the same {@code com/example/Foo.java} report path is a whole-segment suffix of every
   * module's copy, and a {@code <package name="">} default-package entry suffix-matches every file
   * of that name. Attributing one module's coverage to another module's same-named class is the one
   * failure mode that produces a wrong finding instead of no finding, so only a report entry that
   * uniquely matches exactly one of these paths (and is uniquely matched by it) contributes lines.
   *
   * <p>Callers that resolve a whole file list — the patch-coverage intersection — must use this
   * rather than calling {@link #uncoveredLines} per file, because the cross-file collision is
   * invisible to any single-path lookup.
   */
  Map<String, NavigableSet<Integer>> uncoveredLinesByPath(Collection<String> repositoryPaths) {
    var matchesByPath = new LinkedHashMap<String, List<SourceFile>>();
    // Keyed by identity, not by value: a SourceFile record's hashCode would hash its whole line
    // set, and the counting below only ever asks whether two paths reached the same entry object.
    var pathsPerEntry = new IdentityHashMap<SourceFile, Integer>();
    // Distinct paths, so the same file listed twice cannot make an entry look like it matches two
    // repository files and drop coverage that is in fact unambiguous.
    for (var repositoryPath : new LinkedHashSet<>(repositoryPaths)) {
      if (repositoryPath == null || repositoryPath.isBlank()) {
        continue;
      }
      var candidates = byFileName.get(fileName(repositoryPath));
      if (candidates == null) {
        continue;
      }
      var matched = new ArrayList<SourceFile>();
      for (var candidate : candidates) {
        if (isSuffixPath(repositoryPath, candidate.path())) {
          matched.add(candidate);
        }
      }
      if (matched.isEmpty()) {
        continue;
      }
      matchesByPath.put(repositoryPath, matched);
      for (var sourceFile : matched) {
        pathsPerEntry.merge(sourceFile, 1, Integer::sum);
      }
    }
    var result = new LinkedHashMap<String, NavigableSet<Integer>>();
    for (var entry : matchesByPath.entrySet()) {
      var matched = entry.getValue();
      if (matched.size() != 1) {
        Log.debugf("Ambiguous coverage entries for %s; ignoring them", entry.getKey());
        continue;
      }
      var sourceFile = matched.get(0);
      if (pathsPerEntry.get(sourceFile) != 1) {
        Log.debugf(
            "Coverage entry %s matches more than one repository file; ignoring it",
            sourceFile.path());
        continue;
      }
      result.put(entry.getKey(), sourceFile.uncoveredLines());
    }
    return result;
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
   * The coverage in a downloaded artifact archive: <em>every</em> {@code .xml} entry that parses as
   * a JaCoCo report is merged, so a multi-module artifact carrying one report per module
   * contributes all of them rather than only the first. {@link #EMPTY} when the bytes are not a
   * readable zip, hold no such entry, or hold nothing this parser understands. The merged size is
   * bounded by {@link #MAX_SOURCE_FILES} (and each file by {@link #MAX_LINES_PER_FILE}); a path two
   * reports both describe keeps only what they agree on, for the reasons on {@link #mergeInto}.
   *
   * <p>Every entry — not only the {@code .xml} we want — is inflated through a counting copy
   * bounded by {@link #MAX_TOTAL_INFLATED_BYTES}. Reading only the entries we care about is not
   * enough: the next {@link ZipInputStream#getNextEntry()} implicitly inflates the whole of an
   * unread entry to reach the following header, which is exactly the path a maximally-compressed
   * archive takes to gigabytes. The walk aborts the moment the aggregate budget is blown, keeping
   * whatever it had already merged.
   */
  static JacocoCoverageReport fromArtifactZip(byte[] zipBytes) {
    if (zipBytes == null || zipBytes.length == 0) {
      return EMPTY;
    }
    var merged = new HashMap<String, NavigableSet<Integer>>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      var seen = 0;
      var inflatedTotal = 0L;
      for (var entry = zip.getNextEntry();
          entry != null && seen < MAX_ZIP_ENTRIES;
          entry = zip.getNextEntry(), seen++) {
        var budgetLeft = MAX_TOTAL_INFLATED_BYTES - inflatedTotal;
        var isReport =
            !entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml");
        // Every entry is drained through the counting copy — a report to collect, anything else to
        // discard — because leaving an entry partly read would hand the implicit inflation back to
        // the next getNextEntry(). Only the aggregate budget can stop the drain.
        var sink = isReport ? new ByteArrayOutputStream() : null;
        var read = inflateEntry(zip, budgetLeft, MAX_ENTRY_BYTES, sink);
        if (read < 0) {
          Log.debugf(
              "Coverage artifact inflates past the %d-byte aggregate cap; refusing it as a zip bomb",
              MAX_TOTAL_INFLATED_BYTES);
          break;
        }
        inflatedTotal += read;
        if (sink != null && sink.size() > 0) {
          var one = parseToMap(new ByteArrayInputStream(sink.toByteArray()));
          if (!one.isEmpty()) {
            Log.infof("Read patch coverage from artifact entry %s", entry.getName());
            mergeInto(merged, one);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      Log.debugf(e, "Could not read the coverage artifact archive");
    }
    // An intersection that emptied out says every report disagreed about that file, which is not
    // coverage data; the rest of the class may assume a present path has at least one line.
    merged.values().removeIf(NavigableSet::isEmpty);
    return merged.isEmpty() ? EMPTY : new JacocoCoverageReport(merged);
  }

  /**
   * Adds one report's uncovered lines to the accumulator, bounded by {@link #MAX_SOURCE_FILES} so a
   * pathological multi-module artifact cannot grow the merged map without limit — each file's line
   * list is already capped at {@link #MAX_LINES_PER_FILE} by the parse that produced it.
   *
   * <p>A report path <em>two</em> reports both describe keeps only the lines every one of them
   * recorded as missed, never their union. Two reports claim one path when a same-named class
   * exists in two modules, and when one class is measured twice — a per-module report sitting next
   * to an aggregate report of the same build, which the {@code **}{@code /jacoco.xml} upload this
   * merge exists for collects together. Unioning is wrong for both: it charges one module's misses
   * to the other module's file, and it reports a line as never executed that the aggregate run did
   * execute. Neither is something {@link #uncoveredLinesByPath} can catch afterwards — it sees one
   * merged entry with one repository file matching it — so the disagreement has to be resolved
   * here, at the only point that still knows two reports claimed the same path. Intersecting is
   * sound whichever file the entry later matches: a line every report recorded as missed is missed
   * in that file's own report too.
   */
  private static void mergeInto(
      Map<String, NavigableSet<Integer>> merged, Map<String, NavigableSet<Integer>> one) {
    for (var entry : one.entrySet()) {
      var already = merged.get(entry.getKey());
      if (already != null) {
        Log.debugf(
            "Two coverage reports describe %s; keeping only what they agree on", entry.getKey());
        already.retainAll(entry.getValue());
        continue;
      }
      if (merged.size() >= MAX_SOURCE_FILES) {
        continue;
      }
      merged.put(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Fully inflates the current zip entry, charging its bytes against {@code budgetLeft}. Returns
   * the number of bytes inflated, or {@code -1} the moment that running aggregate budget is
   * exceeded — the archive is then a decompression bomb and the caller must abandon it. The whole
   * entry is always consumed (short of an abort) so the next {@code getNextEntry()} never has to
   * inflate a remainder. When {@code sink} is non-null, up to {@code collectLimit} bytes are
   * captured into it for parsing; a report larger than that captures nothing — a truncated report
   * is not one — but is still drained so the walk can safely reach the next entry.
   *
   * <p>Package-private so a test can drive the two bounds directly: reaching either through {@link
   * #fromArtifactZip} alone would mean building a 64&nbsp;MB XML entry.
   */
  static long inflateEntry(
      ZipInputStream zip, long budgetLeft, int collectLimit, ByteArrayOutputStream sink)
      throws IOException {
    var buffer = new byte[8192];
    var read = 0L;
    var collecting = sink != null;
    int n;
    while ((n = zip.read(buffer)) != -1) {
      read += n;
      if (read > budgetLeft) {
        return -1;
      }
      if (collecting) {
        if (read > collectLimit) {
          collecting = false;
          sink.reset();
        } else {
          sink.write(buffer, 0, n);
        }
      }
    }
    return read;
  }

  /** Parses one JaCoCo XML document, or {@link #EMPTY} when it is not one / cannot be read. */
  static JacocoCoverageReport parse(InputStream xml) {
    var map = parseToMap(xml);
    return map.isEmpty() ? EMPTY : new JacocoCoverageReport(map);
  }

  /**
   * The raw uncovered-lines-per-report-path map of one JaCoCo XML document, or an empty map when it
   * is not one / cannot be read. The archive walk merges these across entries before building a
   * single report; {@link #parse} wraps one directly.
   */
  static Map<String, NavigableSet<Integer>> parseToMap(InputStream xml) {
    XMLStreamReader reader = null;
    try {
      reader = secureInputFactory().createXMLStreamReader(xml);
      return readSourceFiles(reader);
    } catch (XMLStreamException | RuntimeException e) {
      Log.debugf(e, "Could not parse the coverage report as JaCoCo XML");
      return Map.of();
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
