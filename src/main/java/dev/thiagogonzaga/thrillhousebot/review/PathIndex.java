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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Items looked up by a path under {@link FilePaths#same} and an extra key, without comparing the
 * path against every item (#1031). An item is filed under its own path and under each proper suffix
 * of it that starts after a {@code /} and still contains one, which are the only shorter paths
 * {@link FilePaths#same} accepts. A lookup reads the items filed exactly under the path, under each
 * such suffix of it, and under the path as a suffix of a longer one: exactly the items whose path
 * is the same file, each once, in the order they were added.
 *
 * @param <T> the item type
 */
final class PathIndex<T> {

  private record Entry<T>(int order, T item) {}

  private final Map<String, List<Entry<T>>> exact = new HashMap<>();
  private final Map<String, List<Entry<T>>> bySuffix = new HashMap<>();
  private int added;

  /**
   * The items indexed by {@code path} and {@code key}; an item whose path is {@code null} is left
   * out, since it matches no path.
   */
  static <T> PathIndex<T> of(Iterable<T> items, Function<T, String> path, Function<T, String> key) {
    var index = new PathIndex<T>();
    for (var item : items) {
      var p = path.apply(item);
      if (p != null) {
        index.add(p, key.apply(item), item);
      }
    }
    return index;
  }

  private void add(String path, String key, T item) {
    var entry = new Entry<>(added++, item);
    exact.computeIfAbsent(compound(path, key), unused -> new ArrayList<>()).add(entry);
    for (var suffix : properSuffixes(path)) {
      bySuffix.computeIfAbsent(compound(suffix, key), unused -> new ArrayList<>()).add(entry);
    }
  }

  /**
   * The items whose path is the same file as {@code path} and whose key equals {@code key}, in the
   * order they were added; empty for a {@code null} path.
   */
  List<T> candidates(String path, String key) {
    if (path == null) {
      return List.of();
    }
    var found = new ArrayList<Entry<T>>(exact.getOrDefault(compound(path, key), List.of()));
    for (var suffix : properSuffixes(path)) {
      found.addAll(exact.getOrDefault(compound(suffix, key), List.of()));
    }
    if (path.indexOf('/') >= 0) {
      found.addAll(bySuffix.getOrDefault(compound(path, key), List.of()));
    }
    found.sort(Comparator.comparingInt(Entry::order));
    return found.stream().map(Entry::item).toList();
  }

  /**
   * The suffixes of {@code path} that start right after a {@code /} and contain another one: the
   * shorter paths {@link FilePaths#same} matches it with.
   */
  private static List<String> properSuffixes(String path) {
    var suffixes = new ArrayList<String>();
    for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
      var suffix = path.substring(slash + 1);
      if (suffix.indexOf('/') >= 0) {
        suffixes.add(suffix);
      }
    }
    return suffixes;
  }

  private static String compound(String path, String key) {
    return path + '\n' + key;
  }
}
