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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The deterministic security scan (#60): reads the lines a pull request adds for leaked credentials
 * ({@link SecretScanner}) and risky infrastructure-as-code settings ({@link IacScanner}), and
 * merges what it finds into the review's finding set. No model call is made.
 *
 * <p><b>Why the findings skip the verifier.</b> They are merged after the second-pass verification
 * and after {@link SeverityCalibrator}, so neither sees them. The verifier judges a finding against
 * the diff, which is exactly what the pattern already read; a model that can see no more than the
 * pattern did demoting or dropping a leaked credential would reintroduce the unreliability a
 * deterministic pass exists to remove. It also keeps them out of the verifier's fail-open marking
 * (#885): only the candidates handed to {@code FindingVerificationService.verify} can be marked
 * unverified or counted in {@link VerificationCoverage}, so a scan finding never posts as
 * "unverified" and never inflates the round's unverified count. Each carries its own grade ({@link
 * SecurityRule}) and says in its text that it came from a pattern match.
 *
 * <p><b>Never echo the secret.</b> A secret finding's title and description show only the value's
 * first characters and its length ({@link SecretScanner#redact}); it carries no {@code
 * suggestion_old}, because the content anchor is persisted with the session and shown on the
 * dashboard; and every other finding and status note in the response has the matched values
 * scrubbed out, so a model finding that quoted the line cannot post it either. Nothing here logs a
 * value. The summary call that follows is code-blind and only reads the findings, so its text is
 * built from the scrubbed set.
 *
 * <p><b>Across rounds.</b> A detection whose finding the effective previous round already raised
 * (same file, title and anchor) is not raised again, so a push that leaves the secret in place does
 * not post a second comment. That prior finding's status is set from the scan rather than from the
 * model: {@code unresolved} while the pattern is still on an added line (a maintainer's {@code
 * justified} is kept), {@code resolved} once a file the scan read no longer carries it — removed,
 * or exempted with the allow marker. Without an anchor, a secret finding from an older round that
 * the backstop still holds is judged present while its file is in the diff; that is the holding
 * direction, cleared by a reply or {@code @thrillhousebot resolved} like any finding with no
 * anchor.
 *
 * <p><b>Duplicates.</b> A model finding within {@link FindingDeduplicator#LINE_TOLERANCE} lines of
 * a detection in the same file is dropped when it is the same defect by {@link
 * FindingDeduplicator#sameDefect} or its title uses at least two of the rule's own words ({@link
 * SecurityRule#topic}), so the reviewer does not post the same secret twice; the scan's finding is
 * the one kept, because it is the redacted one.
 */
@ApplicationScoped
public class SecurityScan {

  /** Exempts a line (or the line below it) from the secret rules. */
  static final String ALLOW_SECRET_MARKER = "thrillhousebot:allow-secret";

  /** Exempts a line (or the line below it) from the IaC rules. */
  static final String ALLOW_IAC_MARKER = "thrillhousebot:allow-iac";

  /**
   * Rule words a model title needs, besides the deduplicator's own title match, to count as the
   * same defect. One is not enough: the topic sets are ordinary words ("network", "token", "open"),
   * so a single shared word would drop an unrelated finding that happens to sit nearby. A same
   * defect worded with only one of them survives as a duplicate, but its text is scrubbed like any
   * other, so it cannot repeat a secret.
   */
  static final int MIN_TOPIC_WORDS = 2;

  /** Longest credential key name a title repeats. */
  private static final int MAX_KEY_NAME = 40;

  /** Base64 runs a private key's body is made of, scrubbed out of other findings' text. */
  private static final Pattern PEM_MATERIAL = Pattern.compile("[A-Za-z0-9+/=]{40,}");

  private static final String PEM_MATERIAL_REDACTION = "[redacted private key material]";

  private static final String PROVENANCE =
      "_Raised by the deterministic security scan: a pattern match on the added lines, not model"
          + " output, so it is not put to the second-pass verifier._";

  private static final String STILL_DETECTED_NOTE =
      "Still detected by the deterministic security scan on an added line.";

  private static final String NO_LONGER_DETECTED_NOTE =
      "No longer detected by the deterministic security scan: the line was removed or changed, or"
          + " carries the allow marker.";

  private static final String STATUS_UNRESOLVED = "unresolved";
  private static final String STATUS_RESOLVED = "resolved";
  private static final String STATUS_JUSTIFIED = "justified";

  private final boolean secretsEnabled;
  private final boolean iacEnabled;
  private final double entropyThreshold;
  private final ReviewDiffFormatter.IgnoreGlobs skippedFiles;

  @Inject
  public SecurityScan(ThrillhouseConfig config) {
    this(
        config.review().securityScan().secretsEnabled(),
        config.review().securityScan().iacEnabled(),
        config.review().securityScan().entropyThreshold(),
        config.review().securityScan().skippedFiles());
  }

  SecurityScan(
      boolean secretsEnabled,
      boolean iacEnabled,
      double entropyThreshold,
      List<String> skippedFiles) {
    this.secretsEnabled = secretsEnabled;
    this.iacEnabled = iacEnabled;
    this.entropyThreshold = entropyThreshold;
    this.skippedFiles = ReviewDiffFormatter.IgnoreGlobs.compile(skippedFiles);
  }

  /** A scan with both halves off, for callers that construct the pipeline without configuration. */
  public static SecurityScan disabled() {
    return new SecurityScan(false, false, Double.MAX_VALUE, List.of());
  }

  boolean enabled() {
    return secretsEnabled || iacEnabled;
  }

  /** One scan finding and the rule that raised it. */
  record Detection(SecurityRule rule, ReviewResponse.Finding finding) {}

  /** A matched value and the only form of it any surface may show. */
  record Redaction(String literal, String replacement) {}

  /**
   * What one pass over the diff produced.
   *
   * @param detections the scan's findings, in file then line order
   * @param redactions the matched secret values to scrub out of every other text, longest first
   * @param scannedFiles the files the scan read, so a finding it no longer sees there can be closed
   */
  record Result(List<Detection> detections, List<Redaction> redactions, List<String> scannedFiles) {
    Result {
      detections = List.copyOf(detections);
      redactions = List.copyOf(redactions);
      scannedFiles = List.copyOf(scannedFiles);
    }
  }

  /**
   * Merges the scan's findings into a refined review response: drops model findings that report the
   * same defect, scrubs every matched secret out of what remains, sets the status of prior scan
   * findings from the scan, and appends the findings the effective previous round did not already
   * raise. Returns the response untouched when both halves are off.
   */
  public ReviewResponse merge(ReviewResponse response, ReviewContextLoader.ReviewContext ctx) {
    if (!enabled()) {
      return response;
    }
    return merge(
        response,
        scan(ctx.reviewableFiles()),
        ctx.previousFindingsList(),
        FollowUpAnalyzer.settledPreviousIds(ctx.priorAiResponses()));
  }

  ReviewResponse merge(
      ReviewResponse response,
      Result result,
      List<ReviewResponse.Finding> previous,
      Set<Integer> settledIds) {
    var scanPriors = openScanPriors(previous, settledIds);
    var scrubber = Scrubber.of(result.redactions());
    var tracked = new LinkedHashMap<Integer, Detection>();
    var raised = new ArrayList<ReviewResponse.Finding>();
    for (var detection : result.detections()) {
      int priorId = priorIdOf(detection.finding(), scanPriors, tracked.keySet());
      if (priorId > 0) {
        tracked.put(priorId, detection);
      } else {
        raised.add(detection.finding());
      }
    }
    var cleared = clearedPriorIds(scanPriors, tracked.keySet(), result.scannedFiles());

    var kept = new ArrayList<ReviewResponse.Finding>();
    int duplicates = 0;
    for (var finding : response.findings()) {
      if (duplicatesDetection(finding, result.detections())) {
        duplicates++;
      } else {
        kept.add(scrub(finding, scrubber));
      }
    }
    kept.addAll(raised);
    var statuses = statuses(response.previousFindingsStatus(), tracked.keySet(), cleared, scrubber);
    // A duplicate needs a detection, so these two cover every merge that changed anything.
    if (!result.detections().isEmpty() || !cleared.isEmpty()) {
      Log.infof(
          "Security scan: raised %d finding(s), kept %d from the previous round open, closed %d"
              + " no longer detected, dropped %d model duplicate(s)",
          raised.size(), tracked.size(), cleared.size(), duplicates);
    }
    return new ReviewResponse(
        kept, statuses, FindingVerificationService.recount(response.summary(), kept));
  }

  /** Reads every file the review covers, minus the skipped globs and files with no patch text. */
  Result scan(List<GitHubPullRequestClient.FileDiff> files) {
    var detections = new ArrayList<Detection>();
    var redactions = new ArrayList<Redaction>();
    var scanned = new ArrayList<String>();
    for (var file : files) {
      if (file.patch() == null || file.patch().isBlank() || skippedFiles.matches(file.filename())) {
        continue;
      }
      scanned.add(file.filename());
      var patch = PatchLines.parse(file.patch());
      if (secretsEnabled) {
        scanSecrets(file.filename(), patch, detections, redactions);
      }
      if (iacEnabled) {
        scanIac(file, patch, detections);
      }
    }
    redactions.sort(Comparator.comparingInt((Redaction r) -> r.literal().length()).reversed());
    return new Result(detections, redactions, scanned);
  }

  private void scanSecrets(
      String filename, PatchLines patch, List<Detection> detections, List<Redaction> redactions) {
    var lines = patch.lines();
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      if (!line.added() || allowed(lines, i, ALLOW_SECRET_MARKER)) {
        continue;
      }
      // The body must follow in the same hunk: the next patch line of another hunk is not what
      // follows the header in the file.
      var next =
          i + 1 < lines.size() && lines.get(i + 1).hunk() == line.hunk()
              ? lines.get(i + 1).text()
              : null;
      for (var hit : SecretScanner.scan(line.text(), next, entropyThreshold)) {
        var redacted = SecretScanner.redact(hit);
        detections.add(new Detection(hit.rule(), secretFinding(filename, line.number(), hit)));
        if (hit.rule() == SecurityRule.PRIVATE_KEY) {
          addPemMaterial(lines, i, redactions);
        } else {
          redactions.add(new Redaction(hit.literal(), "[redacted: " + redacted + "]"));
        }
      }
    }
  }

  /**
   * Records the base64 runs of a private key — on its header line and on the added body lines that
   * follow — so a model finding quoting the key body is scrubbed as well as one quoting the header.
   */
  private static void addPemMaterial(
      List<PatchLines.Line> lines, int headerIndex, List<Redaction> redactions) {
    for (int i = headerIndex; i < lines.size(); i++) {
      var line = lines.get(i);
      if (i > headerIndex && (!line.added() || !SecretScanner.isPemBodyLine(line.text()))) {
        return;
      }
      var material = PEM_MATERIAL.matcher(line.text());
      while (material.find()) {
        redactions.add(new Redaction(material.group(), PEM_MATERIAL_REDACTION));
      }
    }
  }

  private static void scanIac(
      GitHubPullRequestClient.FileDiff file, PatchLines patch, List<Detection> detections) {
    var lines = patch.lines();
    for (var hit : IacScanner.scan(file.filename(), patch, "added".equals(file.status()))) {
      if (!allowed(lines, hit.index(), ALLOW_IAC_MARKER)) {
        detections.add(new Detection(hit.rule(), iacFinding(file.filename(), hit)));
      }
    }
  }

  /**
   * Whether the allow marker is on the line or on the line directly above it — above, for the
   * formats that cannot put a comment after a value (a Dockerfile instruction, a YAML block
   * scalar).
   */
  private static boolean allowed(List<PatchLines.Line> lines, int index, String marker) {
    var line = lines.get(index);
    if (line.text().contains(marker)) {
      return true;
    }
    if (index == 0) {
      return false;
    }
    var above = lines.get(index - 1);
    return above.hunk() == line.hunk() && above.text().contains(marker);
  }

  private static ReviewResponse.Finding secretFinding(
      String filename, int line, SecretScanner.Hit hit) {
    var rule = hit.rule();
    var redacted = SecretScanner.redact(hit);
    var key = rule == SecurityRule.GENERIC_SECRET ? keyName(hit.keyName()) : null;
    var title = rule.secretTitleHead() + (key == null ? "" : " in " + key) + " (" + redacted + ")";
    var what =
        switch (rule) {
          case GENERIC_SECRET ->
              "This added line assigns a high-entropy literal to `"
                  + key
                  + "`, a name that marks a credential.";
          case PRIVATE_KEY ->
              "This added line starts a PEM private key, and the key body follows it.";
          default -> "This added line carries a value in the " + rule.label() + " format.";
        };
    var description =
        what
            + " The bot never repeats a matched secret: it is shown here only as its first"
            + " characters and its length ("
            + redacted
            + "). Treat it as leaked. Revoke and rotate it where it was issued, since removing it"
            + " from the branch does not remove it from the git history, and load it from a"
            + " secret store or an environment variable instead. If it is a deliberate test value,"
            + " add a `"
            + ALLOW_SECRET_MARKER
            + "` comment on the same line or the line above it.\n\n"
            + PROVENANCE;
    return new ReviewResponse.Finding(
        rule.risk(), rule.confidence(), filename, line, title, description, null, null);
  }

  private static ReviewResponse.Finding iacFinding(String filename, IacScanner.Hit hit) {
    var rule = hit.rule();
    var description =
        rule.explanation()
            + " If this is intended, add a `"
            + ALLOW_IAC_MARKER
            + "` comment on the line or the line above it.\n\n"
            + PROVENANCE;
    // The anchor is the line itself: infrastructure configuration, not a secret, and what lets a
    // later round judge whether the finding is still present.
    return new ReviewResponse.Finding(
        rule.risk(),
        rule.confidence(),
        filename,
        hit.line(),
        rule.iacTitle(),
        description,
        hit.text().strip(),
        null);
  }

  private static String keyName(String raw) {
    return raw.length() <= MAX_KEY_NAME ? raw : raw.substring(0, MAX_KEY_NAME) + "…";
  }

  /**
   * The effective previous round's findings the scan raised and no newer round settled, by 1-based
   * id. Selected once per merge, so matching a detection or closing a prior walks only the scan's
   * own findings rather than the whole previous round. A settled id is left out: a finding a newer
   * round closed is not the one to keep open, and a value re-added after it was resolved is a new
   * leak.
   */
  private static Map<Integer, ReviewResponse.Finding> openScanPriors(
      List<ReviewResponse.Finding> previous, Set<Integer> settledIds) {
    var priors = new LinkedHashMap<Integer, ReviewResponse.Finding>();
    for (int i = 0; i < previous.size(); i++) {
      var prior = previous.get(i);
      if (!settledIds.contains(i + 1)
          && prior.file() != null
          && SecurityRule.fromTitle(prior.title()) != null) {
        priors.put(i + 1, prior);
      }
    }
    return priors;
  }

  /**
   * The id of the open scan prior this detection repeats, or 0: same file, title and anchor, not
   * already claimed by an earlier detection of this scan, and the nearest by line among those. A
   * secret finding carries no anchor and its title names only the value's prefix and length, so two
   * values of one format in one file share every other key; letting both detections claim the same
   * prior would leave the second prior unclaimed and close it as resolved while its value is still
   * there.
   */
  private static int priorIdOf(
      ReviewResponse.Finding detection,
      Map<Integer, ReviewResponse.Finding> scanPriors,
      Set<Integer> claimed) {
    int best = 0;
    int bestDistance = Integer.MAX_VALUE;
    for (var entry : scanPriors.entrySet()) {
      var prior = entry.getValue();
      int distance = Math.abs(prior.line() - detection.line());
      if (!claimed.contains(entry.getKey())
          && distance < bestDistance
          && FilePaths.same(prior.file(), detection.file())
          && Objects.equals(prior.title(), detection.title())
          && Objects.equals(stripped(prior.suggestionOld()), stripped(detection.suggestionOld()))) {
        best = entry.getKey();
        bestDistance = distance;
      }
    }
    return best;
  }

  /**
   * The open scan priors this scan provably no longer sees: raised by a rule whose half is on, in a
   * file the scan read, and not repeated by any detection.
   */
  private Set<Integer> clearedPriorIds(
      Map<Integer, ReviewResponse.Finding> scanPriors,
      Set<Integer> trackedIds,
      List<String> scannedFiles) {
    var cleared = new HashSet<Integer>();
    for (var entry : scanPriors.entrySet()) {
      var prior = entry.getValue();
      if (halfEnabled(SecurityRule.fromTitle(prior.title()))
          && !trackedIds.contains(entry.getKey())
          && scannedFiles.stream().anyMatch(f -> FilePaths.same(f, prior.file()))) {
        cleared.add(entry.getKey());
      }
    }
    return cleared;
  }

  private boolean halfEnabled(SecurityRule rule) {
    return rule.category() == SecurityRule.Category.SECRET ? secretsEnabled : iacEnabled;
  }

  /**
   * The previous-round statuses with the scan's verdicts applied: a still-detected finding is
   * {@code unresolved} unless the model reported a maintainer's {@code justified}; a finding no
   * longer detected is {@code resolved}. Ids the model did not report are appended in id order.
   */
  private static List<ReviewResponse.PreviousFindingStatus> statuses(
      List<ReviewResponse.PreviousFindingStatus> reported,
      Set<Integer> stillDetected,
      Set<Integer> noLongerDetected,
      Scrubber scrubber) {
    var result = new ArrayList<ReviewResponse.PreviousFindingStatus>(reported.size());
    var seen = new HashSet<Integer>();
    for (var status : reported) {
      seen.add(status.id());
      result.add(applyScan(status, stillDetected, noLongerDetected, scrubber));
    }
    Stream.concat(stillDetected.stream(), noLongerDetected.stream())
        .filter(id -> !seen.contains(id))
        .sorted()
        .map(
            id ->
                stillDetected.contains(id)
                    ? new ReviewResponse.PreviousFindingStatus(
                        id, STATUS_UNRESOLVED, STILL_DETECTED_NOTE)
                    : new ReviewResponse.PreviousFindingStatus(
                        id, STATUS_RESOLVED, NO_LONGER_DETECTED_NOTE))
        .forEach(result::add);
    return result;
  }

  private static ReviewResponse.PreviousFindingStatus applyScan(
      ReviewResponse.PreviousFindingStatus status,
      Set<Integer> stillDetected,
      Set<Integer> noLongerDetected,
      Scrubber scrubber) {
    var note = scrubber.scrub(status.note());
    if (stillDetected.contains(status.id())
        && !STATUS_JUSTIFIED.equalsIgnoreCase(status.status())) {
      return new ReviewResponse.PreviousFindingStatus(
          status.id(), STATUS_UNRESOLVED, STILL_DETECTED_NOTE);
    }
    if (noLongerDetected.contains(status.id())) {
      return new ReviewResponse.PreviousFindingStatus(
          status.id(), STATUS_RESOLVED, NO_LONGER_DETECTED_NOTE);
    }
    return Objects.equals(note, status.note())
        ? status
        : new ReviewResponse.PreviousFindingStatus(status.id(), status.status(), note);
  }

  /**
   * Whether a model finding reports a defect the scan already raised: same file, within the
   * deduplicator's line tolerance, and either the same defect by its title match or a title using
   * at least {@link #MIN_TOPIC_WORDS} of the rule's own words.
   */
  private static boolean duplicatesDetection(
      ReviewResponse.Finding finding, List<Detection> detections) {
    if (finding.file() == null) {
      return false;
    }
    var titleWords = words(finding.title());
    for (var detection : detections) {
      var scanned = detection.finding();
      if (FilePaths.same(finding.file(), scanned.file())
          && Math.abs(finding.line() - scanned.line()) <= FindingDeduplicator.LINE_TOLERANCE
          && (FindingDeduplicator.sameDefect(finding, scanned)
              || titleWords.stream().filter(detection.rule().topic()::contains).count()
                  >= MIN_TOPIC_WORDS)) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> words(String title) {
    if (title == null) {
      return Set.of();
    }
    return Arrays.stream(title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
        .filter(word -> !word.isEmpty())
        .collect(Collectors.toSet());
  }

  private static ReviewResponse.Finding scrub(ReviewResponse.Finding finding, Scrubber scrubber) {
    var scrubbed =
        new ReviewResponse.Finding(
            finding.risk(),
            finding.confidence(),
            finding.file(),
            finding.line(),
            scrubber.scrub(finding.title()),
            scrubber.scrub(finding.description()),
            scrubber.scrub(finding.suggestionOld()),
            scrubber.scrub(finding.suggestionNew()));
    return scrubbed.equals(finding) ? finding : scrubbed;
  }

  /**
   * Replaces every matched value in a text with its redacted form in one pass: the values are
   * compiled into a single alternation once per merge, longest first so a value that contains
   * another is replaced whole, instead of rebuilding each text once per value.
   */
  static final class Scrubber {
    private static final Scrubber NONE = new Scrubber(null, Map.of());

    private final Pattern values;
    private final Map<String, String> replacements;

    private Scrubber(Pattern values, Map<String, String> replacements) {
      this.values = values;
      this.replacements = replacements;
    }

    /** Built from redactions already ordered longest first ({@link Result#redactions()}). */
    static Scrubber of(List<Redaction> redactions) {
      if (redactions.isEmpty()) {
        return NONE;
      }
      var replacements = new LinkedHashMap<String, String>();
      for (var redaction : redactions) {
        replacements.putIfAbsent(redaction.literal(), redaction.replacement());
      }
      var alternation =
          replacements.keySet().stream().map(Pattern::quote).collect(Collectors.joining("|"));
      return new Scrubber(Pattern.compile(alternation), replacements);
    }

    String scrub(String text) {
      if (text == null || values == null) {
        return text;
      }
      return values
          .matcher(text)
          .replaceAll(match -> Matcher.quoteReplacement(replacements.get(match.group())));
    }
  }

  private static String stripped(String text) {
    return text == null ? null : text.strip();
  }
}
