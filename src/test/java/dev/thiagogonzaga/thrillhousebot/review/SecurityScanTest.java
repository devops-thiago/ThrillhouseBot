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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.HashMap;
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

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

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
    assertSame(response, SecurityScan.disabled().merge(response, ctx, BOT));
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
    assertTrue(scan(true, false).merge(response(), withIgnored, BOT).findings().isEmpty());
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
  void theModelsOwnSecretFindingOnTheMatchedLineCollapsesIntoTheScans() throws Exception {
    // #932: round 10's model titles each shared one rule word with the scan, so both posted.
    var value = fake.genericSecret(40);
    var line = "val apiToken: String = \"" + value + "\"";
    for (var title :
        List.of(
            "CDN API token committed in source and shipped in every build",
            "Directory API token committed in source",
            "API token committed in source; the token plumbing it disables is dead")) {
      var model =
          new ReviewResponse.Finding(
              "critical", "high", "Main.kt", 2, title, "It reads `" + line + "`.", line, "x");
      var merged =
          merge(scan(true, false), response(model), List.of(added("Main.kt", "package pod", line)));
      assertEquals(1, merged.findings().size(), title);
      assertTrue(merged.findings().get(0).title().startsWith(SecurityRule.TITLE_PREFIX), title);
      assertEquals("high", merged.findings().get(0).risk(), "one thread, at the scan's grade");
      assertFalse(everySurface(merged).contains(value));
    }
  }

  @Test
  void aHyphenatedRuleWordCountsAsTheWord() {
    // "hard-coded" reads as "hardcoded": with "credential" that is two rule words, which is enough
    // within the line tolerance even off the matched line.
    var value = fake.genericSecret(40);
    var model =
        modelFinding(
            "config.go", 3, "Provider API credential is committed as a hard-coded default", "d");
    var merged =
        merge(
            scan(true, false),
            response(model),
            List.of(added("config.go", "package main", "var apiToken string = \"" + value + "\"")));
    assertEquals(1, merged.findings().size());
    assertEquals(Set.of("hard", "coded", "hardcoded", "a"), SecurityScan.words("a hard-coded"));
  }

  @Test
  void aDifferentDefectThatOnlyNamesTheKeyOnTheMatchedLineIsKept() {
    var value = fake.genericSecret(40);
    var named =
        modelFinding(
            "config.py",
            1,
            "PAYMENT_API_KEY is never read from the environment, so the documented key is dead",
            "d");
    var merged =
        merge(
            scan(true, false),
            response(named),
            List.of(added("config.py", "PAYMENT_API_KEY = \"" + value + "\"")));
    assertEquals(1, merged.findings().size(), "one rule word in the prose is enough on the line");

    var identifierOnly =
        modelFinding("config.py", 1, "PAYMENT_API_KEY is never read from the environment", "d");
    var kept =
        merge(
            scan(true, false),
            response(identifierOnly),
            List.of(added("config.py", "PAYMENT_API_KEY = \"" + value + "\"")));
    assertEquals(2, kept.findings().size(), "an identifier the title names is not a rule word");
  }

  @Test
  void oneRuleWordOnAnIacLineIsNotEnough() {
    var sameLine = modelFinding("pod.yaml", 2, "Host network namespace is shared", "d");
    var merged =
        merge(
            scan(false, true),
            response(modelFinding("pod.yaml", 2, "Pod network is flat", "d")),
            List.of(added("pod.yaml", "spec:", "  hostNetwork: true")));
    assertEquals(2, merged.findings().size(), "network is not a host-namespace defect word");
    var twoWords =
        merge(
            scan(false, true),
            response(sameLine),
            List.of(added("pod.yaml", "spec:", "  hostNetwork: true")));
    assertEquals(1, twoWords.findings().size());
  }

  @Test
  void oneSharedRuleWordDoesNotMakeANearbyModelFindingADuplicate() {
    var nearby = modelFinding("pod.yaml", 2, "Add a network policy for egress", "d");
    var merged =
        merge(
            scan(false, true),
            response(nearby),
            List.of(added("pod.yaml", "spec:", "  hostPID: true")));
    assertEquals(2, merged.findings().size());
    assertSame(nearby, merged.findings().get(0));
  }

  // --- #921 / #952: deterministic IaC grade and its model duplicate ---

  @Test
  void aFinalStageRootUserPostsAtHighConfidenceWithNoHedge() throws Exception {
    var merged =
        merge(
            scan(false, true),
            response(),
            List.of(added("node/Dockerfile", "FROM node:20", "USER root")));
    assertEquals(1, merged.findings().size());
    var finding = merged.findings().get(0);
    assertEquals("medium", finding.risk(), "the README rule table's grade");
    assertEquals("high", finding.confidence());
    assertFalse(everySurface(merged).contains("verify before acting"));
  }

  @Test
  void theModelsRootFindingOnTheLineBelowCollapsesIntoTheScans() {
    // Round 12, ThrillhouseBot-test#158: the scan on USER root (line 16), the model on the
    // ENTRYPOINT line below it, titled with one rule word.
    var lines = new ArrayList<String>();
    for (int i = 1; i <= 15; i++) {
      lines.add("RUN step" + i);
    }
    lines.add("USER root");
    lines.add("ENTRYPOINT [\"node\", \"dist/bundle.js\"]");
    var model =
        modelFinding(
            "node/Dockerfile",
            17,
            "Container reverts to root just before the entrypoint",
            "The process starts as root.");
    var merged =
        merge(
            scan(false, true),
            response(model),
            List.of(added("node/Dockerfile", lines.toArray(String[]::new))));
    assertEquals(1, merged.findings().size());
    assertEquals(SecurityRule.ROOT_USER.iacTitle(), merged.findings().get(0).title());
    assertEquals(16, merged.findings().get(0).line());
  }

  @Test
  void eachIacRuleCollapsesAModelFindingNamingItsDefectNearby() {
    var cases =
        Map.of(
            "Container is privileged",
            added("pod.yaml", "spec:", "  privileged: true"),
            "Pod joins the hostPID namespace",
            added("pod.yaml", "spec:", "  hostPID: true"),
            "SSH reachable from anywhere",
            added(
                "sg.tf",
                "  ingress {",
                "    from_port = 22",
                "    to_port = 22",
                "    cidr_blocks = [\"0.0.0.0/0\"]",
                "  }"),
            "Bucket is public",
            added("s3.tf", "  acl = \"public-read\""),
            "Wildcard grants",
            added("p.json", "{", "  \"Action\": \"*\",", "  \"Resource\": \"*\"", "}"),
            "Volume left unencrypted",
            added("ebs.tf", "  encrypted = false"));
    for (var entry : cases.entrySet()) {
      var file = entry.getValue();
      var detections = scan(false, true).scan(List.of(file)).detections();
      assertEquals(1, detections.size(), entry.getKey());
      var at = detections.get(0).finding();
      var model = modelFinding(at.file(), at.line() + 1, entry.getKey(), "d");
      var merged = merge(scan(false, true), response(model), List.of(file));
      assertEquals(1, merged.findings().size(), entry.getKey());
      assertTrue(merged.findings().get(0).title().startsWith(SecurityRule.TITLE_PREFIX));
    }
  }

  @Test
  void aNeighbouringDefectThatSharesADefectWordIsKept() {
    var dockerfile = added("node/Dockerfile", "FROM node:20", "USER root", "ENTRYPOINT [\"node\"]");
    for (var title :
        List.of(
            "Root filesystem is not read-only",
            "Container root filesystem is writable",
            "/app is left root-owned")) {
      var merged =
          merge(
              scan(false, true),
              response(modelFinding("node/Dockerfile", 3, title, "d")),
              List.of(dockerfile));
      assertEquals(2, merged.findings().size(), title);
    }
    var bucket = added("s3.tf", "  acl = \"public-read\"", "  subnet_id = aws_subnet.a.id");
    var subnet =
        merge(
            scan(false, true),
            response(modelFinding("s3.tf", 2, "Public subnet assigns public IPs", "d")),
            List.of(bucket));
    assertEquals(2, subnet.findings().size());
  }

  @Test
  void aDefectWordBeyondTheLineToleranceIsADifferentFinding() {
    var lines = new ArrayList<>(List.of("FROM node:20", "USER root"));
    for (int i = 0; i < FindingDeduplicator.LINE_TOLERANCE + 1; i++) {
      lines.add("RUN step" + i);
    }
    var far =
        modelFinding(
            "Dockerfile",
            2 + FindingDeduplicator.LINE_TOLERANCE + 1,
            "Script writes to a root path",
            "d");
    var merged =
        merge(
            scan(false, true),
            response(far),
            List.of(
                new FileDiff(
                    "Dockerfile",
                    "added",
                    lines.size(),
                    0,
                    lines.size(),
                    "@@ -0,0 +1," + lines.size() + " @@\n+" + String.join("\n+", lines) + "\n")));
    assertEquals(2, merged.findings().size());
  }

  @Test
  void theCalendarTokenFindingFromRoundNineCollapsesIntoTheScans() throws Exception {
    // #927 item 4, ThrillhouseBot-test#109: a model CRITICAL and the scan HIGH on config.js:5.
    var value = fake.genericSecret(40);
    var line = "export const CALENDAR_API_TOKEN = \"" + value + "\";";
    var model =
        new ReviewResponse.Finding(
            "critical",
            "high",
            "node/src/config.js",
            5,
            "Committed calendar gateway API token in source",
            "It reads `" + line + "`.",
            line,
            null);
    var merged =
        merge(
            scan(true, false),
            response(model),
            List.of(
                added(
                    "node/src/config.js",
                    "// config",
                    "import x from 'y';",
                    "",
                    "// Shared secret used to auth",
                    line)));
    assertEquals(1, merged.findings().size());
    assertTrue(merged.findings().get(0).title().startsWith(SecurityRule.TITLE_PREFIX));
    assertFalse(everySurface(merged).contains(value));
  }

  // --- #1072: the scanned secret raised again by name, away from its line ---

  /** A file the pull request adds, {@code length} lines long, blank except the ones given. */
  private static FileDiff addedWith(String name, int length, Map<Integer, String> lines) {
    var all = new String[length];
    for (int i = 0; i < length; i++) {
      all[i] = lines.getOrDefault(i + 1, "");
    }
    return added(name, all);
  }

  private static void assertOnlyTheScansFinding(ReviewResponse merged, String value)
      throws Exception {
    assertEquals(1, merged.findings().size(), () -> merged.findings().toString());
    assertTrue(merged.findings().get(0).title().startsWith(SecurityRule.TITLE_PREFIX));
    assertEquals("high", merged.findings().get(0).risk(), "the scan's grade, not the model's");
    assertFalse(everySurface(merged).contains(value));
  }

  @Test
  void theJavaFallbackUseSiteFindingCollapsesIntoTheScans() throws Exception {
    // ThrillhouseBot-test#245: the scan at WaitlistConfig.java:12, the model CRITICAL at :20.
    var value = fake.genericSecret(40);
    var file = "java/src/main/java/com/clinic/waitlist/WaitlistConfig.java";
    var declaration = "  private static final String SCHEDULER_API_TOKEN = \"" + value + "\";";
    var use = "        env.getOrDefault(\"SCHEDULER_API_TOKEN\", SCHEDULER_API_TOKEN),";
    var model =
        new ReviewResponse.Finding(
            "critical",
            "high",
            file,
            20,
            "Scheduler API token committed as a source constant and used as default",
            "Line 12 reads '"
                + declaration.strip()
                + "' and line 20 installs it as the fallback: '"
                + use.strip()
                + "'. The token must be rotated and removed from source.",
            use,
            "        Objects.requireNonNull(env.get(\"SCHEDULER_API_TOKEN\")),");
    var merged =
        merge(
            scan(true, false),
            response(model),
            List.of(addedWith(file, 24, Map.of(12, declaration, 20, use))));
    assertOnlyTheScansFinding(merged, value);
  }

  @Test
  void theKotlinFallbackUseSiteFindingCollapsesIntoTheScans() throws Exception {
    // ThrillhouseBot-test#252: the scan at Config.kt:39, the model CRITICAL at :28, above it.
    var value = fake.genericSecret(40);
    var file = "kotlin/src/main/kotlin/newsletter/billing/Config.kt";
    var declaration = "val apiToken: String = \"" + value + "\"";
    var use = "                paymentToken = env[\"PAYMENT_API_TOKEN\"] ?: apiToken,";
    var model =
        new ReviewResponse.Finding(
            "critical",
            "high",
            file,
            28,
            "Payment gateway API token committed in source as a hardcoded fallback",
            "The file commits the literal bearer token used to charge subscribers: '"
                + declaration
                + "', used by 'paymentToken = env[\"PAYMENT_API_TOKEN\"] ?: apiToken'.",
            use,
            "                paymentToken = env[\"PAYMENT_API_TOKEN\"] ?: error(\"unset\"),");
    var merged =
        merge(
            scan(true, false),
            response(model),
            List.of(
                addedWith(
                    file,
                    39,
                    Map.of(
                        28,
                        use,
                        38,
                        "// Fallback used by deployments that do not inject PAYMENT_API_TOKEN.",
                        39,
                        declaration))));
    assertOnlyTheScansFinding(merged, value);
  }

  /** ThrillhouseBot-test#246: the C# client and the doc row that contradicts its fallback. */
  private FileDiff[] csharpCatalog(String value) {
    return new FileDiff[] {
      addedWith(
          "csharp/HoldsService/CatalogClient.cs",
          53,
          Map.of(
              14,
              "    // Used when HOLDS_CATALOG_TOKEN is not set, so local runs can reach the catalog.",
              15,
              "    private const string CatalogApiToken = \"" + value + "\";",
              25,
              "            new AuthenticationHeaderValue(\"Bearer\", options.CatalogToken ??"
                  + " CatalogApiToken);")),
      addedWith(
          "csharp/docs/CONFIG-CSHARP.md",
          20,
          Map.of(
              11,
              "| `HOLDS_CATALOG_TOKEN` | none | Bearer token sent to the branch catalog API. |"))
    };
  }

  @Test
  void theCsharpUseSiteAndDocFindingsCollapseIntoTheScans() throws Exception {
    // ThrillhouseBot-test#246: the scan at CatalogClient.cs:15, the model CRITICAL at :25 and a
    // MEDIUM on the doc row, all three about the one fallback token.
    var value = fake.genericSecret(40);
    var useSite =
        new ReviewResponse.Finding(
            "critical",
            "high",
            "csharp/HoldsService/CatalogClient.cs",
            25,
            "Hardcoded catalog API token committed to the repository",
            "A live-looking bearer credential is committed in source: private const string"
                + " CatalogApiToken = \""
                + value
                + "\"; and it is used at line 25 (options.CatalogToken ?? CatalogApiToken).",
            null,
            null);
    var docRow =
        new ReviewResponse.Finding(
            "medium",
            "medium",
            "csharp/docs/CONFIG-CSHARP.md",
            11,
            "Documented default of 'none' for HOLDS_CATALOG_TOKEN contradicts the code",
            "The configuration table states the default is none, but CatalogClient.cs line 25"
                + " falls back to a constant when the variable is unset: 'options.CatalogToken ??"
                + " CatalogApiToken'. An operator who leaves the key unset is silently sending a"
                + " hardcoded token to the catalog.",
            null,
            null);
    var merged = merge(scan(true, false), response(useSite, docRow), List.of(csharpCatalog(value)));
    assertOnlyTheScansFinding(merged, value);
  }

  @Test
  void aNonSecretFindingOnTheUseSiteLineIsKept() {
    var value = fake.genericSecret(40);
    var timeout =
        modelFinding(
            "csharp/HoldsService/CatalogClient.cs",
            25,
            "No timeout on the catalog HTTP client",
            "The client built around `options.CatalogToken ?? CatalogApiToken` never sets a"
                + " timeout, so a stalled catalog hangs the expiry worker.");
    var merged = merge(scan(true, false), response(timeout), List.of(csharpCatalog(value)));
    assertEquals(2, merged.findings().size());
    assertEquals(timeout.title(), merged.findings().get(0).title());
  }

  @Test
  void aSecretFindingAboutADifferentIdentifierInTheSameFileIsKept() {
    var value = fake.genericSecret(40);
    var file = "kotlin/src/main/kotlin/newsletter/billing/Config.kt";
    var files =
        List.of(
            addedWith(
                file,
                39,
                Map.of(
                    20,
                    "    val dbPassword = \"changeme\"",
                    39,
                    "val apiToken: String = \"" + value + "\"")));
    var otherKey =
        modelFinding(
            file,
            20,
            "Database password hardcoded in dbPassword",
            "The `dbPassword` default is committed in source.");
    var otherKeyNamingBoth =
        modelFinding(
            file,
            20,
            "Hardcoded dbPassword default ships to production",
            "Like apiToken further down, `dbPassword` is a committed credential.");
    var merged = merge(scan(true, false), response(otherKey, otherKeyNamingBoth), files);
    assertEquals(3, merged.findings().size(), () -> merged.findings().toString());

    var namesBoth =
        modelFinding(
            file,
            20,
            "Hardcoded credentials: apiToken and dbPassword are committed",
            "Both defaults are committed in source.");
    assertEquals(
        2,
        merge(scan(true, false), response(namesBoth), files).findings().size(),
        "a title that also names another key carries a defect the scan did not raise");
  }

  @Test
  void aDocFindingNamingTheIdentifierWithoutACredentialClaimIsKept() {
    var value = fake.genericSecret(40);
    var naming =
        modelFinding(
            "csharp/docs/CONFIG-CSHARP.md",
            11,
            "Configuration table names the wrong property",
            "The row describes `HOLDS_CATALOG_TOKEN`, but the code reads `private const string"
                + " CatalogApiToken` only through `options.CatalogToken`.");
    var merged = merge(scan(true, false), response(naming), List.of(csharpCatalog(value)));
    assertEquals(2, merged.findings().size(), "a declaration's 'private' is no secret claim");
  }

  @Test
  void aSourceFileElsewhereOrADocNotNamingTheIdentifierIsKept() {
    var value = fake.genericSecret(40);
    var files = new ArrayList<>(List.of(csharpCatalog(value)));
    files.add(
        addedWith(
            "csharp/HoldsService/HoldsOptions.cs",
            21,
            Map.of(19, "        CatalogToken = env(\"HOLDS_CATALOG_TOKEN\"),")));
    var otherSource =
        modelFinding(
            "csharp/HoldsService/HoldsOptions.cs",
            19,
            "Catalog token is optional and silently replaced by a hardcoded value",
            "When unset, CatalogClient falls back to CatalogApiToken.");
    var docWithoutIdentifier =
        modelFinding(
            "csharp/docs/CONFIG-CSHARP.md",
            11,
            "Token default documented as none",
            "The documented default token is wrong.");
    var merged = merge(scan(true, false), response(otherSource, docWithoutIdentifier), files);
    assertEquals(3, merged.findings().size());
  }

  @Test
  void aConfigFileFindingNamingTheIdentifierCollapsesButTheSecretsOwnFileNeedsATitleClaim() {
    var value = fake.genericSecret(40);
    var source =
        addedWith("src/Client.kt", 30, Map.of(3, "val apiToken: String = \"" + value + "\""));
    var chartValues =
        modelFinding(
            "deploy/values.yaml",
            4,
            "Chart values do not override the client's fallback",
            "Nothing here sets the token, so the pod runs with the hardcoded apiToken.");
    assertEquals(
        1,
        merge(scan(true, false), response(chartValues), List.of(source)).findings().size(),
        "a configuration file is read like a doc file");

    var config = addedWith("config/app.yml", 30, Map.of(2, "  api_token: \"" + value + "\""));
    var ownFile =
        modelFinding(
            "config/app.yml",
            30,
            "Settings block is read before the profile",
            "The hardcoded token in api_token is read before the profile overrides it.");
    assertEquals(
        2,
        merge(scan(true, false), response(ownFile), List.of(config)).findings().size(),
        "in the secret's own file the claim must be in the title");

    var otherName =
        modelFinding(
            "src/Client.kt",
            30,
            "Hardcoded token flows into fromEnv",
            "The apiToken literal is the default fromEnv returns.");
    assertEquals(
        1,
        merge(scan(true, false), response(otherName), List.of(source)).findings().size(),
        "a non-credential identifier in the title is no other key");

    var noFile =
        new ReviewResponse.Finding("high", "high", null, 0, "Hardcoded apiToken", "d", null, null);
    assertEquals(2, merge(scan(true, false), response(noFile), List.of(source)).findings().size());
  }

  @Test
  void aDocFindingAboutAnotherKeyThatMentionsTheIdentifierIsKept() {
    // Review of #1072: a doc row about DB_PASSWORD that names the scanned constant in passing is
    // its own defect, not the scan's.
    var value = fake.genericSecret(40);
    var otherKey =
        modelFinding(
            "csharp/docs/CONFIG-CSHARP.md",
            12,
            "Documented default of 'none' for DB_PASSWORD contradicts the code",
            "The code falls back to a hardcoded password; the token fallback CatalogApiToken is"
                + " already covered.");
    var merged = merge(scan(true, false), response(otherKey), List.of(csharpCatalog(value)));
    assertEquals(2, merged.findings().size());
  }

  @Test
  void aDocFindingNamingOnlyTheEnvironmentKeyCollapsesWhenItsTitleMakesTheClaim() {
    // #1072: "names the same identifier or its env key". A doc row is where the key is
    // documented, so naming it alone is the scanned secret only when the title says so.
    var value = fake.genericSecret(40);
    var claimed =
        modelFinding(
            "csharp/docs/CONFIG-CSHARP.md",
            11,
            "HOLDS_CATALOG_TOKEN silently falls back to a hardcoded token",
            "The row says the default is none for `HOLDS_CATALOG_TOKEN`.");
    assertEquals(
        1,
        merge(scan(true, false), response(claimed), List.of(csharpCatalog(value)))
            .findings()
            .size());

    var docOnly =
        modelFinding(
            "csharp/docs/CONFIG-CSHARP.md",
            11,
            "HOLDS_CATALOG_TOKEN is documented as optional",
            "The service needs the token in production, but the row marks `HOLDS_CATALOG_TOKEN`"
                + " optional.");
    assertEquals(
        2,
        merge(scan(true, false), response(docOnly), List.of(csharpCatalog(value)))
            .findings()
            .size());
  }

  @Test
  void aTitleNamingTheEnvironmentKeyTheConstantStandsInForIsTheSameSecret() {
    var value = fake.genericSecret(40);
    var file = "kotlin/src/main/kotlin/newsletter/billing/Config.kt";
    var use = "                paymentToken = env[\"PAYMENT_API_TOKEN\"] ?: apiToken,";
    var model =
        modelFinding(
            file,
            28,
            "PAYMENT_API_TOKEN falls back to a hardcoded token",
            "When the variable is unset, `apiToken` is used.");
    var merged =
        merge(
            scan(true, false),
            response(model),
            List.of(addedWith(file, 39, Map.of(28, use, 39, "val apiToken = \"" + value + "\""))));
    assertEquals(1, merged.findings().size(), () -> merged.findings().toString());
  }

  /** The aliases of the one identified secret a scan of {@code files} finds. */
  private static Set<String> aliasesOf(FileDiff... files) {
    return scan(true, false).scan(List.of(files)).detections().stream()
        .filter(detection -> detection.identifier() != null)
        .findFirst()
        .orElseThrow()
        .aliases();
  }

  @Test
  void theAliasesAreTheEnvironmentKeyAboveTheSecretAndOnItsUseSites() {
    var value = fake.genericSecret(40);
    assertEquals(
        Set.of("CatalogApiToken", "HOLDS_CATALOG_TOKEN"), aliasesOf(csharpCatalog(value)[0]));
    assertEquals(
        Set.of("apiToken", "PAYMENT_API_TOKEN"),
        aliasesOf(
            added(
                "Config.kt",
                "paymentToken = env[\"PAYMENT_API_TOKEN\"] ?: apiToken,",
                "region = env[\"BILLING_REGION\"] ?: apiToken.take(2),",
                "val apiToken = \"" + value + "\"")));

    // The last line of an earlier hunk is not the line above it in the file.
    var twoHunks =
        new FileDiff(
            "Config.kt",
            "modified",
            1,
            0,
            2,
            "@@ -1,1 +1,1 @@\n // Used when OTHER_API_TOKEN is not set\n"
                + "@@ -9,0 +9,1 @@\n+val apiToken = \""
                + value
                + "\"\n");
    assertEquals(Set.of("apiToken"), aliasesOf(twoHunks));

    // A removed line is not in the new file (PatchLines leaves it out), so neither as a use site
    // nor as the line above does it tie its key to the secret.
    var replaced =
        new FileDiff(
            "Config.kt",
            "modified",
            1,
            1,
            2,
            "@@ -1,1 +1,1 @@\n-legacy = env[\"LEGACY_PAYMENTS_KEY\"] ?: apiToken\n"
                + "+val apiToken = \""
                + value
                + "\"\n");
    assertEquals(Set.of("apiToken"), aliasesOf(replaced));
  }

  @Test
  void aLinePassingAnotherCredentialTiesNothingToTheSecret() {
    // Review of #1072: a use site that also passes another credential, or two environment keys,
    // does not make the other key an alias, so a finding about it is kept.
    var value = fake.genericSecret(40);
    var file = "kotlin/src/main/kotlin/newsletter/billing/Config.kt";
    var files =
        List.of(
            addedWith(
                file,
                39,
                Map.of(
                    20,
                    "    val dbPassword = \"changeme\"",
                    24,
                    "    val client = connect(dbPassword, apiToken)",
                    25,
                    "    val both = env[\"DB_PASSWORD\"] ?: env[\"PAYMENT_API_TOKEN\"] ?: apiToken",
                    39,
                    "val apiToken: String = \"" + value + "\"")));
    assertEquals(Set.of("apiToken"), aliasesOf(files.get(0)));
    var namesBoth =
        modelFinding(
            file,
            20,
            "Hardcoded credentials: apiToken and dbPassword are committed",
            "Both defaults are committed in source.");
    assertEquals(2, merge(scan(true, false), response(namesBoth), files).findings().size());
  }

  @Test
  void anEnvironmentFallbackInAnotherArgumentIsNotAnAliasOfTheScannedSecret() {
    var value = fake.genericSecret(40);
    var model =
        modelFinding(
            "docs/CONFIG.md",
            1,
            "Hardcoded password fallback for DB_PASSWORD",
            "DB_PASSWORD has a separate fallback; the apiToken leak is already covered.");
    for (var use :
        List.of(
            "connect(env[\"DB_PASSWORD\"] ?: dbPassword, apiToken)",
            "connect(env[\"DB_PASSWORD\"] ?: otherValue, apiToken)",
            "connect(env.getOrDefault(\"DB_PASSWORD\", dbPassword), apiToken)",
            "connect(Environment.GetEnvironmentVariable(\"DB_PASSWORD\") ?? dbPassword, apiToken)")) {
      for (var file :
          List.of(
              added("Config.kt", "val apiToken = \"" + value + "\"", use),
              added("Config.kt", use, "val apiToken = \"" + value + "\""))) {
        assertEquals(Set.of("apiToken"), aliasesOf(file), use);
        assertEquals(
            2, merge(scan(true, false), response(model), List.of(file)).findings().size(), use);
      }
    }
  }

  @Test
  void onlySimpleFallbackExpressionsBecomeEnvironmentAliases() {
    var value = fake.genericSecret(40);
    for (var use :
        List.of(
            "env[\"PAYMENT_API_TOKEN\"] ?: (apiToken)",
            "env[\"PAYMENT_API_TOKEN\"] || Config.apiToken",
            "env.getOrDefault(\"PAYMENT_API_TOKEN\", Config.apiToken)",
            "env.get(\"PAYMENT_API_TOKEN\", ((apiToken)))",
            "os.getenv(\"PAYMENT_API_TOKEN\") or apiToken",
            "connect(\"RUN_MODE\", env[\"PAYMENT_API_TOKEN\"] ?: apiToken)",
            "env.getOrDefault ( \"PAYMENT_API_TOKEN\" , apiToken)")) {
      assertEquals(
          Set.of("apiToken", "PAYMENT_API_TOKEN"),
          aliasesOf(added("Config.kt", "val apiToken = \"" + value + "\"", use)),
          use);
    }
    for (var use :
        List.of(
            "env[\"PAYMENT_API_TOKEN\"] ?: apiToken.take(2)",
            "env[\"PAYMENT_API_TOKEN\"] ?: apiToken + suffix",
            "env[\"PAYMENT_API_TOKEN\"] ?: ()",
            "env[\"PAYMENT_API_TOKEN\"] ?: (apiToken",
            "env[\"PAYMENT_API_TOKEN\"] ?: (apiToken) + suffix",
            "env[\"PAYMENT_API_TOKEN\"] ?: (apiToken).take(2)",
            "env[\"PAYMENT_API_TOKEN\"] ?: Config.",
            "unknown(\"PAYMENT_API_TOKEN\", apiToken)",
            "env[\"PAYMENT_API_TOKEN\"]",
            "\"PAYMENT_API_TOKEN\"",
            "\"PAYMENT_API_TOKEN\", apiToken",
            "\"PAYMENT_API_TOKEN\" ?: apiToken",
            "\"PAYMENT_API_TOKEN\" || apiToken",
            "prefix \"PAYMENT_API_TOKEN\", apiToken",
            "env[\"PAYMENT_API_TOKEN\"] ?: ",
            "env[\"PAYMENT_API_TOKEN\"] ?: (",
            "env[\"PAYMENT_API_TOKEN\"] ?: (apiToken + suffix)")) {
      assertEquals(
          Set.of("apiToken"),
          aliasesOf(added("Config.kt", "val apiToken = \"" + value + "\"", use)),
          use);
    }
  }

  @Test
  void commentAliasesRemainSupportedWithoutBorrowingAnAdjacentCodeKey() {
    var value = fake.genericSecret(40);
    for (var comment : List.of("//", "#", "/*", "*")) {
      assertEquals(
          Set.of("apiToken", "PAYMENT_API_TOKEN"),
          aliasesOf(
              added(
                  "Config.kt",
                  comment + " Used when PAYMENT_API_TOKEN is unset",
                  "val apiToken = \"" + value + "\"")));
    }
  }

  @Test
  void namedSecretDuplicatesKeepThePathAndOtherCredentialGuards() {
    var value = fake.genericSecret(40);
    var file =
        addedWith(
            "service/src/Config.kt",
            40,
            Map.of(
                1,
                "payment = env[\"PAYMENT_API_TOKEN\"] ?: apiToken",
                40,
                "val apiToken = \"" + value + "\""));
    var other =
        modelFinding(
            "service/src/Config.kt",
            10,
            "Hardcoded DB_PASSWORD credential",
            "apiToken and PAYMENT_API_TOKEN are separate from DB_PASSWORD.");
    var near =
        modelFinding("src/Config.kt", 38, "Hardcoded apiToken", "The apiToken literal leaks.");
    var independent =
        modelFinding(
            "other/src/Config.kt", 10, "Hardcoded apiToken", "This file's apiToken literal leaks.");
    var merged = merge(scan(true, false), response(other, near, independent), List.of(file));
    assertEquals(3, merged.findings().size());
    assertTrue(merged.findings().stream().anyMatch(f -> f.title().equals(other.title())));
    assertTrue(merged.findings().stream().anyMatch(f -> f.title().equals(independent.title())));
    assertTrue(
        merged.findings().stream()
            .noneMatch(f -> f.file().equals(near.file()) && f.line() == near.line()));
  }

  @Test
  void aDocFindingIsLookedUpByItsWordsNotComparedWithEverySecret() {
    // Review of #1072: the doc lane must not walk every identified secret for every doc finding.
    var files = new ArrayList<FileDiff>();
    var models = new ArrayList<ReviewResponse.Finding>();
    for (int i = 0; i < 50; i++) {
      files.add(
          added(
              "svc" + i + "/Config.kt",
              "val apiToken" + i + " = \"" + fake.genericSecret(40) + "\""));
      models.add(
          modelFinding(
              "docs/svc" + i + ".md",
              1,
              "Token documented without rotation",
              "The hardcoded token is documented as permanent."));
    }
    var s = scan(true, false);
    var result = s.scan(files);
    long before = SecurityScan.IDENTITY_COMPARISONS.get();
    var merged = s.merge(new ReviewResponse(models, List.of(), null), result, List.of(), Set.of());
    // One earlier-round lookup per detection and none for the doc findings, which name no
    // identifier, where a walk over every secret for every finding makes 2,500.
    assertEquals(50L, SecurityScan.IDENTITY_COMPARISONS.get() - before);
    assertEquals(100, merged.findings().size());
  }

  @Test
  void aBareCredentialWordKeyIsMatchedOnlyWithinTheLineTolerance() {
    var value = fake.genericSecret(40);
    var far =
        modelFinding(
            "app.py",
            20,
            "Password compared with == is not constant-time",
            "`if supplied == password:` leaks timing.");
    var merged =
        merge(
            scan(true, false),
            response(far),
            List.of(addedWith("app.py", 20, Map.of(1, "password = \"" + value + "\""))));
    assertEquals(2, merged.findings().size(), "'password' is a word, not a name for the secret");
  }

  @Test
  void anIdentifierIsTheLastSegmentOfADistinctiveKey() {
    var value = fake.genericSecret(40);
    assertEquals(
        "apiToken",
        SecurityScan.identifierOf(
            new SecretScanner.Hit(SecurityRule.GENERIC_SECRET, value, "config.apiToken")));
    assertEquals(
        "SCHEDULER_API_TOKEN",
        SecurityScan.identifierOf(
            new SecretScanner.Hit(SecurityRule.GENERIC_SECRET, value, "SCHEDULER_API_TOKEN")));
    assertNull(
        SecurityScan.identifierOf(
            new SecretScanner.Hit(SecurityRule.GENERIC_SECRET, value, "password")));
    assertNull(
        SecurityScan.identifierOf(new SecretScanner.Hit(SecurityRule.GENERIC_SECRET, value, null)));
    assertNull(
        SecurityScan.identifierOf(
            new SecretScanner.Hit(SecurityRule.AWS_ACCESS_KEY, value, "AWS_KEY")));
    assertTrue(SecurityScan.names("uses apiToken.", "apiToken"));
    assertTrue(SecurityScan.names("apiToken", "apiToken"));
    assertFalse(SecurityScan.names("uses paymentApiToken", "apiToken"));
    assertFalse(SecurityScan.names("uses x_apiToken", "apiToken"));
    assertTrue(SecurityScan.names("x_apiToken and apiToken", "apiToken"));
    assertFalse(SecurityScan.names("uses apiTokens", "apiToken"));
    assertFalse(SecurityScan.names(null, "apiToken"));
  }

  // --- #926: a model secret finding on an interpolation or a placeholder ---

  private static DiffLineResolver resolver(FileDiff... files) {
    var patches = new HashMap<String, String>();
    for (var file : files) {
      patches.put(file.filename(), file.patch());
    }
    return new DiffLineResolver(patches);
  }

  @Test
  void aModelSecretFindingOnAnInterpolatedValueIsDropped() {
    // Round 12: the ARG/ENV decoy on the go and csharp pull requests.
    var go =
        added(
            "go/Dockerfile",
            "FROM alpine:3.20",
            "ARG FUELRECON_API_TOKEN",
            "ENV FUELRECON_API_TOKEN=${FUELRECON_API_TOKEN}");
    var csharp =
        added(
            "csharp/Dockerfile",
            "WORKDIR /app",
            "ARG PERMIT_API_TOKEN",
            "ENV PERMIT_API_TOKEN=${PERMIT_API_TOKEN}");
    var response =
        response(
            modelFinding(
                "go/Dockerfile",
                3,
                "API token passed as build arg is persisted in the image config via ENV",
                "d"),
            modelFinding(
                "csharp/Dockerfile",
                3,
                "Registry token supplied as a build argument is persisted into the image ENV",
                "d"));
    for (var scan : List.of(SecurityScan.disabled(), scan(true, true))) {
      assertTrue(scan.withoutStandInSecrets(response, resolver(go, csharp)).findings().isEmpty());
    }
  }

  @Test
  void everyInterpolationAndPlaceholderSpellingIsAStandIn() {
    for (var line :
        List.of(
            "ENV API_TOKEN=${API_TOKEN}",
            "ENV API_TOKEN=${API_TOKEN:-}",
            "export API_TOKEN=$API_TOKEN",
            "set DB_PASSWORD=%DB_PASSWORD%",
            "  password: \"{{ .Values.dbPassword }}\"",
            "API_TOKEN=$(cat /run/secrets/token)",
            "FLEET_API_TOKEN=changeme",
            "client_secret: \"<your-client-secret>\"")) {
      var file = added("conf/app.env", line);
      var finding = modelFinding("conf/app.env", 1, "Hardcoded secret in config", "d");
      assertTrue(
          scan(true, false)
              .withoutStandInSecrets(response(finding), resolver(file))
              .findings()
              .isEmpty(),
          line);
    }
  }

  @Test
  void aStandInLineKeepsWhatIsNotASecretClaimOrNotAStandIn() {
    var docs =
        added(
            "docs/CONFIG-C.md",
            "ELEVATOR_LOG_PATH=/data/maintenance.csv",
            "FLEET_API_TOKEN=changeme");
    // Round 12's c finding: an unread config key, not a secret claim.
    var unread =
        modelFinding(
            "docs/CONFIG-C.md",
            2,
            "Example environment file documents FLEET_API_TOKEN, which no code reads",
            "d");
    var offDiff = modelFinding("docs/CONFIG-C.md", 40, "Hardcoded API token", "d");
    var noFile = modelFinding(null, 0, "Hardcoded API token", "d");
    var literal = fake.genericSecret(32);
    var real = added("app.env", "API_TOKEN=${API_TOKEN}", "DB_PASSWORD=\"" + literal + "\"");
    var mixed = added("mixed.env", "API_TOKEN=${API_TOKEN} DB_PASSWORD=\"" + literal + "\"");
    var noAssignment = added("notes.md", "Rotate the API token every quarter.");
    var response =
        response(
            unread,
            offDiff,
            noFile,
            modelFinding("app.env", 2, "Hardcoded database password", "d"),
            modelFinding("mixed.env", 1, "Hardcoded database password", "d"),
            modelFinding("notes.md", 1, "Token rotation policy is undocumented", "d"));
    var kept =
        scan(true, false)
            .withoutStandInSecrets(response, resolver(docs, real, mixed, noAssignment));
    assertSame(response, kept);
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
  void aModelFindingQuotingTheRoundNineCLinePostsOnlyTheRedactedForm() throws Exception {
    var value = fake.genericSecret(40);
    var line = "static const char API_TOKEN[] = \"" + value + "\";";
    var redacted = "[redacted: " + value.substring(0, 4) + "…, 40 chars]";
    var quoting =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "src/client.c",
            2,
            "Header is included twice",
            "Line 2 reads `" + line + "` and sits between two includes of the same header.",
            line,
            "#include \"client.h\"\n" + line);
    var merged =
        merge(
            scan(true, false),
            response(quoting),
            List.of(added("src/client.c", "#include \"client.h\"", line)));

    assertEquals(2, merged.findings().size());
    var scanFinding = merged.findings().get(1);
    assertEquals(
        "Security scan: hardcoded credential in API_TOKEN ("
            + value.substring(0, 4)
            + "…, 40 chars)",
        scanFinding.title());
    var kept = merged.findings().get(0);
    assertEquals("static const char API_TOKEN[] = \"" + redacted + "\";", kept.suggestionOld());
    assertTrue(kept.description().contains(redacted));
    assertTrue(kept.suggestionNew().endsWith(redacted + "\";"));
    assertFalse(everySurface(merged).contains(value));
    assertFalse(everySurface(merged).contains(value.substring(4)));
  }

  @Test
  void aValueTheScanMissedIsStillNotEchoedByAModelFinding() throws Exception {
    var value = fake.genericSecret(40);
    // A declaration form the scan does not read: no detection, so no matched value to scrub.
    var line = "pub static API_TOKEN: Lazy<&str> = Lazy::new(|| \"" + value + "\");";
    var quoting = modelFinding("src/lib.rs", 1, "Hardcoded token", "Remove `" + line + "`.");
    var status = new ReviewResponse.PreviousFindingStatus(1, "unresolved", "Still `" + line + "`");
    var files = List.of(added("src/lib.rs", line));

    var merged =
        scan(true, false)
            .merge(
                new ReviewResponse(List.of(quoting), List.of(status), null),
                scan(true, false).scan(files),
                List.of(),
                Set.of());
    assertEquals(1, merged.findings().size());
    assertFalse(everySurface(merged).contains(value));
    assertTrue(merged.previousFindingsStatus().get(0).note().contains("[redacted: "));

    // The IaC half alone does not touch model text.
    var iacOnly = scan(false, true);
    var untouched = iacOnly.merge(response(quoting), iacOnly.scan(files), List.of(), Set.of());
    assertSame(quoting, untouched.findings().get(0));
    assertNull(SecurityScan.Scrubber.of(List.of(), 3.5).scrub(null));
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
  void aFindingAnEarlierRoundRaisedAndLeftOpenIsNotRaisedAgain() {
    // #939: round two raised one new model finding, so it is the effective previous round and
    // round one's scan findings have no id in it; the scan must still not post them twice.
    var token = fake.githubToken();
    var files =
        List.of(
            added("rust/src/client.rs", "const T: &str = \"" + token + "\";"),
            added("rust/Dockerfile", "FROM rust:1.80", "USER root"));
    var s = scan(true, true);
    var roundOne = s.merge(response(), s.scan(files), List.of(), Set.of());
    assertEquals(2, roundOne.findings().size());
    var roundTwo =
        new ReviewResponse(
            List.of(modelFinding("rust/src/main.rs", 3, "Poller never started", "unwired")),
            List.of(),
            null);

    var merged =
        s.merge(
            response(),
            s.scan(files),
            roundTwo.findings(),
            Set.of(),
            List.copyOf(roundOne.findings()));

    assertTrue(merged.findings().isEmpty(), "no second thread: " + merged.findings());
    assertTrue(merged.previousFindingsStatus().isEmpty(), "they have no id in round two");

    // The same merge driven from a review's context reads the earlier rounds and their threads.
    var threads = new java.util.ArrayList<GitHubReviewClient.PullRequestComment>();
    for (int i = 0; i < roundOne.findings().size(); i++) {
      var posted = roundOne.findings().get(i);
      threads.add(
          new GitHubReviewClient.PullRequestComment(
              100L + i,
              null,
              posted.file(),
              new SuggestionFormatter()
                  .formatReviewComment(Finding.fromAiResponse(posted), true, i + 1),
              new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    }
    var rounds = List.of(roundTwo, roundOne);
    assertTrue(s.merge(response(), context(files, rounds, threads), BOT).findings().isEmpty());

    // With no thread of their own (summary-only, or refused by GitHub) they are raised again: a
    // re-raise is the only way they can still get one.
    assertEquals(
        roundOne.findings().stream().map(ReviewResponse.Finding::title).toList(),
        s.merge(response(), context(files, rounds, List.of()), BOT).findings().stream()
            .map(ReviewResponse.Finding::title)
            .toList());
  }

  private static ReviewContextLoader.ReviewContext context(
      List<FileDiff> files,
      List<ReviewResponse> rounds,
      List<GitHubReviewClient.PullRequestComment> threads) {
    var patches = new HashMap<String, String>();
    files.forEach(file -> patches.put(file.filename(), file.patch()));
    return new ReviewContextLoader.ReviewContext(
        files,
        "",
        "",
        0,
        List.of(),
        List.of("{}", "{}"),
        rounds,
        false,
        true,
        "{}",
        threads,
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        files,
        () -> new DiffLineResolver(patches),
        null);
  }

  @Test
  void anEarlierRoundFindingOnADifferentAnchorDoesNotStopANewDetection() {
    var files = List.of(added("rust/Dockerfile", "FROM rust:1.80", "USER root"));
    var s = scan(false, true);
    var earlier = s.merge(response(), s.scan(files), List.of(), Set.of()).findings().get(0);
    var elsewhere =
        new ReviewResponse.Finding(
            earlier.risk(),
            earlier.confidence(),
            earlier.file(),
            earlier.line(),
            earlier.title(),
            earlier.description(),
            "USER admin",
            null);
    var otherFile =
        new ReviewResponse.Finding(
            earlier.risk(),
            earlier.confidence(),
            "go/Dockerfile",
            earlier.line(),
            earlier.title(),
            earlier.description(),
            earlier.suggestionOld(),
            null);

    var merged =
        s.merge(response(), s.scan(files), List.of(), Set.of(), List.of(elsewhere, otherFile));

    assertEquals(List.of(earlier), merged.findings());
  }

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
    var firstRound = s.merge(response(), context(files, List.of()), BOT);
    assertEquals(1, firstRound.findings().size());

    var secondRound = s.merge(response(), context(files, List.of(firstRound)), BOT);
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
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(github);
    when(github.webhookSecret()).thenReturn("configured-webhook-secret");
    var configured = new SecurityScan(config);
    assertTrue(configured.enabled());
    assertTrue(
        configured.scan(List.of(added("pod.yaml", "  privileged: true"))).detections().isEmpty());
    // #985: the fingerprint is keyed from the webhook secret.
    assertEquals(
        keyed("configured-webhook-secret").contentFingerprint(SecurityRule.JWT, "x"),
        configured.contentFingerprint(SecurityRule.JWT, "x"));
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
  void aPrivateKeyBodyStopsAtTheEndOfItsHunk() {
    var body = fake.pemBodyLine();
    var laterHunk = fake.pemBodyLine();
    var patch =
        String.join(
                "\n",
                "@@ -0,0 +1,2 @@",
                "+" + FakeCredentials.pemHeader("RSA"),
                "+" + body,
                "@@ -9,0 +11,1 @@",
                "+" + laterHunk)
            + "\n";
    var redactions =
        scan(true, false)
            .scan(List.of(new FileDiff("k.pem", "modified", 3, 0, 3, patch)))
            .redactions();
    assertEquals(List.of(body), redactions.stream().map(SecurityScan.Redaction::literal).toList());
  }

  @Test
  void aSummaryThatReachesTheMergeIsScrubbedToo() {
    var key = fake.googleApiKey();
    var summary =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            "Adds " + key,
            "Purpose " + key,
            List.of("gap " + key),
            List.of("security"),
            List.of(new ReviewResponse.FileSummary("a.env", "sets " + key)),
            "flowchart TD\n  A[" + key + "]");
    var merged =
        merge(
            scan(true, false),
            new ReviewResponse(List.of(), List.of(), summary),
            List.of(added("a.env", "K=" + key)));
    var scrubbed = merged.summary();
    assertEquals(1, scrubbed.totalFindings());
    assertEquals(1, scrubbed.critical());
    var text =
        String.join(
            "|",
            scrubbed.overallAssessment(),
            scrubbed.prPurpose(),
            scrubbed.descriptionGaps().get(0),
            scrubbed.fileSummaries().get(0).summary(),
            scrubbed.walkthroughDiagram());
    assertFalse(text.contains(key), text);
    assertEquals(List.of("security"), scrubbed.suggestedLabels());
    assertEquals("a.env", scrubbed.fileSummaries().get(0).path());
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

  // --- a standing decline (#982) ---

  private static ReviewResponse.Finding at(ReviewResponse.Finding finding, String file, int line) {
    return new ReviewResponse.Finding(
        finding.risk(),
        finding.confidence(),
        file,
        line,
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  private static ReviewResponse mergeAfterDecline(
      SecurityScan scan,
      ReviewResponse response,
      List<FileDiff> files,
      List<ReviewResponse.Finding> previous,
      List<ReviewResponse.Finding> declined) {
    // The head moved since the previous round: only what a decline carries can match.
    return scan.merge(
        response,
        scan.scan(files),
        previous,
        Set.of(),
        List.of(),
        new SecurityScan.Declines(declined, false));
  }

  @Test
  void aDeclinedSecretIsNotRaisedAgainUntilItsValueChanges() {
    var token = fake.githubToken();
    var s = scan(true, false);
    var declined =
        s.merge(
                response(),
                s.scan(List.of(added("app.env", "X=1", "T=" + token))),
                List.of(),
                Set.of())
            .findings();
    assertEquals(1, declined.size());
    assertTrue(declined.get(0).description().contains("<!-- thrillhousebot:scan-content="));

    var same =
        mergeAfterDecline(
            s, response(), List.of(added("app.env", "X=1", "T=" + token)), List.of(), declined);
    assertTrue(same.findings().isEmpty(), same.findings().toString());
    assertTrue(same.previousFindingsStatus().isEmpty());

    // The anchor is the content, not the line number: a push above it moves it, nothing more.
    var moved = List.of(added("app.env", "X=1", "Y=2", "T=" + token));
    assertTrue(mergeAfterDecline(s, response(), moved, List.of(), declined).findings().isEmpty());

    // Another token of the format has the same title — prefix and length are all it shows — but
    // it is not the value the maintainer declined.
    var rotated = List.of(added("app.env", "X=1", "T=" + fake.githubToken()));
    var raised = mergeAfterDecline(s, response(), rotated, List.of(), declined).findings();
    assertEquals(1, raised.size());
    assertEquals(declined.get(0).title(), raised.get(0).title());
  }

  @Test
  void aDeclineFromBeforeTheFingerprintCoversItsTitleAndAnchorOnlyOnAnUnchangedHead() {
    var token = fake.githubToken();
    var s = scan(true, false);
    var raised =
        s.merge(response(), s.scan(List.of(added("app.env", "T=" + token))), List.of(), Set.of())
            .findings()
            .get(0);
    var legacy =
        new ReviewResponse.Finding(
            raised.risk(),
            raised.confidence(),
            raised.file(),
            raised.line(),
            raised.title(),
            raised.description().substring(0, raised.description().indexOf("\n\n<!--")),
            null,
            null);
    assertNull(SecurityScan.fingerprintOf(legacy.description()));
    assertNull(SecurityScan.fingerprintOf(null));

    // On the head the round it reports on reviewed, nothing on the line can have changed since.
    var files = List.of(added("app.env", "T=" + token));
    assertTrue(
        s.merge(
                response(),
                s.scan(files),
                List.of(),
                Set.of(),
                List.of(),
                new SecurityScan.Declines(List.of(legacy), true))
            .findings()
            .isEmpty());

    // After a push it cannot tell a rotated value from the one declined, so it lapses: the finding
    // is raised again, now with the fingerprint a new decline keeps.
    var rotated = List.of(added("app.env", "T=" + fake.githubToken()));
    var raisedAgain =
        mergeAfterDecline(s, response(), rotated, List.of(), List.of(legacy)).findings();
    assertEquals(1, raisedAgain.size());
    assertNotNull(SecurityScan.fingerprintOf(raisedAgain.get(0).description()));
    assertEquals(
        1, mergeAfterDecline(s, response(), files, List.of(), List.of(legacy)).findings().size());

    // A later round on the same head revives nothing: the newest round did not record the decline,
    // so a value rotated before that round (here, the same line) is not covered by it.
    assertEquals(
        1,
        s.merge(
                response(),
                s.scan(rotated),
                List.of(),
                Set.of(),
                List.of(),
                new SecurityScan.Declines(List.of(legacy), List.of(), true))
            .findings()
            .size());
  }

  @Test
  void aDeclineCoversOneDetectionTheNearestByLine() {
    var token = fake.githubToken();
    var s = scan(true, false);
    var declined =
        s.merge(
                response(),
                s.scan(List.of(added("app.env", "X=1", "T=" + token))),
                List.of(),
                Set.of())
            .findings()
            .get(0);
    // The same line added a second time, two lines further down.
    var twice = List.of(added("app.env", "X=1", "T=" + token, "Y=2", "T=" + token));

    var oneDecline = mergeAfterDecline(s, response(), twice, List.of(), List.of(declined));
    assertEquals(
        List.of(4), oneDecline.findings().stream().map(ReviewResponse.Finding::line).toList());

    // Two declines of that content: each detection claims the nearest one left.
    var farther = at(declined, declined.file(), 9);
    var both = mergeAfterDecline(s, response(), twice, List.of(), List.of(declined, farther));
    assertTrue(both.findings().isEmpty(), both.findings().toString());
  }

  @Test
  void aCopyAddedAboveTheDeclinedLineDoesNotTakeItsDecline() {
    var token = fake.githubToken();
    var s = scan(true, false);
    var declined =
        s.merge(
                response(),
                s.scan(List.of(added("app.env", "X=1", "T=" + token))),
                List.of(),
                Set.of())
            .findings()
            .get(0);
    // The declined line stays at line 2; a verbatim copy is added above it, at line 1. The copy is
    // scanned first, but the decline goes to the nearest line, the one it was written on.
    var copyAbove = List.of(added("app.env", "T=" + token, "T=" + token));

    var merged = mergeAfterDecline(s, response(), copyAbove, List.of(), List.of(declined));
    assertEquals(List.of(1), merged.findings().stream().map(ReviewResponse.Finding::line).toList());
  }

  @Test
  void aDeclineOfAnotherFileTitleOrAnchorDoesNotCoverADetection() {
    var files = List.of(added("rust/Dockerfile", "FROM rust:1.80", "USER root"));
    var s = scan(false, true);
    var raised = s.merge(response(), s.scan(files), List.of(), Set.of()).findings().get(0);
    var otherAnchor =
        new ReviewResponse.Finding(
            raised.risk(),
            raised.confidence(),
            raised.file(),
            raised.line(),
            raised.title(),
            raised.description(),
            "USER 0",
            null);
    var otherTitle =
        new ReviewResponse.Finding(
            raised.risk(),
            raised.confidence(),
            raised.file(),
            raised.line(),
            SecurityRule.PUBLIC_BUCKET.iacTitle(),
            raised.description(),
            raised.suggestionOld(),
            null);
    var modelOwn = modelFinding(raised.file(), raised.line(), "Runs as root", "USER root");
    var noFile = at(raised, null, raised.line());

    var merged =
        mergeAfterDecline(
            s,
            response(),
            files,
            List.of(),
            List.of(at(raised, "go/Dockerfile", 2), otherAnchor, otherTitle, modelOwn, noFile));
    assertEquals(List.of(raised), merged.findings());

    // The setting itself, declined, is not raised again.
    assertTrue(
        mergeAfterDecline(s, response(), files, List.of(), List.of(raised)).findings().isEmpty());
  }

  @Test
  void aChangedPrivateKeyBodyIsRaisedAgainAfterADecline() {
    var s = scan(true, false);
    var body = fake.pemBodyLine();
    var key =
        List.of(
            added(
                "certs/key.pem",
                FakeCredentials.pemHeader("RSA"),
                body,
                "-----END RSA PRIVATE KEY-----"));
    var declined = s.merge(response(), s.scan(key), List.of(), Set.of()).findings();
    assertEquals(1, declined.size());

    assertTrue(mergeAfterDecline(s, response(), key, List.of(), declined).findings().isEmpty());
    var another =
        List.of(
            added(
                "certs/key.pem",
                FakeCredentials.pemHeader("RSA"),
                fake.pemBodyLine(),
                "-----END RSA PRIVATE KEY-----"));
    assertEquals(
        1, mergeAfterDecline(s, response(), another, List.of(), declined).findings().size());
  }

  @Test
  void aPreviousRoundRaiseOfADeclinedFindingIsReportedJustified() {
    // A round that raised the finding again before declines were remembered: its copy is the
    // effective previous round's, tracked by id, and closes under the decline it repeats.
    var first = fake.githubToken();
    var second = fake.githubToken();
    var files = List.of(added("app.env", "A=" + first, "B=" + second));
    var s = scan(true, false);
    var raisedAgain = s.merge(response(), s.scan(files), List.of(), Set.of()).findings();
    var declined = List.of(raisedAgain.get(0), raisedAgain.get(1));

    var unreported = mergeAfterDecline(s, response(), files, raisedAgain, declined);
    assertTrue(unreported.findings().isEmpty());
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "justified", SecurityScan.DECLINE_STANDS_NOTE),
            new ReviewResponse.PreviousFindingStatus(
                2, "justified", SecurityScan.DECLINE_STANDS_NOTE)),
        unreported.previousFindingsStatus());

    // A maintainer's own decline on the new thread, as the model reports it, is kept as written.
    var reported =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "justified", "a fixture"),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there")),
            null);
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "justified", "a fixture"),
            new ReviewResponse.PreviousFindingStatus(
                2, "justified", SecurityScan.DECLINE_STANDS_NOTE)),
        mergeAfterDecline(s, reported, files, raisedAgain, declined).previousFindingsStatus());

    // Without a decline the same round keeps both open, as before.
    assertEquals(
        List.of("unresolved", "unresolved"),
        mergeAfterDecline(s, response(), files, raisedAgain, List.of())
            .previousFindingsStatus()
            .stream()
            .map(ReviewResponse.PreviousFindingStatus::status)
            .toList());
  }

  @Test
  void theContentFingerprintNamesAMissingAlgorithm() {
    var key = SecurityScan.fingerprintKey("s");
    var thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> SecurityScan.contentFingerprint("NO-SUCH-MAC", key, SecurityRule.JWT, "x"));
    assertTrue(thrown.getMessage().contains("NO-SUCH-MAC"));
    var legacy =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> SecurityScan.legacyContentFingerprint("NO-SUCH-DIGEST", SecurityRule.JWT, "x"));
    assertTrue(legacy.getMessage().contains("NO-SUCH-DIGEST"));
    assertEquals(
        SecurityScan.legacyContentFingerprint(SecurityRule.JWT, "x"),
        SecurityScan.legacyContentFingerprint("SHA-256", SecurityRule.JWT, "x"));
    var s = keyed("s");
    assertNotEquals(
        s.contentFingerprint(SecurityRule.JWT, "x"),
        s.contentFingerprint(SecurityRule.GITHUB_TOKEN, "x"),
        "the rule is part of the fingerprint");
  }

  // --- #1031: identity matching scales with the matches, not the product of the sets ---

  @Test
  void distantModelFindingsInOneFileDoNotWalkEveryDetection() {
    for (int n : List.of(100, 200, 400, 800)) {
      var detections = new ArrayList<SecurityScan.Detection>();
      var models = new ArrayList<ReviewResponse.Finding>();
      for (int i = 0; i < n; i++) {
        var found = modelFinding("src/config.env", i * 10 + 1, "scan identity " + i, "d");
        detections.add(
            new SecurityScan.Detection(SecurityRule.GENERIC_SECRET, found, null, null, Set.of()));
        models.add(modelFinding("src/config.env", 1000000 + i, "unrelated allocation " + i, "d"));
      }
      var result = new SecurityScan.Result(detections, List.of(), List.of("src/config.env"));
      long before = SecurityScan.IDENTITY_COMPARISONS.get();
      var merged =
          scan(true, false)
              .merge(new ReviewResponse(models, List.of(), null), result, List.of(), Set.of());
      long comparisons = SecurityScan.IDENTITY_COMPARISONS.get() - before;
      assertTrue(comparisons <= 3L * n, "n=" + n + ": " + comparisons);
      assertEquals(2 * n, merged.findings().size());
    }
  }

  @Test
  void distantSecretsWithTheSameIdentifierAreLookedUpOnlyInTheirOwnFile() {
    int n = 400;
    var files = new ArrayList<FileDiff>();
    var models = new ArrayList<ReviewResponse.Finding>();
    for (int i = 0; i < n; i++) {
      var path = "svc" + i + "/Config.kt";
      files.add(added(path, "val apiToken = \"" + fake.genericSecret(40) + "\""));
      models.add(modelFinding(path, 100, "Hardcoded apiToken", "The apiToken literal leaks."));
    }
    var s = scan(true, false);
    var result = s.scan(files);
    long before = SecurityScan.IDENTITY_COMPARISONS.get();
    var merged = s.merge(new ReviewResponse(models, List.of(), null), result, List.of(), Set.of());
    assertEquals(2L * n, SecurityScan.IDENTITY_COMPARISONS.get() - before);
    assertEquals(n, merged.findings().size());
    assertTrue(
        merged.findings().stream().allMatch(f -> f.title().startsWith(SecurityRule.TITLE_PREFIX)));
  }

  @Test
  void identityMatchingComparesEachDetectionOnlyWithItsOwnFile() {
    int n = 400;
    var files = new ArrayList<FileDiff>();
    var models = new ArrayList<ReviewResponse.Finding>();
    for (int i = 0; i < n; i++) {
      // The hard case for a file-name index: every file is a Dockerfile, every finding has the
      // same title and anchor, and only the directory tells them apart.
      var path = "svc" + i + "/Dockerfile";
      files.add(added(path, "FROM node:20", "USER root"));
      models.add(modelFinding(path, 2, "Container runs as root with privilege", "d"));
    }
    var s = scan(false, true);
    var result = s.scan(files);
    var firstRound = s.merge(response(), result, List.of(), Set.of()).findings();
    assertEquals(n, firstRound.size());

    long before = SecurityScan.IDENTITY_COMPARISONS.get();
    var merged =
        s.merge(
            new ReviewResponse(models, List.of(), null),
            result,
            firstRound,
            Set.of(),
            firstRound,
            new SecurityScan.Declines(firstRound, false));
    long comparisons = SecurityScan.IDENTITY_COMPARISONS.get() - before;

    // One decline, one prior and one detection per file: 3n, where a walk over every candidate
    // makes 3n² (480,000 here).
    assertEquals(3L * n, comparisons);
    assertTrue(merged.findings().isEmpty(), "every model finding is a duplicate, nothing raised");
    assertEquals(n, merged.previousFindingsStatus().size());
    assertTrue(
        merged.previousFindingsStatus().stream()
            .allMatch(status -> "justified".equals(status.status())));
  }

  // --- #985: the fingerprint is keyed with the server's secret ---

  private static SecurityScan keyed(String serverSecret) {
    return new SecurityScan(true, false, 3.5, SKIPPED, serverSecret);
  }

  @Test
  void theFingerprintIsStableForOneKeyAndChangesWithTheKey() {
    var line = "T=" + fake.githubToken();
    var first = keyed("webhook-secret-one").contentFingerprint(SecurityRule.GITHUB_TOKEN, line);
    // A restart builds a new scan from the same secret.
    assertEquals(
        first, keyed("webhook-secret-one").contentFingerprint(SecurityRule.GITHUB_TOKEN, line));
    assertNotEquals(
        first, keyed("webhook-secret-two").contentFingerprint(SecurityRule.GITHUB_TOKEN, line));
    // Without the key a reader cannot recompute it: the plain hash a guess would be tested with
    // is not what is posted.
    assertNotEquals(first, SecurityScan.legacyContentFingerprint(SecurityRule.GITHUB_TOKEN, line));
    assertEquals(8, first.length());
    assertEquals(
        keyed(null).contentFingerprint(SecurityRule.JWT, "x"),
        keyed("").contentFingerprint(SecurityRule.JWT, "x"),
        "a missing secret still gives a key");
  }

  @Test
  void thePostedMarkerIsTheKeyedFingerprint() {
    var token = fake.githubToken();
    var s = keyed("webhook-secret-one");
    var raised =
        s.merge(response(), s.scan(List.of(added("app.env", "T=" + token))), List.of(), Set.of())
            .findings()
            .get(0);
    assertEquals(
        s.contentFingerprint(SecurityRule.GITHUB_TOKEN, "T=" + token),
        SecurityScan.fingerprintOf(raised.description()));
  }

  @Test
  void aDeclineUnderTheUnkeyedMarkerKeepsMatchingAfterTheUpgrade() {
    var token = fake.githubToken();
    var line = "T=" + token;
    var s = keyed("webhook-secret-one");
    var keyedFinding =
        s.merge(response(), s.scan(List.of(added("app.env", line))), List.of(), Set.of())
            .findings()
            .get(0);
    var legacyMarker = SecurityScan.legacyContentFingerprint(SecurityRule.GITHUB_TOKEN, line);
    var declinedBeforeUpgrade =
        new ReviewResponse.Finding(
            keyedFinding.risk(),
            keyedFinding.confidence(),
            keyedFinding.file(),
            keyedFinding.line(),
            keyedFinding.title(),
            keyedFinding
                .description()
                .replace(SecurityScan.fingerprintOf(keyedFinding.description()), legacyMarker),
            null,
            null);
    assertEquals(legacyMarker, SecurityScan.fingerprintOf(declinedBeforeUpgrade.description()));

    // After a push, on the same content: still covered, so it is not raised again.
    var files = List.of(added("app.env", "X=1", line));
    assertTrue(
        mergeAfterDecline(s, response(), files, List.of(), List.of(declinedBeforeUpgrade))
            .findings()
            .isEmpty());
    // A rotated value is not.
    var rotated = List.of(added("app.env", "X=1", "T=" + fake.githubToken()));
    assertEquals(
        1,
        mergeAfterDecline(s, response(), rotated, List.of(), List.of(declinedBeforeUpgrade))
            .findings()
            .size());
    // A decline recorded under another server's key does not match this one's content.
    assertEquals(
        1,
        mergeAfterDecline(
                keyed("webhook-secret-two"), response(), files, List.of(), List.of(keyedFinding))
            .findings()
            .size());
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

  @Test
  void onlyAScanTitleWithTheScansProvenanceIsRaisedByTheScan() {
    var title = SecurityRule.GITHUB_TOKEN.secretTitleHead() + " (ghp_…, 40 chars)";

    assertTrue(SecurityScan.raisedByScan(title, "d\n\n" + SecurityScan.PROVENANCE));
    assertFalse(SecurityScan.raisedByScan(title, "The model's own words."));
    assertFalse(SecurityScan.raisedByScan(title, null));
    assertFalse(SecurityScan.raisedByScan("Hardcoded token", SecurityScan.PROVENANCE));
  }
}
