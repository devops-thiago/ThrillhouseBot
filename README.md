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
  <a href="https://www.bestpractices.dev/projects/13330"><img src="https://www.bestpractices.dev/projects/13330/badge" alt="OpenSSF Best Practices" /></a>
  <a href="https://github.com/devops-thiago/ThrillhouseBot/releases"><img src="https://img.shields.io/github/v/release/devops-thiago/ThrillhouseBot" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/devops-thiago/ThrillhouseBot" alt="License" /></a>
</p>

ThrillhouseBot is a GitHub App that reviews pull requests. It is built with
Quarkus and distributed as a GraalVM native binary. It sends diffs to an
OpenAI-compatible chat API, including local models served by Ollama. The model
you configure determines which languages it can review.

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
- Splits large diffs into batches reviewed in parallel within a token budget; lists files that exceed the budget or have no reviewable patch
- Inline suggestions you can apply with one click
- A second pass checks findings, suggested fixes and contradicted description sentences against the diff; an optional summary check screens summary statements too
- Follow-up reviews track whether earlier findings were fixed, declined or are still open, and re-check a maintainer's decline against the code
- One summary comment per pull request, edited in place every round, with a risk breakdown, a changed-files walkthrough and every finding still open
- Comment commands (`/review`, `/summary`, `/describe`, `/changelog`, `/add-docs`, `/improve`, `/generate-tests`, `/resolve`, `/pause`, `/resume`, `/help`, and with learnings on `/learnings`, `/remember`, `/forget`) and conversational replies to `@thrillhousebot`
- Auto-review triggers: skip drafts, gate on labels, filter by base branch, and optionally space out reviews of one PR (`AUTO_REVIEW_MIN_INTERVAL`)
- Maintainer 👍/👎 reactions and "not useful" replies are recorded as [finding feedback](https://devops-thiago.github.io/ThrillhouseBot/feedback/)
- Optional review context, each off by default: the issues a PR links and their acceptance criteria, failing CI checks and test names, patch coverage from your own coverage report, and review learnings (maintainer declines and `/remember` conventions from earlier reviews of the same repository)
- Optional deterministic scan of added lines for leaked credentials and risky Terraform, Kubernetes, CloudFormation and Dockerfile settings, with no model call and every matched secret redacted
- Optional per-call review dimension routing, which sends each call only the review rules its files need
- Optional outgoing notification when a review completes or fails: JSON, Slack or Discord, HMAC-signed, metadata only unless you opt in
- Per-repository instructions in `.github/thrillhousebot.md` (falling back to Copilot, Claude and Agents files) and per-repository ignore globs and path-scoped rules in `.github/thrillhousebot.yml`
- Optional reasoning-effort setting and per-model generation and budget caps
- Live dashboard (Next.js) with a WebSocket activity feed, cost charts and token tracking
- OpenTelemetry traces, token histograms, cost counters, latency and review-outcome metrics, exported over OTLP and scrapeable in Prometheus format at `/metrics`
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
| Hugging Face Inference Providers | `https://router.huggingface.co/v1` | `openai/gpt-oss-120b` |
| NVIDIA (build.nvidia.com) | `https://integrate.api.nvidia.com/v1` | `meta/llama-3.3-70b-instruct` |
| Ollama (local) | `http://localhost:11434/v1` | `llama3.2` |

For Hugging Face, `AI_API_KEY` is an access token with the "Make calls to
Inference Providers" permission. A free Hugging Face account includes no
monthly credits, so requests need purchased credits or a PRO, Team or
Enterprise plan; see the
[Inference Providers pricing](https://huggingface.co/docs/inference-providers/pricing).
NVIDIA has a free tier, useful for trying the bot. `AI_API_KEY` is a key from
build.nvidia.com, and the free tier is limited to about 40 requests per minute,
so set `AI_MAX_CONCURRENT_CALLS` low (for example `2`). Check each provider's
current terms; both change them.

Ollama's cloud endpoint (`https://ollama.com/v1`) limits how many requests one
account has in flight and refuses the rest with `timed out waiting for a
concurrent request slot`. Reviews of different pull requests run in parallel and
a large pull request sends its batches at once, so set `AI_MAX_CONCURRENT_CALLS`
to the number of concurrent requests your plan allows. Calls past it wait for a
slot instead of being refused, and a refusal that still gets through is retried
after 30 seconds.

DeepSeek is the default provider. Set `AI_BASE_URL`, `AI_MODEL` and `AI_API_KEY`
to use another provider.
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
| `@thrillhousebot resolved <path>:<line> — <title>` | Close an earlier finding that has no review thread (see **Clearing a finding with no thread** under Configuration), or, replied on a finding's own thread, close that finding and resolve the thread | write |
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
| Organization permissions | Members: Read (shows installation-wide dashboard counters to organization members) |
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

### Most-used settings

<!-- config-reference:essentials:start -->

| Variable | Purpose | Default |
|---|---|---|
| `AI_API_KEY` | API key for the AI provider. Required. | _(none)_ |
| `AI_BASE_URL` | OpenAI-compatible base URL of the AI provider. | `https://api.deepseek.com/v1` |
| `AI_MODEL` | Chat model name. It also selects the model's entry in the per-model settings and the pricing map. | `deepseek-chat` |
| `GITHUB_APP_ID` | GitHub App ID, from the App's settings page. Required. | _(none)_ |
| `GITHUB_PRIVATE_KEY` | GitHub App private key (PEM), on one line with each newline written as `\n`, unquoted. Required. | _(none)_ |
| `GITHUB_WEBHOOK_SECRET` | The GitHub App's webhook secret, used to check each delivery's HMAC signature. Required. | _(none)_ |
| `GITHUB_CLIENT_ID` | OAuth client ID for dashboard login, from the GitHub App's settings. Leave it and `GITHUB_CLIENT_SECRET` unset to disable login. | _(none)_ |
| `GITHUB_CLIENT_SECRET` | OAuth client secret for dashboard login, from the GitHub App's settings. | _(none)_ |
| `DASHBOARD_URL` | Public dashboard URL, used for the OAuth callback and the session links posted to GitHub. | `http://localhost:8080` |
| `DATABASE_URL` | JDBC URL of the production database (`jdbc:postgresql://db:5432/thrillhouse` in Docker Compose). | `%prod`: `jdbc:postgresql://localhost:5432/thrillhouse` |
| `REVIEW_CI_GATING` | How CI affects approval: `strict` holds approval while required CI is pending, failing or unreadable; `warn` approves and notes the CI state; `off` ignores CI. See [CI gating](https://github.com/devops-thiago/ThrillhouseBot#ci-gating). | `strict` |
| `REVIEW_BLOCKING_STRICTNESS` | Which findings request changes: `balanced`, `strict` or `lenient`. See [Blocking strictness](https://github.com/devops-thiago/ThrillhouseBot#blocking-strictness). | `balanced` |
| `REVIEW_MAX_INPUT_TOKENS` | Input-token budget per call for reviews and the batched commands; larger PRs are split into batches. Capped by the model's `max-input-tokens` (see the per-model settings). `0` disables budgeting. | `48000` |
| `REVIEW_MAX_AI_CALLS` | Most AI calls per review (batch calls plus the summary call) and per command run (batch calls plus, for `/describe` and `/changelog`, the merge call). At `1`, a review that makes its review call has none left for the summary and posts a counts-only summary that says so. Verifier calls are not counted. | `6` |
| `WEBHOOK_SKIP_DRAFTS` | Do not auto-review drafts; the PR is reviewed once marked ready (`ready_for_review`). | `false` |
| `WEBHOOK_REQUIRED_LABELS` | Comma-separated labels; auto-review only PRs carrying at least one (case-insensitive exact match). Unset means no label is required. | _(none)_ |

<!-- config-reference:essentials:end -->

Docker Compose also reads `DB_USER` and `DB_PASSWORD` (both `thrillhouse` by
default) for the bot and the PostgreSQL container.

Every setting, with its type and default, is listed in the
[configuration reference](https://github.com/devops-thiago/ThrillhouseBot/blob/main/docs/CONFIGURATION.md),
which is generated from the code. The sections below explain the features those
settings control.

### AI call budget

A review makes a review call, which returns findings and the status of earlier
findings, and then a summary call, which writes the PR summary (purpose,
description gaps, file walkthrough, labels, diagram) from the verified findings
and the list of changed files. The summary call does not carry the diff, so it
is small. When the review call reports findings, a verification call re-sends
the diff with the candidate findings, so a review with findings sends the diff
roughly twice, before retries. Output tokens and the other context add to that
cost. A large PR makes one review call and one verification call per batch,
then one summary call.

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

`REVIEW_SUMMARY_VERIFICATION_ENABLED=true` adds one call after the summary call.
The summary call does not see the diff, so it can describe something the change
does not do. This call gets the summary's sentences, walkthrough rows and
diagram lines with the diff and the findings, and names the statements they
contradict. A named sentence is cut, a named row shows "Not summarized", and a
named diagram line drops the diagram. A "What this PR does" sentence is cut only
on evidence from the diff, rather than a finding alone. If the check would
remove every purpose sentence, all of them stay. It checks only text written
in that round; it does not check text carried from the previous round on the
same head or restore a removed statement from it. Each removal is logged at
INFO with the statement and the evidence. Findings are never changed. It fails
open: an error, a cut answer or the spend ceiling leaves the summary as written.

With `REVIEW_RESPONSE_REPAIR_ENABLED` (on by default), an answer that is not
valid review JSON gets one repair call before a full retry. It gets only the
failed answer, the parser's error and the response shape, and returns the same
content in that shape. The repair runs on the model that wrote the answer and
sees no diff. A repaired review answer must preserve every title from the
failed answer, including repeated titles. It rejects new titles and a
previous-finding status count greater than the failed answer's. If the repair
errors, is cut, does not parse or fails those checks, the full retry runs as
before. A summary answer that
wrote `file_summaries` as a string holding JSON is read as that JSON; any other
string gets one small call that asks for that field alone, and only rows whose
path is in the string are kept. A finding verification call that returns no
response body is made once more before the findings are kept unverified. These
calls are not counted by `REVIEW_MAX_AI_CALLS`; they record their usage against
`REVIEW_MAX_TOKENS_PER_REVIEW` and are skipped at that ceiling.

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
- **Reading a cut response.** When a review, summary, verifier, summary-check
  or repair call spends its whole cap reasoning and returns no answer, it is
  repeated once at a lower effort, and the summary's scope note says so. With
  `AI_REASONING_ENABLED=true` the repeat sends `none`. With it off, nothing was
  sent and the provider's default effort applied, so the repeat sends the lane's
  effort (`low` unless set). A model that reasons by default, such as
  `glm-5.3-flash`, can hit this on most concise calls and bill the cap each
  time; enable reasoning for such a model. If a `none` repeat writes its
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
"Things to double-check" list instead of an inline thread, while CRITICAL and
HIGH findings stay inline at any confidence. Placement reads the finding's final
grade, after the verifier and the deterministic confidence floors, the same way
on every review lane.

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
findings and review bodies stay per round. With `REVIEW_FOLLOW_UP_SUMMARY_ENABLED`,
the review body also carries new, resolved and still-open counts, naming each closed
finding once instead of posting a second delta comment. The review keeps its verdict;
a definite review refusal preserves the same outcome as a regular comment.

The risk counts, Key Findings and "Things to double-check" cover the round's
new findings and every earlier finding still open, each listed once and marked
"open since an earlier review". Key Findings ranks both together by severity. A
finding leaves the summary once it is resolved, declined, cleared with
`@thrillhousebot resolved`, or its code leaves the diff. A later finding replaces
an open double-check item only when it is the same defect. A finding about one
config key does not replace an item about the key on the next row, but the same
key restated on its paired doc or code file does. The same test decides when a
double-check item is marked "Same issue as" an inline finding; location or a
few shared words are not enough. The verdict
is computed over the same open set, so an earlier CRITICAL still open keeps a
follow-up round at `REQUEST_CHANGES` under the configured strictness. When a
follow-up opens nothing new while earlier findings are open, its review body
leads with "No new issues in this revision, but N previous finding(s) remain
unresolved".

The Description vs. Implementation section appears only when the PR
description and the change disagree. A summary with model prose ("What this PR
does", per-file summaries) and no such section means the check ran and found
nothing; a counts-only summary means it did not run, and says why. Each gap
names what the description or a linked issue says. A gap that quotes the
description is dropped when the quoted words are not in it. A finding that
reports a mismatch with the description is named in the section by title and
location.

A gap stays listed on later rounds until one of these happens: the summary call
says what resolved it, the same gap is reported again, the quoted claim leaves
the description, or the PR stops linking the issue the gap names. On the head
the previous round reviewed, the summary call cannot resolve a gap, because
nothing it reads has changed. The exception is an edited title or description:
then the call may release a description gap (never a linked-issue gap), and a
gap saying the description omits something leaves once the edited text holds it.

A follow-up on the head the previous round reviewed also keeps that round's
overview where its own summary has none. When the summary call returns no
overview, or is skipped, cut or fails, the whole overview is carried, and a
banner says so and that only the counts are new. Otherwise the new summary keeps
each earlier file summary and the diagram that its answer leaves out.

### Re-checking declines

When a maintainer replies to a finding to decline it, the next review records
the finding justified. A decline is a claim, though, and a correct finding
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
- A re-opened finding is not posted again. It stays in Previous Findings
  Status and counts toward the verdict at its own severity.
- When the review keeps a finding open after a maintainer replied on its
  thread, the review body says why, while the decline re-check is enabled. A
  decline the re-check overturns reads "Decline re-checked against the code
  and not accepted: …" with the code (a line, or a comment that says the path
  runs concurrently) that refutes the reply. Otherwise the model's status note
  is quoted under "Reply on this finding's thread weighed, finding kept open:
  …", since the reply may decline the finding or acknowledge it; while the
  thread has a single reply, the note ends with "If the reply declines the
  finding, reply again to keep the decline." A finding nobody replied to, or
  whose status note is empty, gets no such note.

A follow-up round's model reports on the findings of the newest round that
raised any. A decline on a finding from an older round is weighed by the
approval backstop under the same one-push-back rule: a lone reply the reviewed
diff contradicts keeps the finding open, and so does a lone "this cannot run
concurrently" reply to a concurrency finding, because what makes a path
concurrent (a server that handles each request on its own thread, an executor
in another file) is rarely in the diff. Any other reply, or a second one,
stands: the finding is counted justified, its thread is resolved, and with
review learnings on the reply is offered to the store under the same rules as
any other decline.

Every older round's finding gets a status, so the summary's counts add up to
the findings carried in. One whose code left the diff is counted superseded,
and one cleared or declined from the PR conversation is counted resolved or
justified. One whose flagged line is still there is closed as resolved when
every line its suggestion adds is now an added line of the file beside the
flagged code, with at least one real line of code among them; otherwise it
stays open until a reply or a directive closes it.

A finding whose code left the diff, whose suggested fix landed, or that a
maintainer cleared is closed once. Its review thread is resolved, the review body
names it under the round's closed findings, and the round records the close so
later rounds neither name it nor count it again. A clear wins over the code
leaving the diff, so the thread still gets the reply that records the clear.
None of these re-post the summary.

Set it to `false` to make a maintainer's reply final.

### Clearing a finding with no thread

A finding listed under "Things to double-check" has no review thread to reply
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
  it has no title). Each "Things to double-check" row prints both, title first
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
thread if it has one. The next review records a named finding resolved.

**On a finding's own review thread**, reply `@thrillhousebot resolved` (the
`path:line` and title are optional there, since the thread names the finding).
The bot resolves the thread straight away and replies with a fixed
acknowledgement, not a generated answer; if GitHub does not confirm the
resolution, the reply says the next review closes the finding instead. The next
review records the finding resolved either way. The same write-access rule
applies, and a reply whose `path:line` is not that finding's own (another file,
or another line of the same file) closes nothing on that thread.

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

The next review records the named finding justified, unless the reviewed code
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
lines a pull request adds, for leaked credentials and risky
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

IaC rules (high confidence):

| Rule | Files | Matches | Risk |
|---|---|---|---|
| Admin port open to the internet | `.tf`, `.yaml`/`.yml`, `.json` | an ingress rule admitting `0.0.0.0/0` or `::/0` to port 22, 23, 3389, 5985 or 5986, to a range containing one, or to every protocol | high |
| S3 bucket made public | `.tf`, `.yaml`/`.yml`, `.json` | a `public-read`/`public-read-write` ACL (`AccessControl: PublicRead…`), or a Block Public Access setting set to `false` | high |
| IAM wildcard | `.tf`, `.yaml`/`.yml`, `.json` | `Action: "*"` with `Resource: "*"` in the same statement and no `Effect: Deny`; a `"*"` anywhere in a valid one-line list counts, with no element-count or element-width limit; scalar wildcards allow trailing Terraform `#`/`//` and whitespace-separated YAML `#` comments | high |
| Privileged container | `.yaml`/`.yml`, `.json` | `privileged: true` (in YAML also `yes`, `on`, `y`, any case) | high |
| Host namespace | `.yaml`/`.yml`, `.json` | `hostNetwork`, `hostPID` or `hostIPC` set to `true` (in YAML also `yes`, `on`, `y`) | high |
| Final stage runs as root | Dockerfiles | `USER root` (or `0`) with no later `FROM` or `USER`, in a new file or in a last hunk that reaches the end of the file | medium |
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
six identical characters, and for assignments any value starting with `$` or
written as a Windows `%VAR%` reference, a
regular expression (a value holding `(?`, a character-class range such as
`[A-Z0-9]` or an escape such as `\d`, with at least a quarter of its characters
regex syntax), and sample text quoted inside another complete string, including
the explicit prose leaders `before`, `text before` or `prefix` before the
credential name in a raw list or a `List.of`, `Set.of`, `Arrays.asList` or
`Stream.of` builder, and a string
assigned to a fixture/sample/example-named variable
(`String fixture = "prefix client_secret: 'abc…'";`). New sample forms require
a bare key or one of those prose leaders and whitespace only after the
assignment inside the string, and a standalone literal/factory or simple
fixture/sample/example-named assignment or collection declaration. Unknown outer
calls, other assignment targets, expressions and additional statements on the
same line, including preceding comma-separated sibling entries, remain scanned;
closing delimiters and further quoted inputs are
allowed. Unknown prefixes and trailing command text
stay scanned even with a sample-named variable. Other calls, including
`Map.of` and `fixtures.add`, retain scanning for prefixed strings because
their values can be executable command text. An assignment in a
direct or assigned command string, or a prefixed execution call such as
`Runtime.exec("export API_KEY='…'")`, is still reported. Bare command strings
written as list elements can read exactly like samples, as before. Known
token formats are reported wherever they appear. To
accept a line, put `thrillhousebot:allow-secret` (or `thrillhousebot:allow-iac`)
in a comment on that line or the line above; above is the only place a
Dockerfile instruction can take one. JSON cannot hold a comment, so put such a
JSON file in the skipped globs.

IAM list elements use their file format: YAML ARN/URL slashes, non-whitespace
`#` fragments, and quotes or parentheses inside plain scalars do not start
comments or expressions. Nested or tagged stars do not prove a literal wildcard.
Block-comment trivia belongs to Terraform, and an unclosed comment proves no
wildcard.

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
unverified. A model finding in the same file is dropped as a duplicate in these
cases:

- It is within three lines and reports the same defect: the same title by the
  deduplicator's measure, or a title using two of the rule's words.
- For a secret, it sits on the matched line and its title uses one of the
  rule's words.
- For a generic secret assigned to a distinctive name (`apiToken`,
  `SCHEDULER_API_TOKEN`), its title makes a secret claim and its text names
  that identifier, at any distance. A finding in a documentation or
  configuration file of the same pull request that names the identifier with a
  credential word, or names the environment key it stands in for with a
  credential claim in its title, is dropped the same way.
- For an IaC rule, it is within three lines and its title uses a word that
  names that rule's defect (`root`, `privileged`,
  `hostNetwork`/`hostPID`/`hostIPC`, `SSH`/`RDP`/`WinRM`/`Telnet`, `public`,
  `wildcard`/`administrator`, `encryption`/`encrypted`/`unencrypted`) and no
  word naming a neighbouring defect (`filesystem`, `read-only`, `writable`,
  `root-owned`, `subnet`).

A title that only names a key (`PAYMENT_API_KEY is never read`) is a different
defect and is kept. Nearby detections are indexed by file and line; distant
secret duplicates are looked up by the identifiers their text names.

An
environment key aliases a secret only when its fallback expression reads that
secret's simple identifier, or a comment directly above the declaration names
the key. SQL/Lua `--` and HTML/XML/SVG `<!--` comments are recognized in
those file types, along with `//`, `#`, `/*` and `*` comment lines. INI and
systemd unit files accept `;`, including unit drop-in `.conf` files under
`name.service.d/` or `service.d/` (and the other unit types); an arbitrary
`.conf` extension does not establish this syntax. Properties files accept `!`.
A complete standalone Python triple-quoted string (plain, raw or Unicode)
may document the next declaration, with only whitespace or a trailing `#`
comment after its closing delimiter. Assigned, concatenated or unclosed
strings and Java operators do not supply aliases. A different call argument
does not tie the key to the secret.

**Grades.** Every scan finding is high confidence, so none carries the "verify
before acting" note: a match settles whether the pattern is on the line. The
risk is the rule's, from the tables above. A final stage running as root is
medium, the grade the review gives the same defect when the model raises it: a
container that starts as root breaks nothing by itself, but a compromise of it
starts with root. Medium never requests changes under any strictness, so it
informs the verdict without deciding it. Under `balanced` strictness a critical
or high scan finding requests changes on its own.

**Model secret findings on a stand-in.** Whether or not the scan is on, a model
finding whose title reports a secret is dropped when its own line assigns every
credential-named key an interpolation (`${VAR}`, `$VAR`, `%VAR%`, `$(…)`,
`{{ … }}`) or a placeholder (the list under "What is skipped"), and no scan rule
matches the line. `ENV API_TOKEN=${API_TOKEN}` names where a credential comes
from, and `API_TOKEN=changeme` in a sample stands in for one.

**Later rounds.** A scan finding an earlier round raised is not posted again. It
stays `unresolved` while the pattern is still on an added line, and becomes
`resolved` once the line is gone or carries the allow marker. A declined scan
finding stays declined: once a round has recorded the decline `justified`, the
finding is not raised again while the rule matches the same content (same file,
title and anchor, and for a secret the same line). A secret finding ends with a
hidden `thrillhousebot:scan-content` marker holding the first eight hex digits
of an HMAC-SHA-256 of the flagged line (for a private key, its block), keyed
from `GITHUB_WEBHOOK_SECRET`, which is how a later round tells a new value with
the same prefix and length from the declined one. The key means a reader of the
pull request cannot test a guessed value against the marker. The same secret
gives the same marker across rounds and restarts; changing the webhook secret
makes each declined secret finding come back once, and the next decline stores
the new marker. A decline recorded under the unkeyed marker of 0.7.0 keeps
matching its line. A new value, or any other change to the line apart from
leading and trailing whitespace, is raised again. A secret finding declined
before 0.7.0 has no fingerprint, so its decline holds only until the head moves;
it is then raised once more, with the marker.

### CI-failure context

`REVIEW_CI_CONTEXT_ENABLED=true` gives the review the checks that had already
failed on the head commit when it started, so a finding can name the test or
build step the change breaks. The checks come from the CI gate's own read (with
`REVIEW_CI_GATING=off` that read is still made for this, and never holds
approval). For each failing check, required or not, the prompt gets its name,
conclusion, output title and summary, and up to eight annotations from one page,
failures first; the runner's own notices on the workflow (a deprecated Node.js
version, a cache miss) are left out. Five checks are described in full and any
others by name. A failed GitHub Actions job's annotations rarely name the test
(they say "Process completed with exit code 1"), so the log of up to two failing
jobs is read and the lines that name a failed test (Surefire, Gradle, TAP and
`node --test`, Jest, Vitest, pytest, unittest, `go test`, `cargo test`,
`dotnet test`, ScalaTest, munit, CTest, Zig, RSpec) are listed, up to ten per job.
`REVIEW_CI_CONTEXT_INCLUDE_LOGS=true` also adds the last 30 lines of those logs.
The extra cost is one annotations request per detailed check that has
annotations, plus one log download per failing Actions job (at most two). Job
logs use the `Actions: Read` permission the app already has.

Check output is written by the PR's own code, so it is handled like the PR
description: ANSI codes and control and bidi characters are stripped, each field
is clipped, and the list sits inside the untrusted-data fence. The model is told
to use a failure to find the changed line that causes it, to name the failing
test in the finding that explains it, to quote the failure it relied on, and not
to report a failure the diff does not explain. The summary's overview then
says, for each failing check, which open finding explains it or that none does.
That sentence is computed rather than written by the model: a finding explains
a check when it names the check or one of the failing tests listed for it, and
findings still open from earlier reviews count.

Checks still running add only a count next to a failure. An automatic review
usually starts before CI finishes, so the review call often sees no failure.
When a check was still running at the start, or no check had appeared yet,
the head's check runs are read once more after the model call. A check that
has failed by then is added before the summary is rendered: the overview's sentence names it
and the open findings that cite it, and a finding that cites it is raised from
low to medium confidence. A CI failure after the review is posted does not start
a new one; comment `/review` to get a review whose findings see it.

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
not address under Description vs. Implementation, each starting with
`Linked issue #N:`. A missing criterion is a question of scope, so it never
becomes a finding or changes the verdict. The summary call does not see the
diff, so it reports only what it can show (a file the criterion names that the
change never touches, or a description that defers it). An entry naming an
issue the PR does not link is rewritten to the linked one. When this section is
present, the bug-fix efficacy check uses it instead of reading the issue again.

A criterion that an earlier pull request already met looks unmet to the summary
call, since no changed file touches it. When the PR description states such a
criterion in a paragraph or list item, says it is already met ("already",
"shipped", "existing code", "is met") with no negation earlier in that clause,
and cites the code as `path:line` or as a
`Type.member` code span (a method or a test), the bot checks each citation
against the repository: the file must exist, must not be added or renamed by
the pull request, and the cited line or member must not be one the pull request
adds. When every citation passes, the criterion moves from Description vs.
Implementation to a "Met by existing code" section that lists what was cited.
Text inside a fenced code block, a blockquote or an HTML comment is not read as
a claim. A criterion with no citation, a citation that cannot be found, or a
repository that cannot be read keeps the entry listed.

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

A deterministic check also recognizes one explicit policy: a selected active
decline saying "We don't pin images by digest in Dockerfiles" (or "We do not")
as its complete text, or followed by "the release pipeline resolves every image
to a digest at deploy time", optionally with "and the deployed manifest carries
that digest". Case, whitespace and a final period may vary. The contraction accepts
a straight or curly apostrophe, or the explicit `dont` spelling. The exact rationale
may follow whitespace or one colon, period, comma or semicolon. Other punctuation,
qualifiers, incomplete text and other rationales are left to the model. A low-risk
mutable-reference finding on that file type, anchored on a `FROM` or external
`COPY --from=` image it names, stays
visible under **Things to double-check** with a note naming the learning id.
The check uses the exact bounded selection sent to the review call. A disabled
feature, a decision outside those caps, another defect class or file type,
an unknown premise, an applicable current digest requirement, or independent
failure or security evidence leaves the finding alone. Linked-issue evidence
can still raise its confidence afterward. Deterministic scan findings skip this
check. Other policies continue to rely on the model's assessment.

The deterministic backstop recognizes complete policy-only titles and descriptions:
the unpinned-base-image title forms with the mutable-tag/rebuild claim or the
identical-image-content reproduction forms. Unknown wording, extra consequences
and linked acceptance-criterion claims leave the finding unchanged. Only case,
whitespace and a final period may vary; arbitrary prefixes, suffixes, punctuation
or negation are not removed. The verifier's exact appended unverified paragraph
stays visible as metadata. Other wording continues to rely on the model's
assessment of the selected learning.

A rebuild losing identical image contents is the accepted policy's own
consequence; a functional failure or a failed CI check still leaves the finding
unchanged. Current instructions mentioning an image, container, Dockerfile or
base subject, or a Dockerfile directive together with digest/SHA wording leave the
finding unchanged, including
declarative rules without a requirement keyword. Wrapped lines, headings and
implicit references across sentences stay together. A Dockerfile-scoped rule
already supplies the subject, so digest/SHA wording in an applicable scoped rule
also leaves the finding unchanged. SHA-family tokens include bare SHA, numeric
suffixes such as SHA1 and SHA512, and hyphenated forms; lookalike words do not
establish digest context. Global artifact-only digest prose and the
ordinary words "from" and "copy" do not make an image-pinning requirement;
ambiguous image requirements leave the finding unchanged.

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
  "bot": { "name": "thrillhousebot", "version": "0.8.0" },
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

The dashboard asks GitHub, with your own sign-in, which installations of the
app you can access and which of their repositories you can read: as a
collaborator of any role, through a team, or through your organization's base
permission. You see exactly those repositories, across every installation, and
anyone who can read none of them sees an access-denied screen. An outside
contributor, or an organization member whose base permission is none, does not
see a private repository GitHub would not show them. With several installations,
the overview and the session history offer an installation filter. Set
`thrillhousebot.dashboard.github.account-owner` to serve only one account's
installation.

Everything the dashboard shows is scoped this way: the session list and its
totals, session details, costs, tokens, the overview, feedback, learnings and
the live feed, which follows a change in your access within five minutes. A
`?repository=` you cannot access returns 403, and a session id from such a
repository returns 404. The overview's skip counts span a whole installation, so
they are shown for a personal account to that account, and for an organization
to its members. Checking membership needs the app's Organization → Members: read
permission; without it the counts stay hidden. Viewing never grants acting:
`/review` and anything else the bot does on request needs write, maintain or
admin on the repository.

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
| `thrillhouse.reviews.total` | Counter: finished reviews by `outcome` (`completed`/`failed`) |

Spans and metrics carry `gen_ai.provider.name`, derived from `AI_BASE_URL`
(`deepseek`, `openai`, `groq`, `openrouter`, …). Loopback and unrecognized
endpoints report `unknown`; set `AI_PROVIDER` to name them (a local `ollama` or
`vllm` server, a proxy, a gateway).

### Prometheus

With `PROMETHEUS_METRICS_ENABLED=true`, the same metrics are also served in
Prometheus text format at `GET /metrics` on the main HTTP port — no OTLP
collector needed:

```yaml
scrape_configs:
  - job_name: thrillhousebot
    static_configs:
      - targets: ["your-bot-host:8080"]
```

Metric names follow the Prometheus conversion of the OTel names above (e.g.
`thrillhouse_ai_cost_total`, `gen_ai_client_token_usage`,
`thrillhouse_reviews_total`).

The endpoint is off by default because it is unauthenticated and reveals model
names, token counts, and spend. On an internet-facing deployment, restrict
`/metrics` at your reverse proxy or firewall before enabling it. While it is
disabled, scrapes return `404`; OTLP export is unaffected.

### Turning telemetry off

Set `OTEL_SDK_DISABLED=true` to turn telemetry off. Nothing is exported, the
bot makes no connection to `OTEL_EXPORTER_ENDPOINT`, and `/metrics` returns
`404` even with `PROMETHEUS_METRICS_ENABLED=true`. The dashboard's cost and
token figures come from the database, so they still work.

## Restricted-network deployment

To keep code inside your network, run the model yourself and allow only the
connections the bot needs. The bot is a GitHub App for GitHub.com, so it can
never be fully offline: it must reach GitHub. GitHub Enterprise Server is not
supported.

### Outbound connections

| Destination | When | Required |
|---|---|---|
| `api.github.com` | Every review: reading the pull request and posting the review | Yes |
| `AI_BASE_URL` | Every review and command. Diffs and context are sent here | Yes. Point it inside your network to keep code there |
| `github.com/login/oauth` (`GITHUB_OAUTH_URL`) | Dashboard login | Only with the dashboard (`GITHUB_CLIENT_ID` set) |
| The Actions artifact and log storage GitHub redirects to | Patch coverage and CI-failure logs | Only with `REVIEW_PATCH_COVERAGE_ENABLED` or `REVIEW_CI_CONTEXT_ENABLED` |
| `NOTIFICATIONS_WEBHOOK_URL` | After a review | Only when set |
| `OTEL_EXPORTER_ENDPOINT` | Telemetry export | No. Point it at your own collector, or set `OTEL_SDK_DISABLED=true` |

The database is the only other connection. The bot does not phone home or check
for updates. GitHub must also be able to send webhooks to the bot, so its
`/api/webhook` must be reachable from GitHub (directly, or through a relay such
as Smee).

### Local model

Run [Ollama](https://ollama.com/) (or any OpenAI-compatible server such as vLLM)
on your network and point the bot at it:

```bash
AI_BASE_URL=http://ollama.internal:11434/v1
AI_MODEL=llama3.2
AI_API_KEY=unused   # required at boot; Ollama ignores it
AI_PROVIDER=ollama  # label for telemetry; loopback and unknown hosts report "unknown"
OTEL_SDK_DISABLED=true
```

From Docker Compose, a server on the host is `http://host.docker.internal:11434/v1`
(on Linux, add `extra_hosts: ["host.docker.internal:host-gateway"]` to the bot's
service). Do not use Ollama's cloud endpoint (`https://ollama.com/v1`) here: it
sends the code off your network. Size `REVIEW_MAX_INPUT_TOKENS` for the local
model's context window, and raise `AI_TIMEOUT` if it is slow.

### Dashboard

The dashboard is served by the bot itself and loads no fonts, scripts or styles
from a CDN. In the viewer's browser, two things reach GitHub: the signed-in
user's avatar (`avatars.githubusercontent.com`) and the links to pull requests.
Login goes through GitHub OAuth, so leave `GITHUB_CLIENT_ID` and
`GITHUB_CLIENT_SECRET` unset to run without the dashboard.

### Installing

The [manifest install](#option-a-manifest-install-recommended) can be served
locally (see **Offline alternative** there). The container images come from
`ghcr.io`; mirror them into your own registry and set the image in
`docker-compose.yml` if the host cannot pull from it.

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
private code. To keep code on your network, see
[Restricted-network deployment](#restricted-network-deployment).

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
- **Dashboard access.** GitHub OAuth only, for users who can read a repository
  where the app is installed; no admin UI or guest mode. A change in someone's
  access reaches the dashboard within five minutes. GitHub lets only the owner of
  a private app sign in with it, so anyone else needs the app to be public; set
  `GITHUB_ALLOWED_ACCOUNTS` then, because anyone can install a public app.
- **Production database.** Container and native builds use PostgreSQL. H2 is for
  `quarkus:dev` and the tests only, and its driver is not packaged in the image.
- **OpenAI-compatible APIs only.** The endpoint must implement the
  chat-completions API that LangChain4j expects.
- **Review output.** At most `THRILLHOUSEBOT_REVIEW_MAX_REVIEW_COMMENTS` (50)
  inline comments per review. Lockfiles (`*.lock`, `package-lock.json`,
  `pnpm-lock.yaml`, `go.sum`), minified bundles and sourcemaps, generated code
  (`*.generated.*`, protobuf output) and build or vendor directories (`target/`,
  `node_modules/`, `dist/`, `build/`, `out/`, `.next/`, `vendor/`,
  `__pycache__/`, `.venv/`, `bin/`, `obj/`) are skipped by default and named in
  the summary's scope note; build manifests (`pom.xml`, `build.gradle`,
  `Cargo.toml`, `package.json`, `build.zig`) are reviewed. See
  `THRILLHOUSEBOT_REVIEW_IGNORED_FILES` and
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
  ghcr.io/devops-thiago/thrillhousebot:v0.8.0

# Provenance (image)
gh attestation verify oci://ghcr.io/devops-thiago/thrillhousebot:v0.8.0 \
  --repo devops-thiago/ThrillhouseBot

# Provenance (a downloaded binary)
gh attestation verify thrillhousebot-v0.8.0-linux-amd64.tar.gz \
  --repo devops-thiago/ThrillhouseBot
```

## Operations

[docs/OPERATIONS.md](docs/OPERATIONS.md) covers what the bot stores and what
it keeps only in memory, the single-replica limit, the health endpoints with
Compose and Kubernetes probes, restarts, backups, and going back to an earlier
release.

## Upgrading

Only the latest release receives fixes, including security fixes. To upgrade,
read the release's section in [CHANGELOG.md](CHANGELOG.md), then point your
deployment at the new image tag and restart it:

```bash
docker compose pull && docker compose up -d
```

Each release's notes start with what changes for an existing installation:
new or renamed settings, changed defaults and database changes. Database
changes are Flyway migrations that run on startup; a database created before
v0.9.0 is recognised as the v0.8.0 schema on its first start. New
settings default to the previous behaviour unless the notes say otherwise. If
you pin a version with `IMAGE_TAG` in `.env`, set it to the new tag before
pulling.

[docs/VERSIONING.md](docs/VERSIONING.md) describes what each kind of release
can change, what counts as the public contract, how a setting is deprecated
and which releases get fixes.

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
unquoted, since quotes are read as part of the key:

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

[GOVERNANCE.md](GOVERNANCE.md) explains how decisions are made, who holds
which role, and how the project continues if the maintainer cannot.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tech stack

| Layer | Choice |
|---|---|
| Framework | Quarkus 3.40 (REST) |
| LLM | Quarkus LangChain4j 1.14 (OpenAI-compatible API) |
| Frontend | Next.js 16 + React 19 (static export) |
| Database | H2 (dev) / PostgreSQL (prod) + Panache |
| Observability | OpenTelemetry |
| Native | GraalVM / Mandrel |
| Container | UBI9-micro (default) / distroless (`-distroless`) |

## Container images

Both images are built from the same native binary and published to GHCR:

- `ghcr.io/devops-thiago/thrillhousebot:latest`: UBI9-micro (default).
- `ghcr.io/devops-thiago/thrillhousebot:latest-distroless`: distroless base
  (`:v0.8.0-distroless`, and so on).
- Snapshot tags: `:snapshot`, `:v<version>-<short-sha>-snapshot` and
  `:<full-sha>`, each with a `-distroless` variant.

Both are multi-arch (linux/amd64, linux/arm64), signed with cosign, and carry
build-provenance attestations (see [Verifying a release](#verifying-a-release)).

## License

Licensed under the [Apache License 2.0](LICENSE) (SPDX: `Apache-2.0`).
