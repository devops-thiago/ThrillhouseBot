# Changelog

All notable changes to ThrillhouseBot.

## [0.3.0] — unreleased


### Added

- **`/describe` command**: ask the bot to generate or improve the PR title and description from the diff. It posts a suggestion comment the author can copy in — it never edits the pull request, so the author's own title and body are never overwritten. Respects the repository instructions file, is write-gated like the other paid commands, and honors a `/pause` (#35)

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

- **Multi-line suggestions**: when a finding's fix replaces several consecutive lines, the bot now posts a multi-line review comment (`start_line`..`line`) so the GitHub suggestion replaces the whole range in one click, instead of anchoring to a single line and mis-applying the rest. The range is derived from the flagged code's position in the diff and falls back to a single-line comment when it can't be resolved (#71)
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
