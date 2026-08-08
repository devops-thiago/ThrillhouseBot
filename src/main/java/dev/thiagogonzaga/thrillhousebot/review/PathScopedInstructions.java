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

import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * The path-scoped review rules that actually apply to one review: each scope a repository declared
 * under {@code review.path-instructions} in {@code .github/thrillhousebot.yml}, paired with the
 * reviewable files it matched.
 *
 * <p>Resolved once per review by {@link ReviewContextLoader} — the globs are compiled and walked a
 * single time, exactly like the ignore set — and rendered into the prompt by {@link
 * PromptSections#pathInstructionsSection}. A scope that matched nothing in this PR is dropped here
 * rather than in the renderer, so rules for untouched directories never reach the model at all.
 *
 * <p>Matching goes through {@link ReviewDiffFormatter.IgnoreGlobs}, the codebase's one compiled
 * glob-set type: a scope's glob gets its own single-pattern set, so "which scopes match this file"
 * is answered by the same matcher (and the same {@code **} semantics) as "is this file ignored".
 *
 * @param scopes the declared scopes that matched at least one reviewable file, in declaration order
 * @param source the repo-relative config path the scopes came from, or {@code "none"}
 */
public record PathScopedInstructions(List<AppliedScope> scopes, String source) {

  /** No scoped rules apply — either none were declared or none matched a file in this PR. */
  public static final PathScopedInstructions NONE = new PathScopedInstructions(List.of(), "none");

  /**
   * One declared scope and the files in this PR it governs.
   *
   * @param glob the path glob the maintainers scoped the rules to
   * @param instructions the maintainer prose for that glob — untrusted data, escaped before it
   *     reaches a prompt
   * @param files the reviewable files matching {@code glob}, so the model can tell which rules
   *     apply to which paths
   */
  public record AppliedScope(String glob, String instructions, List<String> files) {
    public AppliedScope {
      files = List.copyOf(files);
    }
  }

  public PathScopedInstructions {
    scopes = List.copyOf(scopes);
  }

  /** Whether any scoped rule applies to this review. */
  public boolean isEmpty() {
    return scopes.isEmpty();
  }

  /**
   * The scopes in {@code settings} that match at least one of {@code filenames}, with their matched
   * files. Compiles each declared glob once and walks the file list once per scope; the result is
   * held on the review context so no later stage re-walks a glob per finding.
   *
   * <p>Fails soft in every direction: no declared scopes, an uncompilable glob (dropped by {@link
   * ReviewDiffFormatter.IgnoreGlobs#compile}, which then matches nothing), or no matching file all
   * yield {@link #NONE} and leave the review running on the global instructions alone.
   */
  static PathScopedInstructions resolve(RepoSettings settings, List<String> filenames) {
    if (settings == null || settings.pathInstructions().isEmpty()) {
      return NONE;
    }
    if (filenames == null || filenames.isEmpty()) {
      return NONE;
    }
    var applied = new ArrayList<AppliedScope>(settings.pathInstructions().size());
    for (RepoSettings.PathInstructions declared : settings.pathInstructions()) {
      var globs = ReviewDiffFormatter.IgnoreGlobs.compile(List.of(declared.path()));
      if (globs.matchers().isEmpty()) {
        Log.warnf("Ignoring path-scoped review rules for uncompilable glob: %s", declared.path());
        continue;
      }
      var matched = filenames.stream().filter(globs::matches).toList();
      if (!matched.isEmpty()) {
        applied.add(new AppliedScope(declared.path(), declared.instructions(), matched));
      }
    }
    return applied.isEmpty() ? NONE : new PathScopedInstructions(applied, settings.source());
  }
}
