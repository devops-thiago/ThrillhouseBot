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
package dev.thiagogonzaga.thrillhousebot.config;

import com.sun.source.doctree.AttributeTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.EntityTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.ValueTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.ConfigMappingInterface;
import io.smallrye.config.ConfigMappingInterface.Property;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Name;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Builds the configuration reference ({@code docs/CONFIGURATION.md}) and the README's most-used
 * settings table from the two places configuration is declared: the {@link ThrillhouseConfig}
 * mapping, read through SmallRye's own mapping metadata for names, types and defaults, and {@code
 * application.properties}, which gives most keys a short environment alias and may override the
 * mapping default.
 *
 * <p>A mapping property's description is the first paragraph of its javadoc (everything before the
 * first {@code <p>}); later paragraphs are notes for maintainers and are not published. A variable
 * that only {@code application.properties} reads (a Quarkus or LangChain4j key behind an alias such
 * as {@code AI_API_KEY}) is described by {@code # doc:} comment lines directly above the key. A
 * missing description, or a {@code {@link}} in a published paragraph, fails the build.
 */
final class ConfigReference {

  static final Path SOURCE =
      Path.of("src/main/java/dev/thiagogonzaga/thrillhousebot/config/ThrillhouseConfig.java");
  static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
  static final Path REFERENCE = Path.of("docs/CONFIGURATION.md");
  static final Path README = Path.of("README.md");

  static final String ESSENTIALS_START = "<!-- config-reference:essentials:start -->";
  static final String ESSENTIALS_END = "<!-- config-reference:essentials:end -->";

  static final String REGENERATE =
      "./mvnw test -Dtest=ConfigReferenceTest -Dconfig-reference.write=true";

  /** The variables the README's short table lists, in that order. */
  static final List<String> ESSENTIALS =
      List.of(
          "AI_API_KEY",
          "AI_BASE_URL",
          "AI_MODEL",
          "GITHUB_APP_ID",
          "GITHUB_PRIVATE_KEY",
          "GITHUB_WEBHOOK_SECRET",
          "GITHUB_CLIENT_ID",
          "GITHUB_CLIENT_SECRET",
          "DASHBOARD_URL",
          "DATABASE_URL",
          "REVIEW_CI_GATING",
          "REVIEW_BLOCKING_STRICTNESS",
          "REVIEW_MAX_INPUT_TOKENS",
          "REVIEW_MAX_AI_CALLS",
          "WEBHOOK_SKIP_DRAFTS",
          "WEBHOOK_REQUIRED_LABELS");

  private static final String PREFIX = "thrillhousebot";
  private static final String DOC_TAG = "doc:";
  private static final String NONE = "_(none)_";
  private static final String LIST = "list";
  private static final String SITE = "https://github.com/devops-thiago/ThrillhouseBot";

  /** Placeholder shown for a map key, by the map's property name. */
  private static final Map<String, String> MAP_KEYS = Map.of("models", "model", "pricing", "model");

  private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
  private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
  private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  record Entry(String profile, String key, String value, List<String> doc) {}

  record Row(
      String variable, String property, String type, String defaultValue, String description) {}

  record Section(String prefix, String intro, List<Row> rows) {}

  record Reference(List<Row> other, List<Section> sections) {

    Optional<Row> byVariable(String variable) {
      return Stream.concat(other.stream(), sections.stream().flatMap(s -> s.rows().stream()))
          .filter(r -> r.variable().equals(variable))
          .findFirst();
    }
  }

  private ConfigReference() {}

  /** Reads the mapping, its javadoc and {@code application.properties} from the module root. */
  static Reference load() {
    try {
      return build(
          ThrillhouseConfig.class,
          javadoc(Files.readString(SOURCE, StandardCharsets.UTF_8)),
          parseProperties(Files.readAllLines(PROPERTIES, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Reference build(Class<?> root, Map<String, DocCommentTree> docs, List<Entry> entries) {
    var errors = new ArrayList<String>();
    var base = new HashMap<String, Entry>();
    var profiled = new TreeMap<String, List<Entry>>();
    for (var entry : entries) {
      if (entry.profile().isEmpty()) {
        base.put(entry.key(), entry);
      } else {
        profiled.computeIfAbsent(entry.key(), k -> new ArrayList<>()).add(entry);
      }
    }
    var walker = new Walker(docs, base, profiled, errors);
    walker.walk(ConfigMappingInterface.getConfigurationInterface(root), PREFIX, null);
    var other = otherVariables(entries, walker.leafKeys, walker.aliases, errors);
    if (!errors.isEmpty()) {
      throw new IllegalStateException(
          "Configuration reference cannot be generated:\n  "
              + String.join("\n  ", new LinkedHashSet<>(errors)));
    }
    var sections =
        walker.sections.entrySet().stream()
            .map(
                e ->
                    new Section(
                        e.getKey(),
                        walker.intros.getOrDefault(e.getKey(), ""),
                        e.getValue().stream().sorted(Comparator.comparing(Row::property)).toList()))
            .toList();
    return new Reference(other, sections);
  }

  /** Walks the mapping tree and collects one row per leaf, grouped by the leaf's parent prefix. */
  private static final class Walker {
    private final Map<String, DocCommentTree> docs;
    private final Map<String, Entry> base;
    private final Map<String, List<Entry>> profiled;
    private final List<String> errors;
    private final Map<String, List<Row>> sections = new TreeMap<>();
    private final Map<String, String> intros = new HashMap<>();
    private final Set<String> leafKeys = new LinkedHashSet<>();
    private final Set<String> aliases = new LinkedHashSet<>();

    Walker(
        Map<String, DocCommentTree> docs,
        Map<String, Entry> base,
        Map<String, List<Entry>> profiled,
        List<String> errors) {
      this.docs = docs;
      this.base = base;
      this.profiled = profiled;
      this.errors = errors;
    }

    void walk(ConfigMappingInterface mapping, String prefix, String mapKey) {
      for (var property : mapping.getProperties()) {
        var name = prefix + "." + nameOf(property);
        if (property.isGroup()) {
          walk(property.asGroup().getGroupType(), name, mapKey);
        } else if (property.isMap()) {
          var value = property.asMap().getValueProperty();
          var key = "<" + MAP_KEYS.getOrDefault(nameOf(property), "key") + ">";
          var keyed = name + "." + key;
          describe(property.getMethod(), false).ifPresent(intro -> intros.put(keyed, intro));
          if (value.isGroup()) {
            walk(value.asGroup().getGroupType(), keyed, key);
          } else {
            errors.add(name + ": maps of plain values are not supported");
          }
        } else {
          leaf(property, prefix, name, mapKey);
        }
      }
    }

    private void leaf(Property property, String prefix, String name, String mapKey) {
      var description = describe(property.getMethod(), true).orElse("");
      var type = typeOf(property, name);
      String variable;
      String defaultValue;
      if (mapKey != null) {
        variable = code(standardEnv(name, mapKey));
        defaultValue = renderDefault(defaultOf(property), LIST.equals(type));
      } else {
        leafKeys.add(name);
        var entry = base.get(name);
        var resolved = resolve(entry, property);
        if (resolved.alias() != null) {
          aliases.add(resolved.alias());
        }
        variable =
            resolved.follows() != null
                ? "_(follows " + code(resolved.follows()) + ")_"
                : code(resolved.alias() != null ? resolved.alias() : standardEnv(name, null));
        defaultValue =
            withProfiles(renderDefault(resolved.defaultValue(), LIST.equals(type)), name, type);
      }
      sections
          .computeIfAbsent(prefix, k -> new ArrayList<>())
          .add(new Row(variable, name, type, defaultValue, description));
    }

    private String withProfiles(String rendered, String key, String type) {
      var variants = profiled.getOrDefault(key, List.of());
      if (variants.isEmpty()) {
        return rendered;
      }
      var out = new StringBuilder(rendered);
      for (var variant : variants) {
        var resolved = resolve(variant, null);
        if (resolved.alias() != null) {
          aliases.add(resolved.alias());
        }
        out.append("; ").append(code("%" + variant.profile())).append(": ");
        out.append(renderDefault(resolved.defaultValue(), LIST.equals(type)));
      }
      return out.toString();
    }

    private Resolved resolve(Entry entry, Property property) {
      if (entry == null) {
        return new Resolved(null, null, property == null ? null : defaultOf(property));
      }
      Matcher m = EXPRESSION.matcher(entry.value());
      if (m.matches()) {
        if (ENV_NAME.matcher(m.group(1)).matches()) {
          return new Resolved(m.group(1), null, m.group(2));
        }
        var target = base.get(m.group(1));
        var followed = target == null ? null : resolve(target, null).defaultValue();
        return new Resolved(null, m.group(1), followed);
      }
      return new Resolved(null, null, stripDefaults(entry.value()));
    }

    private Optional<String> describe(Method method, boolean required) {
      var key = method.getDeclaringClass().getCanonicalName() + "#" + method.getName();
      var tree = docs.get(key);
      var text = tree == null ? "" : firstParagraph(tree, method.getDeclaringClass(), key, errors);
      if (text.isEmpty()) {
        if (required) {
          errors.add(key + ": no javadoc description");
        }
        return Optional.empty();
      }
      return Optional.of(text);
    }

    private String typeOf(Property property, String name) {
      var p = property.isOptional() ? property.asOptional().getNestedProperty() : property;
      if (p.isCollection()) {
        return LIST;
      }
      Class<?> raw;
      if (p.isPrimitive()) {
        raw = p.asPrimitive().getPrimitiveType();
      } else if (p.isLeaf()) {
        raw = p.asLeaf().getValueRawType();
      } else {
        errors.add(name + ": unsupported property kind");
        return "";
      }
      if (raw == boolean.class || raw == Boolean.class) {
        return "boolean";
      }
      if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
        return "integer";
      }
      if (raw == double.class || raw == Double.class) {
        return "number";
      }
      if (raw == Duration.class) {
        return "duration";
      }
      if (raw == String.class) {
        return "string";
      }
      errors.add(name + ": unsupported type " + raw.getName());
      return "";
    }
  }

  /** How a mapping key's value is set: through an alias, by following another key, or directly. */
  private record Resolved(String alias, String follows, String defaultValue) {}

  /**
   * Variables {@code application.properties} reads for keys outside the mapping, one row per
   * variable. A variable that also aliases a mapping key is documented there instead.
   */
  static List<Row> otherVariables(
      List<Entry> entries, Set<String> leafKeys, Set<String> aliases, List<String> errors) {
    var byVariable = new LinkedHashMap<String, List<Entry>>();
    for (var entry : entries) {
      if (leafKeys.contains(entry.key())) {
        if (!entry.doc().isEmpty()) {
          errors.add(entry.key() + ": a mapping key is described by its javadoc, not '# doc:'");
        }
        if (EXPRESSION.matcher(entry.value()).matches()) {
          continue;
        }
      }
      var m = EXPRESSION.matcher(entry.value());
      while (m.find()) {
        var variable = m.group(1);
        if (ENV_NAME.matcher(variable).matches() && !aliases.contains(variable)) {
          var list = byVariable.computeIfAbsent(variable, k -> new ArrayList<>());
          if (!list.contains(entry)) {
            list.add(entry);
          }
        }
      }
    }
    var rows = new ArrayList<Row>();
    for (var e : byVariable.entrySet()) {
      var variable = e.getKey();
      var docs =
          e.getValue().stream()
              .map(entry -> String.join(" ", entry.doc()))
              .filter(d -> !d.isBlank())
              .distinct()
              .toList();
      if (docs.size() != 1) {
        errors.add(
            variable
                + (docs.isEmpty()
                    ? ": no '# doc:' comment above any key that reads it"
                    : ": '# doc:' comments disagree"));
        continue;
      }
      var properties =
          e.getValue().stream()
              .map(entry -> code(entry.key()))
              .distinct()
              .collect(Collectors.joining("<br>"));
      var defaults =
          e.getValue().stream()
              .map(entry -> defaultFor(entry, variable))
              .distinct()
              .collect(Collectors.joining("; "));
      rows.add(new Row(code(variable), properties, "", defaults, escapeCell(docs.get(0))));
    }
    rows.sort(Comparator.comparing(Row::variable));
    return rows;
  }

  private static String defaultFor(Entry entry, String variable) {
    var m = EXPRESSION.matcher(entry.value());
    String rendered;
    if (m.matches() && m.group(1).equals(variable)) {
      rendered = renderDefault(m.group(2), false);
    } else {
      rendered = renderDefault(stripDefaults(entry.value()), false);
    }
    return entry.profile().isEmpty() ? rendered : code("%" + entry.profile()) + ": " + rendered;
  }

  /** {@code ${NAME:default}} becomes {@code ${NAME}}, so a composite value reads as a template. */
  private static String stripDefaults(String value) {
    return EXPRESSION
        .matcher(value)
        .replaceAll(m -> Matcher.quoteReplacement("${" + m.group(1) + "}"));
  }

  static String renderDefault(String value, boolean list) {
    if (value == null || value.isEmpty()) {
      return NONE;
    }
    if (list && !value.contains("${")) {
      return Arrays.stream(value.split(","))
          .map(String::strip)
          .map(ConfigReference::code)
          .collect(Collectors.joining(", "));
    }
    return code(value);
  }

  /** The environment name SmallRye maps onto {@code property}, with the map key left symbolic. */
  static String standardEnv(String property, String mapKey) {
    if (mapKey == null) {
      return NON_ALNUM.matcher(property).replaceAll("_").toUpperCase(Locale.ROOT);
    }
    var parts = property.split(Pattern.quote(mapKey), 2);
    return standardEnv(parts[0], null)
        + mapKey.toUpperCase(Locale.ROOT)
        + standardEnv(parts[1], null);
  }

  /** The {@code @WithDefault} value; a list's sits on its element. */
  private static String defaultOf(Property property) {
    if (property.hasDefaultValue()) {
      return property.getDefaultValue();
    }
    var element = property.isCollection() ? property.asCollection().getElement() : null;
    return element != null && element.hasDefaultValue() ? element.getDefaultValue() : null;
  }

  private static String nameOf(Property property) {
    return property.getPropertyName(ConfigMapping.NamingStrategy.KEBAB_CASE);
  }

  static String code(String text) {
    var fence = text.contains("`") ? "``" : "`";
    var pad = text.contains("`") ? " " : "";
    return fence + pad + text.replace("|", "\\|") + pad + fence;
  }

  private static String escapeCell(String text) {
    return text.replace("|", "\\|");
  }

  /**
   * Parses {@code application.properties} in file order, keeping each key's profile and the {@code
   * # doc:} lines of the comment block directly above it.
   */
  static List<Entry> parseProperties(List<String> lines) {
    var entries = new ArrayList<Entry>();
    var doc = new ArrayList<String>();
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i).strip();
      if (line.isEmpty()) {
        doc.clear();
        continue;
      }
      if (line.startsWith("#") || line.startsWith("!")) {
        var body = line.substring(1).strip();
        if (body.startsWith(DOC_TAG)) {
          doc.add(body.substring(DOC_TAG.length()).strip());
        }
        continue;
      }
      var logical = new StringBuilder();
      while (line.endsWith("\\") && i + 1 < lines.size()) {
        logical.append(line, 0, line.length() - 1);
        line = lines.get(++i).strip();
      }
      logical.append(line);
      var eq = logical.indexOf("=");
      if (eq < 0) {
        throw new IllegalArgumentException("line without '=': " + logical);
      }
      var key = logical.substring(0, eq).strip();
      var value = logical.substring(eq + 1).strip();
      var profile = "";
      if (key.startsWith("%")) {
        var dot = key.indexOf('.');
        if (dot < 0) {
          throw new IllegalArgumentException("profile without a key: " + key);
        }
        profile = key.substring(1, dot);
        key = key.substring(dot + 1);
      }
      entries.add(new Entry(profile, key, value, List.copyOf(doc)));
      doc.clear();
    }
    return entries;
  }

  /** Doc comments of every method in {@code source}, keyed {@code Canonical.Name#method}. */
  static Map<String, DocCommentTree> javadoc(String source) {
    var compiler = ToolProvider.getSystemJavaCompiler();
    var file =
        new SimpleJavaFileObject(
            URI.create("string:///ThrillhouseConfig.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    var task =
        (JavacTask)
            compiler.getTask(null, null, d -> {}, List.of("-proc:none"), null, List.of(file));
    var trees = DocTrees.instance(task);
    var out = new HashMap<String, DocCommentTree>();
    try {
      for (CompilationUnitTree unit : task.parse()) {
        var pkg = unit.getPackageName() == null ? "" : unit.getPackageName() + ".";
        new TreePathScanner<Void, Void>() {
          private final Deque<Name> classes = new ArrayDeque<>();

          @Override
          public Void visitClass(ClassTree node, Void unused) {
            classes.addLast(node.getSimpleName());
            try {
              return super.visitClass(node, unused);
            } finally {
              classes.removeLast();
            }
          }

          @Override
          public Void visitMethod(MethodTree node, Void unused) {
            var doc = trees.getDocCommentTree(getCurrentPath());
            if (doc != null) {
              var owner = classes.stream().map(Name::toString).collect(Collectors.joining("."));
              out.put(pkg + owner + "#" + node.getName(), doc);
            }
            return null;
          }
        }.scan(unit, null);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  /**
   * Renders the first paragraph of {@code doc} as one line of Markdown. {@code {@code}} becomes a
   * code span, {@code {@value #CONSTANT}} the constant's value read from {@code owner}, and an
   * {@code <a href>} with an absolute URL a link; a {@code {@link}} or any other tag is an error.
   */
  static String firstParagraph(
      DocCommentTree doc, Class<?> owner, String where, List<String> errors) {
    var out = new StringBuilder();
    var codeRun = new StringBuilder();
    String href = null;
    for (DocTree node : doc.getFullBody()) {
      if (node instanceof StartElementTree start && start.getName().contentEquals("p")) {
        break;
      }
      if (node.getKind() == DocTree.Kind.CODE) {
        codeRun.append(((LiteralTree) node).getBody().getBody());
        continue;
      }
      if (!codeRun.isEmpty()) {
        out.append(code(codeRun.toString().strip()));
        codeRun.setLength(0);
      }
      switch (node) {
        case TextTree text -> out.append(markdownText(text.getBody()));
        case EntityTree entity -> out.append(markdownText(entity(entity.getName().toString())));
        case LiteralTree literal -> out.append(markdownText(literal.getBody().getBody()));
        case ValueTree value -> out.append(constant(owner, value, where, errors));
        case StartElementTree start when start.getName().contentEquals("em") -> out.append('*');
        case EndElementTree end when end.getName().contentEquals("em") -> out.append('*');
        case StartElementTree start when start.getName().contentEquals("a") -> {
          href = attribute(start, "href");
          if (href == null || !href.startsWith("https://")) {
            errors.add(where + ": a link needs an absolute https:// href");
          }
          out.append('[');
        }
        case EndElementTree end when end.getName().contentEquals("a") ->
            out.append("](").append(href).append(')');
        default -> errors.add(where + ": unsupported javadoc element " + node.getKind());
      }
    }
    if (!codeRun.isEmpty()) {
      out.append(code(codeRun.toString().strip()));
    }
    return WHITESPACE.matcher(out).replaceAll(" ").strip();
  }

  private static String attribute(StartElementTree start, String name) {
    for (var attribute : start.getAttributes()) {
      if (attribute instanceof AttributeTree a && a.getName().contentEquals(name)) {
        return a.getValue().stream().map(Object::toString).collect(Collectors.joining());
      }
    }
    return null;
  }

  private static String constant(
      Class<?> owner, ValueTree value, String where, List<String> errors) {
    var signature = value.getReference() == null ? "" : value.getReference().getSignature();
    if (!signature.startsWith("#")) {
      errors.add(where + ": {@value} must name a constant of the same interface");
      return "";
    }
    try {
      return String.valueOf(owner.getField(signature.substring(1)).get(null));
    } catch (ReflectiveOperationException _) {
      errors.add(where + ": no constant " + signature);
      return "";
    }
  }

  private static String entity(String name) {
    if (name.startsWith("#")) {
      var number =
          name.startsWith("#x")
              ? Integer.parseInt(name.substring(2), 16)
              : Integer.parseInt(name.substring(1));
      return new String(Character.toChars(number));
    }
    return switch (name) {
      case "lt" -> "<";
      case "gt" -> ">";
      case "amp" -> "&";
      case "quot" -> "\"";
      default -> throw new IllegalArgumentException("unknown entity &" + name + ";");
    };
  }

  /** Plain javadoc text as Markdown table-cell text: pipes and angle brackets escaped. */
  private static String markdownText(String text) {
    return text.replace("|", "\\|").replace("<", "&lt;");
  }

  /** The whole {@code docs/CONFIGURATION.md}. */
  static String renderReference(Reference reference) {
    var out = new StringBuilder();
    out.append("# Configuration reference\n\n");
    out.append("<!-- Generated by ConfigReferenceTest from ThrillhouseConfig.java and\n");
    out.append("     application.properties. Do not edit by hand; regenerate with\n");
    out.append("     ").append(REGENERATE).append(" -->\n\n");
    out.append("<!-- docs:config-reference:start -->\n");
    out.append(
        """
        Every setting the bot reads, with the environment variable that sets it. A row \
        shows the short alias `application.properties` defines for it where there is one; every \
        `thrillhousebot.*` key also accepts its standard Quarkus name (the key in uppercase with \
        each `.` and `-` replaced by `_`), which overrides the alias. What the features do is \
        described in the README's [Configuration](%s#configuration) section.

        Durations take Quarkus' format (`10s`, `5m`, `24h`, or an ISO-8601 value such as \
        `PT1M`). A list is comma-separated.

        """
            .formatted(SITE));
    out.append("## Quarkus and LangChain4j settings\n\n");
    out.append(
        "These variables set Quarkus and LangChain4j keys rather than a `thrillhousebot.*` key.\n\n");
    out.append("| Variable | Description | Default | Sets |\n|---|---|---|---|\n");
    for (var row : reference.other()) {
      out.append("| ")
          .append(row.variable())
          .append(" | ")
          .append(row.description())
          .append(" | ")
          .append(row.defaultValue())
          .append(" | ")
          .append(row.property())
          .append(" |\n");
    }
    for (var section : reference.sections()) {
      out.append("\n## ").append(code(section.prefix())).append("\n\n");
      if (!section.intro().isEmpty()) {
        out.append(section.intro()).append("\n\n");
      }
      out.append("| Variable | Description | Type | Default |\n|---|---|---|---|\n");
      for (var row : section.rows()) {
        out.append("| ")
            .append(row.variable())
            .append("<br>")
            .append(code(row.property()))
            .append(" | ")
            .append(row.description())
            .append(" | ")
            .append(row.type())
            .append(" | ")
            .append(row.defaultValue())
            .append(" |\n");
      }
    }
    out.append("<!-- docs:config-reference:end -->\n");
    return out.toString();
  }

  /** The README's most-used settings table, the text between the essentials markers. */
  static String renderEssentials(Reference reference) {
    var out = new StringBuilder("\n\n| Variable | Purpose | Default |\n|---|---|---|\n");
    for (var variable : ESSENTIALS) {
      var row =
          reference
              .byVariable(code(variable))
              .orElseThrow(() -> new IllegalStateException(variable + " is not in the reference"));
      out.append("| ")
          .append(row.variable())
          .append(" | ")
          .append(row.description())
          .append(" | ")
          .append(row.defaultValue())
          .append(" |\n");
    }
    return out.append('\n').toString();
  }

  /** {@code readme} with the text between the essentials markers replaced. */
  static String spliceEssentials(String readme, String essentials) {
    var start = readme.indexOf(ESSENTIALS_START);
    var end = readme.indexOf(ESSENTIALS_END);
    if (start < 0 || end < start) {
      throw new IllegalStateException("README.md has no " + ESSENTIALS_START + " block");
    }
    return readme.substring(0, start + ESSENTIALS_START.length())
        + essentials
        + readme.substring(end);
  }
}
