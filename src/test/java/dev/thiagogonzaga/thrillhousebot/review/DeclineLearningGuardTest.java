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

import static org.junit.jupiter.api.Assertions.*;

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeclineLearningGuardTest {
  private static final String TITLE = "Base image is not pinned by digest";
  private static final String POLICY =
      "We don't pin images by digest in Dockerfiles: the release pipeline resolves every image to a digest at deploy time and the deployed manifest carries that digest.";
  private static final String DESCRIPTION =
      "The python image is referenced by a mutable tag, so rebuilding can use different image contents.";
  private static final String LINE = "FROM python:3-slim";

  private static ReviewLearningService.LearningView learning(
      String path, String title, String text, String kind, boolean active) {
    return new ReviewLearningService.LearningView(
        12,
        kind,
        title,
        "medium",
        path,
        text,
        180,
        "https://github.com/o/r/pull/180#discussion_r12",
        "maintainer",
        Instant.EPOCH,
        active,
        null,
        null);
  }

  private static ReviewLearningService.LearningView learning(
      String path, String title, String text) {
    return learning(path, title, text, ReviewLearning.KIND_DECLINE, true);
  }

  private static ReviewResponse response(String path, String title, String description) {
    return new ReviewResponse(
        List.of(
            new ReviewResponse.Finding("low", "medium", path, 1, title, description, LINE, null)),
        List.of(),
        null);
  }

  private static ReviewResponse response() {
    return response("b/Dockerfile", TITLE, DESCRIPTION);
  }

  private static ReviewResponse apply(
      ReviewResponse response, List<ReviewLearningService.LearningView> selected, String line) {
    return DeclineLearningGuard.apply(
        response, selected, (path, nr) -> line, ContextEvidenceResolver.disabled(), "");
  }

  private static List<ReviewLearningService.LearningView> selected() {
    return List.of(learning("a/Dockerfile", TITLE, POLICY));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"a/Dockerfile", "a/Dockerfile.prod", "a/prod.Dockerfile", "a/Containerfile"})
  void theSameDefectClassAndFileTypeStayVisibleAsADoubleCheck(String path) {
    var raw = response();
    var held = apply(raw, List.of(learning(path, TITLE, POLICY)), LINE);
    assertEquals(1, held.findings().size());
    var finding = held.findings().getFirst();
    assertEquals("low", finding.risk());
    assertEquals("low", finding.confidence());
    assertEquals(
        FindingPlacement.DOUBLE_CHECK,
        FindingPlacement.of(
            RiskLevel.fromString(finding.risk()), Confidence.fromString(finding.confidence())));
    assertTrue(finding.description().contains("L12"));
    assertEquals(raw.findings().getFirst().file(), finding.file());
    assertEquals(raw.findings().getFirst().line(), finding.line());
    assertEquals(raw.findings().getFirst().title(), finding.title());
    assertEquals(raw.findings().getFirst().suggestionOld(), finding.suggestionOld());
    assertEquals(
        held.findings(), apply(held, selected(), LINE).findings(), "The note is not duplicated");
  }

  @ParameterizedTest
  @ValueSource(strings = {"a/chart.yaml", "a/Dockerfile.md", "a/Handler.java"})
  void anotherLearningFileTypeDoesNotCoverTheFinding(String path) {
    var raw = response();
    assertSame(raw, apply(raw, List.of(learning(path, TITLE, POLICY)), LINE));
  }

  @Test
  void otherClassesConventionsInactiveAndUnscopedLearningsDoNotCoverIt() {
    var raw = response();
    for (var learning :
        List.of(
            learning(
                "a/Dockerfile",
                "Runtime path is root-owned and the non-root user cannot write",
                POLICY),
            learning("a/Dockerfile", TITLE, POLICY, ReviewLearning.KIND_CONVENTION, true),
            learning("a/Dockerfile", TITLE, POLICY, ReviewLearning.KIND_DECLINE, false),
            learning(null, TITLE, POLICY))) {
      assertSame(raw, apply(raw, List.of(learning), LINE));
    }
    var other =
        response("b/Dockerfile", "Container never drops privileges", "No USER instruction exists.");
    assertSame(other, apply(other, selected(), LINE));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "Arbitrary text containing image, digest and mutable tag.",
        "The release pipeline pins tags.",
        "We don't pin images by digest in Dockerfiles for internal images.",
        "We don't pin images by digest in Dockerfiles, for internal images.",
        "We don't pin images by digest in Dockerfiles while the registry stays immutable.",
        "We don't pin images by digest in Dockerfiles; while the registry stays immutable.",
        "We don't pin images by digest in Dockerfiles...",
        "We don't pin images by digest in Dockerfiles…",
        "We don't pin images by digest in Dockerfiles⋯",
        "We don't pin images by digest in Dockerfiles‥",
        "We don't pin images by digest in Dockerfiles?",
        "We don't pin images by digest in Dockerfiles~",
        "We don't pin images by digest in Dockerfiles’",
        "We don't pin images⋯ by digest in Dockerfiles.",
        "We don't; pin images by digest in Dockerfiles.",
        "We don't pin images, by digest in Dockerfiles.",
        "We d'ont pin images by digest in Dockerfiles.",
        "We don't pin im'ages by digest in Dockerfiles.",
        "We don't pin images by digest in Dockerfiles: the release pipeline resolves every image to a digest at deploy time!",
        "We don't pin images by digest in Dockerfiles:: the release pipeline resolves every image to a digest at deploy time.",
        "We don't pin images by digest in Dockerfilesthe release pipeline resolves every image to a digest at deploy time.",
        "We do not not pin images by digest in Dockerfiles.",
        "We don't pin images not by digest in Dockerfiles.",
        "We don't pin images by digest in Dockerfiles: the release pipeline does not resolve every image to a digest at deploy time.",
        "We don't pin images by digest in Dockerfiles: the registry is immutable.",
        "We don't pin images by digest in Dockerfiles. Arbitrary explanatory text follows.",
        "We don't pin images by digest in Dockerfiles except production.",
        "We don't pin images by digest in Dockerfiles if CI resolves them.",
        "We don't pin images by digest in Dockerfiles, but this image must be pinned."
      })
  void conditionalOrUnstatedPremisesDoNotCoverIt(String text) {
    var raw = response();
    assertSame(raw, apply(raw, List.of(learning("a/Dockerfile", TITLE, text)), LINE));
  }

  @Test
  void theUncontractedPolicySpellingHasTheSameMeaning() {
    assertEquals(
        "low",
        apply(
                response(),
                List.of(learning("a/Dockerfile", TITLE, POLICY.replace("don't", "do not"))),
                LINE)
            .findings()
            .getFirst()
            .confidence());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "We don't pin images by digest in Dockerfiles.",
        "We do not pin images by digest in Dockerfiles",
        "We don't pin images by digest in Dockerfiles: the release pipeline resolves every image to a digest at deploy time.",
        "We don’t pin images by digest in Dockerfiles.",
        "WE DON'T  PIN\nIMAGES BY\tDIGEST IN DOCKERFILES .",
        "We dont pin images by digest in Dockerfiles.",
        "We don't pin images by digest in Dockerfiles the release pipeline resolves every image to a digest at deploy time.",
        "We don't pin images by digest in Dockerfiles. The release pipeline resolves every image to a digest at deploy time.",
        "We don't pin images by digest in Dockerfiles; the release pipeline resolves every image to a digest at deploy time.",
        "We don't pin images by digest in Dockerfiles, the release pipeline resolves every image to a digest at deploy time.",
        "We don't pin images by digest in Dockerfiles : the release pipeline resolves every image to a digest at deploy time.",
        "We don't pin images by digest in Dockerfiles:the release pipeline resolves every image to a digest at deploy time."
      })
  void completeRecognizedPoliciesHaveTheSameMeaning(String text) {
    assertEquals(
        "low",
        apply(response(), List.of(learning("a/Dockerfile", TITLE, text)), LINE)
            .findings()
            .getFirst()
            .confidence());
  }

  @Test
  void absentSelectionAndAbsentFindingsChangeNothing() {
    var raw = response();
    assertSame(raw, apply(raw, List.of(), LINE));
    var empty = new ReviewResponse(List.of(), List.of(), null);
    assertSame(empty, apply(empty, selected(), LINE));
    for (var path : new String[] {null, "docs/Dockerfile.md", "src/Main.java"}) {
      var other = response(path, TITLE, DESCRIPTION);
      assertSame(other, apply(other, selected(), LINE));
    }
    var high =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "high", "medium", "b/Dockerfile", 1, TITLE, DESCRIPTION, LINE, null)),
            List.of(),
            null);
    assertSame(high, apply(high, selected(), LINE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "FROM --platform=linux/amd64 python:3-slim AS build",
        "COPY --link --from=registry.example/python:latest /bin/python /bin/python",
        "COPY --from=registry.example/python /bin/python /bin/python",
        "COPY --from=python:latest /bin/python /bin/python"
      })
  void actualImageReferencesCoverFromAndExternalCopy(String line) {
    assertEquals("low", apply(response(), selected(), line).findings().getFirst().confidence());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "FROM",
        "RUN echo python:latest",
        "COPY --from=build /out /app",
        "COPY --from=python /out /app",
        "FROM ${IMAGE}",
        "FROM python@sha256:abcd",
        "FROM py:3-slim",
        "FROM ruby:3-slim"
      })
  void absentOrMismatchedImageEvidenceDoesNotCoverIt(String line) {
    var raw = response();
    assertSame(raw, apply(raw, selected(), line));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "It is incompatible with the API.",
        "The python image crashes at startup.",
        "The python image carries a known CVE.",
        "The python image loses credentials."
      })
  void independentStrongerConsequencesRemainUnchanged(String consequence) {
    var raw = response("b/Dockerfile", TITLE, DESCRIPTION + " " + consequence);
    assertSame(raw, apply(raw, selected(), LINE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Rebuilding the python image fails to reproduce the identical image contents.",
        "Rebuilding the python image fails to reproduce identical image contents.",
        "Rebuilds of the python image fail to reproduce identical image contents.",
        "A rebuild of the python image fails to reproduce identical image contents."
      })
  void imageContentReproducibilityIsTheAcceptedPolicyConsequence(String description) {
    assertEquals(
        "low",
        apply(response("b/Dockerfile", TITLE, description), selected(), LINE)
            .findings()
            .getFirst()
            .confidence());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The python image fails to initialize the validator.",
        "Rebuilding the python image fails to reproduce expected application output.",
        "The python image fails to reproduce identical image contents.",
        "The python image fails to authenticate to the registry.",
        "Rebuilding the python image fails to reproduce identical image contents and the application fails to start.",
        DESCRIPTION + " Rebuilding the python image cannot start the registry authentication.",
        DESCRIPTION + " The python image does not start the application.",
        DESCRIPTION + " The python image refuses to start the application.",
        DESCRIPTION + " The python image times out during startup.",
        DESCRIPTION + " The python image breaks the application."
      })
  void functionalFailuresRemainIndependentOfImageReproducibility(String description) {
    var raw = response("b/Dockerfile", TITLE, description);
    assertSame(raw, apply(raw, selected(), LINE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Base image is not pinned to a digest",
        "Base image pinned to a mutable tag",
        "Unpinned base image",
        "BASE  IMAGE IS NOT PINNED BY DIGEST."
      })
  void completeRecognizedTitlesStillCoverThePolicy(String title) {
    assertEquals(
        "low",
        apply(response("b/Dockerfile", title, DESCRIPTION), selected(), LINE)
            .findings()
            .getFirst()
            .confidence());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        TITLE + "...",
        TITLE + "?",
        "Possibly " + TITLE,
        TITLE + " and cannot start registry authentication",
        TITLE + " and times out at startup",
        "The python image breaks the application: " + TITLE
      })
  void unknownIncompleteOrIndependentTitlesRemainUnchanged(String title) {
    var raw = response("b/Dockerfile", title, DESCRIPTION);
    assertSame(raw, apply(raw, selected(), LINE));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        DESCRIPTION + "...",
        DESCRIPTION + "?",
        "Possibly " + DESCRIPTION,
        "The python image is not referenced by a mutable tag, so rebuilding can use different image contents.",
        DESCRIPTION + " Additional context has an unknown consequence.",
        DESCRIPTION + "\n\nUnverified: arbitrary metadata is not an exact verifier paragraph.",
        FindingVerificationService.UNVERIFIED_NOTE + "\n\n" + DESCRIPTION,
        DESCRIPTION
            + "\n\n"
            + FindingVerificationService.UNVERIFIED_NOTE
            + "\n\nThe python image cannot start.",
        DESCRIPTION
            + " The python image cannot start.\n\n"
            + FindingVerificationService.UNVERIFIED_NOTE
      })
  void unknownOrExtraDescriptionsRemainUnchanged(String description) {
    var raw = response("b/Dockerfile", TITLE, description);
    assertSame(raw, apply(raw, selected(), LINE));
  }

  @Test
  void theExactAppendedVerifierNoteRemainsVisibleWithTheCoveredFinding() {
    var description = DESCRIPTION + "\n\n" + FindingVerificationService.UNVERIFIED_NOTE;
    var finding =
        apply(response("b/Dockerfile", TITLE, description), selected(), LINE).findings().getFirst();
    assertEquals("low", finding.confidence());
    assertTrue(finding.description().startsWith(description));
    assertTrue(finding.description().contains("L12"));
  }

  @Test
  void actualCiEvidenceOverridesTheLearning() {
    var raw =
        response(
            "b/Dockerfile",
            TITLE,
            DESCRIPTION + " This matches CI check test (conclusion: failure).");
    var context =
        ContextEvidenceResolver.forReview(
            "",
            PathScopedInstructions.NONE,
            "Checks on this commit that had completed without passing when this review started: 1\n\n### test (conclusion: failure)\nTitle: test\n",
            new EvidenceBudget());
    assertTrue(context.failingChecks().cites(raw.findings().getFirst()));
    assertSame(raw, DeclineLearningGuard.apply(raw, selected(), (p, n) -> LINE, context, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "All images must be pinned by digest.",
        "Images shall use SHA256 references.",
        "Images use SHA references.",
        "Images must be referenced by sha1.",
        "Images must be referenced by sha512.",
        "Images must be referenced by SHA3-256.",
        "Always reference the base by sha.",
        "Container sources use SHA references.",
        "Dockerfile references use digests.",
        "All FROM references must use SHA-256.",
        "Pin images by digest.",
        "# Dockerfile rules\nPin images by digest.",
        "# Dockerfile rules\nAll images must be pinned\nby digest.",
        "Pin images\nby digest.",
        "All `from` references must use digests.",
        "All COPY sources must use digests.",
        "All base images follow our supply-chain policy. They must be pinned by digest.",
        "Dockerfile images are referenced by digest; tags are forbidden.",
        "Base images use their sha256 digest, never tags.",
        "Image references are digest-addressed.",
        "`FROM` sources are identified by digest.",
        "Image examples and digest details are listed below.",
        "The artifact digest must be recorded. Image examples belong in the README."
      })
  void currentRepositoryRequirementsOverrideTheLearning(String instructions) {
    var raw = response();
    assertSame(
        raw,
        DeclineLearningGuard.apply(
            raw, selected(), (p, n) -> LINE, ContextEvidenceResolver.disabled(), instructions));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The changelog must record the sha256 digest of each artifact, and keep its summary copy short.",
        "Record the artifact digest always from build output."
      })
  void artifactOnlyDigestProseDoesNotEstablishAnImageRequirement(String instructions) {
    assertEquals(
        "low",
        DeclineLearningGuard.apply(
                response(),
                selected(),
                (p, n) -> LINE,
                ContextEvidenceResolver.disabled(),
                instructions)
            .findings()
            .getFirst()
            .confidence());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Images follow shadow and sharing conventions.",
        "Images use xsha512y registry names.",
        "Images use sha512suffix tags."
      })
  void shaLookalikeWordsDoNotEstablishADigestRequirement(String instructions) {
    assertEquals(
        "low",
        DeclineLearningGuard.apply(
                response(),
                selected(),
                (p, n) -> LINE,
                ContextEvidenceResolver.disabled(),
                instructions)
            .findings()
            .getFirst()
            .confidence());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "All images must be pinned by digest.",
        "Dockerfile images are referenced by digest; tags are forbidden.",
        "References must be digest-pinned.",
        "Always digest-pin every reference.",
        "References use SHA-256.",
        "Always reference the base by sha.",
        "References use sha512."
      })
  void onlyApplicableCurrentScopedRequirementsOverrideItEvenWhenNotesDoNotFit(String instructions) {
    var budget = new EvidenceBudget();
    for (var i = 0; i < 5; i++) assertNotNull(budget.attach("x".repeat(1200)));
    var scope =
        new PathScopedInstructions(
            List.of(
                new PathScopedInstructions.AppliedScope(
                    "**/Dockerfile", instructions, List.of("b/Dockerfile"))),
            ".github/thrillhousebot.yml");
    var context = ContextEvidenceResolver.forReview("", scope, budget);
    var raw = response();
    assertNull(context.locate(raw.findings()).forFinding(raw.findings().getFirst()));
    assertSame(raw, DeclineLearningGuard.apply(raw, selected(), (p, n) -> LINE, context, ""));
    var other = response("unrelated/Dockerfile", TITLE, DESCRIPTION);
    assertEquals(
        "low",
        DeclineLearningGuard.apply(other, selected(), (p, n) -> LINE, context, "")
            .findings()
            .getFirst()
            .confidence());
    assertEquals(List.of(), context.scopeInstructions(null));
    assertEquals(List.of(), ContextEvidenceResolver.disabled().scopeInstructions("b/Dockerfile"));
    assertEquals(
        "low",
        DeclineLearningGuard.apply(
                raw,
                selected(),
                (p, n) -> LINE,
                ContextEvidenceResolver.disabled(),
                "Artifacts must preserve digests.")
            .findings()
            .getFirst()
            .confidence(),
        "A current rule about another kind of artifact does not govern this image");
  }

  @Test
  void aLinkedCriterionIsNotCoveredAndItsEvidenceStillRaisesTheFloor() {
    var raw =
        response(
            "b/Dockerfile",
            TITLE,
            DESCRIPTION + " This violates the linked issue's 'Images must be pinned by digest'.");
    assertSame(raw, apply(raw, selected(), LINE), "The extra current criterion is not policy-only");
    var finding = raw.findings().getFirst();
    var low =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    finding.risk(),
                    "low",
                    finding.file(),
                    finding.line(),
                    finding.title(),
                    finding.description(),
                    finding.suggestionOld(),
                    finding.suggestionNew())),
            List.of(),
            null);
    var held = apply(low, selected(), LINE);
    assertSame(low, held, "The guard leaves a model's low-confidence criterion claim untouched");
    var issues =
        "Issues this pull request is linked to: #7\n\n### Issue #7: Image policy\nIssue body:\nImages must be pinned by digest.\n";
    assertEquals(
        "medium", LinkedIssueCitations.apply(held, issues).findings().getFirst().confidence());
  }

  @Test
  void scopedRulesAndSourceAnchorsResolveTheActualFile() {
    var patch = "@@ -0,0 +1 @@\n+" + LINE + "\n";
    var uniqueLines = new DiffLineResolver(Map.of("repo/app/Dockerfile", patch));
    var rule = "All images must be pinned by digest.";
    var unique =
        ContextEvidenceResolver.forReview(
            "",
            new PathScopedInstructions(
                List.of(
                    new PathScopedInstructions.AppliedScope(
                        "**/Dockerfile", rule, List.of("repo/app/Dockerfile"))),
                "config"),
            new EvidenceBudget());
    var raw = response("app/Dockerfile", TITLE, DESCRIPTION);
    assertEquals(LINE, uniqueLines.getLineText("app/Dockerfile", 1));
    assertSame(
        raw, DeclineLearningGuard.apply(raw, selected(), uniqueLines::getLineText, unique, ""));
    var ambiguous =
        ContextEvidenceResolver.forReview(
            "",
            new PathScopedInstructions(
                List.of(
                    new PathScopedInstructions.AppliedScope(
                        "**/Dockerfile", rule, List.of("app/Dockerfile", "other/Dockerfile"))),
                "config"),
            new EvidenceBudget());
    assertEquals(List.of(), ambiguous.scopeInstructions("Dockerfile"));
    var ambiguousLines =
        new DiffLineResolver(Map.of("app/Dockerfile", patch, "other/Dockerfile", patch));
    assertNull(ambiguousLines.getLineText("Dockerfile", 1));
    var bare = response("Dockerfile", TITLE, DESCRIPTION);
    assertSame(
        bare,
        DeclineLearningGuard.apply(bare, selected(), ambiguousLines::getLineText, ambiguous, ""),
        "Production refuses a bare source anchor before considering scope rules");
    var collidingLines =
        new DiffLineResolver(Map.of("first/app/Dockerfile", patch, "second/app/Dockerfile", patch));
    assertNull(collidingLines.getLineText("app/Dockerfile", 1));
    assertSame(
        raw,
        DeclineLearningGuard.apply(raw, selected(), collidingLines::getLineText, unique, ""),
        "Production also refuses a directory suffix shared by two source files");
    var exact = response("app/Dockerfile", TITLE, DESCRIPTION);
    assertEquals(LINE, ambiguousLines.getLineText("app/Dockerfile", 1));
    assertSame(
        exact,
        DeclineLearningGuard.apply(exact, selected(), ambiguousLines::getLineText, ambiguous, ""),
        "An exact source path also keeps its applicable current digest rule");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Use four spaces.", "An artifact copy comes from build output.", ""})
  void applicableScopedRulesWithoutDigestContextStillAllowTheLearning(String instructions) {
    var context =
        ContextEvidenceResolver.forReview(
            "",
            new PathScopedInstructions(
                List.of(
                    new PathScopedInstructions.AppliedScope(
                        "**/Dockerfile", instructions, List.of("b/Dockerfile"))),
                "config"),
            new EvidenceBudget());
    assertEquals(
        "low",
        DeclineLearningGuard.apply(response(), selected(), (p, n) -> LINE, context, "")
            .findings()
            .getFirst()
            .confidence());
  }
}
