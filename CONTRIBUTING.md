# Contributing to ThrillhouseBot

Thanks for your interest! Everything's coming up Thrillhouse. 🎉

This project follows the
[Contributor Covenant](https://github.com/devops-thiago/ThrillhouseBot/blob/main/CODE_OF_CONDUCT.md).
By taking part you agree to uphold it.

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
CI additionally runs SonarCloud, Trivy, and a Docker build check on pull requests.

The bar, enforced by CI:

- **All tests pass** — and new code comes with tests. Test behavior, not implementation: prefer asserting observable outcomes over `assertDoesNotThrow`, and avoid reflection on private members (relax to package-private instead if a test genuinely needs the seam).
- **Coverage doesn't drop** — JaCoCo, Codecov patch coverage, and the SonarCloud quality gate all run on every PR.
- **SpotBugs clean** at `effort=Max`/`threshold=Low`. If you hit a false positive, prefer restructuring; document any exclusion in `config/spotbugs-exclude.xml` with a justification comment.
- **No new Sonar issues** — the quality gate requires an A reliability/security rating on new code.

## Commit messages

The project uses [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `perf:`, `test:`, `docs:`, `ci:`, `deps:`. One logical change per commit; explain the *why* in the body when it isn't obvious.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Package flow:
`webhook/` → `review/` (`review/ai/`) → `github/` → `dashboard/` (`frontend/`).

## Adding an AI provider

There is no provider-specific code to write. The model is reached through
LangChain4j's OpenAI-compatible client, so a new provider is configuration: point
`AI_BASE_URL` and `AI_MODEL` at the endpoint, and add a matching
`thrillhousebot.ai.pricing.<model>.input-per-1k` / `.output-per-1k` pair in
`application.properties` if you want the dashboard to track its cost. The
[README provider table](https://github.com/devops-thiago/ThrillhouseBot#provider-support)
lists the ones that are known to work.

## Reporting security issues

Please **do not** open a public issue — see
[SECURITY.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/SECURITY.md).
