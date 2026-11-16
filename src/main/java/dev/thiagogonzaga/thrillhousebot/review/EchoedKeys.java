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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which listed entry each of a model's answers names, read from the key it echoes (#1322, #1402).
 *
 * <p>A prompt lists entries by number, each with its key, and each answer repeats both. The key is
 * copied, the number is counted, so when the two disagree the key is the one to trust: on #1401 the
 * verifier returned the right keys under rotated numbers, and dropping those answers posted three
 * findings it had rejected. An answer therefore names the entry its key names, in this order:
 *
 * <ol>
 *   <li>its number, when the entry under that number has the echoed key;
 *   <li>otherwise the one entry with the echoed key, unless an answer of the first kind already
 *       names that entry or an earlier answer of this kind took it;
 *   <li>otherwise none: a missing key, a key no entry has, or a key two entries share.
 * </ol>
 */
public final class EchoedKeys {

  private EchoedKeys() {}

  /**
   * For each answer, the 1-based number of the entry it names, or 0 when it names none.
   *
   * @param listed each listed entry's key, in listing order (entry {@code i} is number {@code i +
   *     1})
   * @param ids each answer's number
   * @param echoed each answer's echoed key, {@code null} when it gave none
   */
  public static int[] resolve(List<String> listed, List<Integer> ids, List<String> echoed) {
    var soleEntry = soleEntries(listed);
    var named = new int[ids.size()];
    var taken = new HashSet<Integer>();
    for (var i = 0; i < ids.size(); i++) {
      var id = ids.get(i);
      if (id >= 1 && id <= listed.size() && listed.get(id - 1).equals(normalized(echoed.get(i)))) {
        named[i] = id;
        taken.add(id);
      }
    }
    for (var i = 0; i < ids.size(); i++) {
      if (named[i] == 0) {
        var target = soleEntry.getOrDefault(normalized(echoed.get(i)), 0);
        if (target > 0 && taken.add(target)) {
          named[i] = target;
        }
      }
    }
    return named;
  }

  /** {@code key} stripped and lower-cased, the empty string for {@code null}. */
  public static String normalized(String key) {
    return key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Each listed key mapped to the number of its entry, or to 0 when two entries share it, built in
   * one pass so each answer is a lookup. Listed keys are digests, never blank, so a blank echoed
   * key finds nothing.
   */
  private static Map<String, Integer> soleEntries(List<String> listed) {
    var soleEntry = new HashMap<String, Integer>();
    for (var i = 0; i < listed.size(); i++) {
      soleEntry.merge(listed.get(i), i + 1, (first, again) -> 0);
    }
    return soleEntry;
  }
}
