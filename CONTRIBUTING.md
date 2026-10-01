# Contributing to ThrillhouseBot

Thanks for your interest. Everything's coming up Thrillhouse.
<!-- docs:contributing:start -->

This project follows the [Contributor Covenant](https://github.com/devops-thiago/ThrillhouseBot/blob/main/CODE_OF_CONDUCT.md). By taking
part you agree to uphold it.

## Where to start

- Ask questions and get setup help in [GitHub Discussions](https://github.com/devops-thiago/ThrillhouseBot/discussions). If you are new, read the pinned [welcome post](https://github.com/devops-thiago/ThrillhouseBot/discussions/1) first.
- Issues labeled [`good first issue`](https://github.com/devops-thiago/ThrillhouseBot/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) are small and explained in detail.
- Issues labeled [`help wanted`](https://github.com/devops-thiago/ThrillhouseBot/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22) are larger but ready to pick up.
- Report bugs and request features with the [issue templates](https://github.com/devops-thiago/ThrillhouseBot/issues/new/choose). For a larger change, open an issue first so the approach can be agreed before you write code.

## Development setup

You need Java 25 or later, Node.js 22 or later for the dashboard, and Docker for
native builds. Maven comes with the wrapper (`./mvnw`).

The [README Quick Start](https://github.com/devops-thiago/ThrillhouseBot#quick-start)
covers cloning, credentials, dev mode and webhook forwarding. To work on the
dashboard with hot reload:

```bash
cd frontend && npm install && npm run dev
```

To run the dashboard against mock data, without a backend, use
`npm run dev:mock` instead.

## Branches

Open pull requests against `main`. While a release is being prepared, the
maintainer may ask for a pull request to target that release's
`release/vX.Y.Z` branch instead; see
[RELEASING.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/docs/RELEASING.md).

## Before you open a pull request

Run the Java checks locally:

```bash
./mvnw spotless:apply       # google-java-format; CI runs spotless:check
./mvnw verify               # tests, JaCoCo coverage gate, SpotBugs
```

If you changed the dashboard, also run
`cd frontend && npm ci && npm test && npm run build`. If you changed the docs
site or any page it includes (README, CONTRIBUTING, `docs/`), run
`cd website && npm ci && npm run build`; the build fails on a broken link.

CI runs the same checks, plus actionlint, Dependency Review, SonarCloud, Trivy
and a Docker build; CodeQL runs on pull requests to `main`. CI builds the GraalVM native image when a pull request
touches `pom.xml`, `src/main/**` or `.github/workflows/ci.yml`. To build a
throwaway test image for a branch or pull request without touching `latest`, run
the Docker test image workflow (`.github/workflows/docker-test-image.yml`) from
the Actions tab.

CI enforces this bar:

- All tests pass, and new code has tests. Assert observable outcomes rather than
  `assertDoesNotThrow`, and do not use reflection on private members; make the
  member package-private if a test needs it.
- Coverage does not drop. JaCoCo, Codecov patch coverage and the SonarCloud
  quality gate run on every pull request.
- SpotBugs is clean at `effort=Max` and `threshold=Low`. For a false positive,
  restructure the code first. An exclusion goes in `config/spotbugs-exclude.xml`
  with a comment explaining it.
- No new Sonar issues. The quality gate requires an A reliability and security
  rating on new code.

## Merge policy: two gates

ThrillhouseBot's own review and the static CI checks catch different problems,
so a pull request needs both to pass. A maintainer can waive one, with a written
reason on the pull request.

| Gate | What it catches | Required |
|------|-----------------|----------|
| Static CI | Vulnerable or wrongly licensed dependencies ([Dependency Review](https://github.com/devops-thiago/ThrillhouseBot/blob/main/.github/workflows/dependency-review.yml)), filesystem CVEs (Trivy), format, tests, frontend, SpotBugs, Sonar, CodeQL | `format`, `test`, `frontend`, `trivy` and `dependency-review` are required status checks on `main` and `develop` |
| ThrillhouseBot | Gaps between the description and the change, incomplete fixes, issues that need framework context | Wait for the `ThrillhouseBot Review` check and the posted review. Green CI does not clear open bot threads |

Other review bots, such as Cursor Bugbot, may comment on a pull request. They
are not a merge requirement.

The static gate stays separate from the bot. Linter output is not fed into the
review prompt ([#34](https://github.com/devops-thiago/ThrillhouseBot/issues/34)).

### When the gates disagree

A maintainer decides; neither signal cancels the other.

| Static | ThrillhouseBot | What to do |
|--------|----------------|------------|
| Red | Green or quiet | Fix the static finding or suppress it formally. The bot's silence says nothing about a CVE. Prefer a version bump. If the advisory does not apply, record the exception in the file the failing tool reads: `allow-ghsas` or `.github/dependency-review-config.yml` for Dependency Review, `.trivyignore` for Trivy, `osv-scanner.toml` for OpenSSF Scorecard and osv-scanner (see [#308](https://github.com/devops-thiago/ThrillhouseBot/pull/308) for an example). |
| Green | Red or open threads | Fix or refute each bot finding on the pull request before merging. |
| Red | Red | Clear the static gate first, then resolve the bot threads. |
| Same area, different severity | | Trust the static tool on dependency, CVE and license facts, and the bot on intent, call-site context and whether the fix is complete. Leave a short note on the pull request either way. |

Only a maintainer can merge with a red bot gate or a suppressed static finding,
and the pull request must record who waived what and why.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/): `feat`,
`fix`, `perf`, `refactor`, `test`, `docs`, `ci`, `build`, `deps` and `chore`,
usually with a scope, for example `fix(review): …`. Make one logical change per
commit, and say why in the body when the subject does not.

## Architecture

See the [architecture overview](https://devops-thiago.github.io/ThrillhouseBot/architecture/)
(source: [docs/ARCHITECTURE.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/docs/ARCHITECTURE.md)).
A review flows `webhook/` → `review/` (model calls in `review/ai/`) → `github/`.
`dashboard/` serves the API for the `frontend/` app, `notification/` sends the
outgoing review-outcome webhooks, and `config/` holds configuration and its
startup checks.

Before changing the review pipeline, prompts or model calls, read
[AGENTS.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/AGENTS.md). It lists the
invariants the build and tests enforce, where each pipeline stage lives, and the
approaches that were tried and rejected.

## Adding an AI provider

A new provider needs configuration, not code. The bot uses LangChain4j's
OpenAI-compatible client, so set `AI_BASE_URL` and `AI_MODEL` to the provider's
endpoint and model. For the dashboard to track cost, add a
`thrillhousebot.ai.pricing.<model>.input-per-1k` and `.output-per-1k` pair to
`application.properties`. If the model's context window or output cap matters,
add its `thrillhousebot.ai.models.<model>.*` entry as well. The
[README provider table](https://github.com/devops-thiago/ThrillhouseBot#provider-support)
lists providers known to work.

## Prompt eval corpus

Check any change to the review or verifier prompts (`PrReviewPrompts`,
`FindingVerifierPrompts`) against the labeled corpus in
`src/test/resources/evalcorpus/` before it ships. Each case directory holds a
`case.json` spec and a `diff.txt` in the format the review pipeline sends to the
model, and pins a real outcome from reviews of this repository:

- A verifier case sends a candidate finding through the second-pass check and
  expects a verdict (`confirmed`, `downgraded` or `rejected`).
- A generator case runs the first-pass review prompt over the diff and expects a
  finding that matches the case's keywords on the target file (`must-find`), or
  no such finding (`must-not-find`).

`EvalCorpusTest` validates the corpus schema in every build. The live suite calls
a real model, so it only runs on request:

```bash
QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=<key> \
AI_BASE_URL=<provider endpoint> AI_MODEL=<model> \
./mvnw test -Peval -Dtest=PromptEvalTest
```

Set `AI_BASE_URL` and `AI_MODEL` to a model the provider serves today. Without
them the suite uses the application defaults, and a retired model name makes
every case fail. Set `REVIEW_DIMENSION_ROUTING_ENABLED=true` as well to test the
routed prompts; run that before turning routing on anywhere.

Each case runs `-Deval.samples` times (default 3) and passes on a majority.
`-Deval.tolerated` (default 0) lets that many cases fail, so a known failure that
an open prompt issue tracks does not block unrelated prompt work. The verifier
fails open: a provider error during a verifier sample counts as `confirmed`, so
check the log for a warning before trusting a failed verifier case.

To add a case, create a directory under `evalcorpus/` with `case.json` and
`diff.txt`, copying the shape of an existing case. Take cases from resolved
review threads: a refuted false positive becomes
`expectedVerdicts: ["rejected"]`, a confirmed true positive `["confirmed"]`.
Record where the case came from in `why`.

## Reporting security issues

Do not open a public issue. Follow
[SECURITY.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/SECURITY.md).
<!-- docs:contributing:end -->
