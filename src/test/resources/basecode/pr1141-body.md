## What type of PR is this?

- [x] 🐛 Bug fix
- [ ] ✨ Feature
- [ ] 📝 Documentation
- [ ] 🔧 Refactor
- [ ] 🚀 Performance
- [ ] ✅ Test
- [ ] 🔒 Security
- [ ] 📦 Dependency update
- [ ] 🏗️ CI/CD

## Description

The remaining #952 items, as triaged in https://github.com/devops-thiago/ThrillhouseBot/issues/952#issuecomment-6206672405. ACs 1–4 shipped in 0.7.1 (#1045, #1046, #1048, #1051, #1084), and this PR does not change what they shipped. Line numbers are on this PR's head:

- AC1 (the IaC scan drops a model finding on the same line and the same rule): `SecurityScan.duplicatesDetection` (SecurityScan.java:1098) visits every detection within `FindingDeduplicator.LINE_TOLERANCE` (3) lines, the same line included, and `nearbyIacDefect` (SecurityScan.java:1614) merges the model finding when its title uses that detection's own rule's `defectWords()`. Pinned by `SecurityScanTest.eachIacRuleCollapsesAModelFindingNamingItsDefectNearby` (one case per IaC rule) and `theModelsRootFindingOnTheLineBelowCollapsesIntoTheScans` (the #158 case). The `removesDetectedLine` check below adds a second way to match the same rule's detection; it does not replace the title match.
- AC2 (the check run reports the open total with the new count separate): the count is in the check run's summary line, `VerdictBuilder.newFindingsSummary` (VerdictBuilder.java:458), which reads "3 open findings: 1 new (…), 2 still open from earlier rounds". The check-run title (`checkTitleForResult`, VerdictBuilder.java:372) is the check name and carries no count. Pinned by `VerdictBuilderTest.checkRunCountsLineLeadsWithTheOpenTotalWhenEarlierFindingsAreStillOpen`.

- **#156, CI-confirmed severity not restored.** `FindingVerificationService.floorEstablishedConfidence` raised a CI-cited finding's confidence and kept whatever risk the audit gave it. When a finding cites a failing check or test and the audit graded it below the risk the review raised it at, the raised risk is now restored. The finding is paired with its raised copy by file, line and title (`restoredRisk`). The confidence floor, and the dropped suggestion when the audit had lowered the confidence, work as before. `SeverityCalibrator` grades only its three IaC classes, so it does not undo this on either lane.
- **Round 18, IaC duplicate on c #277.** The model finding "Comment claims serial-board access that no daemon source performs" suggested `USER locker` over the `USER root` the scan reported a line away. Its title has no defect word, so `nearbyIacDefect` missed it. A model finding in the scan's window is now merged into the scan finding when its suggestion replaces the detected line and writes no line equal to it (`SecurityScan.removesDetectedLine`). The `OTHER_IAC_DEFECT_WORDS` veto still applies.
- **Round 18, kotlin #276.** "BOOKING_API_TOKEN is baked into the image via ARG/ENV" sits on `ENV BOOKING_API_TOKEN=${BOOKING_API_TOKEN}`, a line that assigns only an interpolation. `withoutStandInSecrets` kept it because its title names the secret only through the identifier. Such a finding is now treated as a secret claim, and dropped like the other stand-in findings, when its title names a credential key the line assigns and its description uses two of the secret rule's topic words (`SecretScanner.assignedCredentialKeys`). A finding naming the key that is not a secret claim ("… is never read") stays.

The two remaining #952 entries are met by existing code (see above); the summary can't credit code outside the diff, tracked in #1148.

The fourth open item is the round-18 findings left at low confidence on python #268, rust #279 and kotlin #276. Checked against what those PRs show now:
- **python #268:** the round-1 finding names `test_first_applicant_is_position_one`, and `test` failed seconds after round 1 read CI. #1133 applies the CI-cited floor again over the CI section it re-reads after the model call. `CiFailureCitationsTest.theRound18PythonFindingCitesTheTestThatFailedAfterReviewStart` pins that the finding's own text cites the test.
- **kotlin #276:** the CI-confirmed finding (billableDays off by one) was HIGH at medium confidence, which meets the AC. The summary's "no open finding explains" came from the class-qualified Kotlin test name, which #1091 already fixed on main (pinned row in `eachRunnersLineYieldsTheTestsOwnName`).
- **rust #279:** still open. The HIGH/low "rms_of_last window arithmetic is off by one" finding never names the failing test `rms_uses_exactly_the_last_window_values` or the check. It quotes the test's assertion and says `cargo test` "will confirm", so no citation reading ties it to the failure. Linking a finding to a test by the assertion it quotes would be a new matcher.

Merge this after #1133. Both PRs change `floorEstablishedConfidence`, and I'll resolve the merge on whichever lands second.

These are deterministic post-model steps. Nothing that reaches the model changes, so the live eval was not run.

## Related Issues

Refs #952 (the rust #279 bullet above stays open)

## How Has This Been Tested?

- [x] Unit tests

- `EstablishedConfidenceFloorTest`: `aCiCitedFindingTheAuditDemotedGetsItsRaisedRiskBack` (the #156 shape), `aCiCitedFindingKeepsItsConfidenceWhenOnlyTheRiskIsRestored`, `theAuditsRiskStandsWithoutACiCitationOrARaisedCopy`.
- `CiFailureCitationsTest.theRound18PythonFindingCitesTheTestThatFailedAfterReviewStart`.
- `SecurityScanTest`: `aModelFindingSuggestingTheScansFixCollapsesIntoIt` (c #277), `aSuggestionThatKeepsTheDetectedLineStaysBesideTheScan`, `aSecretClaimNamingTheStandInKeyGoes` (kotlin #276, with the "never read" and other-key controls).
- `./mvnw spotless:check verify` passes locally.

## Checklist

- [x] My code follows the project's coding standards
- [x] I have performed a self-review of my own code
- [x] I have added tests that prove my fix is effective or that my feature works
- [x] New and existing unit tests pass locally with my changes
- [x] I have updated the documentation accordingly
- [x] My changes generate no new warnings or errors



