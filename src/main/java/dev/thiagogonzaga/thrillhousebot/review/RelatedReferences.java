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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The deterministic first step of the related context (#55): the repository files a pull request's
 * diff refers to but does not change, found without a model call.
 *
 * <p>Two things in a diff name another file. An import line, added or shown as context around a
 * hunk, names a module, and the module is resolved to a path the way its language lays files out.
 * And an added line that uses a type name ({@code InputSanitizer.clean(x)}) names the file that
 * type is declared in, where the repository has exactly one such file for that language. Nothing is
 * parsed deeper than that: no syntax tree, no build file, no index.
 *
 * <p>Every candidate is a path of the repository listing the review already holds, so a path the
 * diff's text invents resolves to nothing, and the candidates stay in the changed file's own
 * language, so a source line can never point this at a credentials or data file.
 */
final class RelatedReferences {

  /** Candidates handed to the selection call; the lowest-ranked beyond it are left out. */
  static final int MAX_CANDIDATES = 30;

  /** Changed files named per candidate as the ones that refer to it. */
  static final int MAX_REFERRERS = 3;

  /** Type and module names kept per candidate. */
  static final int MAX_SYMBOLS = 6;

  /** Called member names kept per candidate, for clipping the file to the parts in use. */
  static final int MAX_MEMBERS = 40;

  /** Files of one Go package offered for one import. */
  static final int MAX_PACKAGE_FILES = 3;

  /** A patch line longer than this is not scanned: generated or minified text, not a reference. */
  static final int MAX_LINE_CHARS = 600;

  private static final int ADDED_IMPORT_SCORE = 100;
  private static final int CONTEXT_IMPORT_SCORE = 20;
  private static final int SYMBOL_SCORE = 10;
  private static final int MAX_SYMBOL_SCORE = 50;

  /** How a candidate was found. */
  enum Via {
    IMPORT,
    SYMBOL
  }

  /**
   * One repository file the diff refers to.
   *
   * @param path its path in the repository listing
   * @param via how the strongest reference to it was found
   * @param referencedBy the changed files that refer to it, at most {@link #MAX_REFERRERS}
   * @param symbols the type or module names the diff uses for it
   * @param members the member names the referring files' added lines call
   */
  record Candidate(
      String path, Via via, List<String> referencedBy, List<String> symbols, List<String> members) {
    Candidate {
      referencedBy = List.copyOf(referencedBy);
      symbols = List.copyOf(symbols);
      members = List.copyOf(members);
    }
  }

  private enum Language {
    JVM("java", "kt", "kts", "scala", "groovy"),
    PYTHON("py"),
    SCRIPT("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "vue", "svelte"),
    GO("go"),
    RUST("rs"),
    RUBY("rb"),
    C("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx"),
    PHP("php"),
    DOTNET("cs", "fs", "vb"),
    SWIFT("swift"),
    DART("dart");

    private final List<String> extensions;

    Language(String... extensions) {
      this.extensions = List.of(extensions);
    }

    /** Whether files of this language are named in snake case after the type they declare. */
    boolean snakeCaseFiles() {
      return SNAKE_CASE_FILES.contains(name());
    }

    static Language of(String path) {
      var extension = extensionOf(path);
      for (var language : values()) {
        if (language.extensions.contains(extension)) {
          return language;
        }
      }
      return null;
    }
  }

  /** The languages whose files are named in snake case; by name, as an enum cannot hold itself. */
  private static final Set<String> SNAKE_CASE_FILES =
      Set.of("PYTHON", "RUBY", "RUST", "GO", "DART");

  private static final Pattern JVM_IMPORT =
      Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*(?:;|as\\s|$)");
  private static final Pattern PYTHON_FROM =
      Pattern.compile("^\\s*from\\s+(\\.*)([\\w.]*)\\s+import\\s+([\\w\\s,()*]+)");
  private static final Pattern PYTHON_IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+)");
  private static final Pattern SCRIPT_IMPORT =
      Pattern.compile(
          "(?:\\bfrom\\s+|\\bimport\\s+|\\brequire\\(\\s*|\\bimport\\(\\s*)['\"](\\.{1,2}/[^'\"]+)['\"]");
  private static final Pattern GO_IMPORT =
      Pattern.compile("^\\s*(?:import\\s+)?(?:[\\w.]+\\s+)?\"([\\w./-]+/[\\w./-]+)\"\\s*$");
  private static final Pattern RUST_USE =
      Pattern.compile("^\\s*(?:pub(?:\\([\\w:\\s]+\\))?\\s+)?use\\s+crate::([\\w:]+)");
  private static final Pattern RUBY_REQUIRE =
      Pattern.compile("^\\s*require_relative\\s+['\"]([^'\"]+)['\"]");
  private static final Pattern C_INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+\"([^\"]+)\"");
  private static final Pattern PHP_USE = Pattern.compile("^\\s*use\\s+([A-Za-z_][\\w\\\\]+)");

  /** A capitalised identifier with a lower-case letter in it: a type name, not a constant. */
  private static final Pattern TYPE_NAME = Pattern.compile("\\b[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*");

  /** A member called on something: {@code .name(}. */
  private static final Pattern MEMBER_CALL = Pattern.compile("\\.\\s*([A-Za-z_]\\w{2,})\\s*\\(");

  /** Before a capital that follows a lower-case letter or digit, or that ends an acronym. */
  private static final Pattern CAMEL_BOUNDARY =
      Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

  private RelatedReferences() {}

  /**
   * The repository files {@code files} refer to and do not change, strongest reference first, at
   * most {@link #MAX_CANDIDATES}.
   *
   * @param files the review's reviewable changed files, with their patches
   * @param changedPaths every path the pull request changes, reviewable or not; none is a candidate
   * @param repositoryPaths the repository's paths at the head commit
   * @param excluded paths that must not be read, such as the review's ignore globs
   */
  static List<Candidate> extract(
      List<GitHubPullRequestClient.FileDiff> files,
      Set<String> changedPaths,
      Set<String> repositoryPaths,
      Predicate<String> excluded) {
    if (files == null || files.isEmpty() || repositoryPaths == null || repositoryPaths.isEmpty()) {
      return List.of();
    }
    var index = new Index(repositoryPaths);
    var found = new LinkedHashMap<String, Found>();
    for (var file : files) {
      var language = Language.of(file.filename());
      if (language == null || file.patch() == null || "removed".equals(file.status())) {
        continue;
      }
      scan(file, language, index, found);
    }
    return found.values().stream()
        .filter(f -> !changedPaths.contains(f.path) && !excluded.test(f.path))
        .sorted(Comparator.comparingInt(Found::score).reversed().thenComparing(f -> f.path))
        .limit(MAX_CANDIDATES)
        .map(Found::candidate)
        .toList();
  }

  /** Reads one changed file's patch into {@code found}. */
  private static void scan(
      GitHubPullRequestClient.FileDiff file,
      Language language,
      Index index,
      Map<String, Found> found) {
    var referrer = file.filename();
    var members = new LinkedHashSet<String>();
    var referred = new LinkedHashSet<Found>();
    var inHunk = false;
    for (var raw : file.patch().split("\n", -1)) {
      if (raw.startsWith("@@")) {
        inHunk = true;
        continue;
      }
      if (!inHunk || raw.isEmpty() || raw.length() > MAX_LINE_CHARS || raw.charAt(0) == '-') {
        continue;
      }
      var added = raw.charAt(0) == '+';
      var line = raw.substring(1);
      // A comment refers to nothing: a commented-out import would rank as a live one.
      if (isComment(line)) {
        continue;
      }
      for (var imported : imports(line, language, referrer, index)) {
        // The one place the language rule is enforced: an import may name any path of the
        // listing (a relative "./settings.json", an included "secrets.env"), and only a source
        // file of the changed file's own language is ever a candidate.
        if (Language.of(imported.path()) != language) {
          continue;
        }
        var entry = found.computeIfAbsent(imported.path(), Found::new);
        entry.imported(added, imported.symbol());
        entry.referrers.add(referrer);
        referred.add(entry);
      }
      if (added) {
        collectMembers(line, members);
        for (var typed : typeReferences(line, language, referrer, index)) {
          var entry = found.computeIfAbsent(typed.path(), Found::new);
          entry.named(typed.symbol());
          entry.referrers.add(referrer);
          referred.add(entry);
        }
      }
    }
    referred.forEach(entry -> entry.members.addAll(members));
  }

  private static void collectMembers(String line, Set<String> members) {
    var matcher = MEMBER_CALL.matcher(line);
    while (matcher.find() && members.size() < MAX_MEMBERS) {
      members.add(matcher.group(1));
    }
  }

  private static boolean isComment(String line) {
    var text = line.stripLeading();
    return text.startsWith("//")
        || text.startsWith("/*")
        // A block comment's continuation line, not a C dereference or a Python star target.
        || text.equals("*")
        || text.startsWith("* ")
        || text.startsWith("*/")
        || (text.startsWith("#") && !text.startsWith("#include") && !text.startsWith("#["));
  }

  /** A resolved reference: the repository path and the name the diff used for it. */
  private record Reference(String path, String symbol) {}

  /** The repository files an import on {@code line} names; empty for any other line. */
  private static List<Reference> imports(
      String line, Language language, String referrer, Index index) {
    return switch (language) {
      case JVM -> jvmImport(line, index);
      case PYTHON -> pythonImport(line, referrer, index);
      case SCRIPT -> scriptImport(line, referrer, index);
      case GO -> goImport(line, index);
      case RUST -> rustUse(line, index);
      case RUBY -> rubyRequire(line, referrer, index);
      case C -> cInclude(line, referrer, index);
      case PHP -> phpUse(line, index);
      default -> List.of();
    };
  }

  private static List<Reference> jvmImport(String line, Index index) {
    var matcher = JVM_IMPORT.matcher(line);
    if (!matcher.find() || matcher.group(3) != null) {
      return List.of();
    }
    var segments = new ArrayList<>(List.of(matcher.group(2).split("\\.")));
    // A static import ends in a member, and a nested type in its outer type: either way the file
    // is named by an earlier segment, so the name is shortened until a file matches.
    for (var attempt = 0; attempt < 3 && segments.size() >= 2; attempt++) {
      var paths = index.suffixMatches(segments, Language.JVM.extensions, 2);
      if (!paths.isEmpty()) {
        var symbol = segments.get(segments.size() - 1);
        return paths.stream().map(path -> new Reference(path, symbol)).toList();
      }
      segments.remove(segments.size() - 1);
    }
    return List.of();
  }

  private static List<Reference> pythonImport(String line, String referrer, Index index) {
    var from = PYTHON_FROM.matcher(line);
    if (from.find()) {
      var dots = from.group(1).length();
      var module =
          from.group(2).isEmpty() ? List.<String>of() : List.of(from.group(2).split("\\."));
      var base = dots == 0 ? null : ancestor(directoryOf(referrer), dots - 1);
      if (dots > 0 && base == null) {
        return List.of();
      }
      var references = new ArrayList<Reference>();
      for (var name : from.group(3).replaceAll("[()]", " ").split(",")) {
        var imported = name.strip().split("\\s+")[0];
        if (!imported.isEmpty() && !"*".equals(imported)) {
          var submodule = new ArrayList<>(module);
          submodule.add(imported);
          pythonModule(submodule, base, index)
              .forEach(path -> references.add(new Reference(path, imported)));
        }
      }
      if (references.isEmpty() && !module.isEmpty()) {
        var symbol = module.get(module.size() - 1);
        pythonModule(module, base, index)
            .forEach(path -> references.add(new Reference(path, symbol)));
      }
      return references;
    }
    var plain = PYTHON_IMPORT.matcher(line);
    if (!plain.find()) {
      return List.of();
    }
    var module = List.of(plain.group(1).split("\\."));
    var symbol = module.get(module.size() - 1);
    return pythonModule(module, null, index).stream()
        .map(path -> new Reference(path, symbol))
        .toList();
  }

  /** The file of a Python module: relative to {@code base} when given, else by path suffix. */
  private static List<String> pythonModule(List<String> module, String base, Index index) {
    var relative = String.join("/", module);
    if (base != null) {
      var prefix = base.isEmpty() ? "" : base + "/";
      return index.existing(List.of(prefix + relative + ".py", prefix + relative + "/__init__.py"));
    }
    var asFile = index.endingWith(relative + ".py");
    return asFile.isEmpty() ? index.endingWith(relative + "/__init__.py") : asFile;
  }

  private static List<Reference> scriptImport(String line, String referrer, Index index) {
    var references = new ArrayList<Reference>();
    var matcher = SCRIPT_IMPORT.matcher(line);
    while (matcher.find()) {
      var target = normalize(directoryOf(referrer), matcher.group(1));
      if (target == null) {
        continue;
      }
      var tried = new ArrayList<String>();
      tried.add(target);
      // TypeScript sources import "./x.js" for a file that is x.ts on disk.
      var bare = target.replaceFirst("\\.(?:js|jsx|mjs|cjs)$", "");
      for (var extension : Language.SCRIPT.extensions) {
        tried.add(bare + "." + extension);
      }
      for (var extension : Language.SCRIPT.extensions) {
        tried.add(target + "/index." + extension);
      }
      var symbol = stemOf(target);
      index.existing(tried).stream()
          .limit(1)
          .forEach(path -> references.add(new Reference(path, symbol)));
    }
    return references;
  }

  private static List<Reference> goImport(String line, Index index) {
    var matcher = GO_IMPORT.matcher(line);
    if (!matcher.find()) {
      return List.of();
    }
    var imported = matcher.group(1);
    var name = imported.substring(imported.lastIndexOf('/') + 1);
    return index.goPackage(imported).stream().map(path -> new Reference(path, name)).toList();
  }

  private static List<Reference> rustUse(String line, Index index) {
    var matcher = RUST_USE.matcher(line);
    if (!matcher.find()) {
      return List.of();
    }
    var segments = new ArrayList<>(List.of(matcher.group(1).split("::")));
    // The last segments may name an item of the module rather than the module itself.
    while (!segments.isEmpty()) {
      var relative = String.join("/", segments);
      var paths = index.endingWith("src/" + relative + ".rs");
      if (paths.isEmpty()) {
        paths = index.endingWith("src/" + relative + "/mod.rs");
      }
      if (!paths.isEmpty()) {
        var symbol = segments.get(segments.size() - 1);
        return paths.stream().map(path -> new Reference(path, symbol)).toList();
      }
      segments.remove(segments.size() - 1);
    }
    return List.of();
  }

  private static List<Reference> rubyRequire(String line, String referrer, Index index) {
    var matcher = RUBY_REQUIRE.matcher(line);
    if (!matcher.find()) {
      return List.of();
    }
    var target = normalize(directoryOf(referrer), matcher.group(1));
    if (target == null) {
      return List.of();
    }
    var path = target.endsWith(".rb") ? target : target + ".rb";
    return index.existing(List.of(path)).stream()
        .map(found -> new Reference(found, stemOf(found)))
        .toList();
  }

  private static List<Reference> cInclude(String line, String referrer, Index index) {
    var matcher = C_INCLUDE.matcher(line);
    if (!matcher.find()) {
      return List.of();
    }
    var included = matcher.group(1);
    var local = normalize(directoryOf(referrer), included);
    var paths = local == null ? List.<String>of() : index.existing(List.of(local));
    if (paths.isEmpty()) {
      var bySuffix = index.endingWith(included.replaceFirst("^(?:\\./)+", ""));
      paths = bySuffix.size() == 1 ? bySuffix : List.of();
    }
    return paths.stream().map(path -> new Reference(path, stemOf(path))).toList();
  }

  private static List<Reference> phpUse(String line, Index index) {
    var matcher = PHP_USE.matcher(line);
    if (!matcher.find()) {
      return List.of();
    }
    var segments = List.of(matcher.group(1).split("\\\\"));
    if (Set.of("function", "const").contains(segments.get(0))) {
      return List.of();
    }
    var symbol = segments.get(segments.size() - 1);
    return index.suffixMatches(segments, Language.PHP.extensions, 1).stream()
        .map(path -> new Reference(path, symbol))
        .toList();
  }

  /** The files the type names on an added line are declared in, one per name at most. */
  private static List<Reference> typeReferences(
      String line, Language language, String referrer, Index index) {
    var references = new ArrayList<Reference>();
    var seen = new HashSet<String>();
    var matcher = TYPE_NAME.matcher(line);
    while (matcher.find()) {
      var name = matcher.group();
      if (name.length() < 3 || !seen.add(name)) {
        continue;
      }
      var path = index.declaring(name, language, referrer);
      if (path != null) {
        references.add(new Reference(path, name));
      }
    }
    return references;
  }

  /** What the diff says about one repository file, accumulated over every changed file. */
  private static final class Found {
    private final String path;
    private final Set<String> referrers = new LinkedHashSet<>();
    private final Set<String> symbols = new LinkedHashSet<>();
    private final Set<String> members = new LinkedHashSet<>();
    private int importScore;
    private int symbolReferences;

    Found(String path) {
      this.path = path;
    }

    void imported(boolean added, String symbol) {
      importScore = Math.max(importScore, added ? ADDED_IMPORT_SCORE : CONTEXT_IMPORT_SCORE);
      symbols.add(symbol);
    }

    void named(String symbol) {
      symbolReferences++;
      symbols.add(symbol);
    }

    int score() {
      return importScore + Math.min(MAX_SYMBOL_SCORE, symbolReferences * SYMBOL_SCORE);
    }

    Candidate candidate() {
      return new Candidate(
          path,
          importScore > 0 ? Via.IMPORT : Via.SYMBOL,
          referrers.stream().limit(MAX_REFERRERS).toList(),
          symbols.stream().limit(MAX_SYMBOLS).toList(),
          members.stream().limit(MAX_MEMBERS).toList());
    }
  }

  /** The repository listing, indexed by file name and by file name without its extension. */
  private static final class Index {
    private final Set<String> paths;
    private final Map<String, List<String>> byName = new HashMap<>();
    private final Map<String, List<String>> byStem = new HashMap<>();
    private final Map<String, List<String>> goFilesByDirectory = new HashMap<>();

    Index(Set<String> repositoryPaths) {
      this.paths = repositoryPaths;
      for (var path : repositoryPaths) {
        if (Language.of(path) == null) {
          continue;
        }
        byName.computeIfAbsent(nameOf(path), k -> new ArrayList<>()).add(path);
        byStem.computeIfAbsent(stemOf(path), k -> new ArrayList<>()).add(path);
        if (path.endsWith(".go") && !path.endsWith("_test.go")) {
          goFilesByDirectory.computeIfAbsent(directoryOf(path), k -> new ArrayList<>()).add(path);
        }
      }
    }

    /** The ones of {@code candidates} the repository has, in the order given. */
    List<String> existing(List<String> candidates) {
      return candidates.stream().filter(paths::contains).distinct().toList();
    }

    /** Source files that are {@code relative} or end with it on a path-segment boundary. */
    List<String> endingWith(String relative) {
      return byName.getOrDefault(nameOf(relative), List.of()).stream()
          .filter(path -> path.equals(relative) || path.endsWith("/" + relative))
          .sorted()
          .toList();
    }

    /**
     * Files named after the last of {@code segments} whose path ends with the longest run of the
     * trailing segments any file matches, dropping leading segments one at a time down to {@code
     * minSegments}; a match on the file name alone counts only when it is the only one.
     */
    List<String> suffixMatches(List<String> segments, List<String> extensions, int minSegments) {
      for (var from = 0; from <= segments.size() - minSegments; from++) {
        var relative = String.join("/", segments.subList(from, segments.size()));
        var matches = new ArrayList<String>();
        for (var extension : extensions) {
          matches.addAll(endingWith(relative + "." + extension));
        }
        if (!matches.isEmpty()) {
          return from == segments.size() - 1 && matches.size() > 1 ? List.of() : matches;
        }
      }
      return List.of();
    }

    /** The files of the Go package an import path names: the deepest directory it ends with. */
    List<String> goPackage(String imported) {
      return goFilesByDirectory.keySet().stream()
          .filter(directory -> !directory.isEmpty())
          .filter(directory -> imported.equals(directory) || imported.endsWith("/" + directory))
          .max(Comparator.comparingInt(String::length))
          .map(best -> goFilesByDirectory.get(best).stream().sorted().limit(MAX_PACKAGE_FILES))
          .map(java.util.stream.Stream::toList)
          .orElse(List.of());
    }

    /**
     * The one file of {@code language} named after the type {@code name}, or {@code null}. With
     * several, the one sharing the most leading directories with {@code referrer} when no other
     * shares as many: a type is most often used from its own package.
     */
    String declaring(String name, Language language, String referrer) {
      var matches = new ArrayList<String>();
      addOfLanguage(byStem.get(name), language, matches);
      if (language.snakeCaseFiles()) {
        addOfLanguage(byStem.get(snakeCase(name)), language, matches);
      }
      matches.remove(referrer);
      if (matches.size() <= 1) {
        return matches.isEmpty() ? null : matches.get(0);
      }
      String best = null;
      var bestShared = -1;
      var tied = false;
      for (var match : matches) {
        var shared = sharedDirectories(match, referrer);
        if (shared > bestShared) {
          best = match;
          bestShared = shared;
          tied = false;
        } else if (shared == bestShared) {
          tied = true;
        }
      }
      return tied ? null : best;
    }

    private static void addOfLanguage(List<String> from, Language language, List<String> into) {
      if (from != null) {
        from.stream().filter(path -> Language.of(path) == language).forEach(into::add);
      }
    }
  }

  private static int sharedDirectories(String a, String b) {
    var left = directoryOf(a).split("/");
    var right = directoryOf(b).split("/");
    var shared = 0;
    while (shared < left.length
        && shared < right.length
        && !left[shared].isEmpty()
        && left[shared].equals(right[shared])) {
      shared++;
    }
    return shared;
  }

  static String snakeCase(String name) {
    return CAMEL_BOUNDARY.matcher(name).replaceAll("_").toLowerCase(Locale.ROOT);
  }

  private static String nameOf(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String stemOf(String path) {
    var name = nameOf(path);
    var dot = name.lastIndexOf('.');
    return dot <= 0 ? name : name.substring(0, dot);
  }

  private static String extensionOf(String path) {
    var name = path == null ? "" : nameOf(path);
    var dot = name.lastIndexOf('.');
    return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String directoryOf(String path) {
    var slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  /** The directory {@code levels} above {@code directory}, or {@code null} above the root. */
  private static String ancestor(String directory, int levels) {
    var current = directory;
    for (var i = 0; i < levels; i++) {
      if (current.isEmpty()) {
        return null;
      }
      current = directoryOf(current);
    }
    return current;
  }

  /**
   * {@code relative} resolved against {@code directory}, with {@code .} and {@code ..} segments
   * applied; {@code null} when it leaves the repository.
   */
  static String normalize(String directory, String relative) {
    var segments = new ArrayList<String>();
    if (!directory.isEmpty()) {
      segments.addAll(List.of(directory.split("/")));
    }
    for (var segment : relative.split("/")) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (segments.isEmpty()) {
          return null;
        }
        segments.remove(segments.size() - 1);
      } else {
        segments.add(segment);
      }
    }
    return segments.isEmpty() ? null : String.join("/", segments);
  }

  /** The hunk headers of a patch, for the selection call's view of what a file changes. */
  static List<String> hunkHeaders(String patch, int max, int maxChars) {
    if (patch == null) {
      return List.of();
    }
    var headers = new ArrayList<String>();
    for (var line : patch.split("\n", -1)) {
      if (line.startsWith("@@") && headers.size() < max) {
        headers.add(line.length() > maxChars ? line.substring(0, maxChars) + "…" : line);
      }
    }
    return headers;
  }
}
