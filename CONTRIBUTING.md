# Contributing to ThrillhouseBot

Thanks for your interest! Everything's coming up Thrillhouse. 🎉
<!-- docs:contributing:start -->

This project follows the [Contributor Covenant](https://github.com/devops-thiago/ThrillhouseBot/blob/main/CODE_OF_CONDUCT.md). By taking
part you agree to uphold it.

## Where to start

- **[GitHub Discussions](https://github.com/devops-thiago/ThrillhouseBot/discussions)** — questions, setup help, and general conversation. Start with the pinned [welcome post](https://github.com/devops-thiago/ThrillhouseBot/discussions/1) if you are new.
- Issues labeled [`good first issue`](https://github.com/devops-thiago/ThrillhouseBot/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) are well-scoped and explained in detail.
- Issues labeled [`help wanted`](https://github.com/devops-thiago/ThrillhouseBot/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22) are larger but ready to be picked up.
- For bugs and feature requests, use the [issue templates](https://github.com/devops-thiago/ThrillhouseBot/issues/new/choose). For larger changes, open an issue first so we can discuss the approach before you invest time.

## Development setup

Prerequisites: **Java 25+**, **Node.js 20+** (dashboard only), **Docker** (native builds only). Maven is bundled via the wrapper.

Follow the [README Quick Start](https://github.com/devops-thiago/ThrillhouseBot#quick-start) for cloning, credentials, dev mode, and webhook forwarding — it is the single source of truth for setup commands. Contributor-specific extras:

```bash
cd frontend && npm install && npm run dev   # dashboard with hot reload
```

## Before you open a PR

Run the Java verification locally before opening a PR:

```bash
./mvnw spotless:apply       # google-java-format; CI runs spotless:check
./mvnw verify               # tests + JaCoCo coverage gate + SpotBugs
```

If you changed the dashboard, also run `cd frontend && npm ci && npm run test && npm run build`.

To exercise the UI without a backend, run `cd frontend && npm run dev:mock`.
CI additionally runs Dependency Review (required), SonarCloud, Trivy, and a Docker
build check on pull requests — see **Dual-gate merge policy** below.
To build a disposable test image for a branch or PR (JVM or native, without
touching `latest`), use the **Docker test image** workflow in Actions
(`.github/workflows/docker-test-image.yml`).

The bar, enforced by CI:

- **All tests pass** — and new code comes with tests. Test behavior, not implementation: prefer asserting observable outcomes over `assertDoesNotThrow`, and avoid reflection on private members (relax to package-private instead if a test genuinely needs the seam).
- **Coverage doesn't drop** — JaCoCo, Codecov patch coverage, and the SonarCloud quality gate all run on every PR.
- **SpotBugs clean** at `effort=Max`/`threshold=Low`. If you hit a false positive, prefer restructuring; document any exclusion in `config/spotbugs-exclude.xml` with a justification comment.
- **No new Sonar issues** — the quality gate requires an A reliability/security rating on new code.

## Dual-gate merge policy

ThrillhouseBot's LLM review and the repo's static CI gates are **complementary**, not substitutes. Merge only when **both** are green (or explicitly waived by a maintainer with a written reason on the PR).

| Gate | What it catches | Required on this repo |
|------|-----------------|------------------------|
| **Static** | Dependency CVEs/license regressions ([Dependency Review](https://github.com/devops-thiago/ThrillhouseBot/blob/main/.github/workflows/dependency-review.yml)), filesystem CVEs (Trivy), format/tests/frontend, SpotBugs/Sonar/CodeQL | Yes — `format`, `test`, `frontend`, `trivy`, and `dependency-review` are required status checks on `main`/`develop` |
| **ThrillhouseBot** | Diff narrative, incomplete fixes, framework-context issues static tools miss | Soft gate — wait for the **ThrillhouseBot Review** check / posted review when the App is installed; do not merge solely because CI is green if the bot flagged open threads |

Optional third signal: Cursor Bugbot (or similar) may run on PRs. It is **not** a merge requirement here — useful when present, never a replacement for the two gates above.

This policy does **not** fold linters into the LLM prompt ([#34](https://github.com/devops-thiago/ThrillhouseBot/issues/34)); that work stays separate. The static gate is the CI safety net for supply-chain and compile-time classes of bug the bot can miss.

### When the gates disagree

Maintainers triage; do not "average" the signals away.

| Static | ThrillhouseBot | What to do |
|--------|----------------|------------|
| Red | Green / quiet | **Fix or formally suppress the static finding.** LLM silence is not evidence the CVE is safe. Prefer a real version bump; if the advisory does not apply to this codebase, document the hold (e.g. `osv-scanner.toml` / `.trivyignore` with a full reason — see the Jackson GHSA pattern in [#308](https://github.com/devops-thiago/ThrillhouseBot/pull/308)). |
| Green | Red / open threads | **Address or refute the bot findings** in the PR discussion before merge. Static green does not clear logic/copy/incomplete-fix issues. |
| Red | Red | Clear the **static** gate first (often blocks merge already), then resolve bot threads. |
| Same area, conflicting severity | — | Prefer the **static** tool for dependency/CVE/license facts; prefer the **bot** for intent, call-site context, and "did the PR actually finish the fix?" Prefer neither blindly — leave a short maintainer note on the PR. |

Waivers (rare): only a maintainer may merge with a red soft gate or a documented static suppression, and the PR must record who waived what and why.

## Commit messages

The project uses [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `perf:`, `test:`, `docs:`, `ci:`, `deps:`. One logical change per commit; explain the *why* in the body when it isn't obvious.

## Architecture

See the [architecture overview](https://devops-thiago.github.io/ThrillhouseBot/architecture/)
(source: [docs/ARCHITECTURE.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/docs/ARCHITECTURE.md)). Package flow:
`webhook/` → `review/` (`review/ai/`) → `github/` → `dashboard/` (`frontend/`).

## Adding an AI provider

There is no provider-specific code to write. The model is reached through
LangChain4j's OpenAI-compatible client, so a new provider is configuration: point
`AI_BASE_URL` and `AI_MODEL` at the endpoint, and add a matching
`thrillhousebot.ai.pricing.<model>.input-per-1k` / `.output-per-1k` pair in
`application.properties` if you want the dashboard to track its cost. The
[README provider table](https://github.com/devops-thiago/ThrillhouseBot#provider-support) lists the ones that are known
to work.

## Reporting security issues

Please **do not** open a public issue — see
[SECURITY.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/SECURITY.md).
<!-- docs:contributing:end -->
