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

/**
 * Path comparison tolerant of the model shortening or lengthening file paths between findings —
 * {@code B.java} and {@code nested/path/B.java} name the same file. Used everywhere two
 * model-reported paths (or a model path and a GitHub comment path) are compared, so a path variant
 * cannot evade duplicate suppression or thread binding.
 */
final class FilePaths {

  private FilePaths() {}

  /** Exact match, or one path is a suffix of the other at a directory boundary. */
  static boolean same(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return a.equals(b) || suffixAtBoundary(a, b) || suffixAtBoundary(b, a);
  }

  /**
   * The shorter path must itself contain a directory segment: a bare file name such as Handler.java
   * would match every file of that name across the repository, and a wrong match here suppresses
   * findings or binds threads to the wrong defect.
   */
  private static boolean suffixAtBoundary(String longer, String shorter) {
    return shorter.indexOf('/') >= 0 && longer.endsWith("/" + shorter);
  }
}
