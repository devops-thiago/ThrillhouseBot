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
| Top-level run: check run, context, plan, pipeline, verdict, publish, head-moved abort | `review/ReviewOrchestrator` |
| Load diff, every earlier round (from the stored `ReviewSession.aiResponseJson`), threads and conversation, instructions, labels, project stack, config-key and patch-coverage context | `review/ReviewContextLoader` (with `PatchCoverageResolver`, `ConfigKeyContextResolver`, `BugFixContextResolver`, `SupersededFindingsCarryover`) |
| Previous-findings prompt section: the numbered effective round, then the unnumbered "still open from earlier rounds" and "answered in earlier rounds" sections | `review/FollowUpAnalyzer.buildPreviousFindingsContext` (with `effectivePreviousRoundIndex`, `openEarlierRoundFindings`) |
| Early CI reading and the opt-in CI-failure section | `review/CiStatusEvaluator`, `review/CiFailureContextResolver` |
| Opt-in review learnings: recall before the call (ranked, capped section) | `review/ReviewLearnings.promptSection` (reads `ReviewLearningService.listActive`) |
| Opt-in linked-issue context: resolve the PR's linked issues, read them, extract acceptance criteria, sanitize and cap | `review/TicketContextResolver.resolve` over an `review/IssueTrackerProvider` (`review/GitHubIssuesProvider.linkedTickets`: PR-body closing keywords, then `closingIssuesReferences`, then optionally the branch name), called from `ReviewOrchestrator` and handed to `ReviewPromptAssembler.assemble(ctx, req, ciFailures, linkedIssues)` |
| Fence untrusted input, build review and summary guidance, pick the system prompt | `review/ReviewPromptAssembler` (with `PromptSections`, `PromptTemplateEscaper`, `ReviewDimensionRouter`) |
| Token budget and batches | `review/DiffBudgetPlanner` (`plan`, `boundPreviousFindings`, `perCallInputBudget`) |
| Model calls: single call, batches, retries, sequential retry, truncation salvage, summary call | `review/FindingPipeline.run` → `review/ai/AiReviewService` (`review`, `reviewBatch`, `summarize`) |
| Post-model chain, single call, in order: evidence, quote validation, framework filter, dedupe, rejection memory, verifier, severity calibration, replied-duplicate and litigated drops, anchors | `review/FindingPipeline.refine` |
| Post-model chain, multi call: per batch evidence, quote validation, framework filter, rejection memory, verifier (`refineBatchOutcome`); after all batches status merge, dedupe, replied-duplicate and litigated drops, anchors. No severity calibration on this lane | `review/FindingPipeline.runMultiCall` |
| Verifier | `review/ai/FindingVerificationService`, evidence from `review/ContextEvidenceResolver` and `review/CitedLocationResolver` |
| Last steps on every lane, including the one with no review call: the opt-in deterministic security scan (secrets, risky IaC), then the open-thread duplicate guard (#939) | `review/FindingPipeline.finish` → `review/SecurityScan.merge` (rules in `SecurityRule`, detection in `SecretScanner` and `IacScanner` over `PatchLines`) → `FollowUpAnalyzer.withoutOpenThreadDuplicates` |
| Summary call, carried description gaps, reused overview, prompt-label scrub, persistence | `review/FindingPipeline.withSummary` (with `DescriptionGapCarryover`, `PriorOverviewCarryover`, `PromptLabelScrubber`), `persistWithSummary` |
| Follow-up status reconciliation, in order: supersede, decline re-check, conversation clears, phantom ids, double-check replacement, approval backstop, still-open set | `review/VerdictBuilder.build` over `FollowUpAnalyzer` (`supersedeVanished`, `recheckDeclines`, `clearNamedInConversation`, `withoutPhantomUnresolved`, `replacedPriors`, `heldPreviousFindings`, `stillOpenFindings`) |
| Verdict and check-run text | `review/VerdictBuilder.buildResult` |
| Post review, inline comments, thread resolution | `review/ReviewPublisher`, `review/CheckRunManager` |
| Summary comment: render over the round plus the still-open set, edit in place by marker | `review/PrSummaryGenerator` (`currentState`), `review/ReviewPublisher.publishSummary` / `upsertSummaryComment` |
| CI hold and revisit | `review/CiHoldRegistry`, `review/CiHoldRevisit` |
| Opt-in review learnings: capture after the post, from the round's final statuses | `review/ReviewLearnings.captureSurvivingDeclines` → `review/FollowUpAnalyzer.survivingDeclines` → `review/ReviewLearningService.save` |
| `/learnings`, `/remember`, `/forget` | `webhook/LearningCommands` (routed by `CommentCommandService`) |
| Maintainer replies and mentions | `review/MaintainerReplyService`, `review/MaintainerReplyDispatcher` |
| On-request commands (`/describe`, `/changelog`, `/add-docs`, `/improve`, `/generate-tests`) | `review/AbstractPrSuggestionGenerator` and its subclasses |

## Model-call invariants

- **Every model call holds a `ModelCallGate` slot** (`AI_MAX_CONCURRENT_CALLS`). Streamed calls
  take it in `AiReviewService`; every blocking AI service reaches the model through
  `BoundedChatModel`. `BoundedChatModelWiringTest` fails for an AI service wired any other way.
- **Every review-path call is metered by `ReviewTokenLedger`** (`REVIEW_MAX_TOKENS_PER_REVIEW`).
  `FindingPipeline.run` opens and clears the session entry. Streamed calls are recorded by
  `OtelObservabilityListener` through the `ReviewSessionContext` bind and gated by
  `ensureCallAllowed` in `AiReviewService`. The blocking verifier records its own usage and checks
  `ceilingReached` in `FindingVerificationService`. A new review-path call must do one or the
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
- **Verification fails open.** Any verifier error, cut response or ceiling skip keeps the
  candidates. `FindingVerificationService.markUnscreened` caps them at medium confidence and
  appends the unverified note (#885), and `VerificationCoverage` discloses the round's coverage. Do
  not make a verifier failure drop findings or block a review.
- **Deterministic scan findings skip the verifier and calibrator.** `SecurityScan.merge` runs in
  `FindingPipeline.finish`, after every verifier call and (on the single-call lane) after
  `SeverityCalibrator.calibrate`, so no model grades a pattern match, and `markUnscreened` and
  `VerificationCoverage` never see one. The grade comes from `SecurityRule`. Keep new
  deterministic findings on that side of the verifier.
- **`finish` is the one place every lane converges.** The single call, a salvaged cut, the
  batches and the summary-only lane all pass through `FindingPipeline.finish` before the summary
  call and persistence. A filter that must hold for every published finding (the scan merge, the
  open-thread guard) goes there, not in `refine` or the batch path.
- **AI services are stateless.** Every `@RegisterAiService` sets
  `NoChatMemoryProviderSupplier`. Review state travels in the previous-findings section, never in
  chat history.

## Follow-up round invariants

Earlier rounds are read from the stored responses (`ReviewSession.aiResponseJson`), never from the
text of the bot's reviews. Finding ids are positions in the effective round, the newest earlier
round that raised findings (`FollowUpAnalyzer.effectivePreviousRoundIndex`, #455); the same number
is the `finding=N` marker on the inline comment. `SummaryAcrossRoundsTest` and
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
  finding, so it resolves no thread and never becomes a learning.
- **A "Things to double-check" item is counted once and replaced only by the same defect** (#951,
  #961). A listed-only repeat at no higher severity is dropped by the open-thread guard
  (`restatesListed`); a finding at the same or higher severity replaces the item
  (`replacedPriors`, `withoutReplaced`). Both go through `DefectIdentity.sameDefect`: same file,
  then the same title, a similar title within three lines, or the same line with two shared title
  words or a common identifier, and never two titles naming different identifiers
  (`namesDifferentIdentifiers`). Content overlap alone is not identity (`DefectIdentityTest`).
- **A declined scan finding stays settled while its content is unchanged** (#982). The scan reads
  no learning and is not model output, so it needs its own memory:
  `FollowUpAnalyzer.justifiedPriorFindings` hands every persisted `justified` scan finding to
  `SecurityScan.merge`, which does not raise a detection with the same file, title, anchor and, for
  a secret, the same `<!-- thrillhousebot:scan-content=… -->` fingerprint (8 hex digits of a
  SHA-256 over the rule and the stripped line). Each decline covers one detection
  (`DeclinedScanFindingAcrossRoundsTest`).
- **The summary comment is found by its marker every round** (`<!-- thrillhousebot:summary -->`,
  or the heading for summaries from before #868), never by an id held in memory, so a restart
  loses nothing. An unchanged body is not rewritten; a failed edit posts a new comment.
- **A run whose head moved stands down before its first write** (#704, #806) and hands its
  verified findings to the next run through `SupersededFindingsCarryover`, so two runs never edit
  the summary at once and nothing is posted against a diff that changed.

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
- The system prompt reaches the model as a `@V("reviewSystemPrompt")` parameter
  (`PrReviewer.reviewStream`, `@SystemMessage("{{reviewSystemPrompt}}")`) carried by
  `AiReviewService.PromptInputs.reviewSystemPrompt` (null reads as `SYSTEM`). The assembler sets
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
   No new `@V`, record component of `PromptInputs` or planner change is needed. Guidance for the
   summary call goes in `summaryInstructions` instead, which `FindingPipeline` counts when it clamps
   the summary input.
   A section can ride both slots with a different request in each: the linked-issue section
   (`linkedIssuesSection`) gives the review call `TicketContextPrompts.REVIEW_REQUEST` and the
   summary call `TicketContextPrompts.SUMMARY_REQUEST`, each with its own fence.

   A section triggered by the diff text cannot be decided in the assembler: with budgeting on
   (the default) `ReviewContextLoader` leaves `ctx.diff()` empty. Decide it per batch in
   `FindingPipeline.BatchPrompts.forBatch`, as `heuristicFailureModesFor` does. That addition is
   not in the planned overhead, so keep it a small fixed constant the token safety margin absorbs.
2. **A new template variable (lockstep).** A section that needs its own `{{#if}}` block in
   `PrReviewPrompts.USER` changes all of these in one diff: the prompt constant, the `@V`
   parameter on `PrReviewer.reviewStream`, a `PromptInputs` component (and its convenience
   constructors), `AiReviewService.reviewStream`, `ReviewPromptAssembler.assemble`,
   the other places that rebuild the record (`DiffBudgetPlanner.boundPreviousFindings`,
   `FindingPipeline.BatchPrompts.withDiff`), and the `sharedOverhead` sum in
   `DiffBudgetPlanner.plan`. Add the variable to `AiServicePromptRenderingTest`.

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
  `TicketContextPromptsContentTest`. Update a pin
  deliberately when you reword it. Never delete one to make a build pass.
- **The eval corpus is the recall gate.** `src/test/resources/evalcorpus/` holds labeled cases.
  `EvalCorpusTest` validates the schema and routing coverage in every build. The live
  `PromptEvalTest` is tagged `eval`, runs only with `-Peval`, and skips itself without
  `QUARKUS_LANGCHAIN4J_OPENAI_API_KEY`:
  `QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=… ./mvnw test -Peval -Dtest=PromptEvalTest`. Run it before
  shipping a change to `PrReviewPrompts` or `FindingVerifierPrompts`, with
  `REVIEW_DIMENSION_ROUTING_ENABLED=true` too when routing is involved. Without a key, say in the
  PR that it was not run.

## Untrusted data

- **Fence all model-visible external text.** That covers the diff, PR title and body, base
  comparison, project stack, changed tests, previous findings, linked issue text, CI output,
  coverage data, config-key snippets, and maintainer-authored instructions (global and
  path-scoped). Use `PromptTemplateEscaper.fence`, which wraps text in per-call CSPRNG fence
  lines and passes it byte-exact, and give it a heading in the prompt that calls it untrusted data.
  `escape` / `neutralizeMarkers` only rewrite legacy markers. They are not an injection defense.
- **Linked issues are read, never written, and their text is untrusted.** An
  `IssueTrackerProvider` only reads: no comment, label or state change on the issue, ever
  (`GitHubIssuesProviderTest.onlyReadsNeverWrites`). Only issues of the PR's own repository are
  read. Issue title and body are attacker-editable, so `TicketContextResolver` strips them with
  `CiFailureContextResolver.clean`, clips them and caps the section, and the assembler fences it
  in both calls. An acceptance criterion the change does not address is a summary
  `description_gaps` entry (`Linked issue #N: …`), never a finding: a finding claims a defect on
  a changed line, a missing criterion is scope. The bug-fix efficacy section drops its own copy
  of the issue text when the linked-issue section is present.
- **Sanitize model and GitHub text before logging it.** Wrap every such value in
  `LogSafe.oneLine` at the point where the accessor is read. `LogSafeInvariantTest` derives the
  untrusted accessors from the `*Response` records in `review.ai` and fails on a log call that
  splices one in, directly or through a same-file local. It cannot follow a value across a method
  boundary, so wrap early. `%d` numbers are exempt.
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
  holds 8 hex digits of a SHA-256 over the rule and the line, never the line itself. Never log,
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
