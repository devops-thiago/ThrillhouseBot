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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * A narrow, deterministic backstop for the accepted Dockerfile image-digest policy (#1070). Only an
 * active decline actually selected for the review can cover a finding. The learning must explicitly
 * state the complete recognized policy, alone or with the audited release-pipeline rationale; both
 * titles must name the mutable-reference class and both paths must have the Dockerfile type. An
 * actual FROM or external COPY source must name the image the finding discusses.
 *
 * <p>Covered findings stay visible, at low confidence with their learning id. Unknown premises,
 * other classes/types, missing anchors, current digest requirements and independent
 * failure/security evidence are left alone. This runs after calibration, before linked-criterion
 * floors and the deterministic scan, which never passes through this guard. It reads no prompt text
 * and makes no model call.
 */
final class DeclineLearningGuard {
  private DeclineLearningGuard() {}

  private static final List<String> IMAGE_POLICIES =
      List.of(
          "we dont pin images by digest in dockerfiles",
          "we do not pin images by digest in dockerfiles");
  private static final List<String> IMAGE_RATIONALES =
      List.of(
          "",
          " the release pipeline resolves every image to a digest at deploy time",
          " the release pipeline resolves every image to a digest at deploy time and the deployed manifest carries that digest");
  private static final List<String> INDEPENDENT_FAILURES =
      List.of(
          " crash ",
          " crashes ",
          " crashed ",
          " incompatible ",
          " unsupported ",
          " exception ",
          " fails at ",
          " fails to ",
          " fail at ",
          " fail to ",
          " permission denied ",
          " data loss ",
          " incorrect output ",
          " incorrect result ",
          " wrong result ",
          " will not start ",
          " wont start ");
  private static final List<String> IMAGE_WORDS = List.of(" image ", " images ");
  private static final List<String> IMAGE_REPRODUCTION =
      List.of(
          " fails to reproduce identical image contents ",
          " fails to reproduce the identical image contents ",
          " fail to reproduce identical image contents ",
          " fail to reproduce the identical image contents ");
  private static final Pattern IMAGE_DIRECTIVES =
      Pattern.compile("\\b(?:FROM|COPY)\\b|(?i)`(?:from|copy)`");

  static ReviewResponse apply(
      ReviewResponse response,
      List<ReviewLearningService.LearningView> selected,
      BiFunction<String, Integer, String> lineText,
      ContextEvidenceResolver.Round context,
      String repoInstructions) {
    if (selected.isEmpty() || response.findings().isEmpty()) return response;
    var kept = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (var finding : response.findings()) {
      var learning = coveredBy(finding, selected, lineText, context, repoInstructions);
      if (learning == null) {
        kept.add(finding);
      } else {
        var note =
            "Maintainer learning L"
                + learning.id()
                + " accepts this Dockerfile image-digest policy; verify whether it still applies.";
        var description = Objects.toString(finding.description(), "");
        if (!description.contains(note)) description = (description + "\n\n" + note).strip();
        kept.add(
            new ReviewResponse.Finding(
                finding.risk(),
                "low",
                finding.file(),
                finding.line(),
                finding.title(),
                description,
                finding.suggestionOld(),
                finding.suggestionNew()));
        changed = true;
      }
    }
    return changed
        ? new ReviewResponse(
            kept,
            response.previousFindingsStatus(),
            FindingVerificationService.recount(response.summary(), kept),
            response.closedEarlierRound())
        : response;
  }

  private static ReviewLearningService.LearningView coveredBy(
      ReviewResponse.Finding finding,
      List<ReviewLearningService.LearningView> selected,
      BiFunction<String, Integer, String> lineText,
      ContextEvidenceResolver.Round context,
      String repoInstructions) {
    if (finding.file() == null
        || !"dockerfile".equals(ReviewLearnings.typeOf(finding.file()))
        || RiskLevel.fromString(finding.risk()) != RiskLevel.LOW
        || !SeverityCalibrator.isMutableExternalReference(finding)
        || context.failingChecks().cites(finding)) return null;
    var claim =
        SeverityCalibrator.normalized(
            Objects.toString(finding.title(), "")
                + "\n"
                + Objects.toString(finding.description(), ""));
    if (hasIndependentFailure(claim)
        || !namesAnchoredImage(claim, lineText.apply(finding.file(), finding.line()))
        || requiresDigest(repoInstructions)
        || context.scopeInstructions(finding.file()).stream()
            .anyMatch(DeclineLearningGuard::requiresDigest)) return null;
    return selected.stream().filter(learning -> covers(learning, finding)).findFirst().orElse(null);
  }

  private static boolean requiresDigest(String instructions) {
    // Keep headings, wrapped demands and implicit references across sentences together. Ambiguous
    // image/digest context abstains even without an imperative keyword. Bare English "from" and
    // "copy" do not establish that subject.
    var raw = Objects.toString(instructions, "");
    var text = SeverityCalibrator.normalized(raw);
    return (text.contains(" digest ")
            || text.contains(" digests ")
            || text.contains(" sha256 ")
            || text.contains(" sha 256 "))
        && (IMAGE_WORDS.stream().anyMatch(text::contains) || IMAGE_DIRECTIVES.matcher(raw).find());
  }

  private static boolean hasIndependentFailure(String claim) {
    var remainder = claim;
    // Losing identical image contents on rebuild is the mutable-reference policy's own
    // consequence. Functional output failures and every other failure clause stay independent.
    if (claim.contains(" rebuild ")
        || claim.contains(" rebuilds ")
        || claim.contains(" rebuilding ")) {
      for (var wording : IMAGE_REPRODUCTION) {
        remainder = remainder.replace(wording, " ");
      }
    }
    return INDEPENDENT_FAILURES.stream().anyMatch(remainder::contains);
  }

  private static boolean covers(
      ReviewLearningService.LearningView learning, ReviewResponse.Finding finding) {
    if (!learning.active()
        || !ReviewLearning.KIND_DECLINE.equals(learning.kind())
        || learning.path() == null
        || !ReviewLearnings.typeOf(learning.path()).equals(ReviewLearnings.typeOf(finding.file())))
      return false;
    if (!statesSupportedPolicy(learning.text())) return false;
    return SeverityCalibrator.isMutableExternalReference(
        new ReviewResponse.Finding(
            learning.findingRisk(),
            "low",
            learning.path(),
            0,
            learning.findingTitle(),
            "",
            null,
            null));
  }

  /** Whole recognized forms only: a prefix cannot establish an unknown qualifier or premise. */
  private static boolean statesSupportedPolicy(String text) {
    var raw = Objects.toString(text, "");
    if (raw.contains("...") || raw.indexOf('…') >= 0) return false;
    var policy = SeverityCalibrator.normalized(raw).strip();
    return IMAGE_POLICIES.stream()
        .anyMatch(
            statement ->
                IMAGE_RATIONALES.stream().anyMatch(reason -> policy.equals(statement + reason)));
  }

  private static boolean namesAnchoredImage(String claim, String line) {
    var source = imageSource(line);
    if (source == null || source.indexOf('$') >= 0 || source.indexOf('@') >= 0) return false;
    var name = SeverityCalibrator.imageName(source);
    return name.length() >= 3 && claim.contains(SeverityCalibrator.normalized(name));
  }

  private static String imageSource(String line) {
    if (line == null || line.isBlank()) return null;
    var tokens = line.strip().split("\\s+");
    var instruction = tokens[0].toLowerCase(Locale.ROOT);
    for (var i = 1; i < tokens.length; i++) {
      var token = tokens[i];
      if (instruction.equals("from") && !token.startsWith("--")) return token;
      if (instruction.equals("copy") && token.startsWith("--from=")) {
        var source = token.substring("--from=".length());
        return source.indexOf(':') >= 0 || source.indexOf('/') >= 0 ? source : null;
      }
    }
    return null;
  }
}
