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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
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
import java.util.zip.ZipEntry;
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
 * defensive throughout — external entities and DTD loading are off, the archive's report-entry
 * count and its <em>aggregate</em> inflated size are both capped against a zip bomb (see {@link
 * #MAX_TOTAL_INFLATED_BYTES}), and every failure yields an empty report rather than an exception.
 * An archive the walk refused says why on that empty report ({@link #refusal}), so the review can
 * tell a maintainer who configured the artifact that it was not read (#813).
 */
final class JacocoCoverageReport {

  /**
   * Ceiling on the entries that can carry a report — names ending in {@code .xml} — before the
   * archive is refused whole. Nothing else counts: the usual upload is the whole {@code
   * target/site/jacoco/} tree, one {@code .html} per class beside the one {@code jacoco.xml}, and a
   * cap that charged every file refused that shape on a project of a few hundred classes with a
   * single legitimate report inside (#813). The entries left uncounted are still drained against
   * {@link #MAX_TOTAL_INFLATED_BYTES}, and {@code ArtifactZipFetcher.MAX_BYTES} bounds how many of
   * them a download can hold at all.
   */
  static final int MAX_ZIP_ENTRIES = 512;

  /**
   * Ceiling on how much of one entry is <em>kept</em> for parsing — a coverage report is not tens
   * of megabytes. Deliberately not a ceiling on inflation: an entry past this is still drained in
   * full and simply contributes nothing (half a report is not a report), because stopping the read
   * early would hand the rest of the entry to the next {@code getNextEntry()} to inflate anyway.
   * Only {@link #MAX_TOTAL_INFLATED_BYTES} bounds the decompression itself.
   */
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
  static final JacocoCoverageReport EMPTY = new JacocoCoverageReport(Map.of(), null);

  /**
   * Why {@link #fromArtifactZip} gave up on an archive, carried on the empty report it returns so
   * the review can say so (#813). Each reason is phrased for the summary's review-scope note, where
   * it follows "the configured coverage artifact was not read:". An archive that was merely
   * unhelpful — no {@code .xml} entry, nothing that parsed as JaCoCo — is not refused and carries
   * no reason: that is the designed quiet path for a repository publishing nothing usable.
   */
  enum Refusal {
    /**
     * More {@code .xml} entries than {@link JacocoCoverageReport#MAX_ZIP_ENTRIES}. Merging the
     * prefix that fit would let whoever built the archive choose which reports the review saw.
     */
    ENTRY_CAP("it holds more than " + MAX_ZIP_ENTRIES + " `.xml` entries"),
    /**
     * Inflation past {@link JacocoCoverageReport#MAX_TOTAL_INFLATED_BYTES}: a zip bomb, or an
     * upload far larger than any coverage report.
     */
    INFLATION_BUDGET(
        "it inflates past the " + (MAX_TOTAL_INFLATED_BYTES >> 20) + " MB decompression limit"),
    /** The bytes broke mid-walk — a truncated download, or a body that is not a zip at all. */
    UNREADABLE("it could not be read as a zip archive");

    private final String reason;

    Refusal(String reason) {
      this.reason = reason;
    }

    /** The reason in the summary's words, without the lead-in. */
    String reason() {
      return reason;
    }
  }

  /** Uncovered lines per report source path, indexed by that path's file name for lookup. */
  private final Map<String, List<SourceFile>> byFileName;

  /**
   * Why the archive walk gave up, or {@code null} for a report read to the end — {@link #EMPTY}
   * included, since an archive that held nothing usable was not refused.
   */
  private final Refusal refusal;

  private record SourceFile(String path, NavigableSet<Integer> uncoveredLines) {}

  private JacocoCoverageReport(
      Map<String, NavigableSet<Integer>> uncoveredLinesByPath, Refusal refusal) {
    var index = new HashMap<String, List<SourceFile>>();
    uncoveredLinesByPath.forEach(
        (path, lines) ->
            index
                .computeIfAbsent(fileName(path), unused -> new ArrayList<>())
                .add(new SourceFile(path, lines)));
    this.byFileName = index;
    this.refusal = refusal;
  }

  /** An empty report that also says why the archive it came from was not read. */
  private static JacocoCoverageReport refused(Refusal refusal) {
    return new JacocoCoverageReport(Map.of(), refusal);
  }

  boolean isEmpty() {
    return byFileName.isEmpty();
  }

  /**
   * Why {@link #fromArtifactZip} refused the archive, or {@code null} when nothing was refused. An
   * empty report with no refusal is the common "nothing usable was published" case and is not
   * disclosed; a refused one is, because the artifact was there and this reader would not read it.
   */
  Refusal refusal() {
    return refusal;
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
      var matched = suffixMatches(repositoryPath);
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
      var sourceFile = unambiguousEntry(entry.getKey(), entry.getValue(), pathsPerEntry);
      if (sourceFile != null) {
        result.put(entry.getKey(), sourceFile.uncoveredLines());
      }
    }
    return result;
  }

  /**
   * Every report entry whose path is a whole-segment suffix of {@code repositoryPath}; empty for a
   * null, blank, or unmentioned path. The order is unspecified — the index behind it is hashed by
   * file name — and nothing here depends on it, because a second match makes the attribution
   * ambiguous whichever one came first. Deliberately says nothing about that ambiguity: whether
   * more than one match must be refused, and whether the single match is also claimed by another
   * repository path, are decisions only {@link #uncoveredLinesByPath} can make, because they depend
   * on the rest of the requested paths.
   */
  private List<SourceFile> suffixMatches(String repositoryPath) {
    if (repositoryPath == null || repositoryPath.isBlank()) {
      return List.of();
    }
    var candidates = byFileName.get(fileName(repositoryPath));
    if (candidates == null) {
      return List.of();
    }
    var matched = new ArrayList<SourceFile>();
    for (var candidate : candidates) {
      if (isSuffixPath(repositoryPath, candidate.path())) {
        matched.add(candidate);
      }
    }
    return matched;
  }

  /**
   * The one report entry {@code repositoryPath} may take its lines from, or {@code null} when the
   * attribution is ambiguous from either side: several entries suffix-match this path, or the
   * single entry that does is also suffix-matched by another path in the same request ({@code
   * pathsPerEntry} holds that count, built over all of them). Both directions are refused rather
   * than guessed at, for the reason on {@link #uncoveredLinesByPath} — another module's coverage
   * charged to this file is a wrong finding, where no attribution is merely no finding.
   */
  private static SourceFile unambiguousEntry(
      String repositoryPath, List<SourceFile> matched, Map<SourceFile, Integer> pathsPerEntry) {
    if (matched.size() != 1) {
      Log.debugf("Ambiguous coverage entries for %s; ignoring them", repositoryPath);
      return null;
    }
    var sourceFile = matched.get(0);
    if (pathsPerEntry.get(sourceFile) != 1) {
      Log.debugf(
          "Coverage entry %s matches more than one repository file; ignoring it",
          LogSafe.oneLine(sourceFile.path()));
      return null;
    }
    return sourceFile;
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
   * contributes all of them rather than only the first. The merged size is bounded by {@link
   * #MAX_SOURCE_FILES} (and each file by {@link #MAX_LINES_PER_FILE}); a path two reports both
   * describe keeps only what they agree on, for the reasons on {@link #mergeInto}, and a path they
   * agree on nothing about is dropped outright rather than reaching callers as a file with an empty
   * line set.
   *
   * <p>{@link #EMPTY} when the bytes hold no {@code .xml} entry or nothing this parser understands;
   * an empty report carrying a {@link Refusal} when the walk gave up part-way, per the paragraph
   * below. At most {@link #MAX_ZIP_ENTRIES} {@code .xml} entries are read, and an archive with more
   * is refused whole rather than merged as far as the cap allowed. No other entry counts toward
   * that cap — not the HTML report beside the XML, not a directory name — but none is trusted
   * either: a name ending in a slash may still carry a payload, so every entry is drained against
   * the same aggregate budget and refused the same way when it blows it.
   *
   * <p>Every entry — not only the {@code .xml} we want — is inflated through a counting copy
   * bounded by {@link #MAX_TOTAL_INFLATED_BYTES}. Reading only the entries we care about is not
   * enough: the next {@link ZipInputStream#getNextEntry()} implicitly inflates the whole of an
   * unread entry to reach the following header, which is exactly the path a maximally-compressed
   * archive takes to gigabytes. The moment that aggregate budget is blown — or the stream breaks
   * mid-walk — the archive is abandoned and <em>everything</em> already merged is discarded for an
   * empty report: the surviving prefix is chosen by whoever built the archive rather than by the
   * build, so returning it would report as uncovered whatever the unread remainder covers. No
   * partial answer leaves this method; the abort itself carries the full reasoning.
   */
  static JacocoCoverageReport fromArtifactZip(byte[] zipBytes) {
    if (zipBytes == null || zipBytes.length == 0) {
      return EMPTY;
    }
    var merged = new HashMap<String, NavigableSet<Integer>>();
    Refusal refusal;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      refusal = walkRefused(zip, merged);
    } catch (IOException | RuntimeException e) {
      Log.warn("Could not read the coverage artifact archive; refusing it", e);
      refusal = Refusal.UNREADABLE;
    }
    // A walk that gave up carries no partial answer out. Whatever merged before the abort is a
    // prefix chosen by the archive, not by the build: an attacker who appends a bomb entry after a
    // benign report would otherwise decide which reports the merge sees, and the truncated result
    // reads as complete coverage — lines the rest of the artifact covers come back "uncovered".
    // The same holds for an IOException mid-walk, where the prefix is chosen by where the stream
    // broke. This is what the class javadoc means by every failure yielding an empty report; the
    // refusal rides along so the review can say the artifact was there and was not read.
    if (refusal != null) {
      return refused(refusal);
    }
    // An intersection that emptied out says every report disagreed about that file, which is not
    // coverage data; the rest of the class may assume a present path has at least one line.
    merged.values().removeIf(NavigableSet::isEmpty);
    return merged.isEmpty() ? EMPTY : new JacocoCoverageReport(merged, null);
  }

  /**
   * Whether an entry can carry a report and so counts toward {@link #MAX_ZIP_ENTRIES}: a name
   * ending in {@code .xml}. A directory name ends in a slash, so it can never pass; a non-report
   * XML (a surefire report, say) does pass and costs a slot, because the cap bounds how many
   * documents the merge will parse, not how many of them turn out to be JaCoCo.
   */
  private static boolean isReport(ZipEntry entry) {
    return entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml");
  }

  /**
   * Inflates one {@code .xml} entry within {@code budgetLeft} and merges it into {@code merged}
   * when it turns out to be a JaCoCo report, answering how many bytes it cost — or {@code -1} when
   * the aggregate budget is gone and the archive must be abandoned. A report larger than {@link
   * #MAX_ENTRY_BYTES} is drained in full but contributes nothing, per {@link #inflateEntry}.
   */
  private static long readReportInto(
      ZipInputStream zip,
      ZipEntry entry,
      long budgetLeft,
      Map<String, NavigableSet<Integer>> merged)
      throws IOException {
    var sink = new ByteArrayOutputStream();
    var read = inflateEntry(zip, budgetLeft, MAX_ENTRY_BYTES, sink);
    if (read < 0 || sink.size() == 0) {
      return read;
    }
    var one = parseToMap(new ByteArrayInputStream(sink.toByteArray()));
    if (!one.isEmpty()) {
      Log.infof("Read patch coverage from artifact entry %s", entry.getName());
      mergeInto(merged, one);
    }
    return read;
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
      } else if (merged.size() < MAX_SOURCE_FILES) {
        // The cap bounds only how many NEW paths the accumulator takes on; a path already in it is
        // always intersected above, so reaching the cap can never turn a disagreement into a
        // union.
        merged.put(entry.getKey(), entry.getValue());
      }
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
    return map.isEmpty() ? EMPTY : new JacocoCoverageReport(map, null);
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

  /**
   * Walks the archive's entries into {@code merged}, and reports why it gave up — or {@code null}
   * when it read the archive to the end.
   *
   * <p>Each refusal leaves by returning rather than by breaking, which keeps the two limits reading
   * as the two answers they are. It also matters mechanically: a {@code continue} here would run
   * the loop's update expression, and {@code getNextEntry} inflates the remainder of the entry it
   * is leaving — so skipping past a bomb entry would pay exactly the cost the aggregate budget
   * exists to refuse.
   *
   * <p>The cap counts only entries that can carry a report ({@link #isReport}), because it exists
   * to bound how many documents the merge parses, not how many files the archive holds (#813): the
   * usual upload is the whole {@code target/site/jacoco/} tree, one {@code .html} per class beside
   * the one {@code jacoco.xml}, and a cap that charged every file refused that shape on a project
   * of a few hundred classes with a single legitimate report inside. Every other entry is drained
   * in full against the aggregate budget and refused the same way when it blows it, so nothing left
   * uncounted can smuggle inflation. A directory name in particular is not a promise of zero data:
   * nothing in the local-header format stops a crafted entry called {@code bomb/} carrying
   * megabytes of deflate, and leaving it to the loop's {@code getNextEntry()} would inflate that
   * payload uncharged, since the stream must dispose of the current entry before it can reach the
   * next header. A real directory costs one immediate EOF read.
   *
   * <p>Both refusals are logged at WARN — a coverage section that goes quiet with only a DEBUG line
   * behind it is a reason nobody ever reads (#813) — and with counts only, never an entry name,
   * since the names are whoever built the archive's to choose.
   *
   * @return why a limit stopped the walk, so whatever merged is a prefix the archive chose and no
   *     report may be built from it; {@code null} when nothing stopped it
   */
  private static Refusal walkRefused(ZipInputStream zip, Map<String, NavigableSet<Integer>> merged)
      throws IOException {
    var walked = 0;
    var reports = 0;
    var inflatedTotal = 0L;
    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
      walked++;
      var budgetLeft = MAX_TOTAL_INFLATED_BYTES - inflatedTotal;
      long read;
      if (isReport(entry)) {
        if (reports++ >= MAX_ZIP_ENTRIES) {
          // Padding an archive past the cap would otherwise let whoever built it decide which
          // reports the merge saw, while the result still reads as this build's coverage: lines
          // the unread reports cover come back uncovered, against a diff the model is told to
          // treat as fact.
          Log.warnf(
              "Coverage artifact holds more than %d .xml entries (%d entries walked, %d bytes"
                  + " inflated); refusing it rather than merging the prefix that fit",
              MAX_ZIP_ENTRIES, walked, inflatedTotal);
          return Refusal.ENTRY_CAP;
        }
        read = readReportInto(zip, entry, budgetLeft, merged);
      } else {
        read = inflateEntry(zip, budgetLeft, 0, null);
      }
      if (read < 0) {
        Log.warnf(
            "Coverage artifact inflates past the %d-byte aggregate cap (%d entries walked, %d of"
                + " them .xml); refusing it as a zip bomb",
            MAX_TOTAL_INFLATED_BYTES, walked, reports);
        return Refusal.INFLATION_BUDGET;
      }
      inflatedTotal += read;
    }
    return null;
  }
}
