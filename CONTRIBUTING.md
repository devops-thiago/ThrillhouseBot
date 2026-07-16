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
CI additionally runs SonarCloud, Trivy, and a Docker build check on pull requests.
To build a disposable test image for a branch or PR (JVM or native, without
touching `latest`), use the **Docker test image** workflow in Actions
(`.github/workflows/docker-test-image.yml`).

The bar, enforced by CI:

- **All tests pass** — and new code comes with tests. Test behavior, not implementation: prefer asserting observable outcomes over `assertDoesNotThrow`, and avoid reflection on private members (relax to package-private instead if a test genuinely needs the seam).
- **Coverage doesn't drop** — JaCoCo, Codecov patch coverage, and the SonarCloud quality gate all run on every PR.
- **SpotBugs clean** at `effort=Max`/`threshold=Low`. If you hit a false positive, prefer restructuring; document any exclusion in `config/spotbugs-exclude.xml` with a justification comment.
- **No new Sonar issues** — the quality gate requires an A reliability/security rating on new code.

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

## Prompt eval corpus

Changes to the review or verifier prompts (`PrReviewPrompts`, `FindingVerifierPrompts`)
should be checked against the labeled regression corpus in
`src/test/resources/evalcorpus/` before they ship. Each case directory pins a real
dogfood outcome — a `case.json` spec plus `diff.txt` in the exact format the review
pipeline sends to the model:

- **verifier** cases feed a candidate finding through the second-pass audit and assert
  the expected verdict (`confirmed` / `downgraded` / `rejected`);
- **generator** cases run the first-pass review prompt over a diff and assert that a
  finding matching the case's keywords is (`must-find`) or is not (`must-not-find`)
  raised on the target file.

The corpus schema is validated by `EvalCorpusTest` in every build. The live suite is
opt-in — it calls the configured AI provider:

```bash
QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=<key> ./mvnw test -Peval -Dtest=PromptEvalTest
```

Point `AI_BASE_URL` / `AI_MODEL` at the provider you want to evaluate (defaults apply
otherwise). Each case is sampled `-Deval.samples` times (default 3) and judged by
majority; `-Deval.tolerated` (default 0) allows a known-unfixed label — one tracked by
an open prompt-hardening issue — to fail without blocking unrelated prompt work.

To add a case, create a new directory under `evalcorpus/` with `case.json` and
`diff.txt` (see an existing case for the shape). Source new cases from resolved PR
review threads: a refuted false positive becomes `expectedVerdicts: ["rejected"]`, a
confirmed true positive `["confirmed"]`, and note the provenance in `why`.

## Reporting security issues

Please **do not** open a public issue — see
[SECURITY.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/SECURITY.md).
<!-- docs:contributing:end -->
