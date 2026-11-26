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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;

/**
 * Parsed JSON returned by the {@link FixGenerator} for one {@code /fix} request: a one-line
 * summary, the file edits making up the fix, and optional maintainer notes. An empty {@code edits}
 * list means the model declined to fix (the {@code notes} say why).
 */
@RegisterForReflection
public record FixResponse(String summary, List<FileEdit> edits, String notes) {
  public FixResponse {
    // The model may emit null array elements; drop them before copying so a single bad entry never
    // fails the whole command.
    edits = List.copyOf(withoutNulls(edits));
  }

  private static List<FileEdit> withoutNulls(List<FileEdit> values) {
    return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
  }

  /**
   * One file change: either an {@code replace} of a verbatim-unique {@code search} snippet with
   * {@code replace}, or a {@code create} of a new file whose whole content is {@code replace}.
   */
  @RegisterForReflection
  public record FileEdit(String file, String operation, String search, String replace) {

    public boolean isCreate() {
      return "create".equalsIgnoreCase(operation);
    }

    /**
     * Whether this edit carries the data needed to apply it: a file path, a replacement, and — for
     * a replace — the non-blank snippet to substitute. A create needs no search snippet but its
     * content must be non-blank (an intentionally empty new file is not worth a commit).
     */
    public boolean isApplicable() {
      if (file == null || file.isBlank() || replace == null) {
        return false;
      }
      if (isCreate()) {
        return !replace.isBlank();
      }
      return "replace".equalsIgnoreCase(operation) && search != null && !search.isBlank();
    }
  }
}
