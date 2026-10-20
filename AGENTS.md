# AGENTS.md

Engineering reference for anyone changing this code base, human or tool. It records the rules
that are easy to break without knowing they exist, where each pipeline stage lives, and the
alternatives that were tried and rejected.

- Product and request-flow overview: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- Setup, PR bar, dual-gate merge policy, commit style, eval corpus how-to: [CONTRIBUTING.md](CONTRIBUTING.md).
- Operator configuration: [README.md](README.md) and `src/main/resources/application.properties`.

Paths below are relative to `src/main/java/dev/thiagogonzaga/thrillhousebot/` unless they start
with `src/`, `.github/` or a repo-root file. Classes and methods are cited by name; line numbers
rot.

**Keep this file current.** A PR that changes an invariant listed here updates this file in the
same diff.

## Build and verify

- **JDK 25 is required** (`maven.compiler.release` is 25 in `pom.xml`). An older default JDK fails
  with "release version 25 not supported"; point `JAVA_HOME` at a JDK 25 first.
- The local loop, in order:
  1. `./mvnw spotless:apply` (google-java-format; CI runs `spotless:check`).
  2. `./mvnw verify`: unit tests, the JaCoCo bundle line-coverage floor (0.80), and SpotBugs at
     `effort=Max` / `threshold=Low`. Restructure a SpotBugs false positive first; an exclusion in
     `config/spotbugs-exclude.xml` needs a justification comment.
  3. Patch coverage: `codecov.yml` sets the patch target to `auto`, so the bar is the project's
     current coverage (near 100%), not the JaCoCo floor. Treat every uncovered changed line as a
     failure. Files listed under `ignore:` in `codecov.yml` do not count.
- Dashboard changes: `cd frontend && npm ci && npm test && npm run build` (what the `frontend` CI
  job runs, plus `npm audit`).
- Docs-site changes (`website/`, or text inside the `docs:*:start/end` markers of `README.md`,
  `CONTRIBUTING.md` or `docs/ARCHITECTURE.md`, which the site includes):
  `cd website && npm ci && npm run build`. Links inside the included regions are absolute GitHub
  URLs because the site renders them out of repo context.
- CI also runs Dependency Review, Trivy, SonarCloud (new issues fail the `test` job's scan step)
  and, when `pom.xml`, `src/main/**` or the CI workflow change, the GraalVM native build.

## Package map

| Package | Owns |
|---|---|
| `webhook/` | `WebhookController` (`POST /api/webhook`), HMAC check (`WebhookVerifier`), triggers and filters (`TriggerDetector`, `ReviewTriggerFilter`), dedupe, pause state, the comment commands (`CommentCommandService`) |
| `review/` | Everything between "a review was requested" and "the result is on GitHub": orchestration, context loading, prompt assembly, budgeting, the post-model finding chain, verdict, publishing, CI gating, follow-ups, the on-request commands |
| `review/ai/` | The LangChain4j layer: AI service interfaces and their prompt constants (`*Prompts`), streaming and retries (`AiReviewService`), parsing and salvage, the verifier, the token ledger, the call gate |
| `github/` | REST and GraphQL clients, app auth, write pacing and retry (`GitHubWritePacer`, `GitHubWriteRetry`), instructions and repo-settings resolution |
| `dashboard/` | OAuth sessions, the WebSocket broadcaster, `ReviewSession` persistence, finding-feedback aggregates |
| `notification/` | The opt-in outgoing review-outcome webhook |
| `config/` | `ThrillhouseConfig` (typed config), `StartupConfigValidator`, the review executor, HTTP client, `ActiveModelSettings`, `BotIdentity` |
| root | `LogSafe` (log sanitizing) |

## Review pipeline map

| Stage | Where |
|---|---|
| Dispatch, per-PR serialization, coalescing | `review/ReviewDispatcher` |
| Top-level run: check run, context, plan, pipeline, verdict, publish | `review/ReviewOrchestrator` |
| Stand down before the first write: a chain of `review/PrePublishGuard`s run in order, first stand-down wins, shared consequences (skip counted, check run skipped) applied once. Order: head moved (#704, with the #806 carry-over), then a rejecting label gate (#1021). A new guard is added to the coordinator's list, not to the orchestrator | `review/ReviewStandDown.beforeFirstWrite` over `HeadMovedGuard` (with `SupersededFindingsCarryover`), `LabelGateGuard` (with `PullRequestLabelGate`) |
| Load diff, every earlier round (from the stored `ReviewSession.aiResponseJson`), threads and conversation, instructions, labels, project stack, config-key and patch-coverage context | `review/ReviewContextLoader` (with `PatchCoverageResolver`, `ConfigKeyContextResolver`, `BugFixContextResolver`, `SupersededFindingsCarryover`) |
| Previous-findings prompt section: the numbered effective round, then the unnumbered "still open from earlier rounds" and "answered in earlier rounds" sections | `review/FollowUpAnalyzer.buildPreviousFindingsContext` (with `effectivePreviousRoundIndex`, `openEarlierRoundFindings`) |
| Early CI reading and the opt-in CI-failure section | `review/CiStatusEvaluator`, `review/CiFailureContextResolver`; TAP `TODO` and `SKIP` directives are expected outcomes, excluded both when collecting failed tests and when reading them back via `CiFailureCitations` (#1110). When the early reading counted a pending check, the summary's citations are read from a section refreshed after the model call (`ReviewOrchestrator.refreshCiFailures` → `CiStatusEvaluator.checkRunFailures`, or the gate's own re-read, then `CiFailureContextResolver.refresh`, #1092), and `FindingVerificationService.floorCiCitedConfidence` lifts a low-confidence finding citing it, the round persisted again (`floorOnRefreshedCi`); the review and verifier calls keep the review-start section |
| Opt-in review learnings: recall before the call (ranked, capped section and typed selection) | `review/ReviewLearnings.recall` (reads `ReviewLearningService.listActive` once) |
| Opt-in linked-issue context: resolve the PR's linked issues, read them, extract acceptance criteria, sanitize and cap | `review/TicketContextResolver.resolve` over an `review/IssueTrackerProvider` (`review/GitHubIssuesProvider.linkedTickets`: PR-body closing keywords, then `closingIssuesReferences`, then optionally the branch name), called from `ReviewOrchestrator` and handed to `ReviewPromptAssembler.assemble(ctx, req, ciFailures, linkedIssues)` |
| Fence untrusted input, build review and summary guidance, pick the system prompt | `review/ReviewPromptAssembler` (with `PromptSections`, `PromptTemplateEscaper`, `ReviewDimensionRouter`) |
| Token budget and batches | `review/DiffBudgetPlanner` (`plan`, `boundPreviousFindings`, `perCallInputBudget`) |
| Model calls: single call, batches, retries, sequential retry, truncation salvage, summary call | `review/FindingPipeline.run` → `review/ai/AiReviewService` (`review`, `reviewBatch`, `summarize`) |
| Post-model chain, single call, in order: evidence, quote validation, framework filter, dedupe, rejection memory, verifier, remember verdicts (`FindingScreen.screenSingleCall`), then severity calibration, replied-duplicate and litigated drops, anchors and the changed file a mistyped path names (`DiffLineResolver.diffPathFor`: case, then a unique path suffix, then a unique file name; #1022, #945), then the lines the finding's `suggestion_old` quotes (`SuggestionAnchor.place`: moved there, or the suggestion dropped when it cannot be placed or rewrites a table row to a different cell count; #924) | `review/FindingPipeline.refine` over `review/FindingScreen` |
| Post-model chain, multi call: per batch evidence, quote validation, framework filter, rejection memory, verifier, remember verdicts (`refineBatchOutcome` → `FindingScreen.screenBatch`); after all batches status merge, dedupe, severity calibration (#1077: so both lanes place an anchored class alike), replied-duplicate and litigated drops, anchors, the mapped path and the suggestion placement | `review/FindingPipeline.runMultiCall` |
| Per-call screen stages: one `FindingStage` per step over an immutable `ScreeningContext`; each lane is an explicit ordered stage list built in the `FindingScreen` constructor (the batch list omits dedupe), pinned by `FindingScreenTest` | `review/FindingScreen` over `EvidenceStage`, `QuoteValidationStage`, `FrameworkFilterStage`, `DedupeStage`, `RejectionScreenStage`, `VerificationStage`, `RememberVerdictsStage` |
| Verifier (a verdict that keeps a finding also judges its suggestion, `keep`, `replace` with corrected code or `drop`, and lists description sentences the material contradicts under `cut`; applied by `FindingRevisions`: a replacement goes into `suggestion_new` with `suggestion_old` kept, a drop clears `suggestion_new`, a cut removes only whole sentences the description holds exactly and never the last one, and each part fails open on its own, #1080), then the deterministic floors on its output: injection-sink risk (`floorInjectionSinkRisk`) and established-evidence confidence (`floorEstablishedConfidence`, #926/#952; a CI-cited finding the audit demoted also gets its raised risk back, paired by file, line and title) | `review/ai/FindingVerificationService`, evidence from `review/ContextEvidenceResolver` (coverage, path-scoped rules, the failing CI checks a finding cites via `CiFailureCitations`, by name or by a failing test the job log names, #1075) and `review/CitedLocationResolver` |
| Last steps on every lane, including the one with no review call: the findings held to the linked issue (#1073: "the linked issue" names its number when one is linked, a quoted or named acceptance criterion the issue does not hold is cut, the finding dropped when it was its only claim, and a finding reporting an unmet criterion the issue holds is floored at medium risk and medium confidence so it keeps a thread), the always-on drop of model secret findings on a line that assigns only an interpolation or a placeholder (#926), the opt-in deterministic security scan (secrets, risky IaC), then the open-thread duplicate guard (#939), then the path mentions in finding descriptions (#1076: a path no changed file, quoted text or repository file resolves is stripped, a near miss of one changed file mapped, and a path only one changed file ends with completed before quoted text is consulted, #1094; a code span quoting the finding's file with an absolute path its line does not hold gets the line's path; a suggestion naming an unresolved file it does not itself write, a glob over an absolute directory that nothing names and the suggestion does not make, or an absolute file nothing names in a directory the patches name (`createdBySuggestion`, `createsDirectory`, `absentProjectFile`), loses `suggestion_new`; the finding stays) | `review/FindingPipeline.finish` → `review/LinkedIssueCitations.apply` → `review/SecurityScan.withoutStandInSecrets` (`SecretScanner.assignsOnlyStandIns`) → `review/SecurityScan.merge` (rules in `SecurityRule`, detection in `SecretScanner` and `IacScanner` over `PatchLines`) → `FollowUpAnalyzer.withoutOpenThreadDuplicates` → `review/PathMentions.screen` (the repository listing is read once, on first need, through `CitedLocationResolver.Round.repositoryPaths`; unknown or truncated leaves the text) |
| Summary call, carried description gaps, reused overview, prompt-label scrub, persistence | `review/FindingPipeline.withSummary` (with `DescriptionGapCarryover`, `PriorOverviewCarryover`, `PromptLabelScrubber`), `persistWithSummary`. Every gap, fresh or carried, is held to the linked issue (`LinkedIssueCitations.screenGaps`, #1073: a `Linked issue #N` criterion of two content words or more must share at least half of them with the issue, a quoted one must be in it) and passes `DescriptionGapScreen` (a quoted claim must be in the PR text, a gap must cite the description or a linked issue, #946; an omission bullet naming files the PR text mostly names is dropped, `falseOmission`, #1074); a gap whose own words hedge it (exactly "may", "might", "could" with or without "n't", "possibly", "perhaps", "presumably", "maybe", "likely", "probably", "potentially", "seemingly", "in which case"; not inside quotes, code spans or hyphenated names, an issue's criterion, a hedge one PR-text sentence makes with the same surrounding words, or a hedge after a consequence of a mismatch naming the code) is dropped even with no PR text known (`DescriptionGapScreen.speculative`, #1127); a gap whose own words concede no mismatch ("no gap" or "no gaps" ending its phrase, "not contradicted", "matches the description", in its last clause with no exception such as "except that", "other than" or "otherwise" in its sentence and not after a negation such as "isn't"; or a closing "(disclosed)", "(already disclosed)" or "(not contradicted)", while a bare "disclosed" is no concession) is dropped even with no PR text known, as is one whose last clause only says, in its own words and not as the description's claim, that the material cannot confirm a claim or that a criterion appears addressed, with no mismatch of its own ("not the described", "omits", "unmet") in an earlier clause or before the non-assertion in the last one and no code span or file named (`DescriptionGapScreen.assertsNothing`); neither rule drops a `Linked issue #N:` gap (`DescriptionGapScreen.concedesNoGap`, #1146); on an unchanged head the call's `addressed_gaps` is ignored (#964) unless the PR text changed since the round that listed the gaps, read from the `<!-- thrillhousebot:pr-text=… -->` fingerprint `ReviewPublisher.publishSummary` writes under the summary marker; then it may release a description gap, never a linked-issue one, and an omission gap whose quoted passage the edited text now holds leaves (`DescriptionGapScreen.omissionCovered`, #1064). On an unchanged head every degraded path keeps the previous overview and `VerdictBuilder` swaps in the carried-overview banner; an answer keeps earlier file summaries and the diagram it lacks (#965). After `OverviewScreen`, `PathMentions` cuts every summary sentence naming a path nothing resolves and removes such a path from the diagram (#1076), then `InventedArtifacts` cuts a purpose or assessment sentence that introduces a page, screen, migration or endpoint ("a settings page") the change does not back (a page or screen: a changed path or an added line naming it; a migration: a path or line naming one; an endpoint: an added line only); a file described by its role ("the config doc") is never judged (#1090) |
| Opt-in summary check (#1078, `REVIEW_SUMMARY_VERIFICATION_ENABLED`): on the answered lane only, before the prior overview is filled in (only the text this answer wrote is checked; a purpose, row or diagram the check removed is not put back by `PriorOverviewCarryover`, `FindingPipeline.withoutRefilled`), one call on the concise model reads the purpose sentences, walkthrough rows and (with the diagram on) diagram lines, each by id (`S<n>`, `R<n>`, `D<n>`), against the batch texts (or the whole diff with budgeting off) packed into the per-call budget, the posted findings and the bounded PR text. An id the answer names with evidence is removed: the sentence is cut and the breaks around it kept, the row's file summary dropped, any diagram line drops the diagram. A purpose sentence goes only when the answer's `source` is `diff` (missing or unknown reads as `finding`), and never when every purpose sentence would go (`SummaryStatements.cuttable`, rc8 session 7451); rows and diagram lines go on either source. An id with no evidence, or one naming nothing, changes nothing; each cut is logged at INFO with its statement and evidence (`LogSafe.oneLine`); the PR text is fenced when it arrives unfenced; findings are never read back | `review/FindingPipeline.withSummary` → `review/ai/SummaryVerificationService` (`SummaryVerifier`, `SummaryVerifierPrompts`), edits in `review/ai/SummaryStatements` |
| Follow-up status reconciliation, in order: supersede, decline re-check, conversation clears, phantom ids, double-check replacement, approval backstop, still-open set | `review/VerdictBuilder.build` over `FollowUpAnalyzer` (`supersedeVanished`, `recheckDeclines`, `clearNamedInConversation`, `withoutPhantomUnresolved`, `replacedPriors`, `heldPreviousFindings`, `stillOpenFindings`) |
| Verdict and check-run text | `review/VerdictBuilder.buildResult` |
| Inline thread or "Things to double-check": one severity × confidence table, read through `Finding.postsInline` by every surface, on the grade after the verifier's floors and the calibrator (#1077). Critical and high are inline at any confidence; medium and low go to double-check only at low confidence | `review/FindingPlacement` |
| Post review, inline comments, thread resolution. A finding on a path the pull request does not change (`DiffLineResolver.isChangedFile`, over every changed file, patch or not) is listed in the review body without trying a route (#925) | `review/ReviewPublisher`, `review/CheckRunManager` |
| Summary comment: render over the round plus the still-open set, edit in place by marker. The Description vs. Implementation section names each description finding (`descriptionFindings`) instead of a boilerplate line (#964), only one that quotes or paraphrases the PR text or linked issue (`DescriptionGapScreen.echoes`, over `PrSummaryGenerator.DescriptionSources` the orchestrator hands `VerdictBuilder.build`), plus, on an unchanged head, every open finding the previous summary named there (`DescriptionGapCarryover.previousFindingBullets`, #1074). A gap crediting its claim to a review pass or a finding (`DescriptionGapScreen.creditsReview`: a review or finding noun within five words of a crediting verb in one clause the description is not said to state; "shows", "indicates" and "reveals" count only with that noun as their subject or after their "by", as in "the FindingPipeline finding shows") is rendered only while a finding of the round plus the still-open set states it, demoted ones included (`PrSummaryGenerator.backedByFindings`, #1127); the next round carries only what was rendered. With exactly one issue linked, every criterion the round states unmet (an open finding citing the issue, an overview sentence saying it is not met, or a negated walkthrough clause, matched by content words) gets a `Linked issue #N: <criterion>` line unless a linked-issue gap names it; a line pointing at a finding replaces that finding's bullet and is not carried as a gap (`LinkedIssueCriteria`, read from `DescriptionSources.linkedIssues`, never from the claim text, #1093). The overview's sentence on each failing CI check is computed when the summary is rendered, over the round plus the still-open set, by the same citation reading as the confidence floor; the summary call's own sentences of that kind are removed (`CiFailureOverview`, from `VerdictBuilder.buildResult`, #1075) | `review/PrSummaryGenerator` (`currentState`), `review/ReviewPublisher.publishSummary` / `upsertSummaryComment` |
| CI hold and revisit | `review/CiHoldRegistry`, `review/CiHoldRevisit` |
| Opt-in review learnings: capture after the post, from the round's final statuses | `review/ReviewLearnings.captureSurvivingDeclines` → `review/FollowUpAnalyzer.survivingDeclines` → `review/ReviewLearningService.save` |
| `/learnings`, `/remember`, `/forget` | `webhook/LearningCommands` (routed by `CommentCommandService`) |
| Maintainer replies and mentions. A clear directive replied on a finding's thread is applied, never answered by the model: the thread is resolved and a fixed acknowledgement posted (`clearFromThread`, #1066) | `review/MaintainerReplyService`, `review/MaintainerReplyDispatcher` |
| On-request commands (`/describe`, `/changelog`, `/add-docs`, `/improve`, `/generate-tests`) | `review/AbstractPrSuggestionGenerator` and its subclasses |

## Model-call invariants

- **Every model call holds a `ModelCallGate` slot** (`AI_MAX_CONCURRENT_CALLS`). Streamed calls
  take it in `AiReviewService`; every blocking AI service reaches the model through
  `BoundedChatModel`. `BoundedChatModelWiringTest` fails for an AI service wired any other way.
- **Every review-path call is metered by `ReviewTokenLedger`** (`REVIEW_MAX_TOKENS_PER_REVIEW`).
  `FindingPipeline.run` opens and clears the session entry. Streamed calls are recorded by
  `OtelObservabilityListener` through the `ReviewSessionContext` bind and gated by
  `ensureCallAllowed` in `AiReviewService`. The blocking verifier records its own usage and checks
  `ceilingReached` in `FindingVerificationService`, and so does the opt-in summary check in
  `SummaryVerificationService`. A new review-path call must do one or the
  other. The on-request commands and maintainer replies are outside the ledger: a command is
  bounded by `REVIEW_MAX_AI_CALLS`, a reply is one call, and both hold a gate slot.
- **`REVIEW_MAX_AI_CALLS` counts planned calls, not retries.** `DiffBudgetPlanner.plan` packs at
  most `max-ai-calls - 1` batches and reserves one call for the summary on both lanes. At `1`,
  `callCapLeavesNoSummaryCall()` makes the pipeline skip the summary and disclose it
  (`SummaryDegradation.SKIPPED_AT_CALL_CAP`). The commands take `max-ai-calls` minus their own
  reserved reduce calls (`AbstractPrSuggestionGenerator.maxBatches`). The token ceiling, not this
  cap, is what bounds actual spend.
- **The review call returns findings and `previous_findings_status` only**
  (`PrReviewPrompts.FINDINGS_RESPONSE_CONTRACT`). Every lane ends with the summary call
  (`AiReviewService.summarize` → `PrSummarizer`, on the `concise` model), which writes every
  summary field from the verified findings. The call is skipped only at the call cap or the spend
  ceiling, and a failed, cut or skipped call leaves a counts-only summary that says so
  (`SummaryDegradation`). Do not add summary fields back to the review contract.
- **Response extraction anchors on the answer's tail.** `ReviewResponseParser.extractJson(raw,
  rootKeys)` picks the earliest root-keyed object from which the rest of the body reads as JSON
  documents to the end, and its probing is bounded by a small multiple of the body length. Keep
  that bound when you touch it. `TruncatedResponseSalvager` keeps complete elements from a cut
  body. A top-level array whose every element is finding-shaped (`isFindingArray`) is read as that
  answer's `findings` and merged with the other documents, so a later empty object cannot discard
  it (#960, `ReviewResponseParserFindingArrayTest`). Any other array, and a lone `[]`, is still
  refused.
- **Length stops.** A length stop with content is not retried: the identical call would be cut
  identically (#495). The response is salvaged or disclosed. A length stop with no content (the
  reasoning spent the cap) is repeated once with reasoning off (#839, `ReasoningStepDownStreamingModel`).
  `InlinedDeliberationGuard` stops that repeat when the model writes its deliberation into the
  content (#893).
- **Truncations state figures.** `ResponseCaps` resolves each lane's `ResponseCap`, and
  `TruncationReport` states the licensed cap, the setting that supplies it and the billed usage. It
  names the setting as the remedy only when the stop reached the cap (#895).
- **Verifier evidence reads hunk code, not diff headers.** `+++` and `---` can begin added or
  removed code inside a hunk; only the file headings outside it are excluded (#1103).
- **The summary check fails open and never touches findings** (#1078). An error, an empty, cut or
  malformed answer, an input over the per-call budget or the spend ceiling keeps the summary as
  written. It is not a planned call: like the verifier, it is bounded by the token ceiling, not by
  `REVIEW_MAX_AI_CALLS`. Degraded summaries (counts-only, salvaged, carried) are not checked.
- **Verification fails open.** Any verifier error, cut response or ceiling skip keeps the
  candidates. `FindingVerificationService.markUnscreened` caps them at medium confidence and
  appends the unverified note (#885), and `VerificationCoverage` discloses the round's coverage. Do
  not make a verifier failure drop findings or block a review.
- **Scanner matching is bounded by compatible identities** (#1031). Model duplicates read only
  detections in the same file's three-line window, or identifiers their text names at a distant use
  site, never every detection in the file. Environment-key aliases belong only to the simple
  identifier read by a fallback expression (possibly qualified or parenthesized), or to a comment
  immediately above the declaration in the same hunk; another argument never supplies an alias
  (#1101). The generic secret rule skips credential assignments embedded in complete sample
  strings with explicit before/text-before/prefix prose in list/sample-builder positions, or
  fixture/sample/example-named assignments (#981). New forms require a bare key or one of those
  prose leaders, and whitespace only after the assignment inside the string. New exclusions
  also require a standalone literal/factory or a simple sample-named assignment/collection:
  unknown outer calls, unknown assignment targets, expressions and additional same-line
  statements and preceding sibling entries stay scanned. Closing delimiters and quoted sibling
  inputs are permitted. Unknown
  prefixes and command suffixes stay scanned even with a sample-named target; earlier bare-key list inputs
  keep their classification. Known-format credentials still match.
  IAM wildcard arrays are read in linear time with no element-count or element-width cap (#983).
  A quoted wildcard ending at a list delimiter proves the setting even when other items are
  Terraform expressions or YAML plain scalars; nested quoted stars do not prove a wildcard.
  Scalar wildcards also allow trailing Terraform `#`/`//` or whitespace-separated YAML `#`
  comments; comment text never supplies a later wildcard. The element reader keeps YAML plain
  scalar punctuation (ARN/URL slashes, non-whitespace `#`, quotes and parentheses) as text,
  consumes quoted/tagged/nested nodes without promoting their stars, and accepts block-comment
  trivia only in Terraform. An unclosed comment proves no wildcard. Environment-key aliases from the
  preceding comment preserve SQL/Lua `--`, HTML/XML/SVG `<!--`, INI/systemd-unit `;` and
  properties `!` in their file types. Systemd unit drop-in `.conf` paths also permit `;`; an
  arbitrary `.conf` suffix does not prove comment syntax. A complete standalone Python triple-quoted
  string (plain, raw or Unicode), optionally followed by a comment, can document the next declaration;
  assigned, concatenated and unclosed strings do not. Java operators and adjacent key assignments
  never supply a comment alias.
- **Deterministic scan findings skip the verifier and calibrator.** `SecurityScan.merge` runs in
  `FindingPipeline.finish`, after every verifier call and (on the single-call lane) after
  `SeverityCalibrator.calibrate`, so no model grades a pattern match, and `markUnscreened` and
  `VerificationCoverage` never see one. The grade comes from `SecurityRule`: every rule is high
  confidence (a match settles whether the pattern is on the line, so no scan finding carries the
  hedge, #921), at the risk the README's rule tables state. A model finding near a detection that
  names the same defect collapses into the scan's (`SecurityScan.duplicatesDetection`; for IaC,
  `SecurityRule.defectWords`, or a suggestion that replaces the detected line and does not
  write it again, `removesDetectedLine`, #952). A generic secret assigned to a distinctive name is also matched by
  that name at any distance, in its own file and from a doc or config file
  (`SecurityScan.namesSameSecret`, `documentsIdentifiedSecret`, #1072). Keep new deterministic
  findings on that side of the verifier.
- **`finish` is the one place every lane converges.** The single call, a salvaged cut, the
  batches and the summary-only lane all pass through `FindingPipeline.finish` before the summary
  call and persistence. A filter that must hold for every published finding (the scan merge, the
  open-thread guard) goes there, not in `refine` or the batch path.
- **A selected decline can demote only its supported policy class** (#1070).
  `ReviewLearnings.recall` returns the same ranked, credential-screened and capped list for the
  fenced prompt and `ReviewEvidence.selectedLearnings`; rows excluded by those caps cannot act on
  findings. `DeclineLearningGuard` recognizes only the complete explicit decision not to pin images
  by digest in Dockerfiles, alone or with the audited rationale that the release pipeline resolves
  every image to a digest at deploy time (optionally also stating the deployed manifest carries it).
  Policy matching preserves punctuation. Case, whitespace and one final period may vary, and the
  contraction accepts straight/curly apostrophes or the explicit `dont` spelling. The exact audited
  rationale may follow whitespace or one colon, period, comma or semicolon; other punctuation,
  qualifiers, incomplete text and other rationales abstain. It also requires a mutable-reference
  finding on the same file type, and an actual
  `FROM` or external `COPY --from=` image the finding names. The finding stays visible at low
  confidence with its learning id. Both its title and description must be complete policy-only
  forms from `DeclineLearningGuard`: the four unpinned-base-image titles and five mutable-tag or
  identical-image-content rebuild descriptions. Case, whitespace and one final period may vary;
  punctuation, negation and extra claim text are never discarded. Only the verifier's exact appended
  unverified paragraph is recognized as metadata and preserved. Unfamiliar finding wording relies
  on the model's learning assessment. Unknown or conditional premises, other classes and file types,
  current repository or applicable scoped digest requirements, and independent failure/security
  evidence are left alone. Losing identical image contents on rebuild is that policy's own
  consequence; actual functional failures and failed-check evidence still abstain. Current digest
  context retains headings, wrapped lines and implicit references across sentences. Any image
  subject (image, container, Dockerfile or base) or case-sensitive/backticked `FROM` or `COPY`
  directive together with digest/SHA wording abstains, including declarative rules without a fixed
  requirement keyword. SHA-family tokens accept numeric suffixes (SHA1, SHA512, SHA3) and normalized
  hyphenated forms, with whole-token boundaries that reject lookalike words. An applicable
  Dockerfile-scoped rule already supplies the subject, so digest/SHA wording there also abstains.
  Ordinary English "from" or "copy" and global artifact-only digest prose do not establish an image
  requirement; ambiguous image requirements conservatively abstain.
  The check runs in `finish`, after calibration and before linked-issue
  evidence floors and the deterministic scan. Scan findings never pass through it.
- **AI services are stateless.** Every `@RegisterAiService` sets
  `NoChatMemoryProviderSupplier`. Review state travels in the previous-findings section, never in
  chat history.

## Follow-up round invariants

Earlier rounds are read from the stored responses (`ReviewSession.aiResponseJson`), never from the
text of the bot's reviews. Finding ids are positions in the effective round, the newest earlier
round that raised findings (`FollowUpAnalyzer.effectivePreviousRoundIndex`, #455); the same number
is the `finding=N` marker on the inline comment. Positions restart at 1 every round, so since
#927 the marker also carries `key=` (8 hex digits of a SHA-256 over the finding's file, line and
title, `SuggestionFormatter.findingKey`), which makes it unique across rounds; a keyed thread
binds to a finding only by that key, and a pre-#927 marker without one binds by the finding's own
content as before (`FollowUpAnalyzer.markerRootComment`). A position held by another finding's
comment never binds; it only keeps the title scan for pre-marker comments from binding there
(`positionMarkedOnFile`). `SummaryAcrossRoundsTest` and
`OpenThreadDuplicateGuardTest` drive most of the rules below through several rounds.

- **A finding still open on its own thread is never posted again, from any round** (#939).
  `buildPreviousFindingsContext` lists the older rounds' open findings in an unnumbered section the
  model must not report on, and `FollowUpAnalyzer.withoutOpenThreadDuplicates` drops a restatement
  that gets through anyway, in `FindingPipeline.finish` so every lane and retry is covered.
  `SecurityScan.merge` applies the same rule to scan detections.
- **The still-open set is one entry per open finding, by identity** (#917, #934).
  `FollowUpAnalyzer.stillOpenFindings` is the distinct in-range `unresolved` ids plus the backstop's
  holds; the two cannot overlap because the backstop skips ids the round reported. The summary's
  counts and lists, the "Still present" count and the verdict all read this one set.
  `PrSummaryGenerator.currentState` folds a carried finding into a new one only on an exact
  re-raise (`reRaises`: same file, line and title). Do not fold by similarity.
- **The verdict is computed over the round's findings plus the still-open set** (#948).
  `VerdictBuilder.buildResult` passes both to `ReviewState.fromFindings`, so a backstop hold can
  request changes, not only downgrade an approval
  (`SummaryAcrossRoundsTest.anEarlierCriticalStillOpenRequestsChangesWhenTheNewFindingsAreLow`).
- **No "no issues" wording while anything is open** (#933). Every surface reads
  `ReviewResult.unresolvedPreviousCount()`; `ReviewPublisher.noIssuesBody` leads with the
  unresolved-previous message and `LargePrNudge` stays silent.
- **A decline is weighed, on every round it applies to** (#169, #947). One write-access reply whose
  premise the reviewed code contradicts reopens the finding (`recheckDeclines`); a second reply
  always stands. A decline on an older round's finding goes through the same test in the backstop
  (`backstopEntry`, `reopenedEarlierDecline`): held `unresolved` when contradicted or resting on
  an unconfirmed premise, otherwise `justified` with `EARLIER_ROUND_ID` (0). Id 0 maps to no
  finding, so nothing keyed by id resolves or learns from it; the standing decline instead carries
  its finding and the root of its own thread, found by marker and content
  (`ReviewResult.EarlierRound`), which `ReviewPublisher.resolveAddressedThreads` resolves and
  `FollowUpAnalyzer.survivingEarlierRoundDeclines` offers to the learnings capture (#958).
- **Every finding carried in gets a status, and an earlier round's close is recorded once**
  (#957, #959, #1066). `backstopEntry` gives each cluster one entry: a hold, `resolved` for a
  maintainer's clear (a conversation directive, or `@thrillhousebot resolved` replied on its own
  thread, `clearedOnOwnThread`), `superseded` when its code left the diff, `resolved` when its
  code is still there but its suggested fix landed (`suggestedFixLanded`: every line of
  `suggestion_new` minus `suggestion_old` is an added line of the file within the suggestion's
  length of where `suggestion_old` sits (`DiffLineResolver.addsEveryLineNear`), one of at least
  `MIN_FIX_LINE_CHARS`; lean to hold, a false close is worse than a missed one), `justified` for a
  conversation decline (re-checked like the effective round's), or the thread decline's outcome.
  A clear is checked before presence, so it is not swallowed by the supersede. Each backstop
  status carries `ReviewResult.EarlierRound`; its id is relative to its own round, so
  `HeadMovedGuard.findingsToCarry` skips it. A `resolved` or `superseded` entry
  (`PreviousFindingStatus.closesEarlierRound`) also carries its own thread's root
  (`clusterThreadRoot`: marker and own content, no reply required), so `resolveAddressedThreads`
  resolves it; it counts in `ReviewResult.resolvedPreviousCount` and `ClosedFindingNames` names it
  from the finding, unless the round's own status already closes the effective round's copy of the
  same defect (`EarlierRound.namedByTheRound`), so a defect is named once. The orchestrator then records those findings, every round's copy of the defect
  (`EarlierRound.members`, `ReviewResult.earlierRoundClosedMembers`), with the round's stored response
  (`ReviewResponse.closedEarlierRound`, never written by the model: `ReviewResponseParser` drops
  it from every answer, and every lane rebuilds the response with the three-argument constructor
  in `FindingPipeline.persistWithSummary`), and
  `replayRounds` drops them from every later replay, so no later round resolves, names or counts
  them again (#470). `hasSupersededPrevious` still leaves earlier rounds out, so an older close
  never re-posts the summary. Holds and standing declines are recomputed every round. The
  summary's status table counts every status.
- **A "Things to double-check" item is counted once and replaced only by the same defect** (#951,
  #961). A listed-only repeat at no higher severity is dropped by the open-thread guard
  (`restatesListed`); a finding at the same or higher severity replaces the item
  (`replacedPriors`, `withoutReplaced`). An item this round's own finding replaces
  (`replacedThisRound`) keeps an `unresolved` status under `REPLACED_NOTE` and is listed as its
  replacement in the still-open set, so "Still present" adds up to the findings carried in and the
  summary folds it into the round's entry as open since an earlier review; the delta section does
  not count it as new (`FollowUpDeltaSummary.newFindingCount`, #969). The guard also drops a new
  listed item restating an open threaded finding. The summary's "Same issue as" note
  (`SummarySurfaceDeduplicator.restatedBy`) uses the same predicate, so the note and the counts
  never disagree (#972). All of them go through `DefectIdentity.sameDefect`, which is symmetric: in
  one file, the same title, a similar title or a title-vs-description phrase within three lines,
  the same line with two shared title words or a common identifier, or titles that restate each
  other (shared phrase and 0.75 overlap); in any file, a key or symbol named by both titles plus
  another shared title word or defect class (#956); a subject that is only the class a file is
  named after (common prose) pairs only when one description also names a symbol of the other's
  title; or a description citing the other's `file:line` and naming a symbol of its title. A qualified
  citation must match its directory too; a shared basename alone cannot bind another directory
  (#1100). Two
  vetoes come first: titles naming different identifiers (`namesDifferentIdentifiers`), and titles
  naming disjoint defect classes (`classesConflict`: a race is not an off-by-one, #964). A key
  named only in a description, or common title words, are not identity (`DefectIdentityTest`).
- **A declined scan finding stays settled while its content is unchanged** (#982). The scan reads
  no learning and is not model output, so it needs its own memory:
  `FollowUpAnalyzer.justifiedPriorFindings` hands every persisted `justified` scan finding to
  `SecurityScan.merge`, which does not raise a detection with the same file, title, anchor and, for
  a secret, the same `<!-- thrillhousebot:scan-content=… -->` fingerprint (8 hex digits of an
  HMAC-SHA-256 over the rule and the stripped line, keyed from the webhook secret by
  `SecurityScan.fingerprintKey`, #985; a decline under the unkeyed 0.7.0 marker still matches
  through `Detection.legacyFingerprint`). Each decline covers one detection
  (`DeclinedScanFindingAcrossRoundsTest`).
- **Summary carry-over preserves every distinct path.** `PriorOverviewCarryover` uses
  `PathIndex.contains` for coverage membership, preserving `FilePaths.same` directory-suffix
  semantics without enumerating groups of same-named files (#1104).
- **Follow-up counts and closures use one review outcome** (#1117). The opt-in delta is a body
  section, replacing the default closed-names section rather than creating a separate comment.
  The review retains its verdict and definite-refusal fallback; first reviews, a successful
  `/summary` and zero movement add no delta. Its coverage/verification notice is not repeated.
  Direct publisher callers and disabled/failed enrichment still name closures by default.
  `FollowUpAnalyzer` removes the delta's bounded counts/closed-name section from fallback prior
  context, preserving subsequent findings; leading delta status prose is never a prior finding.
- **The summary comment is found by its marker every round** (`<!-- thrillhousebot:summary -->`,
  or the heading for summaries from before #868), never by an id held in memory, so a restart
  loses nothing. An unchanged body is not rewritten; a failed edit posts a new comment.
- **A run whose head moved stands down before its first write** (#704, #806) and hands its
  verified findings to the next run through `SupersededFindingsCarryover`, so two runs never edit
  the summary at once and nothing is posted against a diff that changed. This is `HeadMovedGuard`,
  run by `ReviewStandDown` before `LabelGateGuard`; the orchestrator only closes out the session.
- **The label gates read the labels the pull request carries now, not only the payload's** (#1021).
  Dependabot labels a pull request a second or two after opening it, so `WebhookController` reads
  them from the API on `opened`, `reopened` and `ready_for_review` (payload fallback on a failed
  read), `LabelGateGuard` re-reads them before an automatic run's first write, after the head
  guard (fail open, like the head guard), and `CiHoldRevisit` before posting a held approval (fail
  closed: the hold stays). All three go through `PullRequestLabelGate`, which reads nothing when
  no label gate is configured, and skip a manual `/review` (`HeldVerdict.manualTrigger`).

## Prompts

### Structure

- `PrReviewPrompts.SYSTEM` is the core (`CORE_IDENTITY`, `CORE_FINDING_FIELDS_AND_SEVERITY`,
  `CORE_SELF_CHECK`, `FINDINGS_RESPONSE_CONTRACT`) plus ten `DIMENSION_*` blocks. Correctness,
  security and regressions are always on (`ReviewDimension.alwaysOn`).
- `ReviewDimensionRouter` picks each call's blocks when `REVIEW_DIMENSION_ROUTING_ENABLED` is on
  (off by default, and then every call sends `SYSTEM` byte for byte). The routed prompt comes from
  `PrReviewPrompts.reviewSystemPrompt(Set)`, with the core first so providers can cache the
  prefix. The verifier's carve-outs follow the same switch:
  `FindingVerificationService.verifierSystemPrompt` routes them by the files its candidates are
  anchored in (`ReviewDimensionRouter.dimensionsForPaths`).
- The review call's one template variable is the `AiReviewService.PromptInputs` record, bound as
  `@V("inputs")` on `PrReviewer.reviewStream` (#993); `PrReviewPrompts.USER` reads its components
  as `{{inputs.diff}}` and so on. The record is `@TemplateData` so the native image resolves them
  without reflection. The system prompt reaches the model the same way
  (`@SystemMessage("{{inputs.reviewSystemPrompt}}")`, null reads as `SYSTEM`). The assembler sets
  the whole PR's prompt, the planner sizes the overhead from it, and the pipeline narrows it per
  batch. A batch's prompt is never larger than the PR's.
- A new dimension needs a block, a `ReviewDimension` constant, a router rule and a must-find eval
  case: `EvalCorpusTest.everyRoutedDimensionHasAMustFindCase` fails without one.

### Adding a context section to the review call

Two patterns. Pick the first unless the section must sit somewhere else in the user message.

1. **Trailing-guidance slot (the default).** Review-only guidance and data ride
   `PromptInputs.repoInstructions`, which `ReviewPromptAssembler.assemble` builds with
   `combineSections` (mock fidelity, bug-fix efficacy with linked issue text, config-key context,
   heuristic failure modes, patch coverage, then CI failures, linked issues and learnings in that
   fixed order, then repo instructions). The three opt-in sections arrive together as one
   `ReviewPromptAssembler.TrailingContext` passed to `assemble(ctx, req, extra)`; add a component
   there rather than another overload. Add a static `…Section` method that returns `""` when there
   is no data and emits the guidance constant only together with its fenced data (see
   `patchCoverageSection`, `ciFailuresSection`, `learningsSection`). Every batch carries the slot,
   and `DiffBudgetPlanner.plan` already counts it as shared overhead
   (`DiffBudgetPlannerTest.ciFailuresLinkedIssuesAndLearningsTogetherAreAllChargedToTheSharedOverhead`).
   No new record component of `PromptInputs` or planner change is needed. Guidance for the
   summary call goes in `summaryInstructions` instead, which `FindingPipeline` counts when it clamps
   the summary input.
   A section can ride both slots with a different request in each: the linked-issue section
   (`linkedIssuesSection`) gives the review call `TicketContextPrompts.REVIEW_REQUEST` and the
   summary call `TicketContextPrompts.SUMMARY_REQUEST`, each with its own fence, and the CI-failure
   section gives the summary call `PrReviewPrompts.CI_FAILURES_SUMMARY_REQUEST`
   (`ciFailuresSummarySection`, #922); the sentence it asks for is replaced by a computed one (`CiFailureOverview`, #1075).

   A section triggered by the diff text cannot be decided in the assembler: with budgeting on
   (the default) `ReviewContextLoader` leaves `ctx.diff()` empty. Decide it per batch in
   `FindingPipeline.BatchPrompts.forBatch`, as `heuristicFailureModesFor` does. That addition is
   not in the planned overhead, so keep it a small fixed constant the token safety margin absorbs.
2. **A new template variable (lockstep).** A section that needs its own `{{#if}}` block in
   `PrReviewPrompts.USER` changes all of these in one diff: the prompt constant (read as
   `{{inputs.<component>}}`), a `PromptInputs` component (and its convenience constructors),
   `ReviewPromptAssembler.assemble`,
   the other places that rebuild the record (`DiffBudgetPlanner.boundPreviousFindings`,
   `FindingPipeline.BatchPrompts.withDiff`), and the `sharedOverhead` sum in
   `DiffBudgetPlanner.plan`. Add the variable to `AiServicePromptRenderingTest`, including the
   byte-for-byte rendering check.

The verifier does not see review-call context. If a finding raised from the new section must
survive verification, attach the supporting material per finding through
`ContextEvidenceResolver` (#475).

### Where prompt overhead is estimated

A prompt constant or section left out of these sums lets an "in-budget" call overshoot the real
input limit:

- `DiffBudgetPlanner.plan(List, PromptInputs)`: the review call (system prompt, `USER`, fence
  scaffolding and every non-diff section).
- `FindingPipeline.clampOverview` and `FindingPipeline.budgetedFindingsJson`: the summary call
  (`SUMMARY_SYSTEM`, `SUMMARY_USER` and its sections).
- `AbstractPrSuggestionGenerator.sharedPromptOverhead` / `planBatches`: the on-request commands.
  A command with an extra per-call section passes it as `extraPerCallSections`.

The verifier does no budget arithmetic of its own. `PrContextBudget.bound` caps the author's PR
text on that call (#736). Size fences with `PromptTemplateEscaper.fenceForBudgeting()`, never a
live `fence(...)` (#604).

### Guards

- **`@UserMessage` stays on the method, never a parameter.** On a parameter,
  quarkus-langchain4j sends only that parameter and drops every other `@V`. Every AI service
  carries a comment saying so. `AiServiceUserMessagePlacementTest` checks the placement
  structurally (one test per service), and `AiServicePromptRenderingTest` renders the services
  through the real template engine and asserts each `@V` arrives. Add a new service or variable
  to both.
- **Prompt-content tests pin guidance** that specific issues fought for:
  `PrReviewPromptsContentTest`, `FindingVerifierPromptsContentTest`,
  `PrImproveAssistantPromptsContentTest`, `UnitTestAssistantPromptsContentTest`,
  `TicketContextPromptsContentTest`, `SummaryVerifierPromptsContentTest`. Update a pin
  deliberately when you reword it. Never delete one to make a build pass.
- **The eval corpus is the recall gate.** `src/test/resources/evalcorpus/` holds labeled cases:
  `verifier` and `generator` cases for the review prompts, and `summary` cases (#920) that run
  `PrSummarizer` and expect a description claim back as a gap and nowhere it reads as implemented
  (`claim-gapped`), or, for a claim the changed-files evidence can carry, not as a gap
  (`claim-not-gapped`), and `summary-verification` cases (#1078) that run the summary check over a
  written summary and expect the statements their keywords name to be cut and the kept ones to
  stay (`contradicted-cut`), or nothing cut (`nothing-cut`).
  `EvalCorpusTest` validates the schema and routing coverage in every build. The live
  `PromptEvalTest` is tagged `eval`, runs only with `-Peval`, and skips itself without
  `QUARKUS_LANGCHAIN4J_OPENAI_API_KEY`:
  `QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=… ./mvnw test -Peval -Dtest=PromptEvalTest`. Run it before
  shipping a change to `PrReviewPrompts` or `FindingVerifierPrompts`, with
  `REVIEW_DIMENSION_ROUTING_ENABLED=true` too when routing is involved, and with
  `-Deval.only=summary-verification` before shipping a change to `SummaryVerifierPrompts`. Without
  a key, say in the PR that it was not run.

## Untrusted data

- **Fence all model-visible external text.** That covers the diff, PR title and body, base
  comparison, project stack, changed tests, previous findings, linked issue text, CI output,
  coverage data, config-key snippets, and maintainer-authored instructions (global and
  path-scoped). Use `PromptTemplateEscaper.fence`, which wraps text in per-call CSPRNG fence
  lines and passes it byte-exact, and give it a heading in the prompt that calls it untrusted data.
  `escape` / `neutralizeMarkers` only rewrite legacy markers. They are not an injection defense.
- **Linked issues are read, never written, and their text is untrusted.** An
  `IssueTrackerProvider` only reads: no comment, label or state change on the issue, ever
  (`GitHubIssuesProviderTest.onlyReadsNeverWrites`). PR-body fence indentation uses absolute
  four-column tab stops, including list padding and blockquote delimiters; only the indentation
  comparison resets after a quote marker (#1032). Only issues of the PR's own repository are
  read. Issue title and body are attacker-editable, so `TicketContextResolver` strips them with
  `CiFailureContextResolver.clean`, clips them and caps the section, and the assembler fences it
  in both calls. An acceptance criterion the change does not address is a summary
  `description_gaps` entry (`Linked issue #N: …`), never a finding: a finding claims a defect on
  a changed line, a missing criterion is scope. A finding the review call does raise against a
  criterion (the changed code contradicts it) is held to the issue text deterministically
  (`LinkedIssueCitations`, #1073). A numbered attribution reads only that issue's body; explicitly
  plural attributions read the named bodies, and unnamed ones read the full linked-issue section
  (#1109). Each citation pass shares advancing cue/source/sentence searches and quote-position
  indexes; it does not reread every preceding quote or absent-pattern suffix for each cue. Quote
  indexes preserve Unicode guards and prefix-only quote boundaries. Cuts remain sequential and
  recheck the changed text's attribution; this does not bound whole-string cut copying as linear.
  The gaps are not turned into findings. The bug-fix efficacy section drops its own copy
  of the issue text when the linked-issue section is present.
- **Sanitize model and GitHub text before logging it.** Wrap every such value in
  `LogSafe.oneLine` at the point where the accessor is read. `LogSafeInvariantTest` derives the
  untrusted accessors from the `*Response` records in `review.ai` and fails on a log call that
  splices one in, directly or through a same-file local. It cannot follow a value across a method
  boundary, so wrap early. `%d` numbers are exempt.
- **Text screens preserve code and ordinary claims.** `PromptLabelScrubber` keeps the case of a
  recovered compound identifier and removes "confirmed" only when it labels findings, never as
  the verb in "Tests confirmed findings" or "This confirmed findings" (#1098, #1102). Standalone
  labels after bullet, asterisk emphasis and arrow prefixes still clean up; a formatted subject
  keeps its verb. Its whole-text restart pass and `PathMentions` share `MarkdownFences`:
  backtick and tilde blocks close only at a run of their own marker at least as long as the opener
  and at its quote depth; list prefixes are recognized, and mixed, shorter or quoted markers inside
  a block and unclosed blocks stay byte-exact (#1099, #1111). Inline-span probing stops at the next
  marker or newline instead of rereading the line's tail for every span. `OverviewScreen` keeps
  count conjunctions with their clause while screening compound or approximate review counts,
  preserves real file roles using the same numbers, and treats "cf." as an abbreviation before a
  ticket key (#1105, #1106). `DescriptionGapScreen` matches a quoted compound identifier to
  possessive prose such as "server's URL" while still rejecting unsupported quotes (#1108).
  A carried-gap label joined to a word inside a parenthetical takes its part of the
  parenthetical with it (`DescriptionGapLabels.labelInAPhrase`, parts split at commas, semicolons
  and spaced dashes); a part set off by a separator stays (#974, #1095). `PathMentions` never cuts a name out of a code span in a finding's description, only a
  span that fills a parenthetical. `PriorOverviewCarryover` replaces a purpose paragraph led by a size
  statement (`sizeOnly`: the first sentence's leading clause, followed by nothing or a side note that
  opens with "with", "plus", "including" or "also", then any later sentence, the computed CI
  sentence aside) on an unchanged head (#1095).
- **Text the bot posts to GitHub** goes through `review/MarkdownSafe`, not `LogSafe`.
- **Review learnings are maintainer text and stay data.** `ReviewLearningService.save` refuses
  credential-shaped text (`LearningText.containsCredential`) and flattens the rest
  (`LearningText.normalize`); `ReviewLearnings.select` screens again before replay, and
  `ReviewPromptAssembler.learningsSection` fences the list under `PrReviewPrompts.LEARNINGS_REQUEST`.
  `LearningCommands` echoes learnings back only inside inline code spans.
- **Only a decline that survived the re-check becomes a learning.** Capture reads the round's
  final statuses (after `FollowUpAnalyzer.recheckDeclines`), and
  `FollowUpAnalyzer.survivingDeclines` also refuses any reason that
  `RebuttalContradiction.assertsRefutablePremise` flags, a decline that stood only by the
  second-reply escape hatch, a decline without a reason, and every decline while
  `REVIEW_DECLINE_RECHECK_ENABLED` is off (`StartupConfigValidator` refuses that combination).
  Never widen capture to 👍/👎 or bare replies: a wrong learning suppresses a valid finding on
  every later pull request (PR #160).
- **Learnings are scoped per installation and repository.** Every `ReviewLearningService` read
  and write filters on both (`listForAudit` is the dashboard's repo-access-checked exception), and
  `retract` treats an id from another repository as not found.
- **A detected secret is never echoed.** Only `SecretScanner.redact` output (first characters and
  length) goes into a scan finding's title and description, and a secret finding has no
  `suggestion_old`, because the anchor is persisted with the session and shown on the dashboard.
  `SecurityScan.Scrubber` replaces every verbatim occurrence of a matched value in the same
  response's model findings, status notes and summary before it is persisted, posted or handed to
  the summary call. `SecurityScan` logs counts only. The cross-round `scan-content` marker (#982)
  holds 8 hex digits of an HMAC-SHA-256 over the rule and the line, keyed so a reader cannot
  test a guess against it (#985), never the line itself. Never log,
  persist or post `SecretScanner.Hit.literal()`, and keep test credentials generated at run time
  (`FakeCredentials`), never as literals.

## Native image

- The production image is GraalVM native. A class Jackson binds by reflection (a record the bot
  deserializes or serializes, a REST client DTO) needs `@RegisterForReflection`. See
  `review/ai/ReviewResponse` and `github/GitHubLabelClient.Label`. Code that only needs a fixed
  wire shape can build it on Jackson's tree model instead and need no metadata
  (`notification/NotificationPayloads`).
- Classes holding a static `SecureRandom` are initialized at run time
  (`quarkus.native.additional-build-args` in `application.properties`). Add a new one there.
- Classpath resources read at run time must be listed in `quarkus.native.resources.includes`
  (e.g. the jtokkit BPE table `TokenCounter` loads).

## Tests

- **Summary declaration evidence excludes literal bodies.** `ChangeSymbols` treats complete quoted
  words and backtick or dollar substitutions as shell words before a heredoc. Spaces and equals
  signs inside those atoms are not assignment syntax;
  assignments and bare parenthesized/braced groups retain their code controls. The lexical prefix
  facts and suffix end are shared per line, so many candidate operators do not rescan the prefix
  or allocate whole suffixes. Keep spaced/unspaced parity and long-input controls when changing
  this reader.
- **Streaming test doubles** live in `src/test/java/.../review/ai/`, one `TokenStream` per shape:
  `FakeTokenStream` (fixed payload), `CompleteOnlyTokenStream`, `DelayedChunkTokenStream`,
  `ErrorTokenStream`, `PartialThenErrorTokenStream`, `LateErrorTokenStream`, `HangingTokenStream`,
  `PartialHangingTokenStream`, `OrphanedTokenStream`, `OrphanedAfterCompleteTokenStream`,
  `OrphanedAfterErrorTokenStream`, `TruncatedTokenStream` (length stop, including
  `reasoningExhausted`), and `ControlledTokenStream` (finished by hand, counts streams in flight).
  Reuse one before writing a new one.
- `StreamingChatModelListenerOrderingTest` pins the LangChain4j ordering the ledger relies on:
  the listener's `onResponse` runs before the stream's completion handler.
- Test behavior, not implementation. Prefer package-private seams to reflection on private
  members (see CONTRIBUTING.md).

## Persistence

No migration tool. Prod runs Hibernate `schema-management.strategy=update`, and dev runs
`drop-and-create` (`application.properties`). Adding an entity column is picked up
automatically. A rename or drop is not, so it needs a deliberate plan.

Entities: `ReviewSession` (dashboard), `PausedPr` (webhook), `FindingFeedback` (review, table
`finding_feedback`, 👍/👎 signals) and `ReviewLearning` (review, table `review_learning`, #38).
`ReviewSession.aiResponseJson` is more than a dashboard field: it is the record every follow-up
round reads its earlier findings and statuses from, so a change to the stored `ReviewResponse`
shape must still parse the rows already stored. A learning is never deleted
by the bot: `ReviewLearningService.retract` clears `active` and records who and when, and
`dedupKey` makes a re-reported decline or a redelivered `/remember` idempotent.

## Why-nots

| Rejected alternative | Why |
|---|---|
| Default chat memory on AI services | Every call shared one memory id, so each review resent the previous reviews' prompts and answers (#584). |
| Retrying a length stop that produced content | The identical call is cut identically; salvage what completed instead (#495). |
| Stepping reasoning down one tier on an empty length stop | Tiers barely move reasoning length on the measured provider, so the cap burned twice. The repeat goes straight to `none` (#839). |
| Summary fields on the review call | The hottest call paid input for the summary spec and output for the object, a response cut at its cap lost the summary, and the counts described unverified findings. Now a dedicated summary call on both lanes (#664). |
| Sizing each batch from its own routed prompt | Circular (prompt depends on files, files on budget). The PR-wide prompt bounds every batch (#665). |
| Reading the answer from the first bracket or the last fence | Deliberation brackets and fenced excerpts ahead of the answer lost findings, and split answers span fences (#894, #805). |
| Budgeting from a live random fence | Its token width varies, so plans were not reproducible and tests flaked (#604). |
| Dropping findings when the verifier fails | Loses the reviewer's work to an outage. Keep them, marked unverified and capped at medium (#623, #885). |
| Putting deterministic scan findings through the verifier | The verifier sees the same diff the pattern read, so it can only demote or drop a certain match, and a fail-open round would mark it unverified (#60). |
| Showing a follow-up only the effective round's findings | Once a round raised anything new, the older rounds' open findings were no longer listed, and the next `/review` posted most of them again beside their open threads (#939). |
| Folding carried findings that read alike into one summary entry | An open HIGH or CRITICAL dropped out of the counts and Key Findings while "Still present" still counted it, and the survivor could be the lower severity (#934). Carry by identity. |
| Computing the verdict from the round's new findings and the model's `unresolved` ids | Findings only the backstop held could downgrade an approval but never request changes, so a round listing an open CRITICAL ended as COMMENT (#948). |
| Letting any maintainer reply settle an older round's finding | The model reports only on the effective round, so a wrong "cannot run concurrently" reply on an older finding removed it with no re-check (#947). |
| Matching double-check items by proximity and shared wording | A finding about one config key replaced the open item about the key on the next row (#961). `DefectIdentity` requires an anchored match and vetoes different identifiers. |
| Relying on learnings or the model to keep a declined scan finding settled | The scan reads neither, so the decline lasted one round and the next round requested changes on the same head (#982). |
| A call-graph taint pass in `LogSafeInvariantTest` | Measured: 31 extra reports, sampled ones were name collisions with impossible fixes (#764). |

## CHANGELOG and docs

- Add entries under `## [Unreleased]` only, in the matching `### Added` / `### Changed` /
  `### Fixed` subsection, in the existing style (bold one-line lead, issue number in parentheses,
  then why). Never edit a released section. On a merge conflict keep both sides' entries.
- Update `README.md`, `.env.example` and `docs/` in the same PR when operator-visible behavior or
  configuration changes. A new config key also goes through `ThrillhouseConfig` and, if it has a
  valid range, `StartupConfigValidator`.
- This repository's own review instructions are `.github/thrillhousebot.md`. It is first in
  `InstructionsResolver`'s fallback chain, so the bot does not read this file when reviewing
  this repository.
