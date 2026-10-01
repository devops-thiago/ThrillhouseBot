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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
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
 * scrubbed out, so a model finding that quoted the line cannot post it either. With the secret half
 * on, the scrub also redacts a secret-looking quoted literal that a line of that text assigns to a
 * credential-named key, whether or not the scan matched it, so an assignment in a form the scan
 * does not know cannot become an echo (#916). Nothing here logs a value. The summary call that
 * follows is code-blind and only reads the findings, so its text is built from the scrubbed set.
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
 * <p><b>A standing decline.</b> A detection that repeats a scan finding a later round recorded
 * {@code justified} — a maintainer's decline the bot accepted ({@link
 * FollowUpAnalyzer#justifiedPriorFindings}) — is not raised again while the rule still matches the
 * same content (#982): same file, title and anchor, and for a secret the same {@linkplain
 * #contentFingerprint fingerprint} of the flagged line, so a new value on that line is raised
 * whatever prefix and length it shares with the old one (a decline recorded before secret findings
 * carried the fingerprint covers its line only until the head moves). Without this the scan, which
 * is not model output and reads no learning, raised a declined finding again on the next round and
 * blocked the pull request on every push. A decline matches one detection, the nearest by line, so
 * a second identical line added later is raised. A finding the effective previous round raised
 * again before the decline was remembered is reported {@code justified} under the same rule, so its
 * thread closes with the decline it repeats.
 *
 * <p><b>Duplicates.</b> A model finding within {@link FindingDeduplicator#LINE_TOLERANCE} lines of
 * a detection in the same file is dropped when it is the same defect by {@link
 * FindingDeduplicator#sameDefect} or its title uses at least two of the rule's own words ({@link
 * SecurityRule#topic}), so the reviewer does not post the same secret twice; the scan's finding is
 * the one kept, because it is the redacted one. On the very line a secret was matched, one rule
 * word in the model's title is enough (#932): a model's own "API token committed in source" finding
 * is the same leak in other words, and keeping it posted a second thread at a second severity.
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

  static final String PEM_MATERIAL_REDACTION = "[redacted private key material]";

  private static final String PROVENANCE =
      "_Raised by the deterministic security scan: a pattern match on the added lines, not model"
          + " output, so it is not put to the second-pass verifier._";

  private static final String STILL_DETECTED_NOTE =
      "Still detected by the deterministic security scan on an added line.";

  private static final String NO_LONGER_DETECTED_NOTE =
      "No longer detected by the deterministic security scan: the line was removed or changed, or"
          + " carries the allow marker.";

  static final String DECLINE_STANDS_NOTE =
      "A maintainer's decline of this scan finding on an earlier round stands: the rule still"
          + " matches the same content.";

  /**
   * Hidden line a secret finding's description ends with: a fingerprint of the content it flagged,
   * which is what tells a later round whether a declined finding's line still carries the same
   * value. The title shows only the value's first characters and length, which a known format
   * shares across every value.
   */
  private static final Pattern CONTENT_MARKER =
      Pattern.compile("<!-- thrillhousebot:scan-content=([0-9a-f]+) -->");

  /** Hex digits of the content fingerprint kept: enough to tell two values apart, no more. */
  private static final int FINGERPRINT_HEX = 8;

  private static final String FINGERPRINT_ALGORITHM = "SHA-256";

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
   * findings from the scan, and appends the findings neither the effective previous round nor an
   * earlier round still open on its own thread (located by {@code botIdentity}'s comments) already
   * raised. Returns the response untouched when both halves are off.
   */
  public ReviewResponse merge(
      ReviewResponse response, ReviewContextLoader.ReviewContext ctx, BotIdentity botIdentity) {
    if (!enabled()) {
      return response;
    }
    return merge(
        response,
        scan(ctx.reviewableFiles()),
        ctx.previousFindingsList(),
        FollowUpAnalyzer.settledPreviousIds(ctx.priorAiResponses()),
        FollowUpAnalyzer.openEarlierRoundFindings(
            ctx.priorAiResponses(),
            ctx.lineResolver(),
            VerdictBuilder.renameTargets(ctx.files()),
            ctx.inlineComments(),
            botIdentity),
        new Declines(
            FollowUpAnalyzer.justifiedPriorFindings(ctx.priorAiResponses()),
            ctx.previousRoundHeadUnchanged()));
  }

  ReviewResponse merge(
      ReviewResponse response,
      Result result,
      List<ReviewResponse.Finding> previous,
      Set<Integer> settledIds) {
    return merge(response, result, previous, settledIds, List.of());
  }

  /**
   * As {@link #merge(ReviewResponse, ReviewContextLoader.ReviewContext, BotIdentity)}, with {@code
   * earlierOpen} the findings rounds older than the effective previous one left open on their own
   * threads ({@link FollowUpAnalyzer#openEarlierRoundFindings}). A detection that repeats one of
   * them is not raised again (#939): it was posted in that round, its thread is still open, and the
   * verdict backstop holds it while its code is present. It has no id in the effective previous
   * round, so it is neither tracked nor closed here.
   */
  ReviewResponse merge(
      ReviewResponse response,
      Result result,
      List<ReviewResponse.Finding> previous,
      Set<Integer> settledIds,
      List<ReviewResponse.Finding> earlierOpen) {
    return merge(response, result, previous, settledIds, earlierOpen, Declines.NONE);
  }

  /**
   * The findings later rounds recorded justified ({@link FollowUpAnalyzer#justifiedPriorFindings}),
   * and whether this review's head is the one the round it reports on reviewed — the only case in
   * which a decline recorded before secret findings carried a fingerprint may still cover one.
   */
  record Declines(List<ReviewResponse.Finding> justified, boolean headUnchanged) {
    static final Declines NONE = new Declines(List.of(), false);

    Declines {
      justified = List.copyOf(justified);
    }
  }

  /**
   * As {@link #merge(ReviewResponse, Result, List, Set, List)}, with the maintainer declines later
   * rounds recorded. A detection that repeats a declined scan finding on the same content is not
   * raised again (#982), and a previous-round finding it is tracked as is reported justified.
   */
  ReviewResponse merge(
      ReviewResponse response,
      Result result,
      List<ReviewResponse.Finding> previous,
      Set<Integer> settledIds,
      List<ReviewResponse.Finding> earlierOpen,
      Declines declined) {
    var scanPriors = openScanPriors(previous, settledIds);
    var declines = scanFindings(declined.justified(), declined.headUnchanged());
    var scrubber =
        secretsEnabled
            ? Scrubber.of(result.redactions(), entropyThreshold)
            : Scrubber.of(result.redactions());
    var tracked = new LinkedHashMap<Integer, Detection>();
    var standing = new HashSet<Integer>();
    var raised = new ArrayList<ReviewResponse.Finding>();
    int earlier = 0;
    int settled = 0;
    for (var detection : result.detections()) {
      int priorId = priorIdOf(detection.finding(), scanPriors, tracked.keySet());
      boolean repeatsDecline = claimDecline(detection.finding(), declines);
      if (priorId > 0) {
        tracked.put(priorId, detection);
        if (repeatsDecline) {
          standing.add(priorId);
        }
      } else if (repeatsDecline) {
        settled++;
      } else if (repeatsEarlierRound(detection.finding(), earlierOpen)) {
        earlier++;
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
    var statuses =
        statuses(
            response.previousFindingsStatus(),
            new ScanVerdicts(tracked.keySet(), standing, cleared),
            scrubber);
    // A duplicate needs a detection, so these two cover every merge that changed anything.
    if (!result.detections().isEmpty() || !cleared.isEmpty()) {
      Log.infof(
          "Security scan: raised %d finding(s), kept %d from the previous round open, left %d"
              + " open from an earlier round, left %d declined by a maintainer, closed %d no longer"
              + " detected, dropped %d model duplicate(s)",
          raised.size(),
          tracked.size() - standing.size(),
          earlier,
          settled + standing.size(),
          cleared.size(),
          duplicates);
    }
    return new ReviewResponse(
        kept,
        statuses,
        scrub(FindingVerificationService.recount(response.summary(), kept), scrubber));
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
        var fingerprint = contentFingerprint(hit.rule(), flaggedContent(lines, i, hit.rule()));
        detections.add(
            new Detection(hit.rule(), secretFinding(filename, line.number(), hit, fingerprint)));
        if (hit.rule() == SecurityRule.PRIVATE_KEY) {
          addPemMaterial(lines, i, redactions);
        } else {
          redactions.add(new Redaction(hit.literal(), "[redacted: " + redacted + "]"));
        }
      }
    }
  }

  /**
   * What a secret finding on line {@code index} flagged, for its fingerprint: the line, stripped,
   * or for a private key its whole block, since the header line is the same for every key.
   */
  private static String flaggedContent(List<PatchLines.Line> lines, int index, SecurityRule rule) {
    var flagged =
        rule == SecurityRule.PRIVATE_KEY ? pemBlock(lines, index) : List.of(lines.get(index));
    return flagged.stream().map(line -> line.text().strip()).collect(Collectors.joining("\n"));
  }

  /**
   * Records the base64 runs of a private key — on its header line and on the added body lines that
   * follow — so a model finding quoting the key body is scrubbed as well as one quoting the header.
   */
  private static void addPemMaterial(
      List<PatchLines.Line> lines, int headerIndex, List<Redaction> redactions) {
    for (var line : pemBlock(lines, headerIndex)) {
      var material = PEM_MATERIAL.matcher(line.text());
      while (material.find()) {
        redactions.add(new Redaction(material.group(), PEM_MATERIAL_REDACTION));
      }
    }
  }

  /** A private key's header line and the added body lines that follow it in the same hunk. */
  private static List<PatchLines.Line> pemBlock(List<PatchLines.Line> lines, int headerIndex) {
    var block = new ArrayList<PatchLines.Line>();
    int hunk = lines.get(headerIndex).hunk();
    for (int i = headerIndex; i < lines.size(); i++) {
      var line = lines.get(i);
      if (i > headerIndex
          && (line.hunk() != hunk || !line.added() || !SecretScanner.isPemBodyLine(line.text()))) {
        break;
      }
      block.add(line);
    }
    return block;
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
      String filename, int line, SecretScanner.Hit hit, String fingerprint) {
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
            + PROVENANCE
            + "\n\n<!-- thrillhousebot:scan-content="
            + fingerprint
            + " -->";
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
   * Whether a detection repeats a scan finding an earlier round raised and left open: same file,
   * title and anchor, the key {@link #priorIdOf} matches a previous-round finding by.
   */
  private static boolean repeatsEarlierRound(
      ReviewResponse.Finding detection, List<ReviewResponse.Finding> earlierOpen) {
    return earlierOpen.stream()
        .anyMatch(
            prior ->
                FilePaths.same(prior.file(), detection.file())
                    && Objects.equals(prior.title(), detection.title())
                    && Objects.equals(
                        stripped(prior.suggestionOld()), stripped(detection.suggestionOld())));
  }

  /**
   * The scan's own declined findings a detection can claim, in a list it claims them from. A secret
   * finding persisted before it carried a fingerprint has nothing that tells its value from another
   * of the same prefix and length, so its decline counts only while the head is the one the round
   * this review reports on reviewed: nothing on the line can have changed since that round. After a
   * push it lapses, and the finding raised again carries a fingerprint the next decline keeps. An
   * IaC finding's anchor is its line, so its decline needs no fingerprint.
   */
  private static List<ReviewResponse.Finding> scanFindings(
      List<ReviewResponse.Finding> findings, boolean headUnchanged) {
    var scan = new ArrayList<ReviewResponse.Finding>();
    for (var finding : findings) {
      var rule = finding.file() == null ? null : SecurityRule.fromTitle(finding.title());
      if (rule != null
          && (headUnchanged
              || rule.category() == SecurityRule.Category.IAC
              || fingerprintOf(finding.description()) != null)) {
        scan.add(finding);
      }
    }
    return scan;
  }

  /**
   * Whether a detection repeats a declined scan finding, claiming the nearest one by line so it
   * covers no second detection: same file, title and anchor, and the same content fingerprint when
   * the declined finding carries one. One that carries none (an IaC finding, or a secret finding
   * {@link #scanFindings} admitted on an unchanged head) is matched by the other keys.
   */
  private static boolean claimDecline(
      ReviewResponse.Finding detection, List<ReviewResponse.Finding> declines) {
    int best = -1;
    int bestDistance = Integer.MAX_VALUE;
    for (int i = 0; i < declines.size(); i++) {
      var declined = declines.get(i);
      int distance = Math.abs(declined.line() - detection.line());
      if (distance < bestDistance && sameContent(declined, detection)) {
        best = i;
        bestDistance = distance;
      }
    }
    if (best < 0) {
      return false;
    }
    declines.remove(best);
    return true;
  }

  private static boolean sameContent(
      ReviewResponse.Finding prior, ReviewResponse.Finding detection) {
    var priorFingerprint = fingerprintOf(prior.description());
    return FilePaths.same(prior.file(), detection.file())
        && Objects.equals(prior.title(), detection.title())
        && Objects.equals(stripped(prior.suggestionOld()), stripped(detection.suggestionOld()))
        && (priorFingerprint == null
            || priorFingerprint.equals(fingerprintOf(detection.description())));
  }

  /** The content fingerprint a scan finding's description carries, or {@code null}. */
  static String fingerprintOf(String description) {
    if (description == null) {
      return null;
    }
    var marker = CONTENT_MARKER.matcher(description);
    return marker.find() ? marker.group(1) : null;
  }

  /**
   * A short fingerprint of the content a secret finding flagged, the rule's name included: the
   * first {@value #FINGERPRINT_HEX} hex digits of its SHA-256. It tells one value from another
   * without holding either; the value itself is on the pull request's own diff.
   */
  static String contentFingerprint(SecurityRule rule, String content) {
    return contentFingerprint(FINGERPRINT_ALGORITHM, rule, content);
  }

  /**
   * As {@link #contentFingerprint(SecurityRule, String)}; the algorithm is a parameter for tests.
   */
  static String contentFingerprint(String algorithm, SecurityRule rule, String content) {
    try {
      var digest = MessageDigest.getInstance(algorithm);
      digest.update(rule.name().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      var hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, FINGERPRINT_HEX);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
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
   * What the scan decided about the effective previous round's own findings, by id: still detected,
   * still detected on content a maintainer's decline covers (a subset of {@code stillDetected}),
   * and no longer detected.
   */
  private record ScanVerdicts(
      Set<Integer> stillDetected, Set<Integer> declineStands, Set<Integer> noLongerDetected) {}

  /**
   * The previous-round statuses with the scan's verdicts applied: a still-detected finding is
   * {@code unresolved} unless the model reported a maintainer's {@code justified}, or {@code
   * justified} when it repeats a decline that stands; a finding no longer detected is {@code
   * resolved}. Ids the model did not report are appended in id order.
   */
  private static List<ReviewResponse.PreviousFindingStatus> statuses(
      List<ReviewResponse.PreviousFindingStatus> reported,
      ScanVerdicts verdicts,
      Scrubber scrubber) {
    var result = new ArrayList<ReviewResponse.PreviousFindingStatus>(reported.size());
    var seen = new HashSet<Integer>();
    for (var status : reported) {
      seen.add(status.id());
      result.add(applyScan(status, verdicts, scrubber));
    }
    Stream.concat(verdicts.stillDetected().stream(), verdicts.noLongerDetected().stream())
        .filter(id -> !seen.contains(id))
        .sorted()
        .map(id -> scanStatus(id, verdicts))
        .forEach(result::add);
    return result;
  }

  private static ReviewResponse.PreviousFindingStatus scanStatus(int id, ScanVerdicts verdicts) {
    if (verdicts.declineStands().contains(id)) {
      return new ReviewResponse.PreviousFindingStatus(id, STATUS_JUSTIFIED, DECLINE_STANDS_NOTE);
    }
    return verdicts.stillDetected().contains(id)
        ? new ReviewResponse.PreviousFindingStatus(id, STATUS_UNRESOLVED, STILL_DETECTED_NOTE)
        : new ReviewResponse.PreviousFindingStatus(id, STATUS_RESOLVED, NO_LONGER_DETECTED_NOTE);
  }

  private static ReviewResponse.PreviousFindingStatus applyScan(
      ReviewResponse.PreviousFindingStatus status, ScanVerdicts verdicts, Scrubber scrubber) {
    var note = scrubber.scrub(status.note());
    if (verdicts.stillDetected().contains(status.id())
        && !STATUS_JUSTIFIED.equalsIgnoreCase(status.status())) {
      return scanStatus(status.id(), verdicts);
    }
    if (verdicts.noLongerDetected().contains(status.id())) {
      return scanStatus(status.id(), verdicts);
    }
    return Objects.equals(note, status.note())
        ? status
        : new ReviewResponse.PreviousFindingStatus(status.id(), status.status(), note);
  }

  /**
   * Whether a model finding reports a defect the scan already raised: same file, and either within
   * the deduplicator's line tolerance with the same defect by its title match or a title using at
   * least {@link #MIN_TOPIC_WORDS} of the rule's own words, or — for a secret — on the detection's
   * very line with a title using one of them (#932). A model's own secret finding names the defect
   * in its own words ("API token committed in source"), which rarely shares two rule words, and
   * letting it through posted a second thread on the same line at a different severity; on the line
   * the scan matched, one topic word is enough, because the line holds nothing else a credential
   * word could be about. The finding is then collapsed into the scan's, the redacted one. A title
   * that only names the key ({@code PAYMENT_API_KEY is never read}) is not a topic word, so a
   * different defect on that line is kept ({@link #words}).
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
          && (nearbySameDefect(finding, scanned, titleWords, detection.rule())
              || sameLineSecret(finding, scanned, titleWords, detection.rule()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean nearbySameDefect(
      ReviewResponse.Finding finding,
      ReviewResponse.Finding scanned,
      Set<String> titleWords,
      SecurityRule rule) {
    return Math.abs(finding.line() - scanned.line()) <= FindingDeduplicator.LINE_TOLERANCE
        && (FindingDeduplicator.sameDefect(finding, scanned)
            || topicWords(titleWords, rule) >= MIN_TOPIC_WORDS);
  }

  private static boolean sameLineSecret(
      ReviewResponse.Finding finding,
      ReviewResponse.Finding scanned,
      Set<String> titleWords,
      SecurityRule rule) {
    return rule.category() == SecurityRule.Category.SECRET
        && finding.line() == scanned.line()
        && topicWords(titleWords, rule) >= 1;
  }

  private static long topicWords(Set<String> titleWords, SecurityRule rule) {
    return titleWords.stream().filter(rule.topic()::contains).count();
  }

  /**
   * The words of a title, lower-cased. A hyphenated word counts both whole and joined, so
   * "hard-coded" reads as "hardcoded" too. A token with an underscore is an identifier the title
   * names ({@code PAYMENT_API_KEY}), not words describing the defect, so it contributes no word.
   */
  static Set<String> words(String title) {
    if (title == null) {
      return Set.of();
    }
    var words = new HashSet<String>();
    for (var token : title.toLowerCase(Locale.ROOT).split("[^a-z0-9_-]+")) {
      if (token.indexOf('_') >= 0) {
        continue;
      }
      Arrays.stream(token.split("-")).filter(word -> !word.isEmpty()).forEach(words::add);
      var joined = token.replace("-", "");
      if (!joined.isEmpty()) {
        words.add(joined);
      }
    }
    return words;
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
   * The summary with every matched value scrubbed from its text. The review call returns findings
   * only, so a summary rarely reaches the merge; when one does (a salvaged or legacy response), it
   * is persisted and rendered like the findings, so it gets the same treatment.
   */
  private static ReviewResponse.Summary scrub(ReviewResponse.Summary summary, Scrubber scrubber) {
    if (summary == null) {
      return null;
    }
    return new ReviewResponse.Summary(
        summary.totalFindings(),
        summary.critical(),
        summary.high(),
        summary.medium(),
        summary.low(),
        scrubber.scrub(summary.overallAssessment()),
        scrubber.scrub(summary.prPurpose()),
        summary.descriptionGaps().stream().map(scrubber::scrub).toList(),
        summary.suggestedLabels(),
        summary.fileSummaries().stream()
            .map(f -> new ReviewResponse.FileSummary(f.path(), scrubber.scrub(f.summary())))
            .toList(),
        scrubber.scrub(summary.walkthroughDiagram()));
  }

  /**
   * Replaces every matched value in a text with its redacted form in one pass: the values are
   * compiled into a single alternation once per merge, longest first so a value that contains
   * another is replaced whole, instead of rebuilding each text once per value. With the secret half
   * on, it then redacts any quoted literal a line of the text assigns to a credential-named key
   * ({@link SecretScanner#redactAssignedLiterals}), so a value the scan did not match — an
   * assignment in a form it does not know — is not echoed either (#916).
   */
  static final class Scrubber {
    private static final Scrubber NONE = new Scrubber(null, Map.of(), Double.NaN);

    private final Pattern values;
    private final Map<String, String> replacements;

    /** Entropy threshold of the assigned-literal pass, or NaN when that pass is off. */
    private final double assignedLiteralThreshold;

    private Scrubber(
        Pattern values, Map<String, String> replacements, double assignedLiteralThreshold) {
      this.values = values;
      this.replacements = replacements;
      this.assignedLiteralThreshold = assignedLiteralThreshold;
    }

    /** Built from redactions already ordered longest first ({@link Result#redactions()}). */
    static Scrubber of(List<Redaction> redactions) {
      return of(redactions, Double.NaN);
    }

    /**
     * As {@link #of(List)}, plus the assigned-literal pass at the given entropy threshold (NaN
     * leaves it off).
     */
    static Scrubber of(List<Redaction> redactions, double assignedLiteralThreshold) {
      if (redactions.isEmpty()) {
        return Double.isNaN(assignedLiteralThreshold)
            ? NONE
            : new Scrubber(null, Map.of(), assignedLiteralThreshold);
      }
      var replacements = new LinkedHashMap<String, String>();
      for (var redaction : redactions) {
        replacements.putIfAbsent(redaction.literal(), redaction.replacement());
      }
      var alternation =
          replacements.keySet().stream().map(Pattern::quote).collect(Collectors.joining("|"));
      return new Scrubber(Pattern.compile(alternation), replacements, assignedLiteralThreshold);
    }

    String scrub(String text) {
      if (text == null) {
        return null;
      }
      var scrubbed =
          values == null
              ? text
              : values
                  .matcher(text)
                  .replaceAll(match -> Matcher.quoteReplacement(replacements.get(match.group())));
      return Double.isNaN(assignedLiteralThreshold)
          ? scrubbed
          : SecretScanner.redactAssignedLiterals(scrubbed, assignedLiteralThreshold);
    }
  }

  private static String stripped(String text) {
    return text == null ? null : text.strip();
  }
}
