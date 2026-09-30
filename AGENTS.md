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
| Load diff, prior reviews/findings, instructions, labels, project stack, config-key and patch-coverage context | `review/ReviewContextLoader` (with `PatchCoverageResolver`, `ConfigKeyContextResolver`, `BugFixContextResolver`) |
| Early CI reading and the opt-in CI-failure section | `review/CiStatusEvaluator`, `review/CiFailureContextResolver` |
| Opt-in review learnings: recall before the call (ranked, capped section) | `review/ReviewLearnings.promptSection` (reads `ReviewLearningService.listActive`) |
| Fence untrusted input, build review and summary guidance, pick the system prompt | `review/ReviewPromptAssembler` (with `PromptSections`, `PromptTemplateEscaper`, `ReviewDimensionRouter`) |
| Token budget and batches | `review/DiffBudgetPlanner` (`plan`, `boundPreviousFindings`, `perCallInputBudget`) |
| Model calls: single call, batches, retries, sequential retry, truncation salvage, summary call | `review/FindingPipeline.run` → `review/ai/AiReviewService` (`review`, `reviewBatch`, `summarize`) |
| Post-model chain, in order: evidence, quote validation, framework filter, dedupe, rejection memory, verifier, severity calibration, replied-duplicate and litigated drops, anchors | `review/FindingPipeline.refine` (single call) and the per-batch path in the same class |
| Verifier | `review/ai/FindingVerificationService`, evidence from `review/ContextEvidenceResolver` and `review/CitedLocationResolver` |
| Verdict and check-run text | `review/VerdictBuilder` |
| Post review, inline comments, thread resolution | `review/ReviewPublisher`, `review/CheckRunManager` |
| Summary comment | `review/PrSummaryGenerator` |
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
  summary field from the verified findings. Do not add summary fields back to the review contract.
- **Response extraction anchors on the answer's tail.** `ReviewResponseParser.extractJson(raw,
  rootKeys)` picks the earliest root-keyed object from which the rest of the body reads as JSON
  documents to the end, and its probing is bounded by a small multiple of the body length. Keep
  that bound when you touch it. `TruncatedResponseSalvager` keeps complete elements from a cut
  body.
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
- **AI services are stateless.** Every `@RegisterAiService` sets
  `NoChatMemoryProviderSupplier`. Review state travels in the previous-findings section, never in
  chat history.

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
   heuristic failure modes, patch coverage, CI failures, learnings, then repo instructions). Add a static
   `…Section` method that returns `""` when there is no data and emits the guidance constant only
   together with its fenced data (see `patchCoverageSection`, `ciFailuresSection`,
   `learningsSection`). Every batch
   carries the slot, and `DiffBudgetPlanner.plan` already counts it as shared overhead. No new
   `@V`, record component or planner change is needed. Guidance for the summary call goes in
   `summaryInstructions` instead, which `FindingPipeline` counts when it clamps the summary input.

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
  `PrImproveAssistantPromptsContentTest`, `UnitTestAssistantPromptsContentTest`. Update a pin
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

Entities: `ReviewSession` (dashboard), `PausedPr` (webhook), `FindingFeedback` (review, 👍/👎
signals) and `ReviewLearning` (review, table `review_learning`, #38). A learning is never deleted
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
