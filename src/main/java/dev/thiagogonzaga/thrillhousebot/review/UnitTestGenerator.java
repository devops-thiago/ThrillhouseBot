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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestGenerationParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestGenerationResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the {@code /generate-tests} suggestion: unit tests for the code the PR changed, posted as
 * a comment the author may copy in. It never commits or edits a file, so no test file is written on
 * the author's behalf.
 *
 * <p>Loads the PR's diff, current title/body and the repository instructions (via {@link
 * AbstractPrSuggestionGenerator}) plus the project stack, asks the {@link UnitTestAssistant} for
 * test files, and renders each one through {@link SuggestionFormatter}. A proposed test is normally
 * a NEW file with no diff line to anchor a committable {@code suggestion} block to — GitHub only
 * renders those on an inline review comment — so each file is presented as a code block headed by
 * the path it belongs at, ready to copy or paste into a new file. Every step fails soft: a failure
 * simply yields {@code null} (post nothing) rather than a noisy error on the PR.
 */
@ApplicationScoped
public class UnitTestGenerator extends AbstractPrSuggestionGenerator {

  private static final String COMMAND = "/generate-tests";

  /** Upper bound on test files rendered into one comment, so a huge PR cannot flood the thread. */
  static final int MAX_TEST_FILES = 5;

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested unit tests\n\n";

  static final String FOOTER =
      """


      ---
      *Suggestion only — nothing was committed. Create each file at the path shown (or merge the \
      cases into the existing file), then run them: treat the code as a starting point and adjust \
      imports and fixtures to match your suite. Re-run with `/generate-tests`.*
      """;

  static final String NOTHING_TO_TEST =
      "🧪 ThrillhouseBot found nothing in this PR's changes that warrants a new unit test.";

  private final ProjectStackResolver projectStackResolver;
  private final UnitTestAssistant testAssistant;
  private final UnitTestGenerationParser parser;
  private final SuggestionFormatter suggestionFormatter;

  @Inject
  public UnitTestGenerator(
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      ProjectStackResolver projectStackResolver,
      UnitTestAssistant testAssistant,
      UnitTestGenerationParser parser,
      SuggestionFormatter suggestionFormatter) {
    super(prClient, diffFormatter, instructionsResolver);
    this.projectStackResolver = projectStackResolver;
    this.testAssistant = testAssistant;
    this.parser = parser;
    this.suggestionFormatter = suggestionFormatter;
  }

  /**
   * Generates the suggested-tests comment for a PR, or {@code null} when there is nothing to work
   * from (no diff) or the model produced no usable answer. When the model judged nothing testable,
   * the comment says so rather than staying silent — the maintainer asked for tests explicitly. The
   * caller is responsible for posting it.
   *
   * @param auth the {@code Authorization} header for the installation (already minted by the
   *     caller)
   */
  @ActivateRequestContext
  public String generate(
      String owner,
      String repo,
      int prNumber,
      String defaultBranch,
      long installationId,
      String auth) {
    var inputs = loadInputs(owner, repo, prNumber, defaultBranch, installationId, auth, COMMAND);
    if (inputs == null) {
      return null;
    }
    String stack =
        SoftLoaders.projectStack(
            projectStackResolver, owner, repo, defaultBranch, installationId, COMMAND);
    String raw =
        callAssistant(
            COMMAND,
            () ->
                testAssistant.generate(
                    PromptTemplateEscaper.fence(inputs.diff()),
                    PromptTemplateEscaper.escape(
                        PromptSections.prContext(inputs.title(), inputs.body())),
                    PromptTemplateEscaper.escape(stack),
                    PromptTemplateEscaper.escape(inputs.instructions())));
    if (raw == null) {
      return null;
    }
    UnitTestGenerationResponse response;
    try {
      response = parser.parse(raw);
    } catch (RuntimeException e) {
      Log.warnf(e, "%s response could not be parsed — posting nothing", COMMAND);
      return null;
    }
    // The disclosure rides on both outcomes: "nothing to test" derived from a truncated diff must
    // never read as a verdict on the whole PR.
    return render(response) + ReviewResult.truncationDisclosure(inputs.omittedFiles());
  }

  /** The comment body for a parsed response, without the partial-coverage disclosure. */
  private String render(UnitTestGenerationResponse response) {
    var tests = response.postableTests();
    if (tests.isEmpty()) {
      return NOTHING_TO_TEST + notesLine(response.notes());
    }
    var sb = new StringBuilder(HEADER);
    int rendered = Math.min(tests.size(), MAX_TEST_FILES);
    for (var test : tests.subList(0, rendered)) {
      sb.append(
              suggestionFormatter.formatGeneratedTestFile(
                  test.path(), test.language(), test.covers(), test.code()))
          .append('\n');
    }
    if (tests.size() > rendered) {
      sb.append("_")
          .append(tests.size() - rendered)
          .append(" further proposed test file(s) were left out to keep this comment readable —")
          .append(" re-run `/generate-tests` once these are in._\n");
    }
    return sb.append(notesLine(response.notes())).append(FOOTER).toString();
  }

  /** The model's coverage caveats as a trailing line, or empty when it flagged none. */
  private static String notesLine(String notes) {
    return notes.isBlank() ? "" : "\n**Not covered:** " + notes.strip() + "\n";
  }
}
