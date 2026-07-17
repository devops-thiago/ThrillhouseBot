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
package dev.thiagogonzaga.thrillhousebot.review.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads the labeled prompt-regression corpus (issue #113). Each subdirectory of {@code
 * src/test/resources/evalcorpus} is one {@link EvalCase}; new dogfood outcomes are added by
 * dropping in a new directory with {@code case.json} + {@code diff.txt} — no code change needed.
 */
final class EvalCorpus {

  static final Path CORPUS_DIR = Path.of("src", "test", "resources", "evalcorpus");

  private EvalCorpus() {}

  static List<EvalCase> load() {
    var mapper = new ObjectMapper();
    var cases = new ArrayList<EvalCase>();
    try (Stream<Path> dirs = Files.list(CORPUS_DIR)) {
      for (Path dir : dirs.filter(Files::isDirectory).sorted(Comparator.naturalOrder()).toList()) {
        var spec = mapper.readValue(dir.resolve("case.json").toFile(), EvalCase.Spec.class);
        var diff = Files.readString(dir.resolve("diff.txt"), StandardCharsets.UTF_8);
        cases.add(new EvalCase(dir.getFileName().toString(), diff, spec));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load eval corpus from " + CORPUS_DIR, e);
    }
    return List.copyOf(cases);
  }
}
