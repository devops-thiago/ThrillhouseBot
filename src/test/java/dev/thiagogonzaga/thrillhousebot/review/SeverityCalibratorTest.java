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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #773. The round-8 corpus graded the same infrastructure defect differently in different pull
 * requests, so every test here states the SAME defect twice — once as the pull request that drew
 * the lower grade, once as the one that drew the higher — and asserts the two come out equal.
 * Asserting one finding at a time would pass on a calibration that is merely consistent with
 * itself; the pair is the property the issue asks for.
 */
class SeverityCalibratorTest {

  /** The python half of the corpus pair: `ensure_schema()` writes first, and the grade was low. */
  private static final String ROOT_VOLUME_PYTHON =
      "The image declares VOLUME /var/lib/app and then switches to USER appuser without a mkdir"
          + " and chown for that path. Docker creates it root-owned, so the first write from"
          + " ensure_schema() fails with permission denied and the container exits.";

  /** The node half: the same shape, and it drew medium. */
  private static final String ROOT_VOLUME_NODE =
      "VOLUME /data is declared before USER node, and nothing chowns it, so the directory is"
          + " root-owned at run time and the first flush() cannot write to it.";

  private static final String UNPINNED_NODE =
      "FROM node:20-alpine is an unpinned base image: the tag moves, so two builds of this commit"
          + " can resolve to different images. Pin it by digest.";

  private static final String UNPINNED_RUST =
      "The base image rust:1.81 carries no digest, so the build is not reproducible even with"
          + " Cargo.lock and --locked pinning the dependency set.";

  private static final String NO_USER_CSHARP =
      "The Dockerfile never adds a USER directive, so the published service runs as root.";

  private static final String NO_USER_GO =
      "No USER instruction is set anywhere in this Dockerfile; the container runs as root user.";

  private static ReviewResponse.Finding finding(
      String risk, String confidence, String file, String description) {
    return new ReviewResponse.Finding(
        risk, confidence, file, 7, "container image finding", description, null, null);
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(
        List.of(findings),
        List.of(),
        new ReviewResponse.Summary(
            findings.length, 0, 0, 0, findings.length, "assessment", "purpose", List.of()));
  }

  private static ReviewResponse.Finding calibrateOne(ReviewResponse.Finding raw) {
    return SeverityCalibrator.calibrate(response(raw)).findings().get(0);
  }

  private static void assertGrade(
      ReviewResponse.Finding calibrated, String risk, String confidence) {
    assertEquals(risk, calibrated.risk(), "risk");
    assertEquals(confidence, calibrated.confidence(), "confidence");
  }

  @Test
  void theSameUnpinnedBaseImageGradesTheSameInBothPullRequests() {
    ReviewResponse.Finding node = calibrateOne(finding("low", "low", "Dockerfile", UNPINNED_NODE));
    ReviewResponse.Finding rust =
        calibrateOne(finding("medium", "high", "rust/Dockerfile", UNPINNED_RUST));

    assertGrade(node, "low", "medium");
    assertGrade(rust, "low", "medium");
  }

  @Test
  void theSameRootOwnedVolumeGradesTheSameInBothPullRequests() {
    ReviewResponse.Finding python =
        calibrateOne(finding("low", "low", "python/Dockerfile", ROOT_VOLUME_PYTHON));
    ReviewResponse.Finding node =
        calibrateOne(finding("medium", "high", "node/Dockerfile", ROOT_VOLUME_NODE));

    assertGrade(python, "high", "medium");
    assertGrade(node, "high", "medium");
  }

  @Test
  void theSameMissingUserDirectiveGradesTheSameInBothHalvesOfAPairedChange() {
    ReviewResponse.Finding csharp =
        calibrateOne(finding("low", "low", "csharp/Dockerfile", NO_USER_CSHARP));
    ReviewResponse.Finding go =
        calibrateOne(finding("medium", "high", "go/Dockerfile", NO_USER_GO));

    assertGrade(csharp, "medium", "medium");
    assertGrade(go, "medium", "medium");
  }

  /**
   * The surface split the same corpus measured: a "low"-confidence finding below high risk is
   * collapsed into the summary block and opens no thread, so the same omission reached the diff in
   * one language and nowhere in the other.
   */
  @Test
  void anAnchoredFindingPostsInlineWhateverConfidenceTheReviewGaveIt() {
    for (String description : List.of(UNPINNED_NODE, ROOT_VOLUME_PYTHON, NO_USER_CSHARP)) {
      ReviewResponse.Finding calibrated =
          calibrateOne(finding("low", "low", "Dockerfile", description));
      assertTrue(
          Finding.fromAiResponse(calibrated).postsInline(),
          "anchored finding must open a thread on the diff: " + description);
    }
  }

  /** The anchor pins: a level above it is as much a miscalibration as one below. */
  @ParameterizedTest
  @CsvSource({"critical,high", "high,medium", "medium,medium", "medium,low", "low,high"})
  void anUnpinnedReferenceIsLowWhateverTheReviewRatedIt(String risk, String confidence) {
    assertGrade(
        calibrateOne(finding(risk, confidence, "Dockerfile", UNPINNED_NODE)), "low", "medium");
  }

  @Test
  void aManifestOutsideADockerfileIsAnchoredToo() {
    ReviewResponse.Finding manifest =
        calibrateOne(
            finding(
                "low",
                "low",
                "deploy/k8s/api.yaml",
                "The pod template leaves runAsNonRoot unset, so the container runs as root."));

    assertGrade(manifest, "medium", "medium");
  }

  /** The same ownership defect, said the way a Kubernetes manifest finding says it. */
  @Test
  void aRootOwnedPathUnderANonRootPodIsTheSameClassAsUnderANonRootImage() {
    ReviewResponse.Finding pod =
        calibrateOne(
            finding(
                "low",
                "low",
                "deploy/k8s/api.yaml",
                "The pod runs as non-root (uid 1000) but /var/lib/data is root-owned, so the first"
                    + " write is denied and the container restarts."));

    assertGrade(pod, "high", "medium");
  }

  /** A non-root image with an unpinned base is the reference class, not the ownership one. */
  @Test
  void aNonRootImageWhoseBaseIsUnpinnedGradesAsTheReferenceClass() {
    ReviewResponse.Finding both =
        calibrateOne(
            finding(
                "low",
                "low",
                "Dockerfile",
                "The image drops to a non-root user, but FROM node:20-alpine is an unpinned base"
                    + " image, so the build is not reproducible."));

    assertGrade(both, "low", "medium");
  }

  /** Unpinned is said of things that are not external references, and those are not the class. */
  @Test
  void anUnpinnedToolVersionIsNotAnExternalReference() {
    ReviewResponse tool =
        response(
            new ReviewResponse.Finding(
                "low",
                "low",
                ".github/workflows/ci.yml",
                4,
                "unpinned linter version",
                "The linter is installed unpinned, so a new release can change which warnings the"
                    + " job reports.",
                null,
                null));

    assertSame(tool, SeverityCalibrator.calibrate(tool));
  }

  /** The colon is what makes {@code :latest} a reference rather than the English word. */
  @Test
  void theLatestTagIsTheSameClassAsAMissingDigest() {
    ReviewResponse.Finding latest =
        calibrateOne(
            finding(
                "low",
                "low",
                "Dockerfile",
                "FROM ubuntu:latest resolves to a different image on every build."));

    assertGrade(latest, "low", "medium");
  }

  /** The colon is Docker tag syntax, so the occurrence names the reference by itself. */
  @Test
  void theLatestTagNamesTheReferenceWithoutTheWordImage() {
    ReviewResponse.Finding terse =
        calibrateOne(
            finding(
                "low",
                "low",
                "Dockerfile",
                "FROM alpine:latest — the tag drifts under the build."));

    assertGrade(terse, "low", "medium");
  }

  /** An article between the words is the same claim. */
  @Test
  void anArticleBeforeRootIsTheSameClaim() {
    ReviewResponse.Finding article =
        calibrateOne(
            finding(
                "low", "low", "Dockerfile", "The image runs as the root user in the final stage."));

    assertGrade(article, "medium", "medium");
  }

  /** The claim is read on the finding's words, so a contraction is the same claim. */
  @Test
  void aContractionStatesTheSameClaim() {
    ReviewResponse.Finding contracted =
        calibrateOne(
            finding(
                "low",
                "low",
                "Dockerfile",
                "The container doesn't drop privileges before the entrypoint starts."));

    assertGrade(contracted, "medium", "medium");
  }

  /**
   * The two container classes share a vocabulary, and the privilege-drop claim denies the other's
   * premise: a container that runs as root has no non-root user for a root-owned path to be
   * unwritable by, however much ownership the finding goes on to discuss.
   */
  @Test
  void anOmissionThatNamesTheIntendedAccountIsStillThePrivilegeDropClass() {
    ReviewResponse.Finding omission =
        calibrateOne(
            finding(
                "low",
                "low",
                "Dockerfile",
                "There is no USER appuser directive, so the app runs as root and the files it"
                    + " writes take root ownership."));

    assertGrade(omission, "medium", "medium");
  }

  /** A chown that is present, redundant or merely mentioned is not a path nobody can write. */
  @Test
  void aNitAboutARedundantChownIsNotTheOwnershipClass() {
    ReviewResponse redundant =
        response(
            finding(
                "low",
                "low",
                "Dockerfile",
                "USER appuser is already set, so the explicit RUN chown -R appuser:appuser is"
                    + " redundant and adds a duplicate layer; use COPY --chown instead."));

    assertSame(redundant, SeverityCalibrator.calibrate(redundant));
  }

  /**
   * "non-root user" normalizes to three words, and a phrase list matches inside them: the explicit
   * non-root claim settles which container class the finding is, so it is read before the words
   * that merely appear in both.
   */
  @Test
  void anOwnershipFindingThatSaysNonRootUserIsNotThePrivilegeDropClass() {
    ReviewResponse.Finding ownership =
        calibrateOne(
            finding(
                "low",
                "low",
                "Dockerfile",
                "The image runs as a non-root user, but /data stays root-owned, so the first write"
                    + " fails with permission denied and the container exits."));

    assertGrade(ownership, "high", "medium");
  }

  /** A committed password is a credential, whatever the finding calls it. */
  @Test
  void aCommittedPasswordKeepsTheReviewsGrade() {
    ReviewResponse baked =
        response(
            finding(
                "high",
                "high",
                "Dockerfile",
                "The Dockerfile bakes the deploy password into an ARG and the container runs as"
                    + " root, so anyone who pulls the image reads it."));

    assertSame(baked, SeverityCalibrator.calibrate(baked));
  }

  /** A digest is asked of things that are not external references, and those are not the class. */
  @Test
  void aMissingDigestForSomethingOtherThanAReferenceIsNotAnchored() {
    ReviewResponse archive =
        response(
            new ReviewResponse.Finding(
                "low",
                "low",
                ".github/workflows/ci.yml",
                9,
                "downloaded toolchain is not verified",
                "The step pins the toolchain by version but carries no digest for the archive it"
                    + " downloads, so a replaced artifact would go unnoticed.",
                null,
                null));

    assertSame(archive, SeverityCalibrator.calibrate(archive));
  }

  /** The claim has to be about the reference, not merely in the same finding as one. */
  @Test
  void anUnpinnedPackageInstallBesideAPinnedImageIsNotAnchored() {
    ReviewResponse aptInstall =
        response(
            finding(
                "low",
                "low",
                "Dockerfile",
                "The base image is correctly pinned by digest, but the apt install is unpinned, so"
                    + " runtime package versions drift between builds."));

    assertSame(aptInstall, SeverityCalibrator.calibrate(aptInstall));
  }

  /**
   * The escalation defeater is read on the words a finding uses, not only on the manifest field
   * names: missing it would pin a high finding DOWN, which is the direction the design forbids.
   */
  @Test
  void anEscalationWrittenInProseAlsoKeepsTheReviewsGrade() {
    ReviewResponse hostNamespace =
        response(
            finding(
                "high",
                "high",
                "deploy/k8s/api.yaml",
                "The base image is unpinned, and the pod also shares the host PID namespace with"
                    + " the node."));

    assertSame(hostNamespace, SeverityCalibrator.calibrate(hostNamespace));
  }

  /** A variant suffix names the same artifact; only a file type says the file is about it. */
  @Test
  void aVariantDockerfileIsStillTheArtifact() {
    assertGrade(
        calibrateOne(finding("low", "low", "docker/Dockerfile.prod", UNPINNED_RUST)),
        "low",
        "medium");
  }

  /** Podman spells the same artifact differently, and it is the same artifact. */
  @Test
  void aContainerfileIsTheSameArtifactAsADockerfile() {
    assertGrade(
        calibrateOne(finding("low", "low", "build/Containerfile", UNPINNED_RUST)), "low", "medium");
  }

  @Test
  void aFindingThatAlsoAssertsAnEscalationBeyondTheClassKeepsItsOwnGrade() {
    ReviewResponse escalating =
        response(
            finding(
                "critical",
                "high",
                "deploy/k8s/api.yaml",
                "The container runs as root AND mounts hostPath /var/run/docker.sock, so a"
                    + " compromise owns the node."));

    assertSame(escalating, SeverityCalibrator.calibrate(escalating));
  }

  /** "Not pinned" on its own is not the class: the anchored one is about external references. */
  @Test
  void anUnpinnedSomethingElseInAWorkflowIsNotAnchored() {
    ReviewResponse cacheKey =
        response(
            new ReviewResponse.Finding(
                "low",
                "low",
                ".github/workflows/ci.yml",
                7,
                "stale dependency cache",
                "The cache key is not pinned to the lockfile hash, so a stale dependency cache"
                    + " can be restored on a change that should have missed.",
                null,
                null));

    assertSame(cacheKey, SeverityCalibrator.calibrate(cacheKey));
  }

  /**
   * A manifest field name is written the same way by a finding that says the field is missing and
   * by one that says it is set, so naming it is not the class.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "The securityContext sets runAsUser: 1000, but the service binds port 80, so startup fails.",
        "runAsNonRoot: true is set, but readOnlyRootFilesystem is missing, so the filesystem can be"
            + " tampered with."
      })
  void aManifestFindingThatSetsThePrivilegeFieldKeepsItsOwnGrade(String description) {
    ReviewResponse set = response(finding("high", "high", "deploy/k8s/api.yaml", description));

    assertSame(set, SeverityCalibrator.calibrate(set));
  }

  /** Being named after the artifact is not being the artifact. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "src/main/java/dev/app/DockerfileSupport.java",
        "docs/Dockerfile-guide.md",
        "docs/Dockerfile.md",
        "ui/Containerfile.kt"
      })
  void aFileMerelyNamedAfterTheDockerfileIsNotOne(String path) {
    ReviewResponse named = response(finding("low", "low", path, UNPINNED_NODE));

    assertSame(named, SeverityCalibrator.calibrate(named));
  }

  /**
   * Pinning is said of many things in a workflow, so the claim has to name the immutable thing that
   * is missing before a generic noun beside it can anchor anything.
   */
  @Test
  void anOutputTagNotPinnedToTheRunIdIsNotAnchored() {
    ReviewResponse collision =
        response(
            finding(
                "low",
                "low",
                ".github/workflows/deploy.yml",
                "The release job's image output tag is not pinned to the run id, so two concurrent"
                    + " runs can overwrite each other's push."));

    assertSame(collision, SeverityCalibrator.calibrate(collision));
  }

  @Test
  void aFindingOutsideADeclarativeArtifactIsNotAnchored() {
    ReviewResponse code =
        response(
            finding(
                "low",
                "low",
                "src/main/java/dev/example/Builder.java",
                "The image name built here is unpinned, so the tag can move."));

    assertSame(code, SeverityCalibrator.calibrate(code));
  }

  /**
   * The two container classes share a vocabulary, and the omission is the weaker of the two: a
   * finding that says there is no USER directive at all is the privilege-drop class even when it
   * goes on to talk about ownership, because there is no non-root user for the path to be
   * unwritable by.
   */
  @Test
  void talkingAboutOwnershipDoesNotPromoteAPrivilegeDropOmission() {
    ReviewResponse.Finding omission =
        calibrateOne(
            finding(
                "low",
                "low",
                "Dockerfile",
                "There is no USER directive, so every file the entrypoint creates takes root"
                    + " ownership and the service runs as root."));

    assertGrade(omission, "medium", "medium");
  }

  /**
   * The {@code USER} directive is matched case-sensitively, so ordinary English about users in a
   * manifest finding is not read as a privilege drop.
   */
  @Test
  void ordinaryProseAboutUsersIsNotReadAsAPrivilegeDrop() {
    ReviewResponse prose =
        response(
            finding(
                "low",
                "low",
                "deploy/values.yaml",
                "The user quota default is 5, but the chart's README documents 10, so an operator"
                    + " who trusts the README gets the wrong limit."));

    assertSame(prose, SeverityCalibrator.calibrate(prose));
  }

  @ParameterizedTest
  @ValueSource(strings = {"low", "medium", "high", "critical"})
  void aFindingWithNoTitleOrDescriptionIsNotAnchored(String risk) {
    ReviewResponse bare =
        response(new ReviewResponse.Finding(risk, "low", "Dockerfile", 3, null, null, null, null));

    assertSame(bare, SeverityCalibrator.calibrate(bare));
  }

  @Test
  void aFindingWithNoFileIsNotAnchored() {
    ReviewResponse unanchored = response(finding("low", "low", null, UNPINNED_NODE));

    assertSame(unanchored, SeverityCalibrator.calibrate(unanchored));
  }

  @Test
  void aResponseAlreadyAtTheAnchorIsReturnedUntouched() {
    ReviewResponse graded = response(finding("low", "medium", "Dockerfile", UNPINNED_NODE));

    assertSame(graded, SeverityCalibrator.calibrate(graded));
  }

  @Test
  void anEmptyFindingListIsReturnedUntouched() {
    ReviewResponse empty = response();

    assertSame(empty, SeverityCalibrator.calibrate(empty));
  }

  /** Calibration regrades; it never adds or rewords, and keeps every finding but one class. */
  @Test
  void everyFindingSurvivesWithItsTextAndAnchorIntact() {
    ReviewResponse.Finding raw =
        new ReviewResponse.Finding(
            "low",
            "low",
            "Dockerfile",
            12,
            "unpinned base image",
            UNPINNED_NODE,
            "FROM node:20-alpine",
            "FROM node:20-alpine@sha256:abc");

    ReviewResponse calibrated = SeverityCalibrator.calibrate(response(raw));

    assertEquals(1, calibrated.findings().size());
    ReviewResponse.Finding only = calibrated.findings().get(0);
    assertEquals(raw.file(), only.file());
    assertEquals(raw.line(), only.line());
    assertEquals(raw.title(), only.title());
    assertEquals(raw.description(), only.description());
    assertEquals(raw.suggestionOld(), only.suggestionOld());
    assertEquals(raw.suggestionNew(), only.suggestionNew());
  }

  @Test
  void theSummaryCountsFollowTheRegradedFindings() {
    ReviewResponse calibrated =
        SeverityCalibrator.calibrate(
            response(
                finding("low", "low", "python/Dockerfile", ROOT_VOLUME_PYTHON),
                finding("low", "low", "python/Dockerfile", UNPINNED_NODE)));

    assertEquals(2, calibrated.summary().totalFindings());
    assertEquals(1, calibrated.summary().high(), "high");
    assertEquals(0, calibrated.summary().medium(), "medium");
    assertEquals(1, calibrated.summary().low(), "low");
  }

  /**
   * The round-10 corpus (#926): the unpinned-tag finding anchored on a {@code COPY --from=} line
   * whose source is an external image with an explicit version is the answer keys' "legitimate
   * external producer", not a moving reference, so it is not reported at all.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "COPY --from=busybox:1.36 /bin/busybox /usr/local/bin/busybox",
        "COPY --from=busybox:1.36.1 /bin/busybox /bin/busybox",
        "COPY --chown=app:app --from=ghcr.io/grpc-ecosystem/grpc-health-probe:v0.4.25"
            + " /ko-app/grpc-health-probe /bin/grpc_health_probe",
        "  copy --from=ghcr.io/devops-thiago/nginx-conf:1.2.0 /etc/nginx /etc/nginx",
        "COPY --from=registry.example.com:5000/tools/permit-healthcheck:1.4.0 /hc /hc",
        "COPY --from=alpine@sha256:0123abcd /bin/sh /bin/sh"
      })
  void anUnpinnedFindingOnAVersionedCopyFromSourceIsDropped(String anchoredLine) {
    var raw =
        new ReviewResponse.Finding(
            "medium",
            "medium",
            "java/Dockerfile",
            11,
            "External image referenced by a mutable tag, no digest",
            "The COPY --from source is not pinned to a digest, so the build inputs can change.",
            null,
            null);

    ReviewResponse calibrated =
        SeverityCalibrator.calibrate(
            response(raw), (file, line) -> line == 11 ? anchoredLine : null);

    assertEquals(List.of(), calibrated.findings());
    assertEquals(0, calibrated.summary().totalFindings());
  }

  /** Without the diff's line, the finding's own quote of it is read. */
  @Test
  void theSuggestionOldNamesTheAnchoredLineWhenTheDiffDoesNot() {
    var raw =
        new ReviewResponse.Finding(
            "medium",
            "low",
            "csharp/Dockerfile",
            10,
            "permit-healthcheck image is not pinned by digest",
            "ghcr.io/devops-thiago/permit-healthcheck:1.4.0 carries no digest, so the image can"
                + " change.",
            "COPY --from=ghcr.io/devops-thiago/permit-healthcheck:1.4.0 /hc /usr/local/bin/hc\n"
                + "USER app",
            null);

    assertEquals(List.of(), SeverityCalibrator.calibrate(response(raw)).findings());
  }

  /**
   * Only a versioned external source drops the finding: a stage name, an untagged or latest image,
   * an ARG-built tag, and a base image named by a version all stay, at the reference class's grade.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "COPY --from=build /app/dist /app/dist",
        "COPY --from=busybox /bin/busybox /bin/busybox",
        "COPY --from=busybox:latest /bin/busybox /bin/busybox",
        "COPY --from=tools:${TOOLS_VERSION} /bin/tool /bin/tool",
        "COPY --chown=app:app ./dist /app",
        "FROM golang:1.22 AS build",
        "COPY"
      })
  void anUnpinnedFindingOnAnyOtherLineIsKeptAtLow(String anchoredLine) {
    var raw =
        new ReviewResponse.Finding(
            "medium",
            "low",
            "go/Dockerfile",
            1,
            "Base images pinned by mutable tags",
            UNPINNED_NODE,
            null,
            null);

    var calibrated =
        SeverityCalibrator.calibrate(response(raw), (file, line) -> anchoredLine).findings();

    assertEquals(1, calibrated.size());
    assertGrade(calibrated.get(0), "low", "medium");
  }

  /** The drop is for the reference class only: another class on a COPY --from line keeps it. */
  @Test
  void anotherClassOnAVersionedCopyFromLineIsKept() {
    var raw = finding("low", "low", "Dockerfile", NO_USER_CSHARP);

    var calibrated =
        SeverityCalibrator.calibrate(
                response(raw), (file, line) -> "COPY --from=busybox:1.36 /bin/busybox /bin/busybox")
            .findings();

    assertEquals(1, calibrated.size());
    assertGrade(calibrated.get(0), "medium", "medium");
  }

  @ParameterizedTest
  @CsvSource({
    "busybox:1.36,true",
    "ghcr.io/acme/tool:v0.4.25,true",
    "\"busybox:1.36\",true",
    "alpine@sha256:abc,true",
    "registry:5000/tools,false",
    "tools:edge,false",
    "build,false",
    "busybox:LATEST,false"
  })
  void aVersionIsAnExplicitTagWithADigitOrADigest(String source, boolean versioned) {
    assertEquals(versioned, SeverityCalibrator.isVersionedExternalImage(source));
  }
}
