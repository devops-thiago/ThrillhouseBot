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
   * Every trigger below is a list of PHRASES, matched against the finding's own words after {@link
   * #normalized} reduces them to lower-case words separated by single spaces. A phrase carries its
   * own word boundaries, so "privileged" does not match inside "unprivileged" and "image" does not
   * match inside "images", exactly as the expressions these lists replace did — without an
   * alternation whose cost grows with every synonym and whitespace run in it.
   *
   * <p>The finding says the reference is not pinned to something immutable. Every phrase names
   * either the immutable thing that is missing — a digest, a tag that does not move — or the
   * reference it is missing for. A bare "unpinned" is not one of them, and neither is a bare "not
   * pinned": pinning is said of many things in and around a build ("the cache key is not pinned to
   * the lockfile hash", "the apt install is unpinned", "the output tag is not pinned to the run
   * id"), so a bare negation would anchor any of them the moment the word "image" appeared anywhere
   * else in the finding, which is the over-firing the class javadoc rules out.
   */
  private static final List<String> UNPINNED_REFERENCE =
      phrases(
          "unpinned base image",
          "unpinned image",
          "unpinned chart",
          "unpinned action",
          "base image is unpinned",
          "image is unpinned",
          "chart is unpinned",
          "action is unpinned",
          "image tag is unpinned",
          "not pinned to a digest",
          "not pinned by digest",
          "not pinned to a version",
          "without a digest",
          "without a sha256 digest",
          "no digest",
          "no sha256",
          "lacks a digest",
          "carries no digest",
          "pin it by digest",
          "floating tag",
          "mutable tag",
          "moving tag",
          "rolling tag",
          "latest tag");

  /**
   * The one claim that has to be read on the raw text: normalization drops the colon that makes
   * {@code :latest} a reference rather than the ordinary English word.
   */
  private static final String LATEST_TAG = ":latest";

  /**
   * What the unpinned reference refers to, so a pinning claim alone never anchors a finding. The
   * bare words "tag" and "digest" are deliberately not on this list: they are what the claim above
   * is already made of, so accepting them here would make the second half of the test a restatement
   * of the first.
   */
  private static final List<String> EXTERNAL_REFERENCE_SUBJECT =
      phrases("base image", "image", "images", "chart", "action");

  /**
   * The finding says the container runs as someone other than root. A manifest FIELD NAME is not on
   * this list, nor on the privilege-drop one below: {@code runAsNonRoot} and {@code runAsUser} are
   * written the same way by a finding that says the field is missing and by one that says it is
   * set, so the name carries no polarity and reading it as the claim would anchor the opposite of
   * the class. A finding that means either class says so in prose — "runs as root", "non-root" —
   * and one that says only "runAsUser: 1000 is set, but the port bind fails" keeps its own grade.
   */
  private static final List<String> NON_ROOT_USER =
      phrases("non root", "nonroot", "unprivileged user");

  /**
   * A {@code USER} directive naming an account, read case-sensitively on the raw text: the
   * Dockerfile instruction is written in upper case, and matching it either way would read the
   * ordinary English "user" in any sentence as a privilege drop. The lookahead keeps the prose that
   * talks ABOUT the instruction ("no USER directive") from reading as one that is present, which
   * would otherwise sort a privilege-drop omission into the ownership class beside it.
   */
  private static final Pattern USER_DIRECTIVE =
      Pattern.compile(
          "\\bUSER\\s+(?!(?:directive|instruction|line|statement|declaration)\\b)[A-Za-z0-9_$.:-]+");

  /**
   * The finding says that user cannot write the path, in ownership or in failure terms. Every
   * phrase carries the direction: a bare "chown" or "ownership" is said as often of a chown that is
   * present, redundant or merely mentioned as of one that is missing, so matching the word alone
   * read a layer-size nit about an existing {@code RUN chown} as this class.
   */
  private static final List<String> UNWRITABLE_PATH =
      phrases(
          "root owned",
          "owned by root",
          "root root",
          "root ownership",
          "without a chown",
          "no chown",
          "nothing chowns",
          "never chowned",
          "permission denied",
          "eacces",
          "not writable",
          "cannot write",
          "cant write",
          "unable to write",
          "fails to write",
          "fail to write",
          "write fails",
          "write fail",
          "write will fail");

  /** The finding says privilege is never dropped. */
  private static final List<String> NEVER_DROPS_PRIVILEGE =
      phrases(
          "runs as root",
          "run as root",
          "running as root",
          "root user",
          "no user directive",
          "no user instruction",
          "missing user directive",
          "missing user instruction",
          "without a user directive",
          "never adds a user",
          "never drops privilege",
          "never drops privileges",
          "does not drop privilege",
          "does not drop privileges",
          "doesnt drop privileges");

  /**
   * What the finding must NOT also assert. Each of these puts the level somewhere the class does
   * not decide — a container granted the host, a capability, a credential or a known vulnerability
   * is severe for a reason an anchor cannot weigh — so the review's own grade stands.
   */
  private static final List<String> ESCALATION_BEYOND_CLASS =
      phrases(
          "privileged",
          "hostpath",
          "hostnetwork",
          "hostpid",
          "hostipc",
          "sys admin",
          "capabilities",
          "capability",
          "host mount",
          "host mounts",
          "host path",
          "host namespace",
          "host namespaces",
          "host pid",
          "host ipc",
          "host network",
          "docker sock",
          "docker socket",
          "cve",
          "ghsa",
          "secret",
          "secrets",
          "credential",
          "credentials",
          "private key");

  /** The separator between a file name's stem and its extensions. */
  private static final Pattern SEGMENT = Pattern.compile("\\.");

  /** Everything {@link #normalized} turns into the single space that separates two words. */
  private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z0-9]+");

  /** The apostrophes a contraction is written with, dropped so "doesn't" normalizes to one word. */
  private static final Pattern APOSTROPHE = Pattern.compile("['\u2019]");

  /** Each phrase padded with the spaces that make it match whole words and nothing else. */
  private static List<String> phrases(String... words) {
    return Arrays.stream(words).map(word -> " " + word + " ").toList();
  }

  /**
   * The finding's words, lower-cased, stripped of punctuation and padded, so a phrase from the
   * lists above matches whole words wherever the finding put them.
   */
  private static String normalized(String text) {
    String contracted = APOSTROPHE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    return " " + NOT_A_WORD.matcher(contracted).replaceAll(" ").strip() + " ";
  }

  private static boolean states(String normalized, List<String> claim) {
    return claim.stream().anyMatch(normalized::contains);
  }

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
    String raw =
        (finding.title() == null ? "" : finding.title())
            + "\n"
            + (finding.description() == null ? "" : finding.description());
    String text = normalized(raw);
    if (states(text, ESCALATION_BEYOND_CLASS)) {
      return null;
    }
    // The privilege-drop claim is read first because it DENIES the other container class's
    // premise: a container that never drops privilege has no non-root user for a root-owned path
    // to be unwritable by. The two share a vocabulary — "no USER appuser directive, so the app
    // runs as root and the files it writes take root ownership" names an account and an ownership
    // in one sentence — and reading that as the ownership defect would publish it at that class's
    // level under a class label its own words contradict.
    if (states(text, NEVER_DROPS_PRIVILEGE)) {
      return InfrastructureClass.MISSING_PRIVILEGE_DROP;
    }
    if (namesNonRootUser(text, raw) && states(text, UNWRITABLE_PATH)) {
      return InfrastructureClass.UNWRITABLE_RUNTIME_PATH;
    }
    return namesUnpinnedReference(text, raw) && states(text, EXTERNAL_REFERENCE_SUBJECT)
        ? InfrastructureClass.MUTABLE_EXTERNAL_REFERENCE
        : null;
  }

  /** Whether the finding is anchored in one of the declarative artifacts these classes live in. */
  private static boolean isInfrastructureFile(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return Arrays.stream(SEGMENT.split(name)).anyMatch(CONTAINER_FILE_NAMES::contains)
        || INFRASTRUCTURE_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private static boolean namesNonRootUser(String text, String raw) {
    return states(text, NON_ROOT_USER) || USER_DIRECTIVE.matcher(raw).find();
  }

  private static boolean namesUnpinnedReference(String text, String raw) {
    return states(text, UNPINNED_REFERENCE) || raw.toLowerCase(Locale.ROOT).contains(LATEST_TAG);
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
