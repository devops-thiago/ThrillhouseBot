# Changelog

All notable changes to ThrillhouseBot.

## [0.1.1] — unreleased

### Changed

- **CI**: consolidated the seven duplicated Trivy scan + SARIF-upload steps across `ci.yml`, `release.yml`, and `security-scan.yml` into a single `.github/actions/trivy-scan` composite action, centralizing the pinned action SHA, Trivy version, `format: sarif`, and the `limit-severities-for-sarif` flag. The CI filesystem scan now applies `limit-severities-for-sarif` like every other scan (closes the gap tracked in #76).

### Fixed

- **Release bump automation**: the `bump-version` job no longer masks the
  `createPullRequest` permission error. With the repo's "Allow GitHub Actions to
  create and approve pull requests" setting enabled it opens the bump PR using
  the default token; otherwise it pushes the branch and opens a tracking issue
  instead of silently failing. The push is idempotent on release re-runs. New
  release docs in [docs/RELEASING.md](docs/RELEASING.md).

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
