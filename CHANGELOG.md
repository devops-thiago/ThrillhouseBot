# Changelog

All notable changes to ThrillhouseBot.

## [Unreleased]

## [0.6.4] — 2026-08-16

Four defects the audit of 0.6.3 turned up, three of them in the write path
0.6.3 had just changed. A finding the file-level fallback rescued no longer
announces itself as lost, a content-creation block is outlasted however
GitHub words its deadline, an error body is bounded before it is scanned for
credentials, and a review can no longer report full verification over a
finding its audit never ruled on. No configuration changes; upgrading is a
redeploy.

### Fixed

- **A rescued finding is no longer announced as lost** (#729): the dropped-post accounting counted refused HTTP calls, but since #712 one finding has up to three routes to the pull request, so a throttle on the line-anchored route was remembered as a loss even when the file-level fallback then landed the content. The review body that followed carried "an earlier reply on this pull request was never posted — run the command again" directly above the finding it claimed to have thrown away, and counted two losses for one finding when both line-anchored routes were tried. The routes for one finding are now one delivery: a throttled route is held rather than remembered, a route that lands settles it, and only a delivery that ends undelivered is remembered, once. A write that genuinely fails every route is still announced
- **A content-creation block is outlasted however GitHub words its deadline** (#730): #722's floor applied only to a delay derived from headers, so an explicit `Retry-After` shorter than the floor was taken at face value — four attempts inside fifteen seconds against a block measured at seventy-two, which gave up sooner than the linear backoff it replaced. The floor now applies to the wait however it was derived. A `Retry-After` longer than the floor still wins outright, and a primary window that really is exhausted keeps its own reset instant
- **An error body is bounded before it is scanned for credentials** (#731): redaction ran over whatever the configured API host sent, and its JWT shape is quadratic — 160 KB cost nineteen seconds of CPU and 1.2 MB about eighteen minutes, spent on the review's own thread inside the failure path that exists to explain a failed write. The body is now cut before the scan. Bounding first narrowed what redaction covered, so the token shape was widened to match a token the cut split: everything after its first dot is optional, and the one shape left unmasked is a cut before that dot, which exposes the header's algorithm and type claims and no secret. The collapse also takes Unicode format controls, so a bidi override cannot reorder what an operator reads
- **Verification coverage counts only the verdicts the audit acted on** (#735): #710's fix shared the decision reader between the audit and the count but not the duplicate-id resolution — the audit collapsed duplicates first and read the survivor, the count filtered by label first and collapsed after. An id whose first verdict was unreadable was therefore kept unverified and counted as screened, so a review could still report full verification over a finding it never ruled on. Both now collapse through the same first-wins resolution

## [0.6.3] — 2026-08-15

Follow-ups from the round-7 dogfood corpus, on two surfaces: the GitHub write
path, and what a review says about its own work. A comment GitHub throttles is
now outlasted and its refusal explained, a finding whose inline comment cannot
land keeps a working review thread, and a review can no longer claim coverage
or closures its audit never made. The docs site also deploys itself on
release. No configuration changes; upgrading is a redeploy.

### Fixed

- **A comment GitHub refuses is outlasted, and the refusal reason reaches the log** (#722): the content-creation secondary limit was measured blocking for 72 seconds while the write retry's budget spanned 60, and both ways of deriving a delay without a `Retry-After` undershot it — the linear fallback summed to 30 seconds, and a stale `x-ratelimit-reset` yielded zero, spending every attempt while GitHub was still refusing. The retry now makes four attempts and floors a derived delay at 30 seconds during a content-creation block; an explicit `Retry-After` still wins outright, and a genuinely exhausted primary window keeps its own reset instant. Every rejection now logs GitHub's status and message at warning level, so a throttle is distinguishable from a rejected position — the round that motivated this left 29 rejections undiagnosable and blamed line anchoring for them. When the with-suggestion and without-suggestion attempts fail differently, both reasons are kept
- **A finding whose inline comment cannot land posts a file-level thread, not a bare bullet** (#712): the bullet carried no code context, no suggestion block and no review thread, and everything thread-dependent died with it — the finding could not be declined, a status note had nowhere to land, and clearing it acknowledged a count rather than a name. The suspected anchoring defect was ruled out: the same file and line posted successfully at the same commit, which is what pointed to the throttle above
- **An overturned decline is disclosed on every review body, and a round names the findings it closed** (#713, #714): the note explaining that a decline was overturned on diff evidence rode only the no-new-findings body, so a round that also raised a finding dropped it, and a maintainer who wrote a considered decline could not tell whether it was read, rebutted, or missed. The note now rides the with-findings bodies too, above the partial-coverage banner. The resolved tally likewise names each finding it closed instead of reporting a bare count
- **Verification coverage counts only the verdicts the audit acted on** (#710): the coverage record asked whether some verdict carried a finding's id, but `apply()` acts only on `confirmed`, `downgraded` and `rejected` — a blank or unrecognized verdict falls open and the finding posts unscreened. Such findings were counted as screened anyway, so a review could claim full verification over a set it never ruled on. One decision normalizer now feeds both the switch and the count, and an unrecognized verdict counts as unverified, which errs toward an over-cautious clause rather than silence
- **The verifier sees the PR description it judges description-gap findings against** (#711, first part): a finding weighing the author's stated intent against the code had half its claim in material the verifier never received, and the verifier's own prompt tells it to reject a claim whose material is missing — so survival came down to whether the model noticed the absence. Five planted description-versus-code mismatches split three kept, two rejected, on identical grounds. The verifier now receives the PR title and description under the same untrusted-data fencing the reviewer uses
- **The docs site deploys itself on release** (#717): the `release: published` trigger in `docs.yml` has fired zero times in the repository's history, because the release is created with the workflow's own `GITHUB_TOKEN` and GitHub starts no workflows for events that token raises — every site deploy through v0.6.2 was manual. `release.yml` now dispatches the docs build against the release tag once the release exists, gated on `update_latest` so a patch cut on an older line does not republish the site; the job fails loudly when the dispatch is refused

## [0.6.2] — 2026-08-14

Follow-ups to the review threads on 0.6.1, plus the first piece of the release
chain automation. A review GitHub refuses is now diagnosed and preserved, a
review whose PR moved under it stands down for the run that replaces it, and the
decline re-check and injection-sink floor each close a further set of gaps found
by the dogfood corpus. No configuration changes; upgrading is a redeploy.

### Changed

- **The post-release version bump fails loudly** (#11): the release workflow's bump job branched on `gh pr create` succeeding or the PR already existing, and reported success for every other outcome, so a bump lost to a permissions or API error passed silently. It now distinguishes the three cases and fails the job on the third. `docs/RELEASING.md` describes the release flow end to end, including the one-time repository settings the bump depends on

### Fixed

- **A review post GitHub refuses is diagnosed, abandoned when stale, and never lost** (#704): a rejected review post logs GitHub's own response body, redacted and length-capped, so the cause is visible; previously only the status code reached the logs. A run whose PR head moved during the model call now stands down, recorded as a structured `HEAD_MOVED` skip with its check run concluded as skipped, because the coalesced run for the new head re-reviews and posts in its place. A summary-only review that GitHub definitely refused, meaning a response-carrying 4xx, falls back to posting the same body as an issue comment through the capped and paced write path, so the generation survives. An ambiguous timeout or 5xx still fails, since the review may have landed
- **Inline code spans in a decline are stripped delimiter-aware** (#697): the decline re-check scans backtick runs the CommonMark way, where an opening run of N backticks closes at the next run of exactly N. A span whose body carries a longer backtick run (`` `a``b` ``) or one line ending is now stripped whole, so quoted claim text can no longer reopen a correct decline. An unclosed run stays literal, and a length bound keeps a stray backtick from swallowing the reply
- **Mention-form commands follow the configured bot login** (#698): `TriggerDetector` builds the `@<bot> <command>` trigger patterns from `BotIdentity.mentionNames()`, so `@my-review-bot review` works on an install whose GitHub App runs under a custom login. The mention's `@` must open the comment or follow a non-word character, so an email local part never triggers a command. Slash forms and default-config behaviour are unchanged
- **The injection-sink floor closes four residual defeater gaps** (#696): "Nothing escapes parameterization" and "Nothing escapes; the sanitizer runs on render" are read as the mitigations they assert, so the floor no longer over-fires on them. A do-supported mitigation carrying an adverb ("does always escape") defeats the floor. A modal absence claim ("Nothing can sanitize the value") registers and floors at high. A comma-coordinated asserted mitigation ("Nothing escapes, but the sanitizer runs on render") defeats the floor, while its denial twin ("...but the sanitizer is disabled") still floors

### Dependencies

- Bumped the Quarkus platform from 3.38.0 to 3.38.1 (#706)
- Bumped `actions/attest-build-provenance` from 4.1.1 to 4.2.2 in the release workflow (#708)

## [0.6.1] — 2026-08-13

Fixes for defects found after 0.6.0 shipped, most of them during the audit of
that release's review threads. The largest: a review now discloses when its
finding verification did not run, and a GitHub read issued after the
installation token expired mid-review is retried with a fresh token instead of
failing the review. The docs and frontend dependency groups are also brought
current. No configuration changes; upgrading is a redeploy.

### Changed

- **Summary-surface deduplication derives each claim's word and phrase sets once** (#678): at claim construction rather than for every comparison pair. Deduplication decisions are unchanged

### Fixed

- **Token refresh heals reads, not only writes** (#626): every installation-token read (PR details, file and comment pages, check runs, statuses, workflow artifacts, GraphQL thread lookups) goes through the refresh seam #625 added for writes. A read that gets a 401 after the token expires mid-review is repeated once with a fresh token instead of failing the review
- **A review says when finding verification did not run** (#623): the second-pass verifier fails open by design, and roughly one review in three published findings it never screened, with nothing to tell them apart from a verified set. The posted review, the check-run summary and the follow-up delta comment now state when verification did not run, or covered only some findings ("verification covered X of Y"). Fail-open behavior and the verdict are unchanged
- **The clear directive follows the configured bot login** (#679): the `resolved` directive, its acknowledgements and the conversational-mention gate are built from `github.bot-logins`, so an install whose GitHub App runs under a different login can clear findings. The mention's `@` must open the comment or follow a non-word character, so an email address is not a mention; Unicode space separators are accepted inside a directive; and an invisible character cannot turn `resolved?` into a clearing order
- **Files whose review call failed get their own coverage reason** (#655): they are disclosed as "not reviewed because the review call for them did not complete" instead of being blamed on the diff budget, which matched neither the cause nor the remedy
- **The omitted-file count survives a summary degradation** (#659): on the legacy line-cap path, a review whose summary was shortened or skipped dropped the "N file(s) were omitted" count. Both now render
- **An unclosed quote no longer hides dispatch evidence** (#656): when a quote opener never closes (a Rust lifetime, an apostrophe in prose), the comment scan continues quote-aware past it, so a `//` inside a later closed string literal no longer truncates the line and a "runs serially" decline is still checked
- **A context-window rejection is not retried** (#622): the rejection is deterministic, so the call fails on the first attempt instead of paying for up to `max-ai-retries` identical requests. A multi-call review discloses the rejected batch's files and keeps the other batches' findings; a single-call review fails with a notice naming the cause and the `REVIEW_MAX_INPUT_TOKENS` knob to lower (default 48000)
- **The dashboard token test no longer assumes an en-US locale** (#661): it asserts the same `toLocaleString()` output the component renders, so it passes under any runtime locale
- **The injection-sink floor closes three defeater gaps** (#676): "Nothing sanitizes ..." counts as an absence claim, "does not prevent SQL injection" is not read as denying the sink, and a coordinator inside a conditional clause ("If, however, ...") no longer cuts the hypothetical span short and left its mitigation verb read as asserted
- **Dimension 7 carries its own external-producer boundary** (#680): an artifact whose producer is legitimately outside the diff (a base image, a release binary) is treated as unshown state to verify, not as a mismatch to floor, and the closing escape refers back to the same rule
- **`pr_purpose` is rendered through `MarkdownSafe.inline`** (#636): like every other model-supplied string in the summary, so a crafted purpose cannot inject headings, HTML, fences or table pipes into the posted comment
- **A backticked quotation cannot reopen a decline** (#652): inline code spans are stripped from a maintainer's reply before the decline re-check, so quoting a construct such as `` `executor.submit` `` is not matched as an assertion about the reviewed code
- **Format characters no longer defeat the whole-locator guards** (#654): a zero-width space, zero-width joiner, soft hyphen or BOM inside a range spelling (`src/A.java:1<U+200B>-3`) made `:1` read as a whole locator and cleared the wrong finding. Such characters now continue the line-number token and the finding is held
- **A digit-leading finding title clears the finding it names** (#653): `@thrillhousebot resolved src/A.java:1 — 2 call sites of ...` is the exact form the summary prints, but it read as a line range, so the finding stayed held and the maintainer was told the comment named nothing. The em dash followed by the finding's full printed title now clears it; any other spaced separator still reads as a range, and the ambiguous shape is acknowledged as ambiguous

### Dependencies

- Bumped the website docs-minor-patch group — `astro` 7.1.4 → 7.2.0 and `@astrojs/starlight` 0.41.4 → 0.41.7 — adding `@astrojs/markdown-remark` as a devDependency because Astro 7.2 no longer ships it with the new default Sätteri Markdown processor and the site's `remarkInclude` plugin still runs on the unified pipeline (#658, #689)
- Bumped the frontend npm-minor-patch group — `next` 16.2.12 → 16.3.0 (fixes CVE-2025-13465 in its vendored lodash), `@testing-library/jest-dom` 7.0.1, `@testing-library/user-event` 14.6.3, and `@types/node`, `@types/react`, `@types/react-dom` patches — recasting the partial API mocks in the costs and tokens page tests through `unknown`, which the Next 16.3 production type check now requires (#662, #689)
- Bumped `typescript` from 6.0.3 to 7.0.2 in the frontend (#369)

## [0.6.0] — 2026-08-13

On-demand commands for improving a PR and generating tests, per-repository review
configuration, and cost controls that bound what one review may spend. Review
accuracy work concentrates on the verifier and on saying plainly when a file was
not read.

### Added

- **`/improve`** (#452): reads the whole change set under the token budget and posts committable suggestions for improvements a review would otherwise only describe. On by default; disable with `REVIEW_IMPROVE_ENABLED=false`
- **`/generate-tests`** (#461): proposes tests for the code a PR changes. On by default; disable with `REVIEW_GENERATE_TESTS_ENABLED=false`
- **Per-repository ignore patterns** (#51): a repository can declare its own ignore globs in `.github/thrillhousebot.yml` under `review.ignored-files`. The effective set is the union of the deployment list and the repository's, so a repository can remove files from review scope but cannot restore one the deployment excludes. The file is read from the default branch and cached for five minutes. A missing file, invalid YAML, an unexpected shape or an uncompilable glob is logged and skipped, leaving the deployment list in force. Set `THRILLHOUSEBOT_REVIEW_REPO_CONFIG_ENABLED=false` to disable
- **Path-scoped review instructions** (#460): `.github/thrillhousebot.yml` can attach review guidance to path globs, so an API surface, a migration directory and a test tree can be held to different rules
- **Per-review token spend ceiling** (#509): `REVIEW_MAX_TOKENS_PER_REVIEW` bounds the tokens one review may consume across every AI call it makes, counting retries, the finding verifier and the summary call. Once reached, remaining batches are disclosed as not reviewed and the summary degrades to counts. `0`, the default, keeps the previous unbounded behaviour
- **A `concise` model binding for fixed-shape calls** (#507): the summary, the finding verifier and maintainer replies run on their own model with their own response cap, `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` (default `8192`), so they no longer share a cap sized for batch review output
- **`separate-output-budget` per-model flag** (#496): declares whether a provider bills completion against the same window as the prompt, which the input-budget arithmetic needs on shared-window providers
- **Patch coverage in the review context** (#467): when a PR's CI publishes a coverage report, the changed lines it does not cover are given to the review, so untested new code can be named as such. Off by default; enable with `REVIEW_PATCH_COVERAGE_ENABLED=true`
- **Config-key documentation checks** (#109, #450, #465): the review reads a documented config key's real definition out of the repository and reports documentation that is incomplete, such as a missing default, an unstated unit, or an undocumented sibling key
- **Producer-to-consumer tracing** (#117): a change to a shared record or payload shape is followed to the code that reads it, so a field renamed or narrowed at the source is reported against the consumer that still expects the old shape
- **Decline re-check** (#453): when a maintainer declines a finding, the stated reason is checked against the reviewed code before the finding is recorded as justified. It stays open one more round only when the code contradicts the reason. `REVIEW_DECLINE_RECHECK_ENABLED=false` restores the unconditional close
- **Clearing a finding that has no review thread** (#553): a finding raised below the inline-posting bar has no thread to reply on, so commenting `@thrillhousebot resolved path/to/File.java:42 — <title>` on the PR closes it. A question (`resolved?`) is not treated as a directive, and a directive naming no location is answered as such
- **Follow-up delta comment** (#459): a follow-up review can post a short summary of what changed since the last one, listing new, resolved and still-open findings. Off by default; enable with `REVIEW_FOLLOW_UP_SUMMARY_ENABLED=true`
- **Salvage of a length-capped response** (#511): findings completed before a response hit the model's length cap are kept. Previously the whole response was discarded
- **Large-PR notice** (#466): a large PR whose review produced no inline finding now says so explicitly. Off by default; enable with `REVIEW_LARGE_PR_NUDGE_ENABLED=true`, with `REVIEW_LARGE_PR_NUDGE_MIN_FILES` and `REVIEW_LARGE_PR_NUDGE_MIN_CHANGED_LINES` setting what counts as large
- **Write pacing for GitHub calls** (#597): `GITHUB_WRITE_MIN_INTERVAL` (default `1s`) spaces comments, review comments, thread replies and reviews across the process so a burst does not reach GitHub's secondary rate limit. `GITHUB_WRITE_MAX_WAIT` (default `60s`) caps how long one caller waits

### Changed

- **`/describe`, `/changelog` and `/add-docs` are planned as token-budgeted batches** (#463, #469): these commands no longer render one call capped by `REVIEW_MAX_DIFF_LINES`, so a large PR is covered whole. `REVIEW_MAX_DIFF_LINES` now applies only to the remaining single-call renders, which are replies, base comparison, and review with budgeting disabled. The commands added in this release, `/improve` and `/generate-tests`, are planned the same way from the start
- **The summary is grounded in the whole change set** (#335): the summary call is given the authoritative file and line totals and a directory breakdown, so a change spread across several packages is no longer described as a single-file edit when the input budget clamps the file list
- **Security severity follows the defect class** (#575): an unmitigated injection sink is held at high risk even when the surrounding context is only partly visible
- **Chat memory is disabled on every AI service** (#585): each call is stateless, so no round inherits context from an unrelated one

### Fixed

- **Oversized comments were rejected by GitHub** (#487, #503): comment bodies are capped at 65,536 characters and check-run output fields at their own limits, so an oversized review is trimmed and still posts
- **A file GitHub reported without patch text could crash a review** (#472, #551): null filenames reached null-hostile immutable collections. Such files are now counted and disclosed by name, and they withhold approval rather than passing as reviewed (#489)
- **A response cut at the length cap was retried at full price** (#495, #504): a length stop is detected on the blocking command paths and not retried
- **The finding verifier deleted correct findings** (#611, #612, #613, #614): all claims a withheld path licenses are refused, where previously only the first was, a second defect in the same hunk is filed as its own finding, an unmatched-quote finding is kept, and demonstrated sinks and standard-library semantics are carved out of the downgrade pressure
- **The previous-findings context grew without bound across rounds** (#615) and could crowd out the diff. It is now bounded to a share of the input budget
- **Walkthrough rows were missing their file summaries on large PRs** (#544), and a row without a summary now says why (#549)
- **One observation could be published on up to three surfaces of the same comment** (#596)
- **The heuristic failure-mode review dimension was inert** (#618): it was gated on a diff string that token budgeting leaves empty
- **`quarkus:dev` did not boot usable on a fresh clone** (#591, #620): the dev-mode schema is created at startup, and the README states the `.env` and PEM steps a fresh clone needs
- **An empty SSE frame failed the stream on the event loop** (#555). It is now skipped
- **Runtime image size and contents** (#561, #564): the native binary is no longer stored twice, the website is out of the build context, and the H2 driver is kept out of the runtime image

### Dependencies

- Overrode `nanoid` to `^3.3.17` in the `website/` and `frontend/` npm trees to clear GHSA-2v37-7h3g-55p8, in which a custom generator loops indefinitely when it is called with a size of zero. Every Astro and Starlight package reached the vulnerable 3.3.16 through `postcss`, which asks for `^3.3.16`, so the fixed 3.3.18 satisfies the range already declared and no direct dependency moves (#660)
- Bumped the Quarkus platform from 3.37.4 to 3.38.0 and `quarkus-langchain4j` from 1.12.0 to 1.12.2
- Declared `jackson-dataformat-yaml` explicitly. It was already on the classpath transitively and is version-managed by the Jackson BOM; reading a repository's own `.github/thrillhousebot.yml` uses it directly
- Bumped the frontend `next` to 16.2.12 and the dev-only `jsdom` to 30.0.1, with `@types/node` on a patch release
- Bumped GitHub Actions `github/codeql-action` to v4.37.6, `actions/setup-java` to v5.7.0 and `docker/login-action` to v4.6.0
- Bumped the Spotless Maven plugin to 3.9.0

## [0.5.0] — 2026-07-26

Review precision: confidence now decides where a finding lands, newly-added parsers and regexes are stress-tested for their own failure modes, and several classes of false positive are guarded at both the generator and the verifier. Operators gain configurable CI-gating and blocking strictness, structured skip reasons, and per-model generation parameters.

### Added

- **Mock-fidelity review check** (#111): when a PR changes tests, the review prompt compares stubs/mocks (`when`/`doThrow`/`doReturn`) against the real collaborator's contract when that definition is already in the provided material, and flags contradictions (e.g. a mock that throws an exception the real method swallows) at low/medium confidence instead of treating the green test as proof. Softens the related-tests framing that previously reinforced unfaithful stubs; the skeptical verifier gains a matching rule. Broader cross-file collaborator retrieval remains with #55
- **Structured skip transparency for automatic reviews** (#341): every path that skips an automatic review (draft, base-branch and label gates, `/pause`, rate window, duplicate webhook delivery, rejected dispatch) now emits a structured reason code — a `Automatic review skipped [reason=...]` log line, a `thrillhouse.review.skips` OTLP counter tagged with `reason` and `repository`, and per-reason counts on the dashboard summary endpoint (`skippedReviewsByReason`). README gains a "PR opened but no review posted" troubleshooting checklist
- **Bug-fix efficacy check** (#110): when a PR declares itself a bug fix (the PR-template "Bug fix" checkbox or a `Fixes/Closes/Resolves #N` reference), the review prompt now extracts the concrete failure trigger from the PR description and the linked issues' text (fetched best-effort, up to 3 issues) and verifies the change actually alters behavior on that trigger's path — a locally-correct fix that the stated trigger never reaches is reported as a finding instead of passing silently, and when the deciding code is outside the diff the verdict is held at low confidence as a verification request rather than approved
- **Finding feedback capture for the learnings pipeline**: records maintainer 👍/👎 reactions (and conservative "not useful" reply heuristics) on bot finding comments into a `finding_feedback` table, with per-repo aggregates on `GET /api/dashboard/feedback` and a documented data model/retention policy (`docs/FEEDBACK.md`). GitHub Apps have no `reaction` webhook, so reactions are polled via the Reactions API on review-thread replies and follow-up reviews. Feedback is permission-gated in both directions: only verified write-capable collaborators reacting on bot-authored finding threads are recorded, and `GET /api/dashboard/feedback` returns only repositories the authenticated user can access — so the endpoint cannot be used to enumerate activity on repos you are not a member of. Capture runs on a bounded executor that prioritizes newer findings and drops work rather than queueing without limit, and review logs no longer carry source-line contents at INFO. Precursor to cross-review learnings (#38); does not yet feed prompts (#324)
- **Adversarial failure-mode characterization for new heuristics** (#123): when a diff introduces parsing, regex, validation, or heuristic code — a `Pattern.compile`, a tokenizer, a normalize step, a scope window or threshold constant — the reviewer switches those hunks from happy-path reading into decision-boundary characterization: it synthesizes the inputs that probe the rule's edges (a lambda against a paren-counting regex, an NBSP against an ASCII whitespace class, a token inside a fenced code block against a "starts with" check, a present-but-wrong-shape value against a presence check), names them literally, and reports false negatives as well as false positives. The triggering input is absent from the diff by definition, so the verifier gains a matching exemption from the verbatim-quote rule — without it the quote requirement rejects this entire class. Findings land at low/medium confidence as verification requests, and a synthesized failure is only confirmed when the expected domain or contract is visible in the material — a contract-free probe stays a low-confidence verification request. Executing the synthesized inputs is #96. The trigger spans languages: explicit regex construction (Java, JS/TS, Python, Go, C#, Kotlin), JS/TS regex literals and `function`/arrow validators, declared parse/validate/tokenize members including package-private Java methods, and construction split across lines via a bounded, file-reset-aware window. It stays deliberately narrow so ordinary `substring`/`indexOf`/`trim`/`parseInt` code does not trigger it (dogfood: three review rounds on PR #114 returned "no issues" while human review found ~10 precision/recall defects in exactly this kind of code)
- **Configurable CI gating strictness for APPROVE** (#322): `REVIEW_CI_GATING` chooses how CI status factors into approval — `strict` (default) holds APPROVE while required CI is pending, failing, or unreadable; `warn` allows APPROVE but records the CI uncertainty in the summary and check run; `off` decides on findings alone
- **Configurable blocking strictness** (#323): `REVIEW_BLOCKING_STRICTNESS` sets when findings escalate to `REQUEST_CHANGES` — `balanced` (default: CRITICAL/HIGH at high confidence), `strict` (any CRITICAL/HIGH), or `lenient` (CRITICAL at high confidence only), so a repo can tune how loudly the bot blocks without editing prompts
- **Model generation parameters** (#64): `frequency-penalty`, `presence-penalty` and `seed` join the existing per-model settings under `thrillhousebot.ai.models.<model>.*`, sent on every chat call when set. `seed` makes a provider's sampling reproducible where supported, which is what lets the #113 eval corpus compare runs meaningfully

### Changed

- **Passing in-diff tests must exercise the claimed path before suppressing a finding** (#116): the review and verifier prompts no longer treat a green test in the same diff as automatic disproof. A test may invalidate (generator) or reject (verifier) a finding only when it demonstrably exercises the claimed path — asserting on the path's output and stubbing collaborators into the relevant state, not leaving them unmocked so a default bypasses it (and not with stubs that contradict the real collaborator — see #111). When that exercise cannot be shown from the provided material, confidence is lowered with an explanation instead of dropping the finding (dogfood: PR #99 approve-path test green because `getPullRequest` was unmocked)
- **Low-confidence findings post in the summary instead of inline** (#105): inline review comments are now gated on confidence — a low-confidence finding is listed in the summary body rather than anchored to a line. Uncertain claims still surface, but they stop occupying the position that reads as "this line is wrong", and they can no longer request changes on their own
- **Exact-arithmetic and "this test fails" claims are capped and hedged** (#97): a finding that rests on line-count, array-length, or index arithmetic the model performed by counting, or that asserts a specific test fails, is now capped at low confidence when nothing but counting backs it, and must be phrased as a verification request ("CI will confirm") rather than settled fact. The verifier gains the symmetric rule and rejects such a claim stated as fact with no execution or CI signal behind it — it cannot settle the question by recounting, being the same modality reading the same diff. The cap is evidence-dependent, not absolute: when an execution or CI signal in the provided material actually shows the failure, the finding keeps the confidence that evidence justifies instead of being forced low. Mirrors the existing "why would this test still pass" guard for the failure direction (dogfood: PR #84 produced a definitively-worded off-by-one — "the section is 7 lines, so 7 − 3 = 4 omitted" — against a test that passes as written, and the verifier re-did the same arithmetic and agreed)
- **Dual-gate merge policy for contributors** (#342): merging now requires both the static analysis gate and a ThrillhouseBot review, so neither signal alone can carry a change in. Affects anyone opening a PR against this repo rather than anyone running the bot
- **Wider default ignored-files patterns** (#52): `thrillhousebot.review.ignored-files` now skips far more generated and vendored output out of the box — `pnpm-lock.yaml`, `go.sum`, protobuf output (`*.pb.go`, `*_pb2.py`), minified bundles and sourcemaps (`*.min.js`, `*.min.css`, `*.map`), and the directories `node_modules/`, `dist/`, `build/`, `out/`, `.next/`, `vendor/`, `__pycache__/`, `.venv/`, `bin/`, `obj/`, alongside the previous lockfile/`pom.xml`/`target/` set. Less budget burned on artifacts nobody reviews. Worth checking on upgrade: the directory globs match by name, so a repository that keeps handwritten source under `build/`, `bin/`, `out/` or `vendor/` will stop having it reviewed — narrow the list per deployment if that applies to you

### Fixed

- **False parameter-nullability / precondition findings when the caller is outside the diff**: a finding could assert a method parameter may be null (or violates a precondition) and post at MEDIUM even when the only callers that supply the argument were unchanged and absent from the reviewed material — so nullability was assumed rather than demonstrated (a MEDIUM false positive on `DashboardAccessChecker.installedRepos` in PR #101 claimed `accountOwner` could be null while the unshown `checkAccess`/`evaluateAccess` callers already guarantee non-null). The reviewer now caps such claims to low confidence unless the calling code is present and shown to pass a violating value (or the changed signature itself declares a nullable contract such as `@Nullable` / `Optional`), and the verifier rejects them when neither the caller nor such a contract is in the material — with the PR #101 case embedded as an inline regression example (#107)
- **Findings survived a force-push that removed the lines they targeted** (#336): when a rebase or amend made a targeted diff hunk disappear, prior findings anchored there were still carried forward and re-reported against code that no longer existed. Presence is now re-checked against the current diff and findings whose anchors are gone are invalidated instead of re-raised
- **False injection and constructor findings on framework-idiomatic code** (#337): the reviewer flagged idiomatic framework usage as broken — most visibly a "missing no-arg/default constructor" on a CDI/Quarkus bean whose constructor is annotated `@Inject` (and the Spring `@Autowired` equivalent), where constructor injection is the documented idiom and needs no such constructor. The generator and verifier now recognise these shapes and refuse the claim rather than posting it
- **Duplicate CI-pending comment when the summary already listed the pending checks** (#334): a follow-up COMMENT review announcing pending CI could post alongside a summary that already enumerated the same checks, telling maintainers the same thing twice. The extra comment is now suppressed unless the summary did not actually post

### Performance

- **Pure renames no longer consume AI review budget** (#386): a rename with no content change contributes an empty hunk, so it was spending token budget while carrying no reviewable text. Renamed files are excluded from the reviewable diff (still passed to the related-tests and mock-fidelity context so test moves stay visible), leaving more of the budget for files that actually changed
- **Deduplicated work on the follow-up backstop hot path** (#135): the #118 backstop rebuilt the `DiffLineResolver`, re-parsed prior AI responses, and copied the status list more than once per review. Each is now built once and shared across the pass

### Dependencies

- Bumped the Quarkus platform group, the frontend npm-minor-patch group, `@testing-library/jest-dom` to 7.0.0, the website docs-minor-patch group, `dompurify` to 3.4.12, `svgo` to 4.0.2, and the GitHub Actions minor/patch group
- The docs site now requires Node 22 and pins PostCSS 8.5.23 and Sharp 0.35.3, so a clean `npm ci` on npm 11 resolves without known high-severity advisories

## [0.4.0] — 2026-07-12

Token-budgeted reviews for large PRs, per-model AI settings, a published docs site, and a few operator-facing controls (command ack reactions, an opt-in auto-review interval, missing-pricing visibility on the dashboard).

### Added

- **Hosted documentation site** at <https://devops-thiago.github.io/ThrillhouseBot/> — getting started, commands, configuration, providers, architecture, and contributing, built with Astro Starlight from `website/` and linked from the README. Repo markdown stays the source of truth via build-time includes. `install.html` on the site builds the GitHub App manifest from a typed-in hostname (Smee-aware), so first-time App registration no longer needs a local static server. Docs CI fails on broken internal links. The live site tracks GitHub Releases (not every push to `main`): versioned archives via `starlight-versions`, `current` labels the release being cut, and Pages deploys on `release: published`. Freeze a release with `cd website && npm run docs:archive -- <slug>` before starting the next version's docs
- **👀 ack reaction on commands**: the bot reacts 👀 to the comment that triggered any slash or `@mention` command as soon as the webhook is received, before pause/authorization gates and before the work finishes. The reaction is best-effort and bounded by `ACK_REACTION_TIMEOUT` (default 3s) so a slow GitHub API cannot delay the webhook `200`. Conversational `@thrillhousebot` mentions (no command word) get a reply, not a reaction
- **Per-PR automatic review rate limit** (opt-in): `AUTO_REVIEW_MIN_INTERVAL` / `thrillhousebot.review.auto-review-min-interval` spaces automatic reviews of the same PR by that duration — a push inside the window is skipped, even on a new head SHA. Off by default (`0`); use `/pause` to silence one PR without a global interval. In-memory per replica; manual `/review` bypasses and does not shift the window. Marking a draft ready (`ready_for_review`) clears the window so the PR is reviewed immediately
- **Whole-PR review for large diffs (token-aware, multi-call)**: big PRs are split into priority-ordered, token-budgeted batches (jtokkit estimates), each reviewed in its own call, then rolled up with one summary call. Every file is covered by some batch or listed by name as omitted — nothing is dropped silently. Normal-size PRs stay on one streaming call. Multi-call runs fan out batches on virtual threads and emit `review.batch` progress on the dashboard instead of a token stream. Settings: `REVIEW_MAX_INPUT_TOKENS`, `REVIEW_OUTPUT_BUFFER_TOKENS`, `REVIEW_MAX_AI_CALLS`, `REVIEW_TOKEN_SAFETY_MARGIN`; `REVIEW_MAX_DIFF_LINES` still caps single-call renders (on-demand commands, maintainer replies, base comparison, budgeting-disabled reviews) but not token-budgeted review calls (`0` disables the line cap)
- **Per-model AI configuration** under `thrillhousebot.ai.models.<model>.*`, keyed like the pricing map. `max-input-tokens` is the model input hard cap; the review budget becomes `min(REVIEW_MAX_INPUT_TOKENS, cap)` with a 128 000 default for models without an entry. `output-buffer-tokens`, `token-safety-margin`, `temperature`, `top-p`, and `max-output-tokens` can be overridden per model and are sent on every chat call when set. Entries are range-validated at boot. README and `.env.example` document the review knobs and `THRILLHOUSEBOT_AI_MODELS__*` env-var mapping for hyphenated keys
- **Missing model pricing is warned and flagged**: when `AI_MODEL` has no `thrillhousebot.ai.pricing.<model>.*` entry, the bot logs a warning (once per model per process) and sets `pricingMissing` on the session; the dashboard shows "no pricing" instead of `$0.000000`. Token counts are unchanged. Startup backfill clears the flag once pricing exists and cost is recomputed
- **Reasoning-effort control**: `AI_REASONING_ENABLED` (default `false`) and `AI_REASONING_EFFORT` (`none`/`low`/`medium`/`high`, default `low`) send OpenAI-compatible `reasoning_effort` on every AI call when enabled; when disabled, no parameter is sent and the provider default applies
- **Docker test image workflow** (`docker-test-image.yml`): manually build and push disposable JVM or native images for a branch or PR to GHCR (`test-sha-*`, `test-pr-*`, `test-branch-*` tags) without touching `latest` or release tags

### Dependencies

- Bumped the Quarkus platform (`quarkus-bom` and `quarkus-maven-plugin`) from 3.37.0 to 3.37.2, `quarkus-langchain4j` from 1.11.2 to 1.12.0, and `jackson-bom` to 2.22.1
- Pinned OpenTelemetry to 1.62.0 (overrides the platform-managed line for CVE GHSA-rcgg-9c38-7xpx)
- Bumped GitHub Actions `actions/cache` to 6.1.0 and aligned `actions/setup-java` pins across workflows

## [0.3.1] — 2026-07-02

A patch release of disclosure and correctness fixes found dogfooding v0.3.0: truncation is now disclosed on every surface (the formal review body, the check run, and the on-demand commands), the PR summary reports GitHub's authoritative totals, `/summary` regenerates a deleted summary comment, sequence diagrams render again, CI hold copy no longer mislabels checks as required, and the label reconciler sees every page of a PR's labels.

### Fixed

- **A truncated review with findings discloses the partial review in the body and check run**: on a truncated diff, a review that produced findings carried the truncation banner only in the PR summary comment — which is posted best-effort and only on first reviews — so if that post failed or was skipped, nothing on the PR said the review was partial. The formal review body now appends the partial-review notice whenever the diff was truncated (including when every finding anchored inline, where previously no body was posted at all), and the check-run findings caption discloses the omitted-file count alongside the counts (#338)

- **`/summary` regenerates a deleted summary comment**: the command gated on persistence state ("a review ever completed for this PR") rather than the live PR, so once the summary comment was deleted it could never be regenerated — and `/summary` logged that a summary "already exists" when none was present. It now checks the PR for the bot's own summary comment and, when it is missing, re-posts it (even on a PR that already carries a formal review, where the first-review gate alone would suppress it); it still no-ops when the summary is present. The log line and README wording no longer claim a summary exists when it doesn't (#297)
- **PR summary "Changes Overview" no longer undercounts files/lines**: the overview reported the file and line totals summed over the bot's *reviewable* file list — the ignore-glob-filtered subset — so a PR with any ignore-globbed file (e.g. a lockfile) showed fewer files and lines than GitHub's own totals, and the changed-files walkthrough's "…and N more file(s)" rollup was short by the same amount. The overview and the rollup now report GitHub's authoritative `changed_files` / `additions` / `deletions` for the PR (fetched from the pulls endpoint), while the walkthrough table still lists only the reviewable files. Truncation gating and disclosure are unchanged, and if the PR totals can't be fetched the summary falls back to the previous diff-derived counts (#298)
- **Mermaid `sequenceDiagram` in the PR summary now renders on GitHub**: the opt-in control-flow diagram (#181) could emit a sequence diagram that declared participants with flowchart bracket-label syntax (`participant O["ReviewOrchestrator"]`) and quoted its message text — a side effect of the "quote every node label, whatever the shape" guidance that had been added for flowcharts (#299). Bracket labels are a parse error in a sequence diagram, so GitHub silently dropped the whole diagram. The diagram prompt now scopes the double-quote rule to flowchart node labels and gives sequence diagrams their own syntax (`participant X as Label`, plain message text after the colon); as a defensive backstop the summary now drops — rather than posts — a sequence diagram whose participant/actor lines still carry a bracket label (#311)
- **`/describe`, `/changelog`, and `/add-docs` disclose a truncated diff**: the three on-demand commands run against the same size-capped diff as the review path, but silently dropped the omitted-file count — on a large PR they posted a description, changelog entry, or doc suggestions derived from a partial diff and presented them as complete. Each command's comment now ends with a partial-coverage disclosure naming the omitted-file count whenever files were dropped, reusing the review path's wording; nothing is appended when the diff was read in full (#296)
- **CI hold copy no longer calls checks "required" when the required set is unknown**: when branch-protection required contexts cannot be resolved, the bot fails closed and gates on every non-passing check — but the check-run summary and PR summary still described those checks as *required*, which branch protection never named. The copy now uses neutral "CI check(s)" wording in that mode and keeps the accurate "required" wording when a concrete required list was resolved; the gating behavior itself is unchanged (#302)
- **Label reconciliation sees every page of a PR's current labels**: the per-PR label budget was computed from the first page of the PR's labels only (GitHub paginates at 100), so a PR carrying more than a page of labels could have its budget undercounted and labels re-applied past the `max-labels` bound over successive re-reviews. The current-label fetch now walks every page, and a mid-walk fetch failure keeps the pages already read rather than discarding them (#304)

## [0.3.0] — 2026-06-30

### Added

- **`/describe` command**: ask the bot to generate or improve the PR title and description from the diff. It posts a suggestion comment the author can copy in — it never edits the pull request, so the author's own title and body are never overwritten. Respects the repository instructions file, is write-gated like the other paid commands, and honors a `/pause` (#35)
- **`/changelog` command**: ask the bot to draft a CHANGELOG entry for the PR from the diff, in the Keep a Changelog format (Added/Changed/Fixed/Security…). It posts a suggestion comment the author can copy into `CHANGELOG.md` — it never commits, so nothing is changed without consent. Respects the repository instructions file, is write-gated like the other paid commands, and honors a `/pause` (#62)
- **`/add-docs` command**: ask the bot to generate docstrings for undocumented changed symbols. It posts each as an inline `suggestion` comment on the symbol's declaration so the author applies it with one click (or, when the declaration can't be anchored as a committable suggestion, a note describing the gap with the drafted docs to add manually) — it never commits. Write-gated like the other paid commands, honors a `/pause`, and is on by default (turn off with `REVIEW_ADD_DOCS_ENABLED=false`) (#56)
- **Changed-files walkthrough in the PR summary**: the first-review summary comment now includes a table of the changed files with their change type, giving a quick map of the PR before the findings (#179)
- **Opt-in Mermaid control-flow diagram**: the PR summary can include a collapsible Mermaid diagram of the change's control flow, off by default and enabled per-deployment via `REVIEW_DIAGRAM_ENABLED` (#181)

### Changed

- **CI status resolves concurrently with the model call, with one fewer API request**: required-check resolution and CI evaluation depend only on the commit and base branch, not the model response, so they now run concurrently with the (blocking) AI call instead of strictly after it — the GitHub latency overlaps the model latency rather than stacking on top of it — and the base branch is carried on the review request, so the CI resolver no longer issues a separate `getPullRequest` just to read the target branch (#217)
- **Multi-line suggestions**: when a finding's fix replaces several consecutive lines, the bot now posts a multi-line review comment (`start_line`..`line`) so the GitHub suggestion replaces the whole range in one click, instead of anchoring to a single line and mis-applying the rest. The range is derived from the flagged code's position in the diff and falls back to a single-line comment when it can't be resolved (#71)
- **Config/IaC findings no longer suppressed**: the reviewer was high-precision but had weak recall on declarative diffs (Kubernetes/Helm manifests, Terraform, CI workflow YAML, Dockerfiles) — it would *consider* issues like over-broad RBAC, missing container hardening, token automounting, or a schema-invalid manifest and then drop them, because the SECURITY dimension named only application-code threats and severity was anchored on "will fail at runtime". The review prompt now names infra/config security classes, adds a config/IaC correctness dimension, and recalibrates severity so a change that fails schema/lint/CI validation — or that weakens a safety property the PR itself claims to add — is a defensible finding. Precision is preserved by the verifier (it still rejects config claims that rest on cluster/provider state not in the diff), not by blanket suppression (#238)

### Fixed

- **Check run concluded before the review was posted**: the orchestrator marked the check run `completed` with its final conclusion *before* posting the review and comments, so a transient failure while posting left a concluded — for a zero-findings PR, green `success` — check run with no review actually on the PR (branch protection saw green; the human saw nothing). The review and its comments now post first, and the check run is concluded only afterwards. A failure while posting takes the normal failure path — check run marked failed plus a retry notice — instead of being swallowed (#254)
- **A post-result failure no longer leaves a session stuck in progress**: after the review and its comments were on the PR, a failure in a later step — most importantly the session-completion persistence write — was caught and only logged, skipping the completion broadcast and leaving the session shown as perpetually running in the dashboard even though the review had posted. The completion now always runs and the session reaches a terminal state (#254)
- **Trimming the regression context no longer forces a "partial review"**: the truncation gate counted omitted files from the supplementary base↔head comparison as well as the PR diff, so a PR whose full diff was reviewed could still be held back from approval and labelled a partial review when only the extra regression context was trimmed. Only the PR diff's omitted files now gate the verdict (#234)
- **No crash on a prior review from a deleted account**: prior-review author checks dereferenced each review's author with no null guard, so a review left by a since-deleted GitHub account (serialized as `user: null`) threw a NullPointerException — failing the whole review (the first-visible-review check) or aborting the stale-pending-review dismissal partway, which left the bot's own pending review undeleted and could block the next review. Both checks now null-check the author, matching the sibling comment and summary checks
- **Findings that can't be anchored are always reported**: a finding whose line fell outside the current diff was dropped when other findings in the same review *did* anchor inline — they were only listed in the review body when none anchored at all. Such findings are now always reported in the review body, with their description, so a problem is never silently dropped just because a suggestion couldn't be placed (#215)
- **No more green ✅ over a held conclusion on a truncated PR**: the check-run title re-derived "all clear" by hand and omitted the truncation guard, so a clean PR whose diff was truncated showed a `✅` title over a `neutral` (held) conclusion. The title is now derived from the single verdict gate, so it celebrates only when the review actually approves (#234)
- **A truncated review no longer shows the all-clear celebration**: a clean review whose diff was too large to read in full is held to a comment, but the check-run summary and the PR summary comment still rendered the "no issues found" celebration, hiding that only part of the change was reviewed. Both now report a partial review (#234)
- **Unreadable CI status no longer allows an approval**: the CI gate works off the *offending* checks, and an empty list meant "nothing blocks". But when the Check Runs / Combined Status API threw or returned a null body, that empty list was indistinguishable from "CI all green", so a PR whose CI was actually failing or still pending could receive an APPROVE on a transient GitHub hiccup. An unread CI source now holds the verdict to a comment (findings still post, only the approval is held) and is disclosed in the summary, instead of being mistaken for green (#253)

### Dependencies

- Bumped the Quarkus platform (`quarkus-bom` and `quarkus-maven-plugin`) from 3.36.3 to 3.37.0 (#263), the frontend `@types/node` to 26.0.1 (#262), and the GitHub Actions `actions/checkout` (7.0.0), `actions/cache` (6.0.0), and `actions/setup-java` (5.4.0) (#265, #266, #267)

## [0.2.1] — 2026-06-24

A patch release of review-path correctness and robustness fixes. Most are matching and anchoring bugs — duplicate merges that mishandled severity, quote and line lookups that bound to the wrong place, and pagination gaps that truncated comments and reviews — alongside several approval-safety fixes (no approval after a failed file fetch, on an unknown CI state, or on a truncated diff), a stale "retry" notice no longer posted over a finished review, tolerance for malformed model JSON, and the commit-statuses permission that CI gating needs.

### Changed

- **One fewer review fetch per run**: the orchestrator listed a PR's reviews twice on every review — once to decide whether this was the first review, and again to dismiss any stale pending review the bot had left. Nothing creates a review between the two calls, so the second was redundant; dismissal now reuses the list already fetched. The saving grew once that fetch started paginating (see #219) (#74)
- **GitHub App permission — commit statuses (read)**: CI-aware approval gating reads a commit's combined status, which needs the App's commit-statuses permission; it was never requested, so the call failed and gating quietly fell back to gating on every check. Added `statuses: read` to `manifest.json` and `install.html` — which had itself drifted from the manifest, also missing `actions: read` and the `pull_request_review_comment` event. Existing installations must re-accept the updated permissions (#236)

### Fixed

- **Approval on a failed file fetch**: when `getPullRequestFiles` failed — a deleted PR, a rate limit, a transient error — the orchestrator caught the error, carried on with an empty file list, and could approve a PR whose diff it had never read. The failure now propagates to the normal failure path (check run marked failed, no approval) instead of being swallowed (#211)
- **Unknown CI state counted as passing**: the approval gate treated any check state it did not explicitly recognize as a success, so a `null` or unfamiliar status could let an approval through while CI was still unsettled. An unrecognized or pending state is now held as not-yet-passing and the bot waits (#217)
- **Stale "retry" notice after a posted review**: once the verdict was on the check run, a failure in a later step — applying labels, resolving threads, persisting the session — reached the same handler as an early failure and posted a "review could not be completed" comment over the review that had already gone out. The bot now records when the result is posted and takes the full failure path only for failures before that point (#220)
- **A truncated diff could still auto-approve, with no disclosure**: when a PR's diff exceeded the size budget the bot dropped whole files but carried on, and could post a clean approval whose summary reported the full file count without mentioning the omission. A truncated review is now held to a comment — never an approval — and the summary states how many files were left out (#234)
- **Findings lost when every inline comment was rejected**: on a follow-up review where GitHub rejected all of the inline comments — stale line numbers after a force-push, for instance — the bot posted nothing and the findings it had just computed were dropped. It now falls back to a single review body listing those findings (#215)
- **Pending bot review past page one never dismissed**: `listReviews` read only GitHub's first page of 30, so on a long-lived PR a stale pending review left beyond that page was never deleted, and the next review ran into GitHub's one-pending-review-per-user limit; first-review detection and the approval backstop read the same truncated list. It now walks pages of 100, bounded at 10 (#219)
- **Inline-comment fetch truncated to one page**: the inline review-comment fetch also stopped at the first 30, so on a busy PR follow-up de-duplication, `/resolve`, and the unresolved-thread backstop ran against a partial set — re-raising findings that had already been answered and leaving handled threads open. It now paginates, and the single-page variant was removed so nothing reaches for it again (#212)
- **Even-cluster merge downgraded severity**: when an even number of duplicate findings were merged, the bot took the lower of the two middle severities, so a finding could end up reported one level milder than it came in. The merge now keeps the more severe of the two (#213)
- **Distinct findings merged on severity and proximity**: the follow-up de-duplicator treated two findings as the same when they shared a severity and sat close together, even when they described different problems, so a nearby second finding could be dropped. A match now also requires the titles to be similar, with the content-overlap check kept as a fallback (#214)
- **Multi-line quote matched scattered lines**: a finding's multi-line quote counted as a full match as long as each line turned up somewhere in the diff, so a quote assembled from non-adjacent lines could anchor in the wrong place. A full multi-line match now requires the quoted lines to appear as a contiguous run (#216)
- **Line lookup bound to the wrong file on a suffix collision**: the `getLineText` fallback matched a path by suffix, so two changed files ending the same way — `foo/Config.java` and `bar/Config.java` — could resolve to the wrong one. It now resolves only when exactly one file matches, and returns nothing when the match is ambiguous (#218)
- **Model JSON with a raw control character failed the whole review**: the model sometimes emitted a literal tab or newline inside a JSON string value (verbatim code in a suggestion field), which strict parsing rejected, failing the attempt and forcing a full-cost retry. Raw control characters inside string values are now escaped before parsing, so both the review and the verifier tolerate them (#235)

### Dependencies

- Bumped `jackson-bom` to 2.22.0 to clear GHSA-5jmj-h7xm-6q6v / CVE-2026-54515 — a `@JsonIgnoreProperties` deserialization bypass — in the `jackson-databind` 2.21.4 the Quarkus platform manages; the 2.21.x patch fix is not yet on Maven Central (#244)

## [0.2.0] — 2026-06-21

This release makes the bot interactive and controllable from the PR — conversational replies, comment commands, context-aware labels, and configurable triggers — and hardens startup and the manual-review path.

### Added

- **Conversational replies**: a maintainer can `@thrillhousebot` anywhere in a PR thread — including as a reply to one of the bot's review findings — and the bot answers in context, pulling in the original finding, the surrounding diff, and the prior thread replies instead of having to re-run the whole review. An explicit `@`-mention is required; a bare reply on a thread (even the bot's own finding) does not pull it in. Replies are posted back into the same review thread (or as a PR comment for top-level mentions), gated to the same write-access/allowlisted users as a manual `/review`, and can be turned off with `REVIEW_CONVERSATIONAL_REPLIES_ENABLED=false`. Requires subscribing the GitHub App to the new `pull_request_review_comment` event (added to `manifest.json`) (#31, #202)
- **Comment commands**: drive the bot from a PR with `/help`, `/summary`, `/resolve`, `/pause`, and `/resume` (each also accepts the `@Thrillhousebot <command>` mention form). `/pause` silences the bot on a PR — skipping automatic reviews and conversational replies, and ignoring `/review` and `/summary` — until `/resume`; `/resolve` resolves the bot's open finding threads; `/summary` posts the PR summary if one was not generated yet. Every command except `/help` requires repository write access (#32)
- **Context-aware PR labels** (opt-in): the model is shown the repository's existing labels and picks the few that best describe the change. Off by default (`REVIEW_LABELS_ENABLED`); when on, it either posts a one-line suggestion comment or applies the labels (`REVIEW_LABELS_APPLY`), with optional creation of new labels (`REVIEW_LABELS_ALLOW_CREATE`) and a per-PR cap (`REVIEW_LABELS_MAX`, default 3). Labelling is best-effort and never blocks a review (#61)
- **Configurable review triggers**: narrow which pull requests are auto-reviewed — skip drafts (`WEBHOOK_SKIP_DRAFTS`), gate on labels (`WEBHOOK_REQUIRED_LABELS` / `WEBHOOK_EXCLUDED_LABELS`), and filter by base-branch glob (`WEBHOOK_BASE_BRANCHES` / `WEBHOOK_IGNORED_BASE_BRANCHES`); base-branch globs are gitignore-style, so `*` does not cross `/` — use `**` to span slashes (e.g. `dependabot/**`, or `**` alone for every branch). Defaults review every PR, matching prior behavior; a manual `/review` always bypasses the filters (#40)
- **Review on ready-for-review**: a draft PR marked "Ready for review" is reviewed immediately, pairing with `WEBHOOK_SKIP_DRAFTS` so drafts can be skipped until they are ready (#72)
- **Fail-fast configuration validation**: required configuration (`GITHUB_APP_ID`, `GITHUB_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `AI_API_KEY`) is validated at startup, and the app refuses to boot with a single message naming every missing or malformed value — including a non-numeric App id or a private key that is not valid PEM RSA — instead of failing later on the first webhook (#27)
- **Configurable bot identity**: the bot's own account login(s) are configurable via `GITHUB_BOT_LOGINS`, so loop protection, `/resolve`, summary deduplication, and follow-up finding tracking all keep recognizing the bot's own activity when the App is deployed under a different slug (#165, #201)
- **Reviewer flags single-page collection fetches**: the review prompt now has a pagination/truncation dimension, so a diff that lists a paginated collection (a GitHub REST endpoint or a GraphQL connection) and then consumes the result as if complete — searched, counted, iterated, or used to drive an action like `/resolve` — without walking every page is reported as a silent-truncation finding. The bot had been catching one such case while missing analogous REST and GraphQL ones (including in the same PR) because no dimension prompted the pattern; severity scales with what is dropped and confidence stays calibrated for the page-size assumption (#166)
- **Reviewer rejects refuted runtime-crash claims**: the review prompt now traces an alleged runtime failure (`NullPointerException`, index-out-of-bounds, and the like) from the enclosing method's entry down to the flagged line, and discards the finding when an in-diff guard makes that line unreachable for the claimed input — an earlier return/continue/throw, or a null/range check on a value derived from the flagged one. This removes a recurring class of confident false-positive crash findings the reviewer raised against code that already guards the condition (#112)

### Changed

- **Manual-trigger authorization is time-bounded**: the write-access check for a manual `/review` (installation-token mint + collaborator-permission call) now runs under a configurable timeout (`MANUAL_TRIGGER_AUTH_TIMEOUT`, default `5s`) on the webhook ack thread and fails closed if GitHub is too slow, so a degraded GitHub can no longer tie up a webhook worker past the delivery SLA (#92)
- **CI — actionlint guardrail**: workflows and the consolidated Trivy composite action are linted (including inline shell via shellcheck), with the release-gate scan path mirrored so it is validated on PR CI (#93)
- **CI — faster pipeline**: SpotBugs moved off the test job's critical path into the parallel lint job, the test job collapsed into a single Maven reactor, and the native build + image publish skipped for docs-only pushes to `main` (#170)
- **CI — SonarCloud scoping**: the Sonar scan runs only on `main` and same-repo pull requests (matching the SonarCloud community plan), and a `.dockerignore` keeps the Docker build context small (#165)

### Fixed

- **AI prompts dropped every context variable but the first**: each AI service (`PrReviewer`, `ReplyAssistant`, `FindingVerifier`) declared `@UserMessage` on a method *parameter*, which makes quarkus-langchain4j send only that parameter's raw value as the user message and never render the prompt template. So reviews ran on the diff alone — silently ignoring the repository instructions (`.github/thrillhousebot.md`), project stack, PR title/description, base comparison, related tests, and previous findings — the finding verifier audited candidates without the diff, and conversational replies saw only the maintainer's question with no diff, finding, or thread. Moved `@UserMessage` to the method so every `@V` variable is interpolated, and reduced `PromptTemplateEscaper` to marker-neutralization (its Qute unparsed-section wrapper was never stripped for data-bound values and corrupted any content containing `|}`). Added end-to-end and structural regression tests that pin the rendered prompt (#186)
- **Reviewer corrupted the marker-handling code it was reviewing**: the prompt-injection defense rewrote the diff-section delimiters (`<<<DIFF_START>>>` / `<<<DIFF_END>>>`) found *inside* the diff, so whenever the bot reviewed code that legitimately contains those markers — the escaper, the prompt templates, and any PR that edits them — it saw altered source. That produced false "contradictory assertion"/no-op findings and silently degraded review accuracy of exactly those files. Replaced the fixed delimiters with a per-review unguessable random fence around the diff (the "random sequence enclosure"/spotlighting defense) and now pass the diff byte-exact; the small prose context slots keep the lightweight marker-neutralization as defense-in-depth (#187)
- **Large PRs were silently truncated to 30 files**: `getPullRequestFiles` fetched only GitHub's default first page, so any PR with more than 30 changed files was reviewed — and described / changelog'd / replied to — on a partial diff, with no warning. It now paginates (100 files per page, bounded at 30 pages) so the whole diff is assembled before review (#190)
- **False "undefined / missing symbol" findings when the definition is just outside the diff**: a finding could confidently flag a variable, env var, import, or config key as undefined/unset when its definition sat in the same file a few unchanged lines outside the diff hunk's context window — GitHub serves only ~3 lines of context, so the definition was never in the reviewed material (a CRITICAL false positive on `release.yml` in PR #88 claimed `NEXT`/`TAG` were undefined when the step's `env:` block defined them). The reviewer now treats an unseen definition as unconfirmed rather than absent, and the verifier rejects an "undefined / missing symbol" finding only when the scope its definition would occupy isn't shown in the material (an unverifiable claim) — a genuinely missing symbol that the diff *does* demonstrate (e.g. the diff removes the definition) still stands (#192)
- **Approval gating ignored ruleset-based branch protection**: CI-aware approval gating resolved the required status checks only from *classic* branch protection, so a repository that protects its base branch with a repository/organization **ruleset** (the modern mechanism) silently fell back to gating approvals on every check instead of the actual required set. Required contexts are now unioned from rulesets and classic protection both (#178)
- **Duplicate "no issues, but CI pending" message on a clean first review**: when a PR had no findings but a required check was still pending or failing, the bot posted the held-back notice twice — once in the PR summary's CI-status table and again as a separate COMMENT review restating it. The redundant COMMENT review is now skipped when a first review is held back solely by CI; an unresolved prior finding, a follow-up review, or a `REQUEST_CHANGES` verdict still posts it (#175)

### Documentation

- **Repository review guidance**: added a dogfooded `.github/thrillhousebot.md` with GitHub platform facts and review heuristics for this codebase, so the bot stops repeating known false positives and primes recurring misses (#168)
- Documented the new v0.2.0 configuration keys in `README.md` and `.env.example` — review triggers, PR labels, conversational replies, `MANUAL_TRIGGER_AUTH_TIMEOUT`, and `GITHUB_BOT_LOGINS` (#165)

### Dependencies

- Bumped the Quarkus platform and Maven plugins, GitHub Actions (`actions/checkout`, `actions/setup-java`), and frontend packages (`undici`, `vitest`) (#151, #152, #153, #154, #155, #156)

## [0.1.1] — 2026-06-16

### Added

- **Webhook de-duplication**: redelivered webhook events are ignored within a configurable TTL (`WEBHOOK_DEDUP_TTL`), so GitHub redeliveries no longer trigger a second review of the same event (#20)
- **CI-aware approval gating**: the bot no longer posts a green approval while a PR's required checks are red or still pending — it waits for CI before approving. Requires the new `actions: read` GitHub App permission (#95)

### Changed

- **CI**: consolidated the seven duplicated Trivy scan + SARIF-upload steps across `ci.yml`, `release.yml`, and `security-scan.yml` into a single `.github/actions/trivy-scan` composite action, centralizing the pinned action SHA, Trivy version, `format: sarif`, and the `limit-severities-for-sarif` flag. The filesystem and image scans now apply `limit-severities-for-sarif` like every other scan (#12, #76, #82)
- **GitHub App permissions**: added `actions: read` to `manifest.json` (and documented in `README.md`), required to read workflow runs and check-suite status for CI-aware approval gating (#95)
- **Observability**: traces and metrics report the actually-configured AI provider instead of a hardcoded `deepseek` (#19)

### Fixed

- **Follow-up review tracking**: previous-findings tracking now survives a force-push or rebase. Findings are matched by their persisted code anchor rather than a raw line number, so still-open findings are no longer silently dropped or re-raised under a drifted severity, and the approve backstop replays every prior round (not just the newest), judges presence by content, handles unrecognized statuses, resolves path variants, and clears holds on a maintainer reply even for thread-less or null-title findings (#118, #129, #130, #131, #132, #133, #140, #143)
- **First-review summary**: a PR that was persisted but never reviewed still receives its first-run summary comment; first-review UX no longer keys off persistence state (#134)
- **Finding quote validation**: fabricated code quoted in a finding's _description_ is now caught (not just `suggestion_old`); the chained-call citation matcher spans nested parentheses; and the matcher tolerates wrapped lines, Unicode whitespace, and intra-literal spacing — so fewer real findings are demoted and fewer phantom citations slip through (#98, #106, #120, #121, #122)
- **Diff truncation**: oversized diffs are no longer cut mid-hunk in a way that dropped the closing code fence (#21)
- **Webhook delivery**: a dispatch failure no longer burns the dedup slot, so a manual redelivery is processed instead of being silently dropped (#89)

### Security

- **Manual triggers**: manual `/review` triggers are restricted to authorized logins (`manual-trigger-allowed-logins`) (#70)
- **Dashboard access control**: the dashboard fails closed when the GitHub App owner cannot be resolved; installation and repository access checks now paginate, so access is no longer mis-decided past the first page; and the repo-snapshot cache is no longer reused across a changed account owner (#17, #18, #91)

### Documentation

- Documented the new v0.1.1 configuration keys (`WEBHOOK_DEDUP_TTL`, `manual-trigger-allowed-logins`) (#94)
- Corrected the dashboard access section of the `README.md`, which still described the removed fail-open behavior (#90)

## [0.1.0] — 2026-06-12

### Added

- **AI-powered PR review**: analyzes diffs for correctness, security, regressions, comment consistency, and code quality
- **Multi-provider support**: OpenAI-compatible API — works with DeepSeek, OpenRouter, Alibaba Cloud, OpenAI, Ollama, and more
- **Click-to-apply suggestions**: inline GitHub suggestion blocks on PR review comments, applied with one click
- **Risk classification**: every finding tagged `critical`, `high`, `medium`, or `low`
- **Second-pass verifier**: drops or downgrades unverifiable findings before they are posted
- **Follow-up reviews**: tracks whether previous findings were addressed or justified across every prior review round
- **PR summary**: first-run summary comment with risk breakdown and key findings
- **Check runs**: pass/fail status for branch protection, alongside PR reviews with inline comments
- **Live dashboard**: Next.js UI with real-time WebSocket activity feed, cost analytics, token tracking, and session history
- **GitHub OAuth login**: secure dashboard authentication via GitHub App OAuth
- **OpenTelemetry observability**: traces, token histograms, cost counters, and latency metrics
- **Repository instructions**: reads `.github/thrillhousebot.md` (with Copilot/Claude/Agents fallback)
- **GraalVM native binary**: compiles ahead-of-time for fast startup (~50MB footprint)
- **Docker Compose deployment**: one-command setup with PostgreSQL
- **Container images**: multi-arch (linux/amd64, linux/arm64) UBI9-micro and hardened `-distroless` variants
- **Release pipeline**: semver tags, `:snapshot` / `:vX.Y.Z-<sha>-snapshot` main builds, cosign-signed images and tarballs with provenance attestations
- **GitHub App manifest flow**: `install.html` for quick app registration
- **`/review` slash command**: manual trigger for re-review
- **Zero-issues approval**: auto-approves clean PRs; celebratory message lives in the PR summary comment

### Changed

- **Review quality**
  - Findings that quote code absent from the diff are dropped; partially wrong quotes lose their suggestion and post at low confidence
  - Duplicate findings inside one review are merged (median severity, richest description)
  - Findings a maintainer answered on a prior round do not return on follow-up review
  - The verifier receives prior findings and rejects re-raises, cross-scope misattributions, and out-of-diff artifact claims above medium severity
  - Review prompts require verbatim quoting, both sides of consistency comparisons, convention-respecting suggestions, and one finding per defect
  - Review-quality probe (`docs/REVIEW_EVAL.md`) for scoring deploys against collected failure cases

### Fixed

- **Clean-review summary**: zero-finding reviews post the PR summary (with the Thrillhouse message inside it); the approval carries no separate body
- **Dashboard auth**: expired sessions redirect to login; valid sessions without repo access show an access-denied screen instead of looping back to login

### Security

- **Dashboard sessions**: opaque server-side session IDs in cookies — GitHub OAuth tokens never stored in the browser; 8h TTL; HttpOnly, Secure, SameSite=Lax
- **OAuth login**: dynamic authorize/callback parameters are URL-encoded; authorization codes must match a strict allowlist before token exchange
