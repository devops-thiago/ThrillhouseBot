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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Matches each test file of a diff to the source file it covers, by name (#1472), so the planner
 * can keep both in one batch. A wrong match only changes which files share a call, never which
 * files are reviewed.
 */
final class TestSourcePairs {

  private static final Pattern MARKER =
      Pattern.compile("^test_|(\\.test|\\.spec|_test|_spec)$|(?<=[a-z0-9])(Tests?|IT|Spec)$");

  private static final Map<String, String> LANGUAGE_FAMILIES =
      Map.of(
          "kt", "java",
          "scala", "java",
          "groovy", "java",
          "jsx", "js",
          "ts", "js",
          "tsx", "js",
          "mjs", "js",
          "cjs", "js",
          "vue", "js",
          "svelte", "js");

  private TestSourcePairs() {}

  /**
   * The test files that have a source in {@code filenames}, as test index to source index, in the
   * list's order. Among several sources of the same name, the one sharing the most leading and
   * trailing directories with the test wins, and the earlier one on a tie.
   */
  static Map<Integer, Integer> link(List<String> filenames) {
    var sources = new HashMap<String, List<Integer>>();
    var sourceDirectories = new HashMap<Integer, List<String>>();
    for (var i = 0; i < filenames.size(); i++) {
      var name = filenames.get(i);
      if (name != null && !ReviewDiffFormatter.isTestFile(name)) {
        sources.computeIfAbsent(key(name, stem(name)), k -> new ArrayList<>()).add(i);
        sourceDirectories.put(i, directories(name));
      }
    }
    var links = new LinkedHashMap<Integer, Integer>();
    for (var i = 0; i < filenames.size(); i++) {
      var name = filenames.get(i);
      if (!ReviewDiffFormatter.isTestFile(name)) {
        continue;
      }
      var candidates =
          sources.getOrDefault(key(name, MARKER.matcher(stem(name)).replaceAll("")), List.of());
      var testDirectories = directories(name);
      var best = -1;
      var bestScore = -1;
      for (var candidate : candidates) {
        var score = sharedDirectories(testDirectories, sourceDirectories.get(candidate));
        if (score > bestScore) {
          best = candidate;
          bestScore = score;
        }
      }
      if (best >= 0) {
        links.put(i, best);
      }
    }
    return links;
  }

  private static String stem(String path) {
    var name = path.substring(path.lastIndexOf('/') + 1);
    var dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private static String key(String path, String stem) {
    var name = path.substring(path.lastIndexOf('/') + 1);
    var dot = name.lastIndexOf('.');
    var extension = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    return LANGUAGE_FAMILIES.getOrDefault(extension, extension) + ':' + stem;
  }

  private static int sharedDirectories(List<String> a, List<String> b) {
    var shortest = Math.min(a.size(), b.size());
    var leading = 0;
    while (leading < shortest && a.get(leading).equals(b.get(leading))) {
      leading++;
    }
    var trailing = 0;
    while (trailing < shortest - leading
        && a.get(a.size() - 1 - trailing).equals(b.get(b.size() - 1 - trailing))) {
      trailing++;
    }
    return leading + trailing;
  }

  private static List<String> directories(String path) {
    var segments = Arrays.asList(path.split("/"));
    return segments.subList(0, segments.size() - 1);
  }
}
