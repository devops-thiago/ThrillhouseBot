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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic grade for the three infrastructure defect classes that recur in every containerized
 * repository, whatever language it is written in (#773).
 *
 * <p>A round-8 corpus of 65 findings over twelve pull requests was 97% precise and still graded
 * inconsistently: the same unpinned base image drew "medium" on four pull requests and "low" on
 * three, and a root-owned volume under a non-root USER drew "low" on the pull request where it
 * fails first and "medium" on another of the same shape. Nothing in those pull requests
 * distinguished the findings — the level moved with the review, not with the defect. A severity
 * that moves that way teaches a maintainer to read every finding at the same weight, which is the
 * one thing the scale exists to prevent.
 *
 * <p>The calibration rule is that severity follows the consequence and its reachability, and for
 * these three classes both are fixed by the class: an unpinned reference changes the build's inputs
 * without a commit and breaks nothing at run time; a path the running user cannot write fails the
 * first time it is written, on every deployment; a container that never drops privilege breaks
 * nothing by itself but starts a compromise as root. So the grade is a property of the class and is
 * written here rather than judged per review. {@link dev.thiagogonzaga.thrillhousebot.review.ai
 * .PrReviewPrompts} states the same three anchors to the model — this is what makes them hold when
 * it does not follow them, the way {@code FindingVerificationService.floorInjectionSinkRisk} holds
 * the injection-sink floor (#570).
 *
 * <p>Confidence is anchored too, and at "medium" for all three, because the question confidence
 * answers has the same answer in every pull request: the shape is settled by the file in front of
 * the reviewer, so it is never "low", and whether the deployment exercises the consequence is not
 * in that file, so it is never "high". That is what fixes the surface split the same corpus
 * measured — {@link Finding#postsInline} routes a "low"-confidence medium finding to the collapsed
 * "Things to double-check" block, so the same omission reached the diff in one language and nowhere
 * in the other. At "medium" it is always on the diff, and because blocking under the default {@link
 * BlockingStrictness#BALANCED} needs "high" confidence, an anchored finding informs the verdict
 * without deciding it.
 *
 * <p>The anchor pins rather than floors: a level above it is as much a miscalibration as one below,
 * and a floor would close only the half of the spread that happened to point down. Nothing is
 * dropped, added, re-anchored or reworded here, so the precision of the finding set is
 * arithmetically unchanged — only the two graded fields move.
 *
 * <p>Recognition is deliberately narrow in the safe direction. It needs the finding to be anchored
 * in a declarative deployment artifact AND to assert the class in its own words, and any finding
 * that also asserts an escalation beyond the class — a privileged container, host namespaces or
 * mounts, an added capability, a credential, a named CVE — is left exactly as the review graded it,
 * because the level then rests on something the class does not fix. Under-firing costs a finding
 * the calibration it should have had, which is where this class already stood; over-firing would
 * restate a different defect at this one's level.
 */
public final class SeverityCalibrator {

  /**
   * One recurring infrastructure defect class and the risk its consequence fixes. Confidence is
   * {@link #ANCHORED_CONFIDENCE} for all three, so it is not carried per constant.
   */
  private enum InfrastructureClass {
    /** A base image, action or chart referenced by a name that can move under the build. */
    MUTABLE_EXTERNAL_REFERENCE(RiskLevel.MEDIUM),

    /** A path the image's non-root user cannot write, which fails the first write on every run. */
    UNWRITABLE_RUNTIME_PATH(RiskLevel.HIGH),

    /** A container that never drops privilege, so a compromise of it starts as root. */
    MISSING_PRIVILEGE_DROP(RiskLevel.MEDIUM);

    private final RiskLevel risk;

    InfrastructureClass(RiskLevel risk) {
      this.risk = risk;
    }
  }

  /**
   * The confidence every anchored class publishes at; see the class javadoc for why it is fixed.
   */
  private static final Confidence ANCHORED_CONFIDENCE = Confidence.MEDIUM;

  /**
   * The extensions that carry a manifest, a compose file or a workflow. Read against the file's own
   * name rather than the whole path, together with the container-image file names below: a
   * directory component is not the artifact, and one expression over the path both allows that and
   * backtracks over every segment of a deep one.
   */
  private static final List<String> INFRASTRUCTURE_EXTENSIONS =
      List.of(".yml", ".yaml", ".tf", ".tfvars");

  /**
   * The container-image file, matched as a whole dot-separated segment of the file's name so every
   * spelling of the artifact is covered ({@code Dockerfile}, {@code Dockerfile.prod}, {@code
   * prod.dockerfile}, {@code Containerfile}) and a file merely NAMED after it is not ({@code
   * DockerfileSupport.java}, {@code Dockerfile-guide.md}), which is source and documentation rather
   * than a declarative deployment artifact.
   */
  private static final List<String> CONTAINER_FILE_NAMES = List.of("dockerfile", "containerfile");

  /**
   * The finding says the reference is not pinned to something immutable. Spelled as one flat
   * alternation, like every trigger below: a nested group of synonyms reads no better and costs the
   * matcher a backtracking level per synonym.
   *
   * <p>Every alternative names the immutable thing that is missing — a digest, a tag that does not
   * move — rather than the bare word "pinned". Pinning is said of many things in a workflow ("the
   * cache key is not pinned to the lockfile hash", "the output tag is not pinned to the run id")
   * and a generic claim plus a generic noun is one word deep: it would anchor a naming nit at this
   * class's level, which is the over-firing the class javadoc rules out.
   */
  private static final Pattern UNPINNED_REFERENCE =
      Pattern.compile(
          "\\bunpinned\\b|\\bun-pinned\\b|\\bnot\\s+pinned\\s+to\\s+a\\s+digest\\b"
              + "|\\bnot\\s+pinned\\s+by\\s+digest\\b|\\bnot\\s+pinned\\s+to\\s+a\\s+version\\b"
              + "|\\bwithout\\s+a\\s+digest\\b|\\bwithout\\s+a\\s+sha256\\s+digest\\b"
              + "|\\bno\\s+digest\\b|\\bno\\s+sha256\\b|\\blacks\\s+a\\s+digest\\b"
              + "|\\bcarries\\s+no\\s+digest\\b|\\bpin\\s+it\\s+by\\s+digest\\b"
              + "|\\bfloating\\s+tag\\b|\\bmutable\\s+tag\\b|\\bmoving\\s+tag\\b"
              + "|\\brolling\\s+tag\\b|:latest\\b|\\blatest\\s+tag\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * What the unpinned reference refers to, so a pinning claim alone never anchors a finding. The
   * bare words "tag" and "digest" are deliberately not on this list: they are what the claim above
   * is already made of, so accepting them here would make the second half of the test a restatement
   * of the first.
   */
  private static final Pattern EXTERNAL_REFERENCE_SUBJECT =
      Pattern.compile(
          "\\bbase\\s+image\\b|\\bimage\\b|\\bchart\\b|\\baction\\b", Pattern.CASE_INSENSITIVE);

  /**
   * The finding says the container runs as someone other than root. A manifest FIELD NAME is not on
   * this list, nor on the privilege-drop one below: {@code runAsNonRoot} and {@code runAsUser} are
   * written the same way by a finding that says the field is missing and by one that says it is
   * set, so the name carries no polarity and reading it as the claim would anchor the opposite of
   * the class. A finding that means either class says so in prose — "runs as root", "non-root" —
   * and one that says only "runAsUser: 1000 is set, but the port bind fails" keeps its own grade.
   */
  private static final Pattern NON_ROOT_USER =
      Pattern.compile("\\bnon-?\\s?root\\b|\\bunprivileged\\s+user\\b", Pattern.CASE_INSENSITIVE);

  /**
   * A {@code USER} directive naming an account, read case-sensitively: the Dockerfile instruction
   * is written in upper case, and matching it either way would read the ordinary English "user" in
   * any sentence as a privilege drop. The lookahead keeps the prose that talks ABOUT the
   * instruction ("no USER directive") from reading as one that is present, which would otherwise
   * sort a privilege-drop omission into the ownership class beside it.
   */
  private static final Pattern USER_DIRECTIVE =
      Pattern.compile(
          "\\bUSER\\s+(?!(?:directive|instruction|line|statement|declaration)\\b)[A-Za-z0-9_$.:-]+");

  /** The finding says that user cannot write the path, in ownership or in failure terms. */
  private static final Pattern UNWRITABLE_PATH =
      Pattern.compile(
          "\\broot[-\\s]owned\\b|\\bowned\\s+by\\s+root\\b|\\broot:root\\b|\\bchown\\b"
              + "|\\bownership\\b|\\bpermission\\s+denied\\b|\\bEACCES\\b|\\bnot\\s+writable\\b"
              + "|\\bcannot\\s+write\\b|\\bcan't\\s+write\\b|\\bunable\\s+to\\s+write\\b"
              + "|\\bfails\\s+to\\s+write\\b|\\bfail\\s+to\\s+write\\b|\\bwrite\\s+fails?\\b"
              + "|\\bwrite\\s+will\\s+fail\\b",
          Pattern.CASE_INSENSITIVE);

  /** The finding says privilege is never dropped. */
  private static final Pattern NEVER_DROPS_PRIVILEGE =
      Pattern.compile(
          "\\bruns?\\s+as\\s+root\\b|\\brunning\\s+as\\s+root\\b|\\broot\\s+user\\b"
              + "|\\bno\\s+USER\\s+directive\\b|\\bno\\s+USER\\s+instruction\\b"
              + "|\\bmissing\\s+USER\\s+directive\\b|\\bmissing\\s+USER\\s+instruction\\b"
              + "|\\bwithout\\s+a\\s+USER\\s+directive\\b|\\bnever\\s+adds\\s+a\\s+USER\\b"
              + "|\\bnever\\s+drops\\s+privileges?\\b|\\bdoes\\s+not\\s+drop\\s+privileges?\\b"
              + "|\\bdoesn't\\s+drop\\s+privileges?\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * What the finding must NOT also assert. Each of these puts the level somewhere the class does
   * not decide — a container granted the host, a capability, a credential or a known vulnerability
   * is severe for a reason an anchor cannot weigh — so the review's own grade stands.
   */
  private static final Pattern ESCALATION_BEYOND_CLASS =
      Pattern.compile(
          "\\bprivileged\\b|\\bhostPath\\b|\\bhostNetwork\\b|\\bhostPID\\b|\\bhostIPC\\b"
              + "|\\bSYS_ADMIN\\b|\\bcapabilities\\b|\\bdocker\\.sock\\b|\\bdocker\\s+socket\\b"
              + "|\\bCVE-\\d|\\bGHSA-\\w|\\bsecret\\b|\\bcredential\\b|\\bprivate\\s+key\\b",
          Pattern.CASE_INSENSITIVE);

  /** The separator between a file name's stem and its extensions. */
  private static final Pattern SEGMENT = Pattern.compile("\\.");

  private SeverityCalibrator() {}

  /**
   * Rewrites the risk and confidence of every finding that states one of the three anchored classes
   * and leaves every other finding, and the rest of the response, untouched. The same response
   * instance comes back when nothing was regraded.
   */
  public static ReviewResponse calibrate(ReviewResponse response) {
    if (response.findings().isEmpty()) {
      return response;
    }
    var adjusted = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      InfrastructureClass anchored = classify(finding);
      if (anchored == null || alreadyAnchored(finding, anchored)) {
        adjusted.add(finding);
        continue;
      }
      Log.infof(
          "Calibrating %s finding '%s' (%s:%d) from %s/%s to %s/%s risk/confidence",
          anchored,
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          finding.line(),
          LogSafe.oneLine(finding.risk()),
          LogSafe.oneLine(finding.confidence()),
          label(anchored.risk),
          label(ANCHORED_CONFIDENCE));
      adjusted.add(
          new ReviewResponse.Finding(
              label(anchored.risk),
              label(ANCHORED_CONFIDENCE),
              finding.file(),
              finding.line(),
              finding.title(),
              finding.description(),
              finding.suggestionOld(),
              finding.suggestionNew()));
      changed = true;
    }
    if (!changed) {
      return response;
    }
    return new ReviewResponse(
        adjusted,
        response.previousFindingsStatus(),
        FindingVerificationService.recount(response.summary(), adjusted));
  }

  /** The class the finding states, or {@code null} when it states none of them. */
  private static InfrastructureClass classify(ReviewResponse.Finding finding) {
    if (finding.file() == null || !isInfrastructureFile(finding.file())) {
      return null;
    }
    String text =
        (finding.title() == null ? "" : finding.title())
            + "\n"
            + (finding.description() == null ? "" : finding.description());
    if (ESCALATION_BEYOND_CLASS.matcher(text).find()) {
      return null;
    }
    // Most specific first: a finding that names both the non-root user and the write it cannot
    // make is the ownership defect, not the privilege-drop omission that shares its vocabulary.
    if (namesNonRootUser(text) && UNWRITABLE_PATH.matcher(text).find()) {
      return InfrastructureClass.UNWRITABLE_RUNTIME_PATH;
    }
    if (UNPINNED_REFERENCE.matcher(text).find()
        && EXTERNAL_REFERENCE_SUBJECT.matcher(text).find()) {
      return InfrastructureClass.MUTABLE_EXTERNAL_REFERENCE;
    }
    return NEVER_DROPS_PRIVILEGE.matcher(text).find()
        ? InfrastructureClass.MISSING_PRIVILEGE_DROP
        : null;
  }

  /** Whether the finding is anchored in one of the declarative artifacts these classes live in. */
  private static boolean isInfrastructureFile(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return Arrays.stream(SEGMENT.split(name)).anyMatch(CONTAINER_FILE_NAMES::contains)
        || INFRASTRUCTURE_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private static boolean namesNonRootUser(String text) {
    return NON_ROOT_USER.matcher(text).find() || USER_DIRECTIVE.matcher(text).find();
  }

  private static boolean alreadyAnchored(
      ReviewResponse.Finding finding, InfrastructureClass anchored) {
    return RiskLevel.fromString(finding.risk()) == anchored.risk
        && Confidence.fromString(finding.confidence()) == ANCHORED_CONFIDENCE;
  }

  private static String label(Enum<?> level) {
    return level.name().toLowerCase(Locale.ROOT);
  }
}
