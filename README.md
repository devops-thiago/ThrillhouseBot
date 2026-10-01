<p align="center">
  <img src="icon.png" alt="ThrillhouseBot" width="80" />
</p>

# ThrillhouseBot

> **"Everything's coming up Thrillhouse!"**

<p align="center">
  <a href="https://github.com/devops-thiago/ThrillhouseBot/actions/workflows/ci.yml"><img src="https://github.com/devops-thiago/ThrillhouseBot/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://codecov.io/gh/devops-thiago/ThrillhouseBot"><img src="https://codecov.io/gh/devops-thiago/ThrillhouseBot/branch/main/graph/badge.svg" alt="Coverage" /></a>
  <a href="https://sonarcloud.io/dashboard?id=devops-thiago_ThrillhouseBot"><img src="https://sonarcloud.io/api/project_badges/measure?project=devops-thiago_ThrillhouseBot&metric=alert_status" alt="Quality Gate" /></a>
  <a href="https://securityscorecards.dev/viewer/?uri=github.com/devops-thiago/ThrillhouseBot"><img src="https://api.securityscorecards.dev/projects/github.com/devops-thiago/ThrillhouseBot/badge" alt="OpenSSF Scorecard" /></a>
  <a href="https://www.bestpractices.dev/projects/13330"><img src="https://www.bestpractices.dev/projects/13330/badge"></a>
  <a href="https://github.com/devops-thiago/ThrillhouseBot/releases"><img src="https://img.shields.io/github/v/release/devops-thiago/ThrillhouseBot" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/devops-thiago/ThrillhouseBot" alt="License" /></a>
</p>

ThrillhouseBot is a GitHub App that reviews pull requests. It is built with
Quarkus, compiled to a GraalVM native binary, and talks to any OpenAI-compatible
chat API, so it works with any language and any provider you choose.

See [how it compares](docs/COMPARISON.md) to CodeRabbit, PR-Agent and Copilot
code review.

**[📖 Documentation](https://devops-thiago.github.io/ThrillhouseBot/)**: setup
guide, configuration reference, architecture, comparison, and the hosted
[GitHub App installer](https://devops-thiago.github.io/ThrillhouseBot/install.html).

<p align="center">
  <img src="docs/assets/pr-approval.png" alt="ThrillhouseBot approving a clean pull request" width="800" />
</p>

<p align="center">
  <img src="docs/assets/dashboard-overview.png" alt="Dashboard overview: review counts, total cost, and top model" width="800" />
</p>

## Features

<!-- docs:features:start -->
- Reviews diffs for correctness, security, regressions, stale comments and code quality, and tags every finding `critical`, `high`, `medium` or `low`
- Reviews large pull requests whole: the diff is split into token-budgeted batches reviewed in parallel, and any file that does not fit is named
- Inline suggestions you can apply with one click
- A second pass re-checks each finding against the diff before it is posted
- Follow-up reviews track whether earlier findings were fixed, declined or are still open, and re-check a maintainer's decline against the code
- One summary comment per pull request, edited in place every round, with a risk breakdown, a changed-files walkthrough and every finding still open
- Comment commands (`/review`, `/summary`, `/describe`, `/changelog`, `/add-docs`, `/improve`, `/generate-tests`, `/resolve`, `/pause`, `/resume`, `/help`, and with learnings on `/learnings`, `/remember`, `/forget`) and conversational replies to `@thrillhousebot`
- Auto-review triggers: skip drafts, gate on labels, filter by base branch, and optionally space out reviews of one PR (`AUTO_REVIEW_MIN_INTERVAL`)
- Maintainer 👍/👎 reactions and "not useful" replies are recorded as [finding feedback](https://devops-thiago.github.io/ThrillhouseBot/feedback/)
- Optional review context, each off by default: the issues a PR links and their acceptance criteria, the CI checks that already failed on the head commit, patch coverage from your own coverage report, and review learnings (maintainer declines and `/remember` conventions from earlier reviews of the same repository)
- Optional deterministic scan of added lines for leaked credentials and risky Terraform, Kubernetes, CloudFormation and Dockerfile settings, with no model call and every matched secret redacted
- Optional per-call review dimension routing, which sends each call only the review rules its files need
- Optional outgoing notification when a review completes or fails: JSON, Slack or Discord, HMAC-signed, metadata only unless you opt in
- Per-repository instructions in `.github/thrillhousebot.md` (falling back to Copilot, Claude and Agents files) and per-repository ignore globs and path-scoped rules in `.github/thrillhousebot.yml`
- Optional reasoning-effort setting and per-model generation and budget caps
- Live dashboard (Next.js) with a WebSocket activity feed, cost charts and token tracking
- OpenTelemetry traces, token histograms, cost counters and latency metrics
<!-- docs:features:end -->

## Provider support

<!-- docs:providers:start -->
ThrillhouseBot works with any endpoint that implements the OpenAI
chat-completions API. Set `AI_BASE_URL` and `AI_MODEL` for your provider:

| Provider | `AI_BASE_URL` | Example `AI_MODEL` |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| Alibaba Cloud (Model Studio) | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Ollama (local) | `http://localhost:11434/v1` | `llama3.2` |

Ollama's cloud endpoint (`https://ollama.com/v1`) limits how many requests one
account has in flight and refuses the rest with `timed out waiting for a
concurrent request slot`. Reviews of different pull requests run in parallel and
a large pull request sends its batches at once, so set `AI_MAX_CONCURRENT_CALLS`
to the number of concurrent requests your plan allows. Calls past it wait for a
slot instead of being refused, and a refusal that still gets through is retried
after 30 seconds.

DeepSeek is the default only because it is inexpensive. Nothing in the bot is
tied to it.
<!-- docs:providers:end -->

## Commands

<!-- docs:commands:start -->
Comment one of these on a pull request. Each also has a mention form, such as
`@Thrillhousebot review`. The bot reacts with 👀 to acknowledge a command and
does the work in the background. A plain `@thrillhousebot` mention with no
command word gets a written answer instead.

| Command | What it does | Access |
|---|---|---|
| `/help` | List the commands | anyone |
| `/review` | Run (or re-run) a full review | write |
| `/summary` | Post the PR summary when the PR has none, for example after it was deleted. If one is already there, the bot replies that it did nothing. | write |
| `/describe` | Suggest a PR title and description, as a comment to copy in. The PR is never edited. | write |
| `/changelog` | Draft a CHANGELOG entry (Added, Changed, Fixed, Security…), as a comment to copy in | write |
| `/add-docs` | Suggest doc comments for the symbols the PR changed, as committable suggestions | write |
| `/improve` | Suggest improvements across the whole PR, as committable suggestions | write |
| `/generate-tests` | Propose unit tests for the changed code, one code block per test file | write |
| `/resolve` | Resolve the bot's open finding threads | write |
| `/pause` | Silence the bot on the PR | write |
| `/resume` | Re-enable the bot on a paused PR | write |
| `/learnings` | List what the bot remembers about this repository, with ids and source links | write |
| `/remember <text>` | Remember a convention for later reviews of this repository | write |
| `/forget <id>` | Retract one remembered learning | write |
| `@thrillhousebot resolved <path>:<line> — <title>` | Close an earlier finding that has no review thread (see **Clearing a finding with no thread** under Configuration) | write |
| `@thrillhousebot declined <path>:<line> — <title>` | Decline an earlier finding that has no review thread, with the reason on the following lines (see **Declining a finding with no thread** under Configuration) | write |

**Access.** Every slash command except `/help` needs write access to the
repository, or a login listed in
`THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS`, because the commands spend
the operator's AI budget. The `resolved` and `declined` directives always need
write access, judged by the comment's GitHub `author_association` (`OWNER`,
`MEMBER` or `COLLABORATOR`); the allowlist does not extend to them.

**Directives.** `@thrillhousebot resolved` and `@thrillhousebot declined` are
not slash commands. They have no `/` form and are applied by the next review,
not straight away. The bot replies at once to say what that review will
evaluate, and when the comment names no `path:line` it says that nothing will
be cleared. A trailing question mark (`@thrillhousebot resolved?`) makes it a
question, which changes nothing. Do not confuse `resolved` with `/resolve`,
which resolves review threads and does nothing to a finding that never opened
one.

**Learnings.** `/learnings`, `/remember` and `/forget` work only when the
deployment sets `REVIEW_LEARNINGS_ENABLED=true`; otherwise the bot replies that
learnings are off. `/learnings` lists up to 30 entries.

**Pause.** While a PR is paused, pushes are not reviewed and mentions get no
answer. `/review`, `/summary`, `/describe`, `/changelog`, `/add-docs`,
`/improve` and `/generate-tests` reply with a short paused notice and do
nothing else. `/help`, `/resolve`, `/resume` and the learnings commands keep
working.

**Batching.** `/describe`, `/changelog`, `/add-docs`, `/improve` and
`/generate-tests` read the whole change set, not the first
`REVIEW_MAX_DIFF_LINES` of it. The changed files are packed into batches that
each fit `REVIEW_MAX_INPUT_TOKENS`, with one model call per batch, and a run
never makes more than `REVIEW_MAX_AI_CALLS` calls. `/describe` and `/changelog`
merge the per-batch results with one extra call, which they spend only when the
PR needed more than one batch. `/add-docs`, `/improve` and `/generate-tests`
merge their results locally. Any file the budget could not cover is named in a
partial-coverage note. Nothing is ever committed.

**`/add-docs`** proposes doc comments for the public symbols the PR changed, in
each file's language and following the repository instructions. Each one is a
`suggestion` block on the symbol's declaration (spanning the whole signature
when it wraps), so applying it only inserts docs. When a multi-line declaration
cannot be pinned to one diff hunk, the bot posts the drafted docs as a note
instead. Turn it off with `REVIEW_ADD_DOCS_ENABLED=false`.

**`/improve`** proposes better code even when nothing is broken: clearer
naming, dead or duplicated code, simpler control flow, missing error handling,
avoidable work in loops, and gaps in the tests for the change. `/review` looks
for defects; `/improve` does not. Each improvement whose quoted code matches
the diff is posted inline as a `suggestion` block; the rest are listed as
copy-paste blocks in the run's summary comment. Turn it off with
`REVIEW_IMPROVE_ENABLED=false`.

**`/generate-tests`** proposes unit tests in the framework the project already
uses. A new test file has no diff line to anchor a suggestion to, so each test
file is posted as a code block headed by its path. Proposals from different
batches are merged by path. Turn it off with
`REVIEW_GENERATE_TESTS_ENABLED=false`.
<!-- docs:commands:end -->

## Quick start

### Prerequisites

- [Docker and Docker Compose](https://docs.docker.com/compose/install/)
- An API key for any [OpenAI-compatible provider](#provider-support)

### 1. Create the GitHub App

Follow [GitHub App setup](#github-app-setup) below. You get an App ID, a
private key, a webhook secret and an OAuth client ID and secret.

### 2. Clone and configure

```bash
git clone https://github.com/devops-thiago/ThrillhouseBot.git && cd ThrillhouseBot
cp .env.example .env
```

Fill in `.env`:

| Variable | Value |
|---|---|
| `GITHUB_APP_ID` | GitHub App settings → About |
| `GITHUB_PRIVATE_KEY` | The `.pem` from generating a private key, on one line with newlines as `\n`, unquoted |
| `GITHUB_WEBHOOK_SECRET` | The webhook secret you set |
| `GITHUB_CLIENT_ID` | App settings → Identifying and authorizing users |
| `GITHUB_CLIENT_SECRET` | App settings → Identifying and authorizing users |
| `AI_API_KEY` | Your AI provider's API key |

### 3. Start the bot

```bash
docker compose up -d
```

This starts the bot and a PostgreSQL database. The bot listens on
`http://localhost:8080`; put your reverse proxy in front of it. Set `DB_USER`
and `DB_PASSWORD` in `.env` before you expose the database, or `DATABASE_URL`
to use a managed PostgreSQL.

## GitHub App setup

Create the GitHub App before starting the bot; its credentials go in `.env`.

### Option A: manifest install (recommended)

1. Open the hosted installer at
   [devops-thiago.github.io/ThrillhouseBot/install.html](https://devops-thiago.github.io/ThrillhouseBot/install.html),
   enter the public hostname the bot will run on, and click
   **Create ThrillhouseBot GitHub App**. For local development with
   [Smee.io](https://smee.io/), enter your Smee channel URL: the webhook is then
   registered at the channel root, and the smee client forwards it to the bot's
   local `/api/webhook`.

   <details>
   <summary>Offline alternative: serve the installer locally</summary>

   Replace every `<your-host>` in `manifest.json` with your public hostname (no
   trailing slash) and serve the repository root:

   ```bash
   java -m jdk.httpserver -p 8081
   ```

   Then open [http://localhost:8081/install.html](http://localhost:8081/install.html) and click
   **Create ThrillhouseBot GitHub App**.

   </details>

2. On the confirmation page, note the **App ID**, generate a **private key** and
   create a **webhook secret**. Copy the **Client ID** and **Client secret** from
   *Identifying and authorizing users* (used for dashboard login).
3. Install the app on your account or organization and copy the values into
   `.env`. You can also generate `.env` from the manifest conversion response:

   ```bash
   gh api --method POST /app-manifests/<code>/conversions \
     | java scripts/GenEnv.java --host <your-host>
   ```

> Once the bot is running, `install.html` on its own URL
> (`https://<your-host>/install.html`, or `http://localhost:8080/install.html`)
> detects the URL and builds the manifest for you.

### Option B: manual registration

| Setting | Value |
|---|---|
| Webhook URL | `https://<your-host>/api/webhook` |
| Webhook secret | Random string |
| Repository permissions | Pull requests: R/W, Checks: R/W, Contents: Read, Issues: R/W, Actions: Read, Commit statuses: Read |
| Subscribe to events | Pull request, Issue comment, Pull request review comment, Check suite, Status |
| Identifying and authorizing users | Enabled (for dashboard login) |
| Callback URL | `https://<your-host>/api/auth/callback` |

## Configuration

<!-- docs:configuration:start -->
Configuration comes from environment variables (see `.env.example`). The short
names (`AI_*`, `REVIEW_*`, `WEBHOOK_*`, …) are explicit aliases. Any other
`thrillhousebot.*` key can be set through the standard Quarkus mapping:
uppercase, with `.` and `-` replaced by `_` (`thrillhousebot.review.ignored-files`
→ `THRILLHOUSEBOT_REVIEW_IGNORED_FILES`).

Every optional feature added in 0.7.0 is off by default. Each one validates its
bounds at boot while it is on.

#### AI provider

| Variable | Purpose | Default |
|---|---|---|
| `AI_API_KEY` | API key for the AI provider | _(required)_ |
| `AI_BASE_URL` | OpenAI-compatible base URL | `https://api.deepseek.com/v1` |
| `AI_MODEL` | Chat model name | `deepseek-chat` |
| `AI_PROVIDER` | Provider label for telemetry (`gen_ai.provider.name`); derived from `AI_BASE_URL` when unset | _(derived)_ |
| `AI_TIMEOUT` | Per-request timeout | `300s` |
| `AI_MAX_CONCURRENT_CALLS` | Most model calls in flight at once across the process (review batches, summaries, the verifier, replies and commands). A call past it waits for a slot, up to `THRILLHOUSEBOT_REVIEW_AI_TIMEOUT_SECONDS`, and a wait over 5 seconds is logged at INFO. A provider's rate-limit or concurrent-slot refusal is retried after 30 seconds either way. `0` means no limit | `0` |
| `AI_REASONING_ENABLED` | Send a reasoning-effort hint. When `false`, no reasoning parameter is sent and the provider default applies | `false` |
| `AI_REASONING_EFFORT` | Effort for the review and the on-demand commands: `none`, `low`, `medium`, `high`, `xhigh` or `max`. Reasoning tokens are billed as output. See **Reading a cut response** under [Per-model AI settings](#per-model-ai-settings) | `low` |
| `AI_REASONING_EFFORT_CONCISE` | Effort for the calls on the `concise` model (summary, verifier, replies), which do not follow `AI_REASONING_EFFORT`. Same values. Unset means `low`, or `AI_REASONING_EFFORT` when that is lower | `low` |

#### GitHub and webhooks

| Variable | Purpose | Default |
|---|---|---|
| `GITHUB_APP_ID` | GitHub App ID | _(required)_ |
| `GITHUB_PRIVATE_KEY` | GitHub App private key (PEM) | _(required)_ |
| `GITHUB_WEBHOOK_SECRET` | Webhook HMAC secret | _(required)_ |
| `GITHUB_BOT_LOGINS` | Comma-separated bot logins the bot ignores so it never answers itself. Override when the app slug differs (`<app-slug>[bot]`) | `thrillhousebot[bot],thrillhouse-bot[bot]` |
| `GITHUB_WRITE_MIN_INTERVAL` | Minimum spacing between two content-creating GitHub calls (comments, review comments, replies, reviews), shared by the whole process. GitHub's guidance is at most one per second; it answers faster writes with `403`, `429` or a `422` "was submitted too quickly". When GitHub throttles a write anyway, every queued write waits with it. `0` disables pacing | `1s` |
| `GITHUB_WRITE_MAX_WAIT` | Longest one caller waits for its write slot before it goes out unpaced and the backoff handles any refusal | `90s` |
| `GITHUB_WRITE_RETRY_BUDGET` | Longest one review may spend waiting on GitHub's rate limit across all its writes. Once spent, later throttled writes are not retried, and the review body names the findings they carried and asks for a re-run. `0` disables the limit | `5m` |
| `WEBHOOK_DEDUP_TTL` | How long a webhook delivery id is remembered, to ignore GitHub redeliveries | `24h` |
| `THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS` | Comma-separated logins allowed to run slash commands without write access. Does not apply to the `resolved` and `declined` directives | _(empty)_ |
| `MANUAL_TRIGGER_AUTH_TIMEOUT` | Time limit on the write-access check for a command. Slower answers from GitHub deny the command | `5s` |
| `ACK_REACTION_TIMEOUT` | Time limit on posting the 👀 reaction; the reaction may land late | `3s` |

#### Automatic review triggers

| Variable | Purpose | Default |
|---|---|---|
| `AUTO_REVIEW_MIN_INTERVAL` | Minimum time between automatic reviews of one PR. Pushes inside the window are skipped, even on a new head (in memory, per replica). `/review` always runs. `0` reviews every push | `0` |
| `WEBHOOK_SKIP_DRAFTS` | Do not auto-review drafts; the PR is reviewed once marked ready | `false` |
| `WEBHOOK_REQUIRED_LABELS` | Comma-separated labels; auto-review only PRs carrying at least one (case-insensitive) | _(empty, no gate)_ |
| `WEBHOOK_EXCLUDED_LABELS` | Comma-separated labels; skip PRs carrying any. Wins over required labels | _(empty)_ |
| `WEBHOOK_BASE_BRANCHES` | Comma-separated globs; auto-review only PRs whose base branch matches one (`main,release/*`). `*` does not cross `/`; use `**` for that | _(empty, all branches)_ |
| `WEBHOOK_IGNORED_BASE_BRANCHES` | Comma-separated globs; skip PRs whose base branch matches one (`dependabot/**`). Wins over the allowlist | _(empty)_ |

#### Review behaviour

| Variable | Purpose | Default |
|---|---|---|
| `REVIEW_CI_GATING` | How CI affects approval: `strict` holds approval while required CI is pending, failing or unreadable; `warn` approves and notes the CI state; `off` ignores CI. See [CI gating](#ci-gating) | `strict` |
| `REVIEW_BLOCKING_STRICTNESS` | Which findings request changes: `balanced`, `strict` or `lenient`. See [Blocking strictness](#blocking-strictness) | `balanced` |
| `REVIEW_VERIFIER_ENABLED` | Run a second, skeptical pass that re-checks each finding against the diff and drops or downgrades what it cannot confirm. See [AI call budget](#ai-call-budget) | `true` |
| `REVIEW_DIMENSION_ROUTING_ENABLED` | Give each review call only the review-dimension blocks its files can use, instead of all ten. See [Dimension routing](#dimension-routing) | `false` |
| `REVIEW_DECLINE_RECHECK_ENABLED` | Check a maintainer's decline against the reviewed code before recording the finding justified. See [Re-checking declines](#re-checking-declines). `false` makes any reply final | `true` |
| `REVIEW_CONVERSATIONAL_REPLIES_ENABLED` | Answer `@thrillhousebot` mentions in PR threads and finding replies | `true` |
| `REVIEW_ADD_DOCS_ENABLED` | Allow `/add-docs` | `true` |
| `REVIEW_IMPROVE_ENABLED` | Allow `/improve` | `true` |
| `REVIEW_GENERATE_TESTS_ENABLED` | Allow `/generate-tests` | `true` |
| `REVIEW_DIAGRAM_ENABLED` | Add a Mermaid control-flow diagram to the summary | `false` |
| `REVIEW_FOLLOW_UP_SUMMARY_ENABLED` | On follow-up rounds, post a short delta comment with new, resolved and still-open counts. A round where nothing moved posts nothing | `false` |
| `REVIEW_LARGE_PR_NUDGE_ENABLED` | On a large PR whose review opened no inline finding and left no earlier finding open, add a note to the summary pointing at `/review` and `/improve`. No extra AI call; the verdict is unchanged | `false` |
| `REVIEW_LARGE_PR_NUDGE_MIN_FILES` | Changed files (ignored files included) at which a PR counts as large. `0` turns this test off | `20` |
| `REVIEW_LARGE_PR_NUDGE_MIN_CHANGED_LINES` | Added plus deleted lines at which a PR counts as large; either test is enough. `0` turns this test off | `1000` |
| `THRILLHOUSEBOT_REVIEW_MAX_REVIEW_COMMENTS` | Most inline comments per review; the rest are listed in the summary | `50` |
| `THRILLHOUSEBOT_REVIEW_INSTRUCTIONS_FILE` | Repository path of the instructions file | `.github/thrillhousebot.md` |
| `THRILLHOUSEBOT_REVIEW_IGNORED_FILES` | Comma-separated gitignore-style globs never reviewed. A pattern without `/` matches at any depth (`*.lock`, `vendor`); one with `/` is anchored at the root (`docs/generated/**`); a trailing `/` means the directory's tree; `*` does not cross `/`. Write a `{a,b}` alternation as separate patterns. Setting it replaces the default list | `pom.xml`, lockfiles, generated and minified code, sourcemaps, and build and vendor trees (the shipped value of `thrillhousebot.review.ignored-files` in `application.properties`) |
| `THRILLHOUSEBOT_REVIEW_REPO_CONFIG_ENABLED` | Read `.github/thrillhousebot.yml` from each repository (extra ignore globs, path-scoped rules, coverage artifact). See [Repository configuration](#repository-configuration) | `true` |
| `REVIEW_LABELS_ENABLED` | Suggest PR labels. See [PR labels](#pr-labels) | `false` |
| `REVIEW_LABELS_APPLY` | Add the labels to the PR instead of suggesting them in a comment | `false` |
| `REVIEW_LABELS_ALLOW_CREATE` | Allow creating a suggested label that does not exist yet | `false` |
| `REVIEW_LABELS_MAX` | Most labels applied or suggested per PR | `3` |

#### Token and call budget

| Variable | Purpose | Default |
|---|---|---|
| `REVIEW_MAX_INPUT_TOKENS` | Input-token budget per call for reviews and the batched commands; larger PRs are split into batches. Capped by the model's `max-input-tokens` (see [Per-model AI settings](#per-model-ai-settings)). `0` disables budgeting | `48000` |
| `REVIEW_OUTPUT_BUFFER_TOKENS` | Tokens reserved from the input budget for the response | `8192` |
| `REVIEW_TOKEN_SAFETY_MARGIN` | Fraction of the input budget actually used, to absorb token-estimate error | `0.9` |
| `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` | Response cap (`max_tokens`) for the calls on the `concise` model: the summary call, the verifier and replies. A summary cut at this cap is salvaged or replaced by a counts-only summary, and the findings are kept. Set it empty to send no cap | `8192` |
| `REVIEW_MAX_AI_CALLS` | Most AI calls per review (batch calls plus the summary call) and per command run (batch calls plus, for `/describe` and `/changelog`, the merge call). At `1`, a review that makes its review call has none left for the summary and posts a counts-only summary that says so. Verifier calls are not counted | `6` |
| `REVIEW_MAX_TOKENS_PER_REVIEW` | Most tokens one review may use across all its calls, as the provider reports them, retries and the summary call included. Once reached, no further call is made: the remaining batches are named as not reviewed, the summary degrades to counts only, and the findings already paid for are kept. Reviews only. `0` disables it | `0` |
| `REVIEW_MAX_DIFF_LINES` | Line cap for single-call diff renders (replies, base comparison, reviews with budgeting off). Token-budgeted reviews and the batched commands ignore it. `0` disables it | `5000` |
| `THRILLHOUSEBOT_REVIEW_MAX_AI_RETRIES` | Attempts per failed AI call. At most two may end in a timeout | `5` |
| `THRILLHOUSEBOT_REVIEW_AI_RETRY_BASE_DELAY_MS` | Base delay of the exponential retry backoff, in milliseconds | `2000` |
| `THRILLHOUSEBOT_REVIEW_AI_TIMEOUT_SECONDS` | Client-side wait per streaming attempt. Keep it at or above `AI_TIMEOUT` so a timed-out attempt does not leave a stream open at the provider. A second timeout fails the call | `300` |

#### Optional review context

All off by default. The CI, linked-issue and learnings sections are fenced as
untrusted data in the prompt and counted in the per-call token budget.

| Variable | Purpose | Default |
|---|---|---|
| `REVIEW_PATCH_COVERAGE_ENABLED` | Tell the review which added lines the repository's own coverage report never ran. Needs `coverage-artifact` in `.github/thrillhousebot.yml` (see [Repository configuration](#repository-configuration)) | `false` |
| `REVIEW_CI_CONTEXT_ENABLED` | Include the checks that had already failed on the head commit. See [CI-failure context](#ci-failure-context) | `false` |
| `REVIEW_CI_CONTEXT_INCLUDE_LOGS` | Also include the tail of up to two failing GitHub Actions job logs | `false` |
| `REVIEW_CI_CONTEXT_MAX_CHARS` | Character cap on the CI section (500–20000) | `4000` |
| `REVIEW_TICKET_CONTEXT_ENABLED` | Include the issues the PR links and list unaddressed acceptance criteria in the summary. See [Linked-issue context](#linked-issue-context) | `false` |
| `REVIEW_TICKET_CONTEXT_PROVIDER` | Where issues are read from. Only `github` exists | `github` |
| `REVIEW_TICKET_CONTEXT_MAX_ISSUES` | Linked issues read per review (1–5) | `3` |
| `REVIEW_TICKET_CONTEXT_MAX_CHARS` | Character cap on the linked-issue section (1000–20000) | `6000` |
| `REVIEW_TICKET_CONTEXT_FROM_BRANCH` | Also take an issue number from the head branch name (`issue-57`, `fix/57-short-name`) when nothing else links one | `false` |
| `REVIEW_LEARNINGS_ENABLED` | Remember maintainer declines and `/remember` conventions per repository and show them to later reviews. See [Review learnings](#review-learnings). Startup fails if this is on while `REVIEW_DECLINE_RECHECK_ENABLED` is off | `false` |
| `REVIEW_LEARNINGS_MAX_PER_REPO` | Active learnings one repository may hold (1–1000). At the cap a new learning is refused, not evicted; two captures racing at the cap can each add one | `100` |
| `REVIEW_LEARNINGS_PROMPT_MAX_ITEMS` | Most learnings in one review prompt (1–50) | `10` |
| `REVIEW_LEARNINGS_PROMPT_MAX_CHARS` | Character cap on the learnings section (500–20000) | `3000` |

#### Security scan

| Variable | Purpose | Default |
|---|---|---|
| `REVIEW_SECRET_SCAN_ENABLED` | Scan added lines for leaked credentials. See [Security scan](#security-scan) | `false` |
| `REVIEW_IAC_SCAN_ENABLED` | Scan added lines for risky infrastructure-as-code settings | `false` |
| `REVIEW_SECRET_SCAN_ENTROPY_THRESHOLD` | Minimum Shannon entropy (bits per character) for the generic credential-assignment rule; the known token formats ignore it. Must be in (0, 8] | `3.5` |
| `REVIEW_SECURITY_SCAN_SKIPPED_FILES` | Comma-separated globs the scan skips on top of the review's ignore list, for fixtures and snapshots | `**/fixtures/**,**/__fixtures__/**,**/testdata/**,**/test-data/**,**/__snapshots__/**,**/*.snap` |

#### Outgoing notifications

See [Outgoing notifications](#outgoing-notifications).

| Variable | Purpose | Default |
|---|---|---|
| `NOTIFICATIONS_WEBHOOK_URL` | Receiver of review-outcome notifications; unset turns them off. Must be `https://`. Treated as a secret and logged only as scheme, host and port | _(unset, off)_ |
| `NOTIFICATIONS_WEBHOOK_FORMAT` | `json`, `slack` or `discord` | `json` |
| `NOTIFICATIONS_WEBHOOK_SECRET` | HMAC-SHA256 key; when set, each request carries `X-Thrillhousebot-Signature-256: sha256=<hex>` | _(unset, unsigned)_ |
| `NOTIFICATIONS_WEBHOOK_EVENTS` | Comma-separated outcomes to send: `completed`, `failed` | `completed,failed` |
| `NOTIFICATIONS_WEBHOOK_INCLUDE_CONTENT` | Also send the PR title and each finding's severity, file, line and title | `false` |
| `NOTIFICATIONS_WEBHOOK_ALLOW_HTTP` | Accept a plain `http://` URL, for local testing | `false` |
| `NOTIFICATIONS_WEBHOOK_TIMEOUT` | Per-attempt timeout (at most `60s`) | `10s` |
| `NOTIFICATIONS_WEBHOOK_MAX_ATTEMPTS` | Attempts per notification (1–5). A timeout, connection failure, `429` or `5xx` is retried after 1, 2, 4 and 8 seconds, then dropped with a WARN | `3` |

#### Dashboard, database and runtime

| Variable | Purpose | Default |
|---|---|---|
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | OAuth credentials for dashboard login. Leave both unset to disable login | _(required for the dashboard)_ |
| `DASHBOARD_URL` | Public dashboard URL, used for the OAuth callback and session links | `http://localhost:8080` |
| `DATASOURCE_DB_KIND` | `h2` or `postgresql`. Quarkus fixes this at build time, so released images are PostgreSQL builds and ignore `h2` | `h2` (dev), `postgresql` (`%prod`) |
| `DATABASE_URL` | JDBC URL of the production database | `jdbc:postgresql://localhost:5432/thrillhouse` (`db:5432` in Docker Compose) |
| `DB_USER` / `DB_PASSWORD` | Database credentials, read by Docker Compose for both the bot and the PostgreSQL container | `thrillhouse` / `thrillhouse` |
| `HTTP_CONNECT_TIMEOUT` | Outbound HTTP connect timeout (GitHub API, OAuth) | `10s` |
| `HTTP_REQUEST_TIMEOUT` | Outbound HTTP request timeout (GitHub API, OAuth) | `10s` |
| `WEBSOCKET_KEEPALIVE_MS` | Dashboard WebSocket keepalive in ms; `0` or less disables it and stale replay-buffer eviction | `25000` |

### AI call budget

A review makes a review call, which returns findings and the status of earlier
findings, and then a summary call, which writes the PR summary (purpose,
description gaps, file walkthrough, labels, diagram) from the verified findings
and the list of changed files. The summary call does not carry the diff, so it
is small. When the review call reports findings, a verification call re-sends
the diff with the candidate findings, so a review with findings costs roughly
twice the tokens of the diff. A large PR makes one review call and one
verification call per batch, then the one summary call.

At `REVIEW_MAX_AI_CALLS=1` the summary call is skipped after a review call. A
review whose every file exceeded the budget makes no review call and still gets
its summary.

`REVIEW_VERIFIER_ENABLED=false` skips the verifier: cheaper, with more false
positives. A deterministic hedging guard still runs. The verifier fails open: if
it errors, returns an empty or cut response, or is skipped at
`REVIEW_MAX_TOKENS_PER_REVIEW`, the findings it did not rule on are still
posted, each with its confidence capped at medium and a line saying it was not
verified. Under `balanced` strictness such a finding cannot request changes on
its own. The summary states how many findings went unverified.

### Dimension routing

Every review call carries a system prompt made of a core plus ten review
dimension blocks, about 11,000 tokens. With
`REVIEW_DIMENSION_ROUTING_ENABLED=true`, each call carries only the blocks its
files can use. The router reads each file's kind (documentation, configuration
or infrastructure, script, source, test) and probes its patch for API or list
code and for mocks. Correctness, security and regressions are always included,
an unrecognized file type brings in every block, and a batch of mixed files
gets the union. The verifier's dimension rules follow the files its candidates
are in. Each call's choice is logged at INFO with the file that brought each
block in.

In measurements, a documentation-only call drops about 27% of the prompt, a
source-only call 19–22% and a configuration-only call 17%; a call mixing source,
tests, configuration and documentation carries every block. The budget planner
sizes batches from the PR's routed prompt, so a PR with no configuration, test
or API code fits more diff per call. Every routed prompt opens with the same
~7,000-token core, so a provider's prompt cache still matches across batches and
reviews. With routing off, the prompts are unchanged from earlier releases.

### Per-model AI settings

Model-specific settings live under `thrillhousebot.ai.models.<model>.*`, keyed by
the `AI_MODEL` value like the pricing map. Only the active model's entry is
read, so you can keep entries for several models and switch `AI_MODEL` freely:

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
# Set true only when the provider bills the response outside the context
# window (1M in with 384K out on top, rather than 384K carved out of the 1M).
# It switches off both the reservation and the context-tokens ceiling.
thrillhousebot.ai.models.some-separate-budget-model.separate-output-budget=true
```

- **`max-input-tokens` is a cap, not the budget.** `REVIEW_MAX_INPUT_TOKENS`
  stays the spend setting; the per-model value keeps it inside the model's real
  window. To go beyond 128k on a large-context model, raise both. Startup logs a
  warning when the cap lowers your budget.
- **`context-tokens` is the window.** On a shared window the provider charges
  the prompt and the completion to one context, so boot fails when
  `max-input-tokens + max-output-tokens` exceeds it, or when the effective
  budget plus the largest response cap in play (`max-output-tokens` or
  `REVIEW_CONCISE_MAX_OUTPUT_TOKENS`) does. Without it, an impossible pair shows
  up only when the provider rejects every call for length. It is optional.
- **`max-output-tokens` vs `output-buffer-tokens`.** `max-output-tokens` is the
  response cap sent to the provider; `output-buffer-tokens` only reserves room in
  the input budget. On a shared window the buffer must be at least the output
  cap, or boot fails, so set both when you cap output.
- **`separate-output-budget`** (default `false`) says whether the response
  allowance sits on top of the input window. Left off, the budgeter reserves
  `output-buffer-tokens` from the input budget and the completion counts against
  `context-tokens`. Set it `true` only for a model whose provider documents a
  separate response allowance: the reservation and the ceiling are then
  dropped. Getting it wrong is costly either way. A 384000-token cap on a 1M
  window wrongly marked shared costs about 40% of every call's diff budget, and a
  shared model wrongly marked separate loses every guard and has its calls
  rejected, which is how `deepseek-v4-flash` shipped its wrong pair. Check the
  provider's documented window first.
- **The `concise` model.** The summary call every review ends with, the
  verifier and replies run on a second binding, the `concise` named model
  (`quarkus.langchain4j.openai.concise.*`). It uses the same provider,
  credentials and model through the same `AI_*` variables and the active model's
  temperature settings, but has its own response cap,
  `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` (default `8192`), and its own reasoning
  effort, `AI_REASONING_EFFORT_CONCISE` (default `low`). The review call and the
  command generators, whose output grows with the diff, stay on the default model
  and its `max-output-tokens`.
- **Reasoning effort is per lane.** Reasoning tokens count against the response
  cap, so a high effort on the `concise` model can use the whole allowance and
  leave no answer; the verifier then keeps its findings unverified. Raising
  `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` only shifts the odds. Keep
  `AI_REASONING_EFFORT_CONCISE` low and spend effort on `AI_REASONING_EFFORT`.
- **Reading a cut response.** When a streamed review or summary call spends its
  whole cap reasoning and returns no answer, it is repeated once with reasoning
  off, and the summary's scope note says so. If the repeat writes its
  deliberation into the response instead, it is stopped once 8,000 characters
  arrive with no JSON object opened, so the cap is not billed twice. On any
  `finish_reason=length` stop, the log line, the failed check run and the PR
  notice give the `max_tokens` the request carried, the setting that supplied it
  (`max-output-tokens`, or `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` on the concise
  lane), and the completion and prompt tokens billed. They advise raising the
  setting only when the billed completion reached it; a stop short of the cap is
  a provider-side limit, and lowering the reasoning effort is the other lever.
  When the provider reports no usage, the notice says so.
- **Quote model names that contain `.` or `/`**
  (`thrillhousebot.ai.models."gpt-5.5".…`), as in the pricing map. From the
  environment, hyphen-only names use underscores
  (`THRILLHOUSEBOT_AI_MODELS_DEEPSEEK_V4_PRO_MAX_INPUT_TOKENS=1000000`) and dotted
  names use the quoted form
  (`THRILLHOUSEBOT_AI_MODELS__GPT_5_5__MAX_INPUT_TOKENS=256000`).
  `application.properties` ships empty stubs for known models so the
  [Quarkus env mapping](https://quarkus.io/guides/config-reference#environment-variables)
  can resolve hyphenated keys. For another model, add an empty
  `thrillhousebot.ai.models."<model>".max-input-tokens=` line (external
  `application.properties` or `-D`) next to the environment variable.
- **`seed`** asks for repeatable sampling on providers that support it; others
  ignore it. For steadier reviews, lower `temperature` first.
- **`top_k`** is not part of the OpenAI-compatible API, so it cannot be set.
- **Validation.** At boot, `temperature` must be in `[0, 2]`, `top-p` in
  `(0, 1]`, penalties in `[-2, 2]` and token counts positive. A bad value in any
  entry, even an inactive model's, fails startup with the key named.

### Blocking strictness

`REVIEW_BLOCKING_STRICTNESS` decides which findings turn the review into
`REQUEST_CHANGES` and fail the check run. Other findings are posted as comments
with a neutral check.

| Mode | Blocks merge when |
|---|---|
| `balanced` (default) | CRITICAL or HIGH risk at HIGH confidence |
| `strict` | CRITICAL or HIGH risk at any confidence |
| `lenient` | CRITICAL risk at HIGH confidence |

For security-sensitive repositories, use `strict`, so a CRITICAL or HIGH finding
cannot pass as a comment because its confidence was lowered. Under `strict`, the
verifier's confidence demotions and the medium cap on unverified findings no
longer prevent a block; only a lower risk or a dropped finding does. Stay on
`balanced` if you want those demotions to keep speculative findings
non-blocking.

Strictness affects only the verdict. Where a finding is posted is separate: a
LOW-confidence finding at MEDIUM or LOW risk goes to the summary's
**Things to double-check** list instead of an inline thread, while CRITICAL and
HIGH findings stay inline at any confidence.

### CI gating

With `REVIEW_CI_GATING=strict`, a review that finds nothing while a required
check is pending, failing or unreadable ends as `COMMENT` with a neutral check
run instead of `APPROVE`. CI is read while the model call runs and, if that
reading would hold approval, again after it, so a check that finished in the
meantime counts. A verdict with findings is not held, since CI would not change
it.

The hold is not final. The bot subscribes to `check_suite` and `status` events,
and when CI reports on the held head it re-reads the CI gate and nothing else.
If the required checks are green, it posts the `APPROVE` the review earned and
concludes its check run `success`, with no model call. If a check failed, the
hold stays and the check run says so; a re-run that turns green lifts it. A push
replaces the hold, because the new head gets its own review. Held verdicts are
kept in memory, so after a restart a held PR needs a manual `/review`.

Apps registered before these events were in the manifest must subscribe by hand:
**Settings → Developer settings → GitHub Apps → your app → Permissions & events
→ Subscribe to events** (under the organization's settings for an
organization-owned app), tick **Check suite** and **Status**, and save. The
existing `Checks: Read & write` and `Commit statuses: Read` permissions cover
both.

### Summary comment

Each PR has one summary comment. The first round posts it; every later round
finds it by its `<!-- thrillhousebot:summary -->` marker (or, for a summary
posted before 0.7.0, by its heading) and replaces its body, so it always
describes the current head. A round that renders the same body makes no write.
If the summary was deleted, the round posts a new one; if an edit fails, it
posts a new one and logs a WARN. GitHub keeps the edit history. Inline
findings, review bodies and the optional delta comment stay per round.

The risk counts, Key Findings and **Things to double-check** cover the round's
new findings and every earlier finding still open, each listed once and marked
"open since an earlier review". Key Findings ranks both together by severity. A
finding leaves the summary once it is resolved, declined, cleared with
`@thrillhousebot resolved`, or its code leaves the diff. A later finding replaces
an open double-check item only when it is the same defect, so a finding about one
config key does not replace an item about the key on the next row. The verdict
is computed over the same open set, so an earlier CRITICAL still open keeps a
follow-up round at `REQUEST_CHANGES` under the configured strictness. When a
follow-up opens nothing new while earlier findings are open, its review body
leads with "No new issues in this revision, but N previous finding(s) remain
unresolved".

The **Description vs. Implementation** section appears only when the PR
description and the change disagree. A summary with model prose ("What this PR
does", per-file summaries) and no such section means the check ran and found
nothing; a counts-only summary means it did not run, and says why. A gap stays
listed on later rounds until the summary call says what resolved it, the same
gap is reported again, or the PR stops linking the issue the gap names. A
follow-up on the head the previous round reviewed whose summary call returns no
overview keeps that round's overview and file summaries.

### Re-checking declines

When a maintainer replies to a finding to decline it, the next review records
the finding **justified**. A decline is a claim, though, and a correct finding
can be closed by a wrong rebuttal that names the very mechanism making the bug
real ("it only runs after the webhook is acked, so there's no race", on an
executor that starts a thread per event).

With `REVIEW_DECLINE_RECHECK_ENABLED=true` (the default), the bot traces the
stated reason against the code the review saw. When the reviewed diff plainly
contradicts it, the finding stays open for one more round with a note quoting
the claim and the contradicting line. The check is conservative:

- The maintainer is trusted by default. A reason about style, intent, accepted
  risk or priority, or one whose supporting code is not in the diff, is
  respected.
- It pushes back once. The re-check runs only while the thread has a single
  maintainer reply; a second reply always ends it.
- A re-opened finding is not posted again. It stays in **Previous Findings
  Status** and counts toward the verdict at its own severity.

A follow-up round's model reports on the findings of the newest round that
raised any. A decline on a finding from an older round is weighed by the
approval backstop under the same one-push-back rule: a lone reply the reviewed
diff contradicts keeps the finding open, and so does a lone "this cannot run
concurrently" reply to a concurrency finding, because what makes a path
concurrent (a server that handles each request on its own thread, an executor
in another file) is rarely in the diff. Any other reply, or a second one,
stands, and the finding is counted justified.

Set it to `false` to make a maintainer's reply final.

### Clearing a finding with no thread

A finding listed under **Things to double-check** has no review thread to reply
on, yet follow-up reviews keep it unresolved and it holds approval (`APPROVE`
becomes `COMMENT`). To close one, comment on the PR conversation:

```
@thrillhousebot resolved src/main/java/com/example/Widget.java:42 — Missing null check
```

Closing a finding by mistake is worse than leaving it open, so the match is
strict, and an ambiguous comment leaves the finding held:

- **Write `@thrillhousebot resolved` as plain text.** `resolve` is a different
  command, and a mention alone does nothing. A question mark straight after the
  directive (`resolved?`) makes it a question; one later in the comment is fine.
  Directive words in backticks, in a fenced block or on a `>` quoted line are
  read as documentation and clear nothing.
- **Name the finding by its `path:line` and its title** (or its description when
  it has no title). Each **Things to double-check** row prints both, title first
  and then the locator, so copying the row is the reliable way. Naming only one
  clears nothing. The path is written from the repository root and matched
  whole, so `Widget.java:42` never closes `Widget.java:4`. The locator and title
  may be in backticks.
- **Hold write access.** The comment's `author_association` must be `OWNER`,
  `MEMBER` or `COLLABORATOR`. Comments from fork authors, drive-by commenters
  and the bot itself are ignored.
- **Quoted text never counts.** Blockquotes and fenced code are dropped before
  matching, so a *Quote reply* of the summary names nothing.

One comment may name several findings; each is matched on its own. A finding
with neither title nor description cannot be named, so fix it or reply on its
thread if it has one. The next review records a named finding **resolved**.

The bot answers the directive when it sees it, but it has no findings loaded
then, so it says what the next review will evaluate. The exception is a
directive with no `path:line` at all, which clears nothing; the reply says so and
shows the format. A locator that is present but wrong gets the general reply,
and the review reports the finding still unresolved.

**Conversation read limit.** A review reads at most 1000 PR conversation
comments (10 pages of 100). GitHub returns them oldest first, so on a longer PR
the newest comments are the ones left unread, and a directive among them does
nothing that round. The bot logs a warning when it hits the limit; push a commit
to re-review, or reply on the finding's thread if it has one.

### Declining a finding with no thread

Declining says "this is fine, and here is why". On a review thread it is the
maintainer's reply. For a finding with no thread, write it on the PR
conversation: the directive line names the finding and the lines below it give
the reason. The directive may wrap once, before or after `declined`; the line
carrying the `path:line` and title still belongs to the directive.

```
@thrillhousebot declined src/main/java/com/example/Widget.java:42 — Missing null check

The value is validated two frames up, in `RequestGuard`, and this helper is
private to that path.
```

The word is `declined`, not `decline`. All the rules for `resolved` apply
unchanged: plain-text directive, both locator and title, write access, quoted
blocks dropped, `declined?` declines nothing, an ambiguous naming holds the
finding, and the conversation read limit applies.

The next review records the named finding **justified**, unless the reviewed code
plainly contradicts the reason; then the finding stays open for one more round
with the same note a thread re-check writes. Fenced code, blockquotes and inline
code inside the reason are quoted material, not the maintainer's claim, and the
directive line is never read as the reason. With several directives in one
comment, each reason runs to the next directive. A directive with no reason is
still a decline and is recorded as is.

A second `declined` comment naming the same finding always wins and is recorded
justified with no further check, as a second thread reply is.
`REVIEW_DECLINE_RECHECK_ENABLED=false` makes the first comment final. The bot
acknowledges the directive the same way it acknowledges `resolved`.

### Security scan

`REVIEW_SECRET_SCAN_ENABLED` and `REVIEW_IAC_SCAN_ENABLED` turn on a scan of the
lines a pull request **adds**, for leaked credentials and risky
infrastructure-as-code settings. It is a fixed list of regular expressions: no
model call, no extra dependency, and the same input always gives the same
findings. Both halves are off by default, because each adds findings that can
request changes. General linter integration is tracked separately in
[#34](https://github.com/devops-thiago/ThrillhouseBot/issues/34).

Secret rules (high confidence):

| Rule | Matches | Risk |
|---|---|---|
| AWS access key ID | `AKIA`/`ASIA`/`ABIA`/`ACCA` + 16 characters | critical |
| GitHub token | `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_` tokens and `github_pat_` fine-grained tokens | critical |
| Slack token | `xoxb-`, `xoxp-`, `xoxa-`, `xoxo-`, `xoxs-`, `xoxr-` tokens | critical |
| Google API key | `AIza` + 35 characters | critical |
| Stripe live key | `sk_live_` / `rk_live_` keys (test keys are ignored) | critical |
| Private key | a PEM `BEGIN … PRIVATE KEY` header followed by a key body (a header alone is code that parses keys) | critical |
| JSON Web Token | three base64url segments whose first two start `eyJ` | high |
| Credential assignment | a quoted literal assigned to a key named like `password`, `secret`, `token`, `api_key`, `access_key`, `private_key` or `client_secret`, with `=`, `:`, `:=` or `=>`, including typed and array declarations (C `char API_TOKEN[] =`, Rust `const API_TOKEN: &str =`, Zig `const api_token: []const u8 =`, TypeScript, Kotlin, Scala, Swift and Python `apiToken: String =`, Go `var apiToken string =`). The literal must have at least 8 characters, a non-letter, two character classes and entropy at or above `REVIEW_SECRET_SCAN_ENTROPY_THRESHOLD`, and must not be a URL, a path or an identifier such as `DB_PASSWORD` | high |

IaC rules (high confidence unless noted):

| Rule | Files | Matches | Risk |
|---|---|---|---|
| Admin port open to the internet | `.tf`, `.yaml`/`.yml`, `.json` | an ingress rule admitting `0.0.0.0/0` or `::/0` to port 22, 23, 3389, 5985 or 5986, to a range containing one, or to every protocol | high |
| S3 bucket made public | `.tf`, `.yaml`/`.yml`, `.json` | a `public-read`/`public-read-write` ACL (`AccessControl: PublicRead…`), or a Block Public Access setting set to `false` | high |
| IAM wildcard | `.tf`, `.yaml`/`.yml`, `.json` | `Action: "*"` with `Resource: "*"` in the same statement and no `Effect: Deny` | high |
| Privileged container | `.yaml`/`.yml`, `.json` | `privileged: true` (in YAML also `yes`, `on`, `y`, any case) | high |
| Host namespace | `.yaml`/`.yml`, `.json` | `hostNetwork`, `hostPID` or `hostIPC` set to `true` (in YAML also `yes`, `on`, `y`) | high |
| Final stage runs as root | Dockerfiles | `USER root` (or `0`) with no later `FROM` or `USER`, in a new file or in a last hunk that reaches the end of the file | medium, medium confidence |
| Storage encryption off | `.tf` | `encrypted = false` or `storage_encrypted = false` | medium |

A rule that needs context (a firewall rule's direction and port, an IAM
statement's resource, what follows a `USER`) reads it from the same hunk. When
the context is not in the patch the rule does not fire, and the model review
still covers the line.

**What is skipped.** Files the review ignores (the deployment list and the
repository's `review.ignored-files`), files matching
`REVIEW_SECURITY_SCAN_SKIPPED_FILES`, files GitHub sends no patch for, comment
lines for the IaC rules, and placeholder values: anything containing `example`,
`sample`, `dummy`, `placeholder`, `changeme`, `redacted`, `your_`/`your-`,
`fake`, `xxxx`, `****`, `<`/`>`, `${`, `{{`, `$(`, `%s` or an ellipsis, a run of
six identical characters, and for assignments any value starting with `$`. To
accept a line, put `thrillhousebot:allow-secret` (or `thrillhousebot:allow-iac`)
in a comment on that line or the line above; above is the only place a
Dockerfile instruction can take one. JSON cannot hold a comment, so put such a
JSON file in the skipped globs.

**The secret is never repeated.** A secret finding shows only the value's first
characters and its length: the four-character provider prefix for a known
format (`ghp_…, 40 chars`), at most one character in four for a generic value.
It carries no quoted code, so the inline comment, check run, notification,
stored session and dashboard never hold the value, and logs carry only counts.
Every verbatim copy of a matched value in the same review's model findings,
status notes and summary is replaced with the redacted form. Those texts also
have any secret-looking quoted literal redacted when its line assigns it to a
credential-named key through the first `=` or `:` after the name, whatever sits
between (`static API_TOKEN: Lazy<&str> = Lazy::new(|| "…")`), as long as the
literal passes the same placeholder, identifier and entropy checks. A
declaration with no `=` or `:` before the literal (Kotlin
`val apiToken by lazy { "…" }`), a fragment or an altered copy is not covered.
Treat a reported credential as leaked even after it is removed from the branch:
it is still in the git history.

**How scan findings join the review.** They are added after the verifier and
severity calibration, so no model re-grades them, and they are never marked
unverified. A model finding in the same file is dropped as a duplicate when it
is within three lines and reports the same defect (same title by the
deduplicator's measure, or a title using two of the rule's words), or, for a
secret, when it sits on the matched line and its title uses one of the rule's
words. A title that only names a key (`PAYMENT_API_KEY is never read`) is a
different defect and is kept. Under `balanced` strictness a critical or high
scan finding requests changes on its own.

**Later rounds.** A scan finding an earlier round raised is not posted again. It
stays `unresolved` while the pattern is still on an added line, and becomes
`resolved` once the line is gone or carries the allow marker. A declined scan
finding stays declined: once a round has recorded the decline `justified`, the
finding is not raised again while the rule matches the same content (same file,
title and anchor, and for a secret the same line). A secret finding ends with a
hidden `thrillhousebot:scan-content` marker holding the first eight hex digits
of a SHA-256 of the flagged line (for a private key, its block), which is how a
later round tells a new value with the same prefix and length from the declined
one. A new value, or any other change to the line apart from leading and
trailing whitespace, is raised again. A secret finding declined before 0.7.0 has
no fingerprint, so its decline holds only until the head moves; it is then
raised once more, with the marker.

### CI-failure context

`REVIEW_CI_CONTEXT_ENABLED=true` gives the review the checks that had already
failed on the head commit when it started, so a finding can name the test or
build step the change breaks. The checks come from the CI gate's own read (with
`REVIEW_CI_GATING=off` that read is still made for this, and never holds
approval). For each failing check, required or not, the prompt gets its name,
conclusion, output title and summary, and up to eight annotations from one page,
failures first. Five checks are described in full and any others by name.
`REVIEW_CI_CONTEXT_INCLUDE_LOGS=true` adds the last 30 lines of up to two failing
GitHub Actions job logs. The extra cost is one annotations request per detailed
check that has annotations, plus one log download per job with logs on. Job logs
use the `Actions: Read` permission the app already has.

Check output is written by the PR's own code, so it is handled like the PR
description: ANSI codes and control and bidi characters are stripped, each field
is clipped, and the list sits inside the untrusted-data fence. The model is told
to use a failure to find the changed line that causes it, to quote the failure
it relied on, and not to report a failure the diff does not explain.

Checks still running add only a count next to a failure. An automatic review
usually starts before CI finishes, so in practice the section shows up on a
`/review` after CI has failed. A CI failure after the review does not start a
new one; comment `/review` to get a review that sees it.

### Linked-issue context

`REVIEW_TICKET_CONTEXT_ENABLED=true` gives the review the issue the PR says it
implements. Issues are read from GitHub Issues through a provider interface
(`REVIEW_TICKET_CONTEXT_PROVIDER`), so another tracker can be added later. Links
are taken in this order:

1. Closing keywords in the PR body (`Closes #57`, `fixes owner/repo#57`,
   `resolves https://github.com/owner/repo/issues/57`), in order. Code spans,
   fenced blocks and HTML comments are skipped, so a template's commented-out
   `Closes #` is not a link. GitHub records these links only when the base is
   the default branch, so for a PR into a release branch the body is the only
   source.
2. GitHub's closing references for the PR (one GraphQL read), which add issues
   linked from the Development panel.
3. The head branch name (`issue-57`, `fix/57-short-name`), only with
   `REVIEW_TICKET_CONTEXT_FROM_BRANCH=true` and only when the first two link
   nothing.

Only issues in the PR's own repository are read. Pull requests, the PR's own
number and missing or unreadable issues are skipped. At most
`REVIEW_TICKET_CONTEXT_MAX_ISSUES` issues are used, and a review makes at most
ten issue reads plus the one GraphQL read. The issue is never modified. Reading
uses the `Issues` permission the app already has.

For each issue the prompt gets its title, acceptance criteria and body. The
criteria are the list items under a heading such as "Acceptance criteria" or
"Definition of done", or else the issue's task-list items, leaving out an issue
form's code-of-conduct checkbox. Issue text is stripped of control, ANSI and
bidi characters, lines are clipped, each issue gets an equal share of
`REVIEW_TICKET_CONTEXT_MAX_CHARS` with criteria ahead of the body, and the
section sits inside the untrusted-data fence.

The review call reads the issue as intent: a changed line that contradicts a
criterion is a finding and must quote it, but work the diff does not contain is
not. The summary call checks each criterion against the PR description, the
changed-file list and the findings, and lists the ones the change shows it does
not address under **Description vs. Implementation**, each starting with
`Linked issue #N:`. A missing criterion is a question of scope, so it never
becomes a finding or changes the verdict. The summary call does not see the
diff, so it reports only what it can show (a file the criterion names that the
change never touches, or a description that defers it). An entry naming an
issue the PR does not link is rewritten to the linked one. When this section is
present, the bug-fix efficacy check uses it instead of reading the issue again.

### Review learnings

`REVIEW_LEARNINGS_ENABLED=true` lets the bot remember what maintainers taught it
on one pull request when it reviews the next. Without it, a finding declined for
a stable reason (PR #159's "GitHub review threads are flat, every reply's
`in_reply_to_id` is the thread root") can come back on every later PR.
`.github/thrillhousebot.md` is still the place for facts you want to write down
yourself. The feature needs `REVIEW_DECLINE_RECHECK_ENABLED=true`.

**What becomes a learning.** Two things only:

- A decline of a finding, with its reason, once it has survived the
  [decline re-check](#re-checking-declines). The review must record the finding
  `justified`, the re-check must have run against the reviewed code, and the
  reason must not rest on a premise the code could refute. A "this cannot run
  concurrently" decline (only one caller, runs serially, single-threaded) is
  never stored, whatever the finding's title, because the code that refutes it
  (PR #160's unbounded executor) can be outside the diff. Nor is a decline that
  stood only because the maintainer answered the re-check a second time. The
  author must have write access, confirmed through the collaborator-permission
  API.
- A convention stated with `/remember <text>`.

A 👍/👎 or a bare "not useful" reply says a finding was unwelcome but not why, so
it is not a learning. It is still recorded as
[finding feedback](https://devops-thiago.github.io/ThrillhouseBot/feedback/).

**How it is used.** Before each review, the repository's active learnings are
ranked by how close their file is to the changed files: the same file, then the
same directory (and repository-wide conventions), then files of the same type. A
file's type is its extension, except that every container build file spelling
(`Dockerfile`, `Dockerfile.prod`, `prod.Dockerfile`, `Containerfile`) is one
type, `Makefile`, `GNUmakefile` and `*.mk` are one type, and any other name
without a dot (`Jenkinsfile`, `CODEOWNERS`) is its own type. A dotfile such as
`.env` has no type and matches by file and directory only. Learnings about
unrelated file types are left out. The best ones, within
`REVIEW_LEARNINGS_PROMPT_MAX_ITEMS` and `REVIEW_LEARNINGS_PROMPT_MAX_CHARS`, go
into the review call inside the untrusted-data fence, after the CI-failure and
linked-issue sections and before the repository instructions. The model is told
not to raise a declined finding again while its reason still holds, and to
describe a learning in plain words when the diff breaks it; a learning id that
reaches review text anyway is rewritten to "a maintainer's earlier decision".
The verifier and the summary call do not see learnings.

**Governance.** `/learnings` lists active learnings with their ids, what each is
about, who taught it and a link to the source comment. `/forget <id>` retracts
one at once; the row is kept with who retracted it and when. The dashboard
serves the newest 200 learnings, retracted ones included, at
`GET /api/dashboard/learnings?repository=owner/repo` to signed-in users with
access to the repository.

**Safety.** Learnings belong to one repository under one GitHub App installation
and are never read by another. Text with anything credential-shaped (GitHub,
AWS, Slack, OpenAI and Google keys, private-key headers, JWTs, bearer values, a
value assigned to a key such as `secret_key` or `authToken`, and every format
the secret scan knows) is refused, and is filtered again before a learning is
shown to a review. Quoted lines and control and bidi characters are dropped, and
each learning is clipped to 1000 characters. The `review_learning` table is
created on first start, whether the feature is on or not.

### Outgoing notifications

The bot can tell another system when a review finishes, so a team can follow
outcomes in chat or a pipeline. It is off until `NOTIFICATIONS_WEBHOOK_URL` is
set.

**When it fires.** Once per review run, after the outcome is final:
`review.completed` once the verdict is on the PR, `review.failed` once a failed
review has been reported (check run and failure comment). A run abandoned
because the head moved sends nothing; the run for the new head sends its own. A
verdict held on pending CI is sent once, when posted, with
`review.held_on_ci: true`; the approval posted when CI turns green is not sent
again. Commands such as `/describe` or `/improve` send nothing.

**What is sent.** With the default `json` format:

```json
{
  "schema_version": 1,
  "event": "review.completed",
  "timestamp": "2026-09-29T12:00:00Z",
  "bot": { "name": "thrillhousebot", "version": "0.7.0" },
  "repository": "octo/repo",
  "pull_request": { "number": 42, "head_sha": "0123abc…", "url": "https://github.com/octo/repo/pull/42" },
  "session_url": "https://bot.example/session/…",
  "review": {
    "verdict": "REQUEST_CHANGES",
    "check_conclusion": "failure",
    "highest_risk": "HIGH",
    "held_on_ci": false,
    "partial_coverage": false,
    "first_review": true,
    "findings": { "total": 3, "critical": 0, "high": 1, "medium": 2, "low": 0 },
    "previous_findings": { "resolved": 0, "unresolved": 0 }
  }
}
```

A `review.failed` payload carries `failure.category` instead of `review`: one of
`ai_response_truncated`, `ai_context_window_exceeded`, `ai_timeout`,
`token_spend_ceiling`, `ai_error`, `github_auth`, `publish_failed`, `github_api`
or `internal`. The error message is never sent, since it can quote provider or
GitHub responses. No code, diff, finding description, suggested fix, summary or
PR title is sent. `NOTIFICATIONS_WEBHOOK_INCLUDE_CONTENT=true` adds
`pull_request.title` and `review.finding_list` (up to 25 findings, most severe
first, each with `severity`, `file`, `line` and `title`); descriptions and code
are never sent. `session_url` is built from `DASHBOARD_URL`.

**Chat formats.** `slack` and `discord` send one message (verdict, counts, head
commit, links to the PR and the dashboard session) to an incoming-webhook URL.
With content on, the message also lists the PR title and up to five findings. PR
and model text is escaped so it cannot mention anyone or inject links, and
Discord messages disable mentions outright. One endpoint is supported; use a
relay to reach several.

**Verifying requests.** Every request carries `X-Thrillhousebot-Event`
(`review.completed` or `review.failed`) and `X-Thrillhousebot-Delivery` (the same
id on every retry of one notification, for de-duplication). With
`NOTIFICATIONS_WEBHOOK_SECRET` set, it also carries
`X-Thrillhousebot-Signature-256: sha256=<hex>`, the HMAC-SHA256 of the exact
body keyed by the secret, the same scheme GitHub uses for `X-Hub-Signature-256`.
Recompute it over the raw bytes, compare in constant time, and check `timestamp`
to refuse replays.

**Delivery.** Sending never delays or fails a review: it runs on a background
virtual thread. A timeout, connection failure, `429` or `5xx` is retried up to
`NOTIFICATIONS_WEBHOOK_MAX_ATTEMPTS`, then dropped with a WARN; any other status
is final. Nothing is queued across a restart.

**Security.** Notifications go only to the configured URL. Redirects are not
followed, so a receiver cannot bounce the payload and signature elsewhere.
`https://` is required unless `NOTIFICATIONS_WEBHOOK_ALLOW_HTTP=true`, and a URL
with credentials before the host is refused at boot. Slack and Discord webhook
URLs are credentials themselves, so logs and boot errors show only scheme, host
and port. The bot does not restrict which hosts it may reach, so point the URL
only at a receiver you trust with the metadata above.

### Startup checks

The app checks its configuration at startup and fails fast, naming every
problem in one message, when `GITHUB_APP_ID`, `GITHUB_PRIVATE_KEY`,
`GITHUB_WEBHOOK_SECRET` or `AI_API_KEY` is missing, or the private key is not a
valid PEM RSA key. Dashboard OAuth is optional: with `GITHUB_CLIENT_ID` and
`GITHUB_CLIENT_SECRET` unset, dashboard login is disabled.

### Cost tracking

Cost uses per-model pricing keyed by model name:

```properties
thrillhousebot.ai.pricing.deepseek-chat.input-per-1k=0.00014
thrillhousebot.ai.pricing.deepseek-chat.output-per-1k=0.00028
```

When you change `AI_MODEL`, add a matching `thrillhousebot.ai.pricing.<model>.*`
pair. Without one, tokens are still recorded, a warning is logged once per model,
and its sessions are flagged "no pricing" in the dashboard instead of showing
`$0`. Adding the entry later fills in those sessions on the next restart.
<!-- docs:configuration:end -->

## Dashboard

After you sign in with GitHub, the dashboard shows an overview with summary
cards and a live activity feed, plus cost charts by model, input and output token
breakdowns, and a paginated session history with PR links.

Access is limited to the GitHub App owner and to collaborators on at least one
repository where the app is installed under that owner. Everyone else sees an
access-denied screen. The owner is read from the app registration; set
`thrillhousebot.dashboard.github.account-owner` when that fails, because the
dashboard otherwise denies everyone.

Everything the dashboard shows is scoped per repository. The owner sees every
repository where the app is installed; any other login sees only the installed
repositories they collaborate on. This covers the session list and its totals,
session details, costs, tokens, the overview, feedback, learnings and the live
feed. A `?repository=` you cannot access returns 403, and a session id from such
a repository returns 404. The overview's installation-wide skip counts are shown
to the owner only.

A review cut off by a restart or crash would stay marked as running, so on
startup every session still in progress is marked failed with the reason
"Review interrupted before it finished (bot restart or crash)". The tokens and
cost it had already spent stay on the session.

| | |
|---|---|
| ![Cost analytics by model](docs/assets/dashboard-costs.png) | ![Token analytics: input vs. output](docs/assets/dashboard-tokens.png) |
| ![Session history table](docs/assets/dashboard-sessions.png) | ![Session detail with model output and findings](docs/assets/session-detail.png) |

The Overview's live panel streams the model's output as a review runs. On a
batched review, per-token streaming is off and the panel shows `review.batch`
progress (batch X/Y) instead:

<p align="center">
  <img src="docs/assets/live-streaming.png" alt="Dashboard Overview with summary cards, live model-output panel, and recent activity" width="800" />
</p>

## Repository instructions

Add a `.github/thrillhousebot.md` file to a repository to guide its reviews:

```markdown
## Review Priorities
1. Payment calculations must be exact; flag any floating-point usage
2. All DB queries must use the repository pattern, never raw SQL

## Known Gotchas
- The `price` field in Product is in cents, not dollars
```

Fallback chain: `.github/thrillhousebot.md` → `.github/copilot-instructions.md` → `CLAUDE.md` → `AGENTS.md` → `AGENT.md`

<!-- docs:repository-configuration:start -->
## Repository configuration

The instructions file (`.github/thrillhousebot.md`) is prose for the model.
Structured settings go in an optional `.github/thrillhousebot.yml` (or `.yaml`).
They are kept apart because the instructions fallback may land on a file owned by
another tool, and that whole file reaches the model as untrusted prose.

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

**Ignore list: deployment list plus repository list.** A file is skipped if it
matches the deployment's `thrillhousebot.review.ignored-files` or a glob the
repository declares. A repository can take more files out of review but never
put back one the deployment excludes. A repository with no config file gets the
deployment list unchanged.

**Globs are gitignore-style**, with the same meaning as in a root `.gitignore`,
here and in the deployment setting:

| Pattern | Matches |
|---|---|
| `build/` | everything under any directory named `build`, at any depth |
| `vendor` | a file or directory called `vendor` at any depth, and its tree |
| `*.lock` | every lockfile at any depth |
| `generated/**` | the top-level `generated` tree only (a pattern with a `/` is anchored to the root) |
| `**/generated/**` | a `generated` tree at any depth |
| `/Makefile` | only the root `Makefile` |
| `**/*.{js,ts}` | brace alternation, in the YAML form |

`*` never crosses a `/`; use `**` to span directories. Negation (`!path`) is not
supported and is dropped with a warning, since nothing may put back an excluded
file. A glob that matched no file in the PR is named in the review summary, so a
declaration that does nothing is visible.

**Review rules: global instructions plus every matching path scope.** The prose
in `.github/thrillhousebot.md` (or whichever file the fallback lands on) applies
to every file. Each `path-instructions` scope whose glob matches a changed file
adds its rules for that file only; the model sees each scope next to the files it
governs, so `payments/` rules never reach generated code. Scopes may overlap: a
file matching two gets both, in declaration order, and a scope wins over the
global instructions for its own files. A scope matching nothing in the PR is not
sent, and ignored files are never scoped. Path globs use the same syntax and
matcher as `ignored-files`.

**Patch coverage needs a named report.** The bot never builds the PR, so it
cannot measure coverage itself. When the deployment sets
`REVIEW_PATCH_COVERAGE_ENABLED=true` and the repository names an artifact here,
each review looks for that artifact on a completed workflow run for the exact
head commit, downloads it, and intersects the report's never-executed lines with
the lines the diff adds. The reviewer gets a short list of uncovered changed
lines and is told that changed logic nothing runs is reportable, and that a
correctness claim about such a line must not be softened by "a test in this diff
covers it". The workflow must upload the report:

```yaml
- run: ./mvnw clean test jacoco:report
- uses: actions/upload-artifact@v7
  with:
    name: coverage-report          # must match review.coverage-artifact
    path: target/site/jacoco/jacoco.xml
```

Only JaCoCo XML is understood. A repository that names no artifact gets the same
review as before, and so does one whose run uploaded nothing by that name, whose
artifact expired or failed to download, or whose report is in another format:
the section is left out. Coverage is never inferred from the diff, a line's
absence from the list is not evidence that a test covers it, and ignored files
are never reported. Uploading the whole `target/site/jacoco/` tree is fine: only
`.xml` entries count toward the 512-entry limit. An artifact the bot found but
refused (more `.xml` entries than that, or more than 128 MB unpacked) is named
in the summary's scope note.

**Loading and limits.** The file is read from the default branch and cached for
five minutes. YAML anchors, aliases and merge keys are resolved; an oversized or
deeply nested document, or one built on a runaway alias expansion, is refused
whole. Everything else fails soft: a missing file, invalid YAML, an unexpected
shape, an uncompilable glob, a repeated key (the last value wins) or a malformed
`path-instructions` entry is logged and skipped, and the deployment's ignore list
and instructions stay in force. A read that fails for another reason than "no
such file" (a 5xx, a rate limit) is retried on the next review. A repository may
declare at most 25 scopes of at most 4000 characters each; the rest is dropped
with a warning. Set `THRILLHOUSEBOT_REVIEW_REPO_CONFIG_ENABLED=false` to turn the
whole mechanism off.
<!-- docs:repository-configuration:end -->

<!-- docs:pr-labels:start -->
## PR labels

With `REVIEW_LABELS_ENABLED=true`, the summary call suggests a few labels (area,
change type, risk) from the repository's existing labels. A suggestion that
matches no existing label is dropped, unless `REVIEW_LABELS_ALLOW_CREATE=true`
lets the bot create it. `REVIEW_LABELS_APPLY` decides what happens next:

- `false` (default): the labels are posted as a one-line comment on the first
  review for a maintainer to apply.
- `true`: the labels are added to the PR.

`REVIEW_LABELS_MAX` caps the number of labels (default `3`). Labelling never
blocks or fails a review.
<!-- docs:pr-labels:end -->

## Observability

Telemetry is exported over OTLP to `OTEL_EXPORTER_ENDPOINT` (default
`http://localhost:4317`):

| Signal | Metric |
|---|---|
| Traces | One span per LLM call with request and response events |
| `gen_ai.client.token.usage` | Histogram: input and output tokens |
| `gen_ai.client.operation.duration` | Histogram: latency in seconds |
| `thrillhouse.ai.cost.total` | Counter: USD cost by model |
| `thrillhouse.review.skips` | Counter: automatic reviews skipped, tagged with `reason` and `repository` |

Spans and metrics carry `gen_ai.provider.name`, derived from `AI_BASE_URL`
(`deepseek`, `openai`, `groq`, `openrouter`, …). Loopback and unrecognized
endpoints report `unknown`; set `AI_PROVIDER` to name them (a local `ollama` or
`vllm` server, a proxy, a gateway).

## Troubleshooting

### PR opened but no review posted

When a `pull_request` webhook arrives but no review is posted, the bot logs
`Automatic review skipped [reason=...]`, increments `thrillhouse.review.skips`,
and counts skips per reason in the dashboard summary
(`GET /api/dashboard/summary`, field `skippedReviewsByReason`). Check in order:

1. **Is the app installed on the repository?** No delivery at all means the app
   is not installed or the webhook URL or secret is wrong. See the app's
   **Advanced → Recent Deliveries** page.
2. **Draft** (`reason=DRAFT`): with `WEBHOOK_SKIP_DRAFTS=true`, drafts wait until
   marked ready.
3. **Paused** (`reason=PAUSED`): someone commented `/pause`; comment `/resume`.
4. **Label gates** (`reason=MISSING_REQUIRED_LABEL` / `EXCLUDED_LABEL`): check
   `WEBHOOK_REQUIRED_LABELS` and `WEBHOOK_EXCLUDED_LABELS`.
5. **Base-branch filters** (`reason=BASE_BRANCH_NOT_ALLOWED` /
   `IGNORED_BASE_BRANCH`): check `WEBHOOK_BASE_BRANCHES` and
   `WEBHOOK_IGNORED_BASE_BRANCHES`.
6. **Rate window** (`reason=RATE_LIMITED`): an automatic review already ran
   within `AUTO_REVIEW_MIN_INTERVAL`.
7. **Redelivery** (`reason=DUPLICATE_DELIVERY`): GitHub redelivered a webhook
   the bot already handled. This is normal.
8. **Executor saturated** (`reason=DISPATCH_REJECTED`): the review executor
   refused the task (overload or shutdown); redeliver the webhook from GitHub.

A `/review` from a user with write access bypasses the draft, label,
base-branch and rate-window gates, but not `/pause`.

## Responsible use and security

AI review is advisory. The model raises false positives and misses real bugs, so
confirm a finding before acting on it.

Pull request diffs go to the endpoint you configure. Use an HTTPS endpoint with
an API key, and read the provider's data-retention policy before sending it
private code.

Set `AI_API_KEY`, `GITHUB_PRIVATE_KEY` and the webhook secret through your
environment or a secret manager, and never commit them. Treat
`NOTIFICATIONS_WEBHOOK_URL` and `NOTIFICATIONS_WEBHOOK_SECRET` the same way; see
[Outgoing notifications](#outgoing-notifications) for exactly what a
notification sends.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Known limitations

- **GitHub only.** No GitLab or Bitbucket.
- **Large diffs.** A review splits a big PR into up to `REVIEW_MAX_AI_CALLS - 1`
  batch calls, keeping one for the summary; files that still do not fit are named,
  not dropped. See [AI call budget](#ai-call-budget) and **Batching** under
  Commands.
- **Pure renames.** Files GitHub reports as `renamed` with no changes and no
  patch are left out of the review input; the summary lists a short rollup
  (`N pure renames omitted…`). A rename with edits is reviewed.
- **Single process.** OAuth sessions, the WebSocket replay buffer, the per-PR
  auto-review window, the findings a superseded review hands to its replacement,
  verdicts held on pending CI and the claims the verifier rejected on a head are
  kept in memory: lost on restart and not shared between replicas. Review
  history and cost totals are stored in PostgreSQL. Multiple replicas are not
  supported.
- **Dashboard access.** GitHub OAuth only, for the app owner and collaborators on
  installed repositories; no admin UI or guest mode.
- **Production database.** Container and native builds use PostgreSQL. H2 is for
  `quarkus:dev` and the tests only, and its driver is not packaged in the image.
- **OpenAI-compatible APIs only.** The endpoint must implement the
  chat-completions API that LangChain4j expects.
- **Review output.** At most `THRILLHOUSEBOT_REVIEW_MAX_REVIEW_COMMENTS` (50)
  inline comments per review. Build descriptors (`pom.xml`), lockfiles
  (`*.lock`, `package-lock.json`, `pnpm-lock.yaml`, `go.sum`), minified bundles
  and sourcemaps, generated code (`*.generated.*`, protobuf output) and build or
  vendor directories (`target/`, `node_modules/`, `dist/`, `build/`, `out/`,
  `.next/`, `vendor/`, `__pycache__/`, `.venv/`, `bin/`, `obj/`) are skipped by
  default. See `THRILLHOUSEBOT_REVIEW_IGNORED_FILES` and
  [Repository configuration](#repository-configuration).
- **Security scan.** The [security scan](#security-scan) matches a short list of
  patterns on added lines. It does not replace GitHub secret scanning or push
  protection: it reads neither git history nor unchanged lines, and a credential
  in another format is left to the model review. A secret finding has no code
  anchor (that would store the value), so when an older round's finding is no
  longer in the round the bot compares against, it counts as present while its
  file stays in the diff; a reply on its thread or `@thrillhousebot resolved`
  clears it.
- **Self-hosted.** There is no managed offering.

## Verifying a release

Release images and binary tarballs are signed with cosign (keyless, through
Sigstore) and carry build-provenance attestations. To check a release:

```bash
# Signature
cosign verify \
  --certificate-identity-regexp='https://github.com/devops-thiago/ThrillhouseBot.*' \
  --certificate-oidc-issuer='https://token.actions.githubusercontent.com' \
  ghcr.io/devops-thiago/thrillhousebot:v0.7.0

# Provenance (image)
gh attestation verify oci://ghcr.io/devops-thiago/thrillhousebot:v0.7.0 \
  --repo devops-thiago/ThrillhouseBot

# Provenance (a downloaded binary)
gh attestation verify thrillhousebot-v0.7.0-linux-amd64.tar.gz \
  --repo devops-thiago/ThrillhouseBot
```

## Development

Local development without Docker needs Java 25+, Node.js 22+ (dashboard) and a
[Smee.io](https://smee.io/) channel to forward webhooks. Use the included Maven
wrapper, `./mvnw`.

### Dev mode

Dev mode reads a `.env` file in the project root, and the bot will not boot until
`GITHUB_APP_ID`, `GITHUB_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET` and `AI_API_KEY`
are set. Create it first:

```bash
cp .env.example .env
```

Fill it in from your GitHub App (see [GitHub App setup](#github-app-setup)).
`GITHUB_PRIVATE_KEY` must be a real PEM RSA key; placeholder text fails at boot.
Use the `.pem` GitHub gives you, or generate a throwaway key for local work:

```bash
openssl genrsa -traditional 2048 | awk '{printf "%s\\n", $0}'
```

`-traditional` writes the PKCS#1 format (`BEGIN RSA PRIVATE KEY`) that GitHub's
keys use and the bot parses; OpenSSL 3 otherwise writes PKCS#8. The `awk` step
puts the key on one line with literal `\n` escapes, as `.env` needs. Paste it
**unquoted**, since quotes are read as part of the key:

```dotenv
GITHUB_PRIVATE_KEY=-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----
```

`GITHUB_WEBHOOK_SECRET` and `AI_API_KEY` only need to be non-empty to boot, but
webhooks and reviews need the real values. Dashboard OAuth is optional. Then:

```bash
# Terminal 1: Smee proxy
smee -u https://smee.io/YOUR_CHANNEL -t http://localhost:8080/api/webhook

# Terminal 2: Quarkus dev mode
./mvnw quarkus:dev
```

### Build the dashboard

```bash
cd frontend
npm ci
npm run build
cp -r out/* ../src/main/resources/META-INF/resources/dashboard/
```

### Build the native image

```bash
./mvnw package -Pnative -DskipTests -Dquarkus.native.container-build=true
```

### Run tests and checks

```bash
./mvnw verify
./mvnw spotless:check
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow.

## Community

Ask questions and get setup help in [GitHub Discussions](https://github.com/devops-thiago/ThrillhouseBot/discussions) (see the pinned welcome post). Use [Issues](https://github.com/devops-thiago/ThrillhouseBot/issues/new/choose) for bugs and feature requests.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tech stack

| Layer | Choice |
|---|---|
| Framework | Quarkus 3.39 (REST) |
| LLM | Quarkus LangChain4j 1.13 (OpenAI-compatible API) |
| Frontend | Next.js 16 + React 19 (static export) |
| Database | H2 (dev) / PostgreSQL (prod) + Panache |
| Observability | OpenTelemetry |
| Native | GraalVM / Mandrel |
| Container | UBI9-micro (default) / distroless (`-distroless`) |

## Container images

Both images are built from the same native binary and published to GHCR:

- `ghcr.io/devops-thiago/thrillhousebot:latest`: UBI9-micro (default).
- `ghcr.io/devops-thiago/thrillhousebot:latest-distroless`: distroless base
  (`:v0.7.0-distroless`, and so on).
- Snapshot tags: `:snapshot`, `:v<version>-<short-sha>-snapshot` and
  `:<full-sha>`, each with a `-distroless` variant.

Both are multi-arch (linux/amd64, linux/arm64), signed with cosign, and carry
build-provenance attestations (see [Verifying a release](#verifying-a-release)).

## License

Licensed under the [Apache License 2.0](LICENSE) (SPDX: `Apache-2.0`).
