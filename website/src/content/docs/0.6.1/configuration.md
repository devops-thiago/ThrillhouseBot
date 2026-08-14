---
slug: 0.6.1/configuration
title: Configuration
description: Every environment variable the bot reads, with defaults.
---



Configuration is read from environment variables (see `.env.example`). Short
names (`AI_*`, `REVIEW_*`, `WEBHOOK_*`, ...) are explicit aliases; every other
`thrillhousebot.*` key is settable through the standard Quarkus env-var mapping
— uppercase with `.`/`-` replaced by `_` (e.g. `thrillhousebot.review.ignored-files`
→ `THRILLHOUSEBOT_REVIEW_IGNORED_FILES`). The AI variables are the ones you
will change per provider:

| Variable | Purpose | Default |
|---|---|---|
| `AI_API_KEY` | API key for the AI provider | _(required)_ |
| `AI_BASE_URL` | OpenAI-compatible base URL | `https://api.deepseek.com/v1` |
| `AI_MODEL` | Chat model name | `deepseek-chat` |
| `AI_PROVIDER` | Provider label for telemetry (`gen_ai.provider.name`); derived from `AI_BASE_URL` when unset | _(derived)_ |
| `AI_TIMEOUT` | Per-request timeout | `300s` |
| `AI_REASONING_ENABLED` | Send a reasoning hint to reasoning-capable models; when `false` no reasoning parameter is sent and the provider default applies | `false` |
| `AI_REASONING_EFFORT` | Effort sent while enabled: `none`/`low`/`medium`/`high`/`xhigh`/`max` (`none` explicitly asks the model not to reason; `xhigh`/`max` are the extended tiers newer reasoning models expose above `high`); reasoning tokens are billed as output tokens | `low` |
| `AI_REASONING_EFFORT_CONCISE` | Effort for the fixed-shape calls on the `concise` model (final summary, finding verifier, replies), which do **not** follow `AI_REASONING_EFFORT`: reasoning tokens count against `REVIEW_CONCISE_MAX_OUTPUT_TOKENS`, so a high effort there lets the verifier reason its whole allowance away and return an empty response. Same accepted values; unset means `low`, lowered to `AI_REASONING_EFFORT` when that is set below `low` | `low` |
| `GITHUB_APP_ID` | GitHub App ID | _(required)_ |
| `GITHUB_PRIVATE_KEY` | GitHub App private key (PEM) | _(required)_ |
| `GITHUB_WEBHOOK_SECRET` | Webhook HMAC secret | _(required)_ |
| `GITHUB_BOT_LOGINS` | Comma-separated bot account login(s) the bot skips to avoid replying to itself; override when deployed under a different App slug (`<app-slug>[bot]`) | `thrillhousebot[bot],thrillhouse-bot[bot]` |
| `GITHUB_WRITE_MIN_INTERVAL` | Duration spacing two content-creating GitHub calls (comments, review comments, thread replies, reviews), shared process-wide. GitHub secondary-rate-limits rapid content creation and answers `403`; pacing keeps the bot inside that envelope instead of discovering it by rejection — its published guidance is no more than one such request per second. `0` disables pacing | `1s` |
| `GITHUB_WRITE_MAX_WAIT` | Duration ceiling on how long one caller waits for its content-creation slot. Past it the call goes out unpaced and the bounded backoff handles a refusal, so a long queue never parks a finished command | `60s` |
| `WEBHOOK_DEDUP_TTL` | Webhook deduplication time-to-live for GitHub redeliveries | `24h` |
| `THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS` | Comma-separated allowlist of logins permitted to trigger manual `/review` without repo access | _(empty)_ |
| `MANUAL_TRIGGER_AUTH_TIMEOUT` | Upper bound on the manual-trigger write-access check on the webhook ACK thread; fails closed (denies) if GitHub is slower | `5s` |
| `ACK_REACTION_TIMEOUT` | Upper bound on the 👀 command-ack reaction on the webhook ACK thread; the wait is abandoned (reaction may land late) if GitHub is slower | `3s` |
| `AUTO_REVIEW_MIN_INTERVAL` | Minimum interval between automatic reviews of the same PR — pushes within the window are skipped silently, even on a new head SHA (in-memory, per replica). A manual `/review` always bypasses; unset or `0` reviews every push | `0` (disabled) |
| `REVIEW_CI_GATING` | How strictly CI status factors into APPROVE: `strict` holds approval while required CI is pending, failing, or unreadable (fail-closed, safest); `warn` allows APPROVE but notes CI uncertainty in the summary/check; `off` skips CI entirely (findings-only). Prefer `strict` unless flaky CI or incomplete required-context resolution makes soft modes necessary | `strict` |
| `WEBHOOK_SKIP_DRAFTS` | Skip auto-review while a PR is a draft (reviewed once marked ready / on later pushes) | `false` |
| `WEBHOOK_REQUIRED_LABELS` | Comma-separated labels; only auto-review PRs carrying at least one (case-insensitive) | _(empty — no gate)_ |
| `WEBHOOK_EXCLUDED_LABELS` | Comma-separated labels; skip auto-review of PRs carrying any (wins over required) | _(empty)_ |
| `WEBHOOK_BASE_BRANCHES` | Comma-separated globs; only auto-review PRs whose base branch matches one (e.g. `main,release/*`). Globs are gitignore-style: `*` does **not** cross `/`, so use `**` to span slashes (`**` alone matches every branch) | _(empty — all branches)_ |
| `WEBHOOK_IGNORED_BASE_BRANCHES` | Comma-separated globs; skip auto-review of PRs whose base branch matches one (wins over allowlist; same `*`/`**` rule — match nested branches with `**`, e.g. `dependabot/**`) | _(empty)_ |
| `REVIEW_VERIFIER_ENABLED` | Second, skeptical AI pass that re-checks each finding against the diff before posting, dropping or downgrading what it can't confirm (see [AI call budget](#ai-call-budget)); fails open — a verifier error keeps the original findings | `true` |
| `REVIEW_DECLINE_RECHECK_ENABLED` | Re-check a maintainer's decline against the reviewed code before a prior finding is recorded "justified" (see [Re-checking declines](#re-checking-declines)); the finding stays open for one more round only when the reviewed diff plainly contradicts the stated reason. `false` makes a maintainer reply close the finding unconditionally | `true` |
| `REVIEW_BLOCKING_STRICTNESS` | When findings escalate to `REQUEST_CHANGES`: `balanced` (CRITICAL/HIGH + HIGH confidence), `strict` (any CRITICAL/HIGH), or `lenient` (CRITICAL + HIGH confidence only). See [Blocking strictness](#blocking-strictness) | `balanced` |
| `REVIEW_CONVERSATIONAL_REPLIES_ENABLED` | Answer `@thrillhousebot` mentions in PR threads (including finding replies) with an AI reply | `true` |
| `REVIEW_ADD_DOCS_ENABLED` | Allow the on-demand `/add-docs` command to generate docstrings as committable suggestions | `true` |
| `REVIEW_IMPROVE_ENABLED` | Allow the on-demand `/improve` command to run a whole-PR improvement pass and post committable suggestions | `true` |
| `REVIEW_GENERATE_TESTS_ENABLED` | Allow the on-demand `/generate-tests` command to propose unit tests for the changed code | `true` |
| `REVIEW_DIAGRAM_ENABLED` | Include an opt-in Mermaid control-flow diagram in the PR summary | `false` |
| `REVIEW_PATCH_COVERAGE_ENABLED` | Feed patch coverage into the review context: the added lines the repository's own coverage report records as never executed (see [Repository configuration](#repository-configuration)). Only takes effect for a repository that names its coverage artifact in `.github/thrillhousebot.yml` | `false` |
| `REVIEW_FOLLOW_UP_SUMMARY_ENABLED` | Post a short delta comment on follow-up reviews with the new-finding, resolved, and still-open counts. Only the first review posts the full summary; a follow-up pass with no delta (nothing new, nothing resolved) posts nothing | `false` |
| `REVIEW_LARGE_PR_NUDGE_ENABLED` | Add a note to the PR summary when a large PR's review opened **no inline finding** — it may be genuinely clean, or the pass may have been shallow — pointing at `/review` and `/improve`. Costs no extra AI call and never changes the verdict; a PR under both thresholds below is unaffected | `false` |
| `REVIEW_LARGE_PR_NUDGE_MIN_FILES` | Changed files at or above which the nudge applies (PR-level total, so ignored files still count). `0` switches this dimension off | `20` |
| `REVIEW_LARGE_PR_NUDGE_MIN_CHANGED_LINES` | Changed lines (additions + deletions) at or above which the nudge applies; either dimension triggers it on its own. `0` switches this dimension off — with both at `0` the nudge never fires | `1000` |
| `REVIEW_MAX_INPUT_TOKENS` | Per-call input-token budget for review, `/improve`, `/describe` and `/changelog` calls; large PRs are split into batches that each fit it. Bounded by the active model's input cap (see [Per-model AI settings](#per-model-ai-settings)). `0` disables token budgeting | `48000` |
| `REVIEW_OUTPUT_BUFFER_TOKENS` | Tokens reserved out of the input budget for the model's response | `8192` |
| `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` | Response cap (`max_tokens`) for the fixed-shape/short AI calls — the final summary of a multi-call review, the finding verifier, and maintainer replies — which run on the `concise` named model so they don't share a cap sized for batch review output (see [Per-model AI settings](#per-model-ai-settings)). Hitting it surfaces as a truncation error naming this variable, never as a silently cut summary; set it empty to drop the cap and use the provider default | `8192` |
| `REVIEW_MAX_AI_CALLS` | Cap on AI calls per review (batch calls plus the final summary call), per `/describe` and `/changelog` run (batch calls plus one reduce call, spent only when the PR needed more than one batch), and per `/improve`, `/generate-tests` or `/add-docs` run (batch calls only — their results are merged locally); files that still don't fit are reported by name as omitted | `6` |
| `REVIEW_TOKEN_SAFETY_MARGIN` | Fraction of the input budget actually used, absorbing token-estimate error | `0.9` |
| `REVIEW_MAX_TOKENS_PER_REVIEW` | Ceiling on the tokens one review may consume across every AI call it makes — actual input+output as the provider reports them, counting retries and the final summary call, where `REVIEW_MAX_AI_CALLS` only counts planned calls. Once reached no further review call is made: remaining batches are disclosed by name as not reviewed (the verdict holds and the summary names the ceiling as the reason) and the summary degrades to a counts-only rendering that keeps the findings already paid for. `0` disables the ceiling. Review path only — the on-demand commands keep their own call cap | `0` |
| `REVIEW_MAX_DIFF_LINES` | Line cap on single-call diff renders (replies, base comparison, budgeting-disabled review). Token-budgeted reviews and the batched commands — `/improve`, `/describe`, `/changelog`, `/generate-tests`, `/add-docs` — ignore it (the planner owns coverage by tokens); `0` disables the cap | `5000` |
| `THRILLHOUSEBOT_REVIEW_MAX_REVIEW_COMMENTS` | Maximum inline comments posted per review; findings over the cap are surfaced in the summary instead of dropped | `50` |
| `THRILLHOUSEBOT_REVIEW_MAX_AI_RETRIES` | Attempts per failed AI call before the review errors out | `5` |
| `THRILLHOUSEBOT_REVIEW_AI_RETRY_BASE_DELAY_MS` | Base delay of the exponential retry backoff, in milliseconds | `2000` |
| `THRILLHOUSEBOT_REVIEW_AI_TIMEOUT_SECONDS` | Client-side wait per AI streaming attempt; keep it >= `AI_TIMEOUT` so timed-out attempts don't leave orphaned provider streams | `300` |
| `THRILLHOUSEBOT_REVIEW_INSTRUCTIONS_FILE` | Repo-relative path of the per-repo instructions file read on each review | `.github/thrillhousebot.md` |
| `THRILLHOUSEBOT_REVIEW_IGNORED_FILES` | Comma-separated gitignore-style globs excluded from review — lockfiles, generated code, build output. `*` does not cross `/`; use `**` to span directories. Replaces (not extends) the default list, so re-include the defaults you still want | `**/pom.xml,**/package-lock.json,**/*.lock,**/*.generated.*,**/target/**` |
| `THRILLHOUSEBOT_REVIEW_REPO_CONFIG_ENABLED` | Let each repository extend the ignore list with globs of its own, scope review rules to a path, and name its coverage-report artifact, from `.github/thrillhousebot.yml` (see [Repository configuration](#repository-configuration)). Both are additive; set `false` to make the deployment list and the global instructions the only ones that count | `true` |
| `REVIEW_LABELS_ENABLED` | Opt in to context-aware PR labels (see [PR labels](#pr-labels)) | `false` |
| `REVIEW_LABELS_APPLY` | When labels are enabled, add them to the PR instead of only suggesting them in a comment | `false` |
| `REVIEW_LABELS_ALLOW_CREATE` | Allow the bot to create suggested labels that don't exist yet | `false` |
| `REVIEW_LABELS_MAX` | Maximum labels applied or suggested per PR | `3` |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | OAuth credentials for dashboard login | _(required for dashboard)_ |
| `DASHBOARD_URL` | Public dashboard URL (OAuth callback base) | `http://localhost:8080` |
| `DATASOURCE_DB_KIND` | `h2` or `postgresql`. Quarkus fixes the datasource kind at build time, so this picks the driver when the app is built, not when a prebuilt image starts — released images are built for PostgreSQL and ignore an `h2` value | `h2` (dev), `postgresql` (`%prod`) |
| `HTTP_CONNECT_TIMEOUT` | Outbound HTTP connect timeout (GitHub API, OAuth) | `10s` |
| `HTTP_REQUEST_TIMEOUT` | Outbound HTTP request timeout (GitHub API, OAuth) | `10s` |
| `WEBSOCKET_KEEPALIVE_MS` | Dashboard WebSocket keepalive interval in ms; `0` or negative disables it (and stale replay-buffer eviction) | `25000` |

### AI call budget

A review that reports findings makes **two** model calls by default, not one:
the review call itself plus a verification call that re-sends the diff and the
candidate findings, so budget roughly **2× tokens** per flagged review. On
large PRs under token-aware budgeting this becomes N batch review calls + N
per-batch verification calls + one summary call. Set
`REVIEW_VERIFIER_ENABLED=false` to skip only the AI verifier — cheaper, at the
cost of more false positives; a deterministic hedging guard still runs, and a
verifier failure never blocks the review (it fails open, keeping the original
findings).

The on-request commands are budgeted the same way. `/improve`, `/describe`,
`/changelog` and `/generate-tests` each split a large PR into batches under
`REVIEW_MAX_INPUT_TOKENS` and spend one call per batch, capped by
`REVIEW_MAX_AI_CALLS`. `/describe` and `/changelog` additionally reserve one call
of that cap for the step that reduces the per-batch results to a single
description or entry, and only spend it when the PR needed more than one batch —
so a single-batch PR still costs exactly one call. `/improve` and
`/generate-tests` reserve nothing, because their reductions are assembled locally
(a union of suggestions, and a union of proposed test files deduplicated by
path). When the cap is reached before every file has been batched, the uncovered
files are named in the partial-coverage note rather than dropped silently.

### Re-checking declines

When a maintainer replies to a finding to decline it, the follow-up analysis
records that finding as **justified** and the bot moves on. A dismissal is a
claim, though, not ground truth — a correct finding can be closed by an
incorrect rebuttal, and the rebuttal often names the very mechanism that makes
the bug real ("it only runs after the webhook is acked, so there's no race" —
on an executor that starts a thread per event).

`REVIEW_DECLINE_RECHECK_ENABLED=true` (the default) therefore traces a decline's
stated reason against the code the review actually saw. When the reviewed diff
**plainly contradicts** that reason, the finding is kept **open for one more
round** with a note quoting both the claim and the contradicting line, instead
of being recorded justified. It is deliberately conservative:

- Trusting the maintainer is the default. A rebuttal about house style, intent,
  accepted risk, or priority — anything not refutable from the code — is
  respected, as is any premise whose supporting code is not in the diff.
- **One push-back, then defer.** The re-check only fires while the thread carries
  a single maintainer reply; replying again always ends it, so the bot can never
  keep re-opening the same finding round after round.
- The re-opened finding is never re-posted as a new comment — it stays tracked in
  *Previous Findings Status*, so nobody is asked to answer the same comment twice.
- The override holds approval (`APPROVE` → `COMMENT`) exactly like any other
  unresolved previous finding; it never invents a new blocking finding.

Set it to `false` to make a maintainer's reply final, unconditionally.

### Clearing a finding with no thread

Replying on a finding's review thread is the usual way to close it — but a
**LOW**-confidence finding at **MEDIUM** or **LOW** risk never opens one. It is
listed under **Things to double-check** in the summary instead, so there is no
thread to reply on, while follow-up reviews keep reporting it unresolved and
holding approval (`APPROVE` → `COMMENT`). To close one, comment on the PR
conversation:

```
@thrillhousebot resolved src/main/java/com/example/Widget.java:42 — Missing null check
```

Write it as plain text in the comment — pasted back inside a code fence or
backticks it reads as documentation and does nothing (see the quoting rule
below).

Closing a finding wrongly is worse than leaving it open, so the match is strict
and **when the naming is ambiguous the finding stays held**. All of the
following must hold:

- **The comment states `@thrillhousebot resolved`.** `resolve` (the thread
  command) is not it, and a mention alone is not it — an ordinary question about
  a finding is engagement, not a decision. Nor is the interrogative:
  `@thrillhousebot resolved?` *asks* whether a finding was fixed, so it clears
  nothing even when the rest of the comment names one. A question mark anywhere
  later is fine — only one straight after the directive words makes it a question.
- **You name the finding by *both* its `path:line` locator and its own content**
  — its title, or its description when it has no title. Each **Things to
  double-check** row already prints both, the title first and then the locator in
  backticks, so copying the row is the reliable way to get them right. Naming only
  one of the two clears nothing. Locators match whole, so `Widget.java:42` never
  closes a different finding at `Widget.java:4`.
- **You hold write access.** The comment's GitHub `author_association` must be
  `OWNER`, `MEMBER` or `COLLABORATOR`; a fork-PR author's or a drive-by
  commenter's comment is ignored. The bot's own comments are ignored too — its
  summary reproduces every finding verbatim.
- **You *use* the directive rather than quote it.** `@thrillhousebot resolved`
  counts as an instruction only when those words are plain text. Marking them up
  — as `` `@thrillhousebot resolved` ``, inside a fenced block, or on a `>`
  quoted line — reads as documentation, so a comment that *explains* the feature
  (the fenced example above, or a colleague pasting one) clears nothing. The
  **locator and title may still be in backticks**, which is how the summary
  prints them; only the directive words themselves may not be.
- **Quoted blocks do not count at all.** Blockquote lines and fenced code are
  dropped before anything is matched, so GitHub's *Quote reply* on the summary —
  which reproduces every double-check row — names no finding either.

One comment may name several findings; each is matched independently, and one it
does not name stays open. A finding with neither a title nor a description can
never be named this way, so it stays held — fix it, or reply on its thread if it
has one. The clearing is applied by the next review, which records the finding
**resolved**.

The bot answers the directive as soon as it sees it, but only with what is
knowable that early: it has no review loaded and no findings to match against, so
it says what the next review will evaluate rather than reporting an outcome. The
one exception is a directive naming no `path:line` at all — that provably clears
nothing, so the reply says so outright and shows the shape to use. A locator that
is present but wrong still gets the general reply, and the review is what reports
the finding still unresolved.

**Conversation read ceiling.** A review reads the PR conversation in pages of
100, up to 10 pages — 1000 comments — so one review can never turn a runaway
thread into hundreds of API calls. GitHub serves these comments oldest first and
offers no reverse order on that endpoint, so on a PR past the ceiling the
**newest** comments are the ones left unread, and a directive among them will not
clear anything that round. The bot logs a warning naming the ceiling whenever it
is reached, so this shows up as a log line rather than as the feature quietly
doing nothing; push a commit to re-review, or reply on the finding's thread if it
has one.

### Blocking strictness

By default (`REVIEW_BLOCKING_STRICTNESS=balanced`), only **CRITICAL** or **HIGH**
risk findings that the model reports at **HIGH** confidence escalate the PR
review to `REQUEST_CHANGES` (and fail the check run). Medium/low-confidence
severity findings still post as comments with a neutral check.

| Mode | Blocks merge when |
|---|---|
| `balanced` (default) | CRITICAL or HIGH risk **and** HIGH confidence |
| `strict` | CRITICAL or HIGH risk, **any** confidence |
| `lenient` | CRITICAL risk **and** HIGH confidence only |

**Security-team recommendation:** use `strict` so a CRITICAL/HIGH finding cannot
slip through as a comment just because confidence was demoted. Be aware that
under `strict`, the finding verifier's confidence demotions (hedged claims →
medium/low) no longer prevent a merge block — only risk reduction or dropping
the finding does. Stay on `balanced` if you want verifier demotions to keep
speculative severity findings non-blocking.

This knob controls the **review verdict** only. Where findings are posted
(inline thread vs summary) is separate confidence gating tracked in
[#105](https://github.com/devops-thiago/ThrillhouseBot/issues/105); when that
lands, low-confidence findings can move out of the inline stream without
changing these blocking rules.

The app validates configuration at startup and **fails fast** if a required value
(`GITHUB_APP_ID`, `GITHUB_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `AI_API_KEY`) is missing or — for
the private key — not a valid PEM RSA key, naming every offending variable in one message instead of
surfacing later on the first webhook or review. Dashboard OAuth (`GITHUB_CLIENT_ID` /
`GITHUB_CLIENT_SECRET`) is optional: leave both unset and the dashboard login is simply disabled.

Cost tracking uses per-model pricing keyed by the model name, for example:

```properties
thrillhousebot.ai.pricing.deepseek-chat.input-per-1k=0.00014
thrillhousebot.ai.pricing.deepseek-chat.output-per-1k=0.00028
```

If you switch to a different `AI_MODEL`, add a matching
`thrillhousebot.ai.pricing.<model>.*` pair so the dashboard can compute cost.
Without an entry the bot still records tokens, but warns once and flags sessions
as "no pricing" instead of showing `$0`.

### Per-model AI settings

Model-specific settings live under `thrillhousebot.ai.models.<model>.*`, keyed
by the model name (the `AI_MODEL` value) like the pricing map. Only the active
model's entry is read, so you can keep entries for every model you use and
switch `AI_MODEL` freely:

```properties
# Input hard cap. The effective review budget is min(REVIEW_MAX_INPUT_TOKENS,
# cap); models without an entry get a 128000 cap.
thrillhousebot.ai.models.deepseek-chat.max-input-tokens=64000
# The model's total context window. On a shared window the prompt and the
# completion are both charged to it, so boot fails when max-input-tokens +
# max-output-tokens do not fit inside it. Omit it and the ceiling isn't checked.
thrillhousebot.ai.models.deepseek-chat.context-tokens=128000
# Per-model overrides of REVIEW_OUTPUT_BUFFER_TOKENS / REVIEW_TOKEN_SAFETY_MARGIN
thrillhousebot.ai.models.deepseek-chat.output-buffer-tokens=8192
thrillhousebot.ai.models.deepseek-chat.token-safety-margin=0.9
# Generation parameters, sent on every chat call when set
thrillhousebot.ai.models.deepseek-chat.temperature=0.2
thrillhousebot.ai.models.deepseek-chat.top-p=0.95
thrillhousebot.ai.models.deepseek-chat.max-output-tokens=8192
thrillhousebot.ai.models.deepseek-chat.frequency-penalty=0.1
thrillhousebot.ai.models.deepseek-chat.presence-penalty=0.1
thrillhousebot.ai.models.deepseek-chat.seed=42
# Set true only when the provider really bills the response outside the context
# window (1M in with 384K out on top, rather than 384K carved out of the 1M) —
# it switches off both the reservation and the context-tokens ceiling
thrillhousebot.ai.models.some-separate-budget-model.separate-output-budget=true
```

Notes:

- **`max-input-tokens` is a cap, not the budget.** `REVIEW_MAX_INPUT_TOKENS`
  stays the spend knob; the per-model value keeps it from overshooting the
  model's real window. To use a large-context model beyond 128k, raise both.
  Startup logs a warning whenever the cap lowers your configured budget.
- **`context-tokens` is the window itself**, and declaring it is what lets the
  bot refuse an impossible request instead of paying for one. On a shared
  window the provider charges the prompt *and* the completion to that one
  context, so boot fails when `max-input-tokens + max-output-tokens` exceed it,
  and again when your effective budget (`REVIEW_MAX_INPUT_TOKENS` clamped by the
  cap) plus the largest response cap in play — `max-output-tokens` or
  `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` — exceeds it. Without it, an over-large
  pair is only discovered when the provider rejects every call for length.
  It is optional: a model that doesn't declare one is simply not checked.
- **Quote keys with `.` or `/`** (`thrillhousebot.ai.models."gpt-5.5".…`), the
  same rule as the pricing map. Override via env — hyphen-only keys use underscores
  (`THRILLHOUSEBOT_AI_MODELS_DEEPSEEK_V4_PRO_MAX_INPUT_TOKENS=1000000`); dotted keys use the
  quoted-key form (`THRILLHOUSEBOT_AI_MODELS__GPT_5_5__MAX_INPUT_TOKENS=256000`).
  `application.properties` ships empty stubs for known models so SmallRye can
  disambiguate hyphenated keys — [Quarkus env mapping](https://quarkus.io/guides/config-reference#environment-variables).
  For a model without a stub, add an empty
  `thrillhousebot.ai.models."<model>".max-input-tokens=` line (external
  `application.properties` or `-D`) alongside the env var.
- **`top_k` is not available** on the OpenAI-compatible wire; it becomes
  relevant only with native provider integrations.
- **`max-output-tokens` no longer caps every call.** The final summary of a
  multi-call review, the finding verifier, and maintainer replies run on a
  second model binding — the `concise` named model
  (`quarkus.langchain4j.openai.concise.*`) — that points at the same provider,
  credentials, and model through the same `AI_*` variables and follows the
  active model's temperature tuning, but carries its own response cap,
  `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` (default `8192`), and its own reasoning
  effort, `AI_REASONING_EFFORT_CONCISE` (default `low`). Those responses are
  fixed-shape or short, so they don't need — and shouldn't be licensed to
  spend — a cap sized for batch review output. The review itself and the
  command generators (`/describe`, `/changelog`, `/add-docs`, `/improve`,
  `/generate-tests`), whose outputs scale with the diff, stay on the default
  model and its `max-output-tokens`.
- **Reasoning effort is per lane.** Reasoning tokens are billed as output and
  count against the response cap, so on the `concise` model a high effort can
  consume the whole allowance and leave no content — the verifier then reports
  that it kept its findings unverified. That tail is variable-length, not a
  size threshold, so raising `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` only shifts the
  odds. Keep `AI_REASONING_EFFORT_CONCISE` low (the default) and spend the
  effort budget on `AI_REASONING_EFFORT`, which drives the review itself.
- **`max-output-tokens` vs `output-buffer-tokens`**: `max-output-tokens` is the
  hard response-length cap sent to the provider; `output-buffer-tokens` only
  reserves input-budget headroom for the map-reduce budgeter. On a shared-window
  model, keep the buffer at least as large as the output cap so a response the
  model is allowed to produce always has reserved room — set both when capping
  output. Boot fails if you don't.
- **`separate-output-budget`** (default `false`) says which contract the model is
  on. Left off, prompt and completion share one window: the budgeter reserves
  `output-buffer-tokens` out of the input budget, and the buffer must cover
  `max-output-tokens`. Set it `true` for a model that publishes a response
  allowance *on top of* its input window rather than inside it — then the
  budgeter stops reserving (the response never draws on the diff budget), the
  buffer no longer has to cover the cap, and the completion stops counting
  against `context-tokens`. Getting it wrong is expensive in one direction and
  unbootable in the other, so it is explicit rather than inferred: a
  384000-token cap on a 1M window silently costs ~40% of every call's diff
  budget if the model is wrongly marked shared — while marking a genuinely
  shared model separate turns off every guard and the provider rejects the
  calls instead, which is how `deepseek-v4-flash` shipped its wrong pair.
  Verify against the provider's own documented window before setting it.
- **`seed`** is a best-effort determinism hint (same seed + same parameters aims
  for the same sampling) on providers that support it; unsupported providers
  ignore it. For deterministic reviews, prefer a low `temperature` first.
- **Generation-parameter validation** happens at boot: temperature must be in
  `[0, 2]`, `top-p` in `(0, 1]`, penalties in `[-2, 2]`, token counts positive —
  a typo in any entry (even an inactive model's) fails startup with a message
  naming the key.





## Repository configuration

The instructions file (`.github/thrillhousebot.md`) is prose for the model.
Structured settings live in a separate, optional `.github/thrillhousebot.yml`
(`.github/thrillhousebot.yaml` also works) — kept apart on purpose, because the
instructions fallback chain may land on a file owned by another tool, and its whole
content is fed to the model as untrusted prose:

```yaml
review:
  # Extra paths this repository never wants reviewed, on top of the deployment default.
  ignored-files:
    - "docs/generated/**"
    - "**/*.snap"
    - "testdata/**"

  # Review rules for one path only, on top of the prose in .github/thrillhousebot.md
  # (which keeps applying everywhere).
  path-instructions:
    - path: "payments/**"
      instructions: |
        Money is handled in integer cents; flag any floating-point arithmetic.
        Every state change must be idempotent under retry.
    - path: "**/generated/**"
      instructions: "Generated code: style and naming findings do not apply."

  # Name of the workflow artifact holding this repository's JaCoCo XML coverage report.
  # Only read when the deployment sets REVIEW_PATCH_COVERAGE_ENABLED=true.
  coverage-artifact: "coverage-report"
```

**Precedence: the effective ignore list is the union of both — global ∪ per-repo.**
A file is skipped if it matches *either* the deployment-wide
`thrillhousebot.review.ignored-files` list *or* a glob the repository declared. A
repository can therefore take more files out of review scope, but never put back a
file the deployment excludes, and a repository that ships no config file gets the
global list exactly as before. Globs use the same gitignore-style syntax as the
global key (`*` does not cross `/`; use `**` to span directories).

**Precedence: the review rules for a file are the global instructions plus every
matching path scope.** The prose in `.github/thrillhousebot.md` (or whichever file the
fallback chain lands on) applies to every file exactly as before. On top of that, each
`path-instructions` scope whose glob matches a changed file contributes its rules *for
that file only* — the model is shown each scope alongside the files it governs, so
`payments/` strictness is never carried over to generated code. Scopes are additive and
may overlap: a file matching two scopes gets both, in declaration order, and where a
scope and the global instructions conflict, the scope wins for its own files. A scope
matching nothing in the pull request is not sent at all, and a file the ignore list
already excluded is never scoped — ignore rules run first. Path globs use the same
syntax and the same matcher as `ignored-files`.

**Patch coverage: `coverage-artifact` names a report, and nothing is assumed without
it.** The bot never builds the pull request, so it cannot measure coverage itself. When
the deployment sets `REVIEW_PATCH_COVERAGE_ENABLED=true` *and* a repository names an
artifact here, each review looks for that artifact on a **completed workflow run for the
exact head commit** (GitHub's `head_sha` filter), downloads it, and intersects the
report's never-executed lines with the lines the diff adds. The reviewer is then shown a
short "uncovered changed lines" list and told two things: changed logic nothing exercises
is reportable, and a correctness claim about such a line must not be softened by the
usual "but a test in this diff covers it" check — no test runs that line.

For this to work the workflow must upload the report, e.g.

```yaml
- run: ./mvnw clean test jacoco:report
- uses: actions/upload-artifact@v7
  with:
    name: coverage-report          # must match review.coverage-artifact
    path: target/site/jacoco/jacoco.xml
```

Only JaCoCo XML is understood today. **A repository that names no artifact — the common
case — contributes nothing, and its review is exactly what it was before.** The same is
true when the run uploaded nothing by that name, the artifact has expired, the download
fails, or the report is in another format: the section is omitted rather than guessed at.
Nothing about coverage is ever inferred from the diff, and a line's *absence* from the
list is explicitly not evidence that a test covers it. Files the ignore list already
excluded are never reported as under-tested.

The file is read from the repository's default branch on each review and cached for
five minutes. Everything about it fails soft: a missing file, invalid YAML, an
unexpected shape, an uncompilable glob, or a malformed `path-instructions` entry is
logged and skipped, leaving the global ignore list and the global instructions in force
— it never fails a review. A repository may declare at most 25 scopes, each with at most
4000 characters of rules; the rest is dropped with a warning. Operators who do not want
repositories adjusting their own review scope or rules can turn the whole mechanism off
with `THRILLHOUSEBOT_REVIEW_REPO_CONFIG_ENABLED=false`.





## PR labels

ThrillhouseBot can suggest context-aware labels (area, change type, risk) drawn
from the diff. The feature is **off by default**; turn it on with
`REVIEW_LABELS_ENABLED=true`.

When enabled, the model is shown the repository's existing labels and picks the
few that best describe the PR — it only ever chooses from labels that already
exist, so it respects whatever label scheme the repo already uses. What happens
next depends on `REVIEW_LABELS_APPLY`:

- `false` (default): the suggestions are posted as a one-line comment on the
  first review, leaving the decision to a maintainer.
- `true`: the labels are added to the PR automatically.

Set `REVIEW_LABELS_ALLOW_CREATE=true` to let the bot create a suggested label
that doesn't exist yet (off by default, so it never invents labels), and
`REVIEW_LABELS_MAX` to cap how many labels it applies or suggests (default `3`).
Labelling is best-effort — a failure here never blocks or fails the review.


