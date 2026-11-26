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
package dev.thiagogonzaga.thrillhousebot.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Fuzzes {@link RepoSettingsParser#parse(String, String)} with the YAML of a repository's own
 * config file, which any repository the bot is installed on controls. The parser's contract is that
 * it never throws and that every ceiling it declares holds on what it returns, whatever the
 * document looks like (aliases, merge keys, deep nesting, duplicate keys).
 */
class RepoSettingsParserFuzzTest {

  private static final String SOURCE = ".thrillhouse.yml";

  /**
   * The parser logs a warning with a stack trace for every document it refuses, and snakeyaml one
   * for every duplicate key, which together is nearly every fuzz input: gigabytes of output per
   * minute. Both are turned off while this class runs and put back for the tests after it. Held in
   * fields because the log manager keeps only a weak reference to a logger.
   */
  private static final Logger PARSER_LOG = Logger.getLogger(RepoSettingsParser.class.getName());

  private static final Logger SNAKEYAML_LOG = Logger.getLogger("org.yaml.snakeyaml");

  private static Level parserLevel;
  private static Level snakeyamlLevel;

  @BeforeAll
  static void silenceParserWarnings() {
    parserLevel = PARSER_LOG.getLevel();
    snakeyamlLevel = SNAKEYAML_LOG.getLevel();
    PARSER_LOG.setLevel(Level.OFF);
    SNAKEYAML_LOG.setLevel(Level.OFF);
  }

  @AfterAll
  static void restoreParserWarnings() {
    PARSER_LOG.setLevel(parserLevel);
    SNAKEYAML_LOG.setLevel(snakeyamlLevel);
  }

  @FuzzTest
  void parsesAnyDocument(FuzzedDataProvider data) {
    var yaml = data.consumeRemainingAsString();

    var settings = RepoSettingsParser.parse(yaml, SOURCE);

    assertThat(settings).isNotNull();
    assertThat(settings.ignoredFiles())
        .hasSizeLessThanOrEqualTo(RepoSettingsParser.MAX_PATTERNS)
        .allSatisfy(
            pattern ->
                assertThat(pattern)
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(RepoSettingsParser.MAX_PATTERN_LENGTH));
    assertThat(settings.pathInstructions())
        .hasSizeLessThanOrEqualTo(RepoSettingsParser.MAX_PATH_SCOPES)
        .allSatisfy(
            scope -> {
              assertThat(scope.path())
                  .isNotBlank()
                  .hasSizeLessThanOrEqualTo(RepoSettingsParser.MAX_PATTERN_LENGTH);
              assertThat(scope.instructions())
                  .isNotBlank()
                  .hasSizeLessThanOrEqualTo(RepoSettingsParser.MAX_SCOPE_INSTRUCTIONS_LENGTH);
            });
    assertThat(settings.coverageArtifact())
        .hasSizeLessThanOrEqualTo(RepoSettingsParser.MAX_ARTIFACT_NAME_LENGTH);
    if (settings != RepoSettings.EMPTY) {
      assertThat(settings.source()).isEqualTo(SOURCE);
    }
  }
}
