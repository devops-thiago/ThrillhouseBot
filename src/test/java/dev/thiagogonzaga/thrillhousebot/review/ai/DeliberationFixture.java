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

/**
 * Builds the deliberation a reasoning model writes ahead of its answer, in the shape of the
 * production response #894 was measured on: 256,302 characters, 98.5% of them prose ahead of the
 * answer, the first {@code [} a {@code [LOW]} severity tag at index 242 and the first opening brace
 * inside the prose that followed it. Each round carries what that deliberation carried — bracketed
 * severity tags, fenced Swift and diff excerpts full of braces and brackets, a fenced JSON excerpt
 * of a file under review, and prose that names the contract's fields without writing them as JSON.
 */
public final class DeliberationFixture {

  /** The size of the production response's deliberation (252,380 characters before the answer). */
  public static final int PRODUCTION_DELIBERATION_CHARS = 252_380;

  private static final String OPENING =
      "Let me work through this pull request file by file before writing the answer. The change"
          + " rewires how image pulls report progress, so the integration tests, the registry"
          + " client and the progress reporter all move together; I will check each finding"
          + " against the diff.\n\n";

  private static final String ROUND =
      """
      [LOW] CreateIntegrationTests.swift:175 — Pull-progress doc comment now documents the retry, \
      but the {progress} placeholder it names is never substituted.

      Looking at the pull path again:

      ```swift
      func pull(_ image: String) async throws -> [Layer] {
          let layers: [Layer] = try await client.fetch(image) { progress in
              report(progress[0])
          }
          return layers
      }
      ```

      [MEDIUM] Sources/Registry.swift:42 — the cache key drops the tag, so `cache["\\(name)"]` \
      collides for two tags of one image.

      ```diff
      -    let key = [name, tag].joined(separator: ":")
      +    let key = name
      ```

      The package manifest the PR touches pins the retry policy:

      ```json
      {"retries": 3, "backoff": [1, 2, 4], "summary_level": "brief"}
      ```

      Does the findings list from the previous round still apply? Finding 2 (the {tag} collision) \
      is unchanged, so its previous_findings_status stays unresolved; the summary should say so.

      """;

  private DeliberationFixture() {}

  /**
   * Deliberation of at least {@code minChars} characters, opening the way the production one did.
   */
  public static String deliberation(int minChars) {
    var out = new StringBuilder(minChars + ROUND.length() + OPENING.length());
    out.append(OPENING);
    while (out.length() < minChars) {
      out.append(ROUND);
    }
    return out.toString();
  }
}
