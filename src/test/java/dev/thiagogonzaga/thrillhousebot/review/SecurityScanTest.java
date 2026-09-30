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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #60: merging the deterministic security scan into a review — the findings it raises, the model
 * findings it drops or scrubs, the previous-round statuses it sets, and that a matched secret never
 * reaches any text the bot stores or posts.
 */
class SecurityScanTest {

  private static final List<String> SKIPPED =
      List.of("**/fixtures/**", "**/testdata/**", "**/*.snap");

  private final FakeCredentials fake = new FakeCredentials(6060);

  private static SecurityScan scan(boolean secrets, boolean iac) {
    return new SecurityScan(secrets, iac, 3.5, SKIPPED);
  }

  /** A file the pull request adds, one patch line per argument. */
  private static FileDiff added(String name, String... lines) {
    var sb = new StringBuilder("@@ -0,0 +1," + lines.length + " @@\n");
    for (var line : lines) {
      sb.append('+').append(line).append('\n');
    }
    return new FileDiff(name, "added", lines.length, 0, lines.length, sb.toString());
  }

  private static ReviewResponse.Finding modelFinding(
      String file, int line, String title, String description) {
    return new ReviewResponse.Finding("high", "high", file, line, title, description, "old", "new");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(List.of(findings), List.of(), null);
  }

  private static ReviewResponse merge(
      SecurityScan scan, ReviewResponse response, List<FileDiff> files) {
    return scan.merge(response, scan.scan(files), List.of(), Set.of());
  }

  /** Every text the bot stores or posts for a finding set: persisted JSON and each comment body. */
  private static String everySurface(ReviewResponse response) throws Exception {
    var sb = new StringBuilder(new ObjectMapper().writeValueAsString(response));
    var formatter = new SuggestionFormatter();
    for (var finding : response.findings()) {
      sb.append('\n')
          .append(formatter.formatReviewComment(Finding.fromAiResponse(finding), true, 1));
    }
    return sb.toString();
  }

  // --- raising ---

  @Test
  void bothHalvesOffLeavesTheResponseUntouched() {
    var response = response(modelFinding("a.java", 1, "t", "d"));
    var ctx = context(List.of(added("a.env", "KEY=" + fake.githubToken())), List.of());
    assertSame(response, SecurityScan.disabled().merge(response, ctx));
    assertFalse(SecurityScan.disabled().enabled());
  }

  @Test
  void aSecretIsRaisedAtItsRuleGradeWithNoAnchorAndARedactedTitle() throws Exception {
    var token = fake.githubToken();
    var merged =
        merge(
            scan(true, false),
            response(),
            List.of(added("deploy/.env", "APP=1", "GITHUB_TOKEN=" + token)));

    assertEquals(1, merged.findings().size());
    var finding = merged.findings().get(0);
    assertEquals("critical", finding.risk());
    assertEquals("high", finding.confidence());
    assertEquals("deploy/.env", finding.file());
    assertEquals(2, finding.line());
    assertEquals("Security scan: hardcoded GitHub token (ghp_…, 40 chars)", finding.title());
    assertNull(finding.suggestionOld(), "the anchor is persisted, so it must not carry the value");
    assertNull(finding.suggestionNew());
    assertTrue(finding.description().contains("thrillhousebot:allow-secret"));
    assertTrue(finding.description().contains("not put to the second-pass verifier"));
    assertFalse(everySurface(merged).contains(token));
    assertFalse(everySurface(merged).contains(token.substring(4)));
  }

  @Test
  void aGenericAssignmentNamesItsKeyAndShowsAQuarterOfTheValueAtMost() throws Exception {
    var merged =
        merge(
            scan(true, false),
            response(),
            List.of(added("config.py", "DB_PASSWORD = \"q8#Lm2!vRt9Z\"")));
    var finding = merged.findings().get(0);
    assertEquals("high", finding.risk());
    assertEquals(
        "Security scan: hardcoded credential in DB_PASSWORD (q8#…, 12 chars)", finding.title());
    assertTrue(finding.description().contains("`DB_PASSWORD`"));
    assertFalse(everySurface(merged).contains("q8#Lm2!vRt9Z"));
  }

  @Test
  void aVeryLongKeyNameIsShortenedInTheTitle() {
    var key = "a".repeat(45) + "_password";
    var merged =
        merge(scan(true, false), response(), List.of(added("c.py", key + " = \"q8#Lm2!vRt9Z\"")));
    var title = merged.findings().get(0).title();
    var shown = title.substring(title.indexOf(" in ") + 4, title.indexOf("\u2026 ("));
    assertEquals(40, shown.length(), title);
  }

  @Test
  void aPrivateKeyIsRaisedAndItsBodyScrubbedFromModelText() throws Exception {
    var body1 = fake.pemBodyLine();
    var body2 = fake.pemBodyLine();
    var model =
        modelFinding(
            "certs/key.pem",
            40,
            "Unrelated",
            "The file also contains " + body2 + " which should be rotated.");
    var merged =
        merge(
            scan(true, false),
            response(model),
            List.of(
                added(
                    "certs/key.pem",
                    FakeCredentials.pemHeader("RSA"),
                    body1,
                    body2,
                    "-----END RSA PRIVATE KEY-----")));
    assertEquals(2, merged.findings().size());
    assertEquals(
        "Security scan: hardcoded private key (PEM block)", merged.findings().get(1).title());
    var surfaces = everySurface(merged);
    assertFalse(surfaces.contains(body1));
    assertFalse(surfaces.contains(body2));
    assertTrue(surfaces.contains("[redacted private key material]"));
  }

  @Test
  void anIacRuleIsRaisedWithTheLineAsItsAnchor() {
    var merged =
        merge(
            scan(false, true),
            response(),
            List.of(added("k8s/pod.yaml", "spec:", "  hostPID: true")));
    var finding = merged.findings().get(0);
    assertEquals("Security scan: pod shares a host namespace", finding.title());
    assertEquals("high", finding.risk());
    assertEquals("hostPID: true", finding.suggestionOld());
    assertTrue(finding.description().contains("thrillhousebot:allow-iac"));
  }

  @Test
  void eachHalfHasItsOwnSwitch() {
    var files =
        List.of(
            added("k8s/pod.yaml", "  privileged: true"),
            added("app.env", "KEY=" + fake.stripeLiveKey()));
    assertEquals(
        List.of("app.env"),
        merge(scan(true, false), response(), files).findings().stream()
            .map(ReviewResponse.Finding::file)
            .toList());
    assertEquals(
        List.of("k8s/pod.yaml"),
        merge(scan(false, true), response(), files).findings().stream()
            .map(ReviewResponse.Finding::file)
            .toList());
  }

  // --- skipping ---

  @Test
  void theAllowMarkerOnTheLineOrTheLineAboveExemptsIt() {
    var files =
        List.of(
            added("a.env", "KEY=" + fake.googleApiKey() + " # thrillhousebot:allow-secret"),
            added("b.env", "# thrillhousebot:allow-secret", "KEY=" + fake.googleApiKey()),
            added("Dockerfile", "FROM x", "# thrillhousebot:allow-iac", "USER root"),
            added("pod.yaml", "  privileged: true  # thrillhousebot:allow-iac"));
    assertTrue(merge(scan(true, true), response(), files).findings().isEmpty());
  }

  @Test
  void aMarkerForTheOtherHalfOrTwoLinesAboveDoesNotExempt() {
    var files =
        List.of(
            added("a.env", "KEY=" + fake.googleApiKey() + " # thrillhousebot:allow-iac"),
            added("b.env", "# thrillhousebot:allow-secret", "", "KEY=" + fake.googleApiKey()));
    assertEquals(2, merge(scan(true, true), response(), files).findings().size());
  }

  @Test
  void theMarkerOnTheLastLineOfAnEarlierHunkDoesNotReachTheNextHunk() {
    var patch =
        "@@ -1,1 +1,1 @@\n-x\n+# thrillhousebot:allow-secret\n@@ -9,0 +9,1 @@\n+KEY="
            + fake.googleApiKey()
            + "\n";
    var file = new FileDiff("a.env", "modified", 2, 1, 3, patch);
    assertEquals(1, merge(scan(true, false), response(), List.of(file)).findings().size());
  }

  @Test
  void fixturesAndPatchlessFilesAreNotScanned() {
    var token = fake.githubToken();
    var result =
        scan(true, true)
            .scan(
                List.of(
                    added("src/test/fixtures/tokens.txt", "t=" + token),
                    added("pkg/testdata/github.json", "{\"t\": \"" + token + "\"}"),
                    new FileDiff("img.png", "added", 0, 0, 0, null),
                    new FileDiff("huge.tf", "modified", 0, 0, 0, "")));
    assertTrue(result.detections().isEmpty());
    assertTrue(result.scannedFiles().isEmpty());
  }

  @Test
  void theReviewContextScansReviewableFilesOnly() {
    var token = fake.githubToken();
    var ignored = added("vendor/lib/.env", "T=" + token);
    var ctx = context(List.of(), List.of());
    var withIgnored =
        new ReviewContextLoader.ReviewContext(
            List.of(ignored),
            ctx.diff(),
            ctx.baseComparison(),
            ctx.omittedFiles(),
            ctx.priorReviews(),
            ctx.priorAiResponseJsons(),
            ctx.priorAiResponses(),
            ctx.isFirstVisibleReview(),
            ctx.hasContext(),
            ctx.previousAiResponseJson(),
            ctx.inlineComments(),
            ctx.previousFindings(),
            ctx.instructions(),
            ctx.pathInstructions(),
            ctx.repoLabels(),
            ctx.projectStack(),
            ctx.linkedIssuesContext(),
            ctx.configKeyContext(),
            ctx.patchCoverage(),
            List.of(),
            ctx.lineResolverSupplier(),
            ctx.prTotals());
    assertTrue(scan(true, false).merge(response(), withIgnored).findings().isEmpty());
  }

  // --- model duplicates and scrubbing ---

  @Test
  void aModelFindingOnTheSameSecretIsDroppedAndTheRedactedOneKept() throws Exception {
    var key = fake.awsAccessKey();
    var model =
        modelFinding(
            "infra/app.env",
            3,
            "Hardcoded AWS credentials committed",
            "The key " + key + " is committed in plain text.");
    var merged =
        merge(
            scan(true, false),
            response(model),
            List.of(added("infra/app.env", "A=1", "B=2", "AWS_ACCESS_KEY_ID=" + key)));
    assertEquals(1, merged.findings().size());
    assertTrue(merged.findings().get(0).title().startsWith(SecurityRule.TITLE_PREFIX));
    assertFalse(everySurface(merged).contains(key));
  }

  @Test
  void aModelFindingThatMatchesByTitleSimilarityIsDropped() {
    var model = modelFinding("pod.yaml", 2, "Security scan: privileged container", "desc");
    var merged =
        merge(scan(false, true), response(model), List.of(added("pod.yaml", "  privileged: true")));
    assertEquals(1, merged.findings().size());
    assertEquals("  privileged: true".strip(), merged.findings().get(0).suggestionOld());
  }

  @Test
  void anUnrelatedModelFindingIsKeptButCannotQuoteTheSecret() throws Exception {
    var key = fake.googleApiKey();
    var sameLineOtherDefect =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "web/maps.js",
            1,
            "Map is initialized before the DOM is ready",
            "Call init after load.",
            "const maps = init('" + key + "');",
            "window.onload = () => init('" + key + "');");
    var farAway = modelFinding("web/maps.js", 40, "Leaked token in logs", "Mentions " + key + ".");
    var otherFile = modelFinding("web/other.js", 1, "Hardcoded secret", "Something else.");
    var merged =
        merge(
            scan(true, false),
            response(sameLineOtherDefect, farAway, otherFile),
            List.of(added("web/maps.js", "const maps = init('" + key + "');")));

    assertEquals(4, merged.findings().size());
    var kept = merged.findings().get(0);
    assertEquals("Map is initialized before the DOM is ready", kept.title());
    assertTrue(kept.suggestionOld().contains("[redacted: AIza…, 39 chars]"));
    assertTrue(kept.suggestionNew().contains("[redacted: AIza…, 39 chars]"));
    assertTrue(merged.findings().get(1).description().contains("[redacted: "));
    assertSame(otherFile, merged.findings().get(2), "nothing to scrub, so the instance is kept");
    assertFalse(everySurface(merged).contains(key));
  }

  @Test
  void aModelFindingWithNoFileIsKept() {
    var noFile = modelFinding(null, 1, "Hardcoded secret", "General note.");
    var merged =
        merge(
            scan(true, false),
            response(noFile),
            List.of(added("a.env", "K=" + fake.googleApiKey())));
    assertEquals(2, merged.findings().size());
  }

  @Test
  void aLongerSecretIsScrubbedBeforeAShorterOneItContains() {
    var result =
        new SecurityScan.Result(
            List.of(),
            List.of(
                new SecurityScan.Redaction("abcdefgh12345678", "[long]"),
                new SecurityScan.Redaction("abcdefgh", "[short]")),
            List.of());
    var merged =
        scan(true, false)
            .merge(
                response(modelFinding("x", 1, "t", "abcdefgh12345678 and abcdefgh")),
                result,
                List.of(),
                Set.of());
    assertEquals("[long] and [short]", merged.findings().get(0).description());
    assertNull(SecurityScan.Scrubber.of(result.redactions()).scrub(null));
    assertEquals("x", SecurityScan.Scrubber.of(List.of()).scrub("x"));
  }

  @Test
  void theScanSortsItsRedactionsLongestFirst() {
    var longer = "Zx9!" + fake.alnum(20);
    var result =
        scan(true, false)
            .scan(
                List.of(
                    added("a.py", "password = \"q8#Lm2!vRt9Z\"", "api_key = \"" + longer + "\"")));
    assertEquals(longer, result.redactions().get(0).literal());
  }

  // --- across rounds ---

  @Test
  void aSecretThePreviousRoundRaisedIsTrackedNotRaisedAgain() {
    var token = fake.githubToken();
    var files = List.of(added("app.env", "X=1", "T=" + token));
    var s = scan(true, false);
    var previous = s.merge(response(), s.scan(files), List.of(), Set.of()).findings();
    var moved = List.of(added("app.env", "X=1", "Y=2", "T=" + token));

    // The model claims it resolved; the scan still sees it.
    var reported =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed " + token)),
            null);
    var merged = s.merge(reported, s.scan(moved), previous, Set.of());

    assertTrue(merged.findings().isEmpty(), "no second comment for the same secret");
    assertEquals(1, merged.previousFindingsStatus().size());
    var status = merged.previousFindingsStatus().get(0);
    assertEquals("unresolved", status.status());
    assertFalse(status.note().contains(token));
  }

  @Test
  void twoValuesOfOneFormatInOneFileEachKeepTheirOwnPrior() {
    var first = fake.githubToken();
    var second = fake.githubToken();
    var s = scan(true, false);
    var previous =
        s.merge(
                response(),
                s.scan(List.of(added("app.env", "X=1", "A=" + first, "Y=2", "Z=3", "B=" + second))),
                List.of(),
                Set.of())
            .findings();
    assertEquals(2, previous.size());
    assertEquals(previous.get(0).title(), previous.get(1).title(), "the redaction shows no more");

    // Both still present, one line lower: both stay open, neither is re-posted.
    var bothStay =
        s.merge(
            response(),
            s.scan(
                List.of(added("app.env", "W=0", "X=1", "A=" + first, "Y=2", "Z=3", "B=" + second))),
            previous,
            Set.of());
    assertTrue(bothStay.findings().isEmpty());
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "unresolved", stillDetectedNote()),
            new ReviewResponse.PreviousFindingStatus(2, "unresolved", stillDetectedNote())),
        bothStay.previousFindingsStatus());

    // The first is removed: the one left keeps the nearer prior open and the other is resolved.
    var firstGone =
        s.merge(
            response(),
            s.scan(List.of(added("app.env", "X=1", "Y=2", "Z=3", "B=" + second))),
            previous,
            Set.of());
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "resolved", noLongerDetectedNote()),
            new ReviewResponse.PreviousFindingStatus(2, "unresolved", stillDetectedNote())),
        firstGone.previousFindingsStatus());
  }

  @Test
  void aPrivateKeyHeaderEndingOneHunkDoesNotReadTheNextHunksFirstLine() {
    var patch =
        String.join(
                "\n",
                "@@ -1,1 +1,2 @@",
                " class Keys {",
                "+  static final String HEADER = \"" + FakeCredentials.pemHeader("RSA") + "\";",
                "@@ -40,1 +41,1 @@",
                " " + fake.pemBodyLine())
            + "\n";
    var file = new FileDiff("Keys.java", "modified", 1, 0, 1, patch);
    assertTrue(scan(true, false).scan(List.of(file)).detections().isEmpty());
  }

  @Test
  void aTrackedFindingTheModelDidNotReportIsAppendedUnresolved() {
    var files = List.of(added("app.env", "T=" + fake.githubToken()));
    var s = scan(true, false);
    var raised = s.merge(response(), s.scan(files), List.of(), Set.of()).findings();
    var other = modelFinding("b.java", 1, "t", "d");
    var previous = List.of(other, raised.get(0));

    var merged = s.merge(response(), s.scan(files), previous, Set.of());
    assertEquals(
        List.of(new ReviewResponse.PreviousFindingStatus(2, "unresolved", stillDetectedNote())),
        merged.previousFindingsStatus());
  }

  @Test
  void aMaintainersJustificationOfATrackedFindingIsKept() {
    var files = List.of(added("app.env", "T=" + fake.githubToken()));
    var s = scan(true, false);
    var previous = s.merge(response(), s.scan(files), List.of(), Set.of()).findings();
    var justified =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "justified", "test value")),
            null);
    var merged = s.merge(justified, s.scan(files), previous, Set.of());
    assertEquals("justified", merged.previousFindingsStatus().get(0).status());
    assertEquals("test value", merged.previousFindingsStatus().get(0).note());
  }

  @Test
  void aSettledPriorIsNotTrackedSoAReAddedSecretIsRaised() {
    var files = List.of(added("app.env", "T=" + fake.githubToken()));
    var s = scan(true, false);
    var previous = s.merge(response(), s.scan(files), List.of(), Set.of()).findings();
    var merged = s.merge(response(), s.scan(files), previous, Set.of(1));
    assertEquals(1, merged.findings().size());
    assertTrue(merged.previousFindingsStatus().isEmpty());
  }

  @Test
  void aPriorScanFindingNoLongerDetectedInAScannedFileIsResolved() {
    var s = scan(true, true);
    var previous =
        s.merge(
                response(),
                s.scan(
                    List.of(
                        added("app.env", "T=" + fake.githubToken()),
                        added("pod.yaml", "  hostNetwork: true"))),
                List.of(),
                Set.of())
            .findings();
    var model =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
            null);
    var merged =
        s.merge(
            model,
            s.scan(
                List.of(added("app.env", "T=${TOKEN}"), added("pod.yaml", "  hostNetwork: false"))),
            previous,
            Set.of());
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "resolved", noLongerDetectedNote()),
            new ReviewResponse.PreviousFindingStatus(2, "resolved", noLongerDetectedNote())),
        merged.previousFindingsStatus());
  }

  @Test
  void aPriorScanFindingIsLeftAloneWhenTheScanCouldNotSeeItsFileOrItsHalfIsOff() {
    var s = scan(true, true);
    var previous =
        s.merge(
                response(),
                s.scan(
                    List.of(
                        added("app.env", "T=" + fake.githubToken()),
                        added("pod.yaml", "  hostNetwork: true"))),
                List.of(),
                Set.of())
            .findings();
    // The secret's file left the scanned set; the IaC half is off this round.
    var merged =
        scan(true, false)
            .merge(
                response(),
                scan(true, false).scan(List.of(added("pod.yaml", "  hostNetwork: false"))),
                previous,
                Set.of());
    assertTrue(merged.previousFindingsStatus().isEmpty());

    // A settled prior, and a prior the scan did not write, are never touched.
    var model = modelFinding("app.env", 1, "Some model finding", "d");
    var untouched =
        s.merge(
            response(),
            s.scan(List.of(added("app.env", "T=1"), added("pod.yaml", "a: b"))),
            List.of(
                previous.get(0),
                model,
                new ReviewResponse.Finding(
                    "high", "high", null, 1, previous.get(1).title(), "d", null, null)),
            Set.of(1));
    assertTrue(untouched.previousFindingsStatus().isEmpty());
  }

  @Test
  void anUnrelatedStatusIsKeptAndItsNoteScrubbed() {
    var token = fake.githubToken();
    var s = scan(true, false);
    var reported =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "resolved", "moved " + token),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "no secret here")),
            null);
    var merged =
        s.merge(
            reported,
            s.scan(List.of(added("a.env", "T=" + token))),
            List.of(modelFinding("x", 1, "a", "b"), modelFinding("y", 1, "c", "d")),
            Set.of());
    assertFalse(merged.previousFindingsStatus().get(0).note().contains(token));
    assertSame(reported.previousFindingsStatus().get(1), merged.previousFindingsStatus().get(1));
  }

  @Test
  void theMergeGoesThroughTheReviewContext() {
    var token = fake.githubToken();
    var files = List.of(added("app.env", "T=" + token));
    var s = scan(true, false);
    var firstRound = s.merge(response(), context(files, List.of()));
    assertEquals(1, firstRound.findings().size());

    var secondRound = s.merge(response(), context(files, List.of(firstRound)));
    assertTrue(secondRound.findings().isEmpty());
    assertEquals("unresolved", secondRound.previousFindingsStatus().get(0).status());
  }

  @Test
  void theConfiguredScanReadsItsSwitches() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    var security = mock(ThrillhouseConfig.SecurityScanConfig.class);
    when(config.review()).thenReturn(review);
    when(review.securityScan()).thenReturn(security);
    when(security.secretsEnabled()).thenReturn(true);
    when(security.iacEnabled()).thenReturn(false);
    when(security.entropyThreshold()).thenReturn(3.5);
    when(security.skippedFiles()).thenReturn(SKIPPED);
    var configured = new SecurityScan(config);
    assertTrue(configured.enabled());
    assertTrue(
        configured.scan(List.of(added("pod.yaml", "  privileged: true"))).detections().isEmpty());
  }

  @Test
  void theIacHalfAloneEnablesTheScanAndScrubsNothing() {
    var s = scan(false, true);
    assertTrue(s.enabled());
    var model = modelFinding("src/App.java", 1, "t", "d");
    var merged = merge(s, response(model), List.of(added("pod.yaml", "  hostIPC: true")));
    assertSame(model, merged.findings().get(0));
  }

  @Test
  void contextLinesAreNotScannedForSecrets() {
    var patch = "@@ -1,1 +1,2 @@\n K=" + fake.googleApiKey() + "\n+X=1\n";
    var file = new FileDiff("a.env", "modified", 1, 0, 1, patch);
    assertTrue(merge(scan(true, false), response(), List.of(file)).findings().isEmpty());
  }

  @Test
  void aPrivateKeyBodyEndsAtTheFileOrAtAContextLine() {
    var toEnd = fake.pemBodyLine();
    var result =
        scan(true, false).scan(List.of(added("k.pem", FakeCredentials.pemHeader(""), toEnd)));
    assertEquals(toEnd, result.redactions().get(0).literal());

    var body = fake.pemBodyLine();
    var unchanged = fake.pemBodyLine();
    var patch =
        "@@ -1,1 +1,3 @@\n+"
            + FakeCredentials.pemHeader("EC")
            + "\n+"
            + body
            + "\n "
            + unchanged
            + "\n";
    var redactions =
        scan(true, false)
            .scan(List.of(new FileDiff("k.pem", "modified", 2, 0, 2, patch)))
            .redactions();
    assertEquals(List.of(body), redactions.stream().map(SecurityScan.Redaction::literal).toList());
  }

  @Test
  void anIacPriorIsTrackedOnlyByItsOwnAnchor() {
    var s = scan(false, true);
    var files = List.of(added("pod.yaml", "  hostNetwork: true", "  hostPID: true"));
    var previous = s.merge(response(), s.scan(files), List.of(), Set.of()).findings();
    assertEquals(2, previous.size(), "same title, different lines: two findings");

    // hostPID was removed; hostNetwork stays and a new hostIPC line joins under the same title.
    var next = List.of(added("pod.yaml", "  hostNetwork: true", "  hostIPC: true"));
    var merged = s.merge(response(), s.scan(next), previous, Set.of());
    assertEquals(
        List.of("hostIPC: true"),
        merged.findings().stream().map(ReviewResponse.Finding::suggestionOld).toList());
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "unresolved", stillDetectedNote()),
            new ReviewResponse.PreviousFindingStatus(2, "resolved", noLongerDetectedNote())),
        merged.previousFindingsStatus());
  }

  @Test
  void priorsWithNoFileOrAnotherTitleAreNotTracked() {
    var s = scan(true, false);
    var files = List.of(added("app.env", "T=" + fake.githubToken()));
    var raised = s.merge(response(), s.scan(files), List.of(), Set.of()).findings().get(0);
    var noFile =
        new ReviewResponse.Finding("critical", "high", null, 1, raised.title(), "d", null, null);
    var otherTitle =
        new ReviewResponse.Finding(
            "critical", "high", "app.env", 1, "Hardcoded token", "d", null, null);
    var otherFile =
        new ReviewResponse.Finding(
            "critical", "high", "other.env", 1, raised.title(), "d", null, null);
    var otherScanTitle =
        new ReviewResponse.Finding(
            "high", "high", "app.env", 1, SecurityRule.HOST_NAMESPACE.iacTitle(), "d", "x", null);
    var merged =
        s.merge(
            response(),
            s.scan(files),
            List.of(noFile, otherTitle, otherFile, otherScanTitle),
            Set.of());
    assertEquals(1, merged.findings().size());
    assertTrue(merged.previousFindingsStatus().isEmpty());
  }

  @Test
  void aModelTitleThatIsMissingOrOnlyPunctuationIsNotADuplicateByWords() {
    var key = fake.googleApiKey();
    var untitled = modelFinding("a.env", 1, null, "d");
    var punctuated = modelFinding("a.env", 1, "[nit]: rename K", "d");
    var merged =
        merge(
            scan(true, false), response(untitled, punctuated), List.of(added("a.env", "K=" + key)));
    assertEquals(3, merged.findings().size());
  }

  private static String stillDetectedNote() {
    return "Still detected by the deterministic security scan on an added line.";
  }

  private static String noLongerDetectedNote() {
    return "No longer detected by the deterministic security scan: the line was removed or"
        + " changed, or carries the allow marker.";
  }

  private static ReviewContextLoader.ReviewContext context(
      List<FileDiff> reviewable, List<ReviewResponse> priorNewestFirst) {
    return new ReviewContextLoader.ReviewContext(
        reviewable,
        "",
        "",
        0,
        List.of(),
        List.of(),
        priorNewestFirst,
        priorNewestFirst.isEmpty(),
        !priorNewestFirst.isEmpty(),
        null,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        reviewable,
        () -> new DiffLineResolver(Map.of()),
        null);
  }
}
