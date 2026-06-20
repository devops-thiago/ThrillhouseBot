# Changelog

All notable changes to ThrillhouseBot.

## [Unreleased]

This release makes the bot interactive and controllable from the PR — conversational replies, comment commands, context-aware labels, and configurable triggers — and hardens startup and the manual-review path.

### Added

- **Conversational replies**: a maintainer can now reply to one of the bot's review findings, or `@thrillhousebot` it anywhere in a PR thread, and the bot answers in context — pulling in the original finding, the surrounding diff, and the prior thread replies — instead of having to re-run the whole review. Replies are posted back into the same review thread (or as a PR comment for top-level mentions), gated to the same write-access/allowlisted users as a manual `/review`, and can be turned off with `REVIEW_CONVERSATIONAL_REPLIES_ENABLED=false`. Requires subscribing the GitHub App to the new `pull_request_review_comment` event (added to `manifest.json`) (#31)
- **Comment commands**: drive the bot from a PR with `/help`, `/summary`, `/resolve`, `/pause`, and `/resume` (each also accepts the `@Thrillhousebot <command>` mention form). `/pause` silences the bot on a PR — skipping automatic reviews and ignoring `/review` and `/summary` — until `/resume`; `/resolve` resolves the bot's open finding threads; `/summary` posts the PR summary if one was not generated yet. Every command except `/help` requires repository write access (#32)
- **Context-aware PR labels** (opt-in): the model is shown the repository's existing labels and picks the few that best describe the change. Off by default (`REVIEW_LABELS_ENABLED`); when on, it either posts a one-line suggestion comment or applies the labels (`REVIEW_LABELS_APPLY`), with optional creation of new labels (`REVIEW_LABELS_ALLOW_CREATE`) and a per-PR cap (`REVIEW_LABELS_MAX`, default 3). Labelling is best-effort and never blocks a review (#61)
- **Configurable review triggers**: narrow which pull requests are auto-reviewed — skip drafts (`WEBHOOK_SKIP_DRAFTS`), gate on labels (`WEBHOOK_REQUIRED_LABELS` / `WEBHOOK_EXCLUDED_LABELS`), and filter by base-branch glob (`WEBHOOK_BASE_BRANCHES` / `WEBHOOK_IGNORED_BASE_BRANCHES`). Defaults review every PR, matching prior behavior; a manual `/review` always bypasses the filters (#40)
- **Review on ready-for-review**: a draft PR marked "Ready for review" is reviewed immediately, pairing with `WEBHOOK_SKIP_DRAFTS` so drafts can be skipped until they are ready (#72)
- **Fail-fast configuration validation**: required configuration (`GITHUB_APP_ID`, `GITHUB_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `AI_API_KEY`) is validated at startup, and the app refuses to boot with a single message naming every missing or malformed value — including a non-numeric App id or a private key that is not valid PEM RSA — instead of failing later on the first webhook (#27)
- **Configurable bot identity**: the bot's own account login(s) are configurable via `GITHUB_BOT_LOGINS`, so loop protection and `/resolve` keep working when the App is deployed under a different slug (#165)

### Changed

- **Manual-trigger authorization is time-bounded**: the write-access check for a manual `/review` (installation-token mint + collaborator-permission call) now runs under a configurable timeout (`MANUAL_TRIGGER_AUTH_TIMEOUT`, default `5s`) on the webhook ack thread and fails closed if GitHub is too slow, so a degraded GitHub can no longer tie up a webhook worker past the delivery SLA (#92)
- **CI — actionlint guardrail**: workflows and the consolidated Trivy composite action are linted (including inline shell via shellcheck), with the release-gate scan path mirrored so it is validated on PR CI (#93)
- **CI — faster pipeline**: SpotBugs moved off the test job's critical path into the parallel lint job, the test job collapsed into a single Maven reactor, and the native build + image publish skipped for docs-only pushes to `main` (#170)
- **CI — SonarCloud scoping**: the Sonar scan runs only on `main` and same-repo pull requests (matching the SonarCloud community plan), and a `.dockerignore` keeps the Docker build context small (#165)

### Fixed

- **AI prompts dropped every context variable but the first**: each AI service (`PrReviewer`, `ReplyAssistant`, `FindingVerifier`) declared `@UserMessage` on a method *parameter*, which makes quarkus-langchain4j send only that parameter's raw value as the user message and never render the prompt template. So reviews ran on the diff alone — silently ignoring the repository instructions (`.github/thrillhousebot.md`), project stack, PR title/description, base comparison, related tests, and previous findings — the finding verifier audited candidates without the diff, and conversational replies saw only the maintainer's question with no diff, finding, or thread. Moved `@UserMessage` to the method so every `@V` variable is interpolated, and reduced `PromptTemplateEscaper` to marker-neutralization (its Qute unparsed-section wrapper was never stripped for data-bound values and corrupted any content containing `|}`). Added end-to-end and structural regression tests that pin the rendered prompt (#186)
- **Reviewer corrupted the marker-handling code it was reviewing**: the prompt-injection defense rewrote the diff-section delimiters (`<<<DIFF_START>>>` / `<<<DIFF_END>>>`) found *inside* the diff, so whenever the bot reviewed code that legitimately contains those markers — the escaper, the prompt templates, and any PR that edits them — it saw altered source. That produced false "contradictory assertion"/no-op findings and silently degraded review accuracy of exactly those files. Replaced the fixed delimiters with a per-review unguessable random fence around the diff (the "random sequence enclosure"/spotlighting defense) and now pass the diff byte-exact; the small prose context slots keep the lightweight marker-neutralization as defense-in-depth (#187)
- **Large PRs were silently truncated to 30 files**: `getPullRequestFiles` fetched only GitHub's default first page, so any PR with more than 30 changed files was reviewed — and described / changelog'd / replied to — on a partial diff, with no warning. It now paginates (100 files per page, bounded at 30 pages) so the whole diff is assembled before review (#190)

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
