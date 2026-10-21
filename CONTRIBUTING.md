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

If you add a configuration key or change one's javadoc, default or alias,
regenerate the configuration reference (`docs/CONFIGURATION.md`) and the
README's most-used settings table:

```bash
./mvnw test -Dtest=ConfigReferenceTest -Dconfig-reference.write=true
```

`ConfigReferenceTest` fails when either file is out of date. A key's
description is the first paragraph of its javadoc in `ThrillhouseConfig`; a
variable that only `application.properties` reads is described by `# doc:`
comment lines directly above its key.

CI runs the same checks, plus actionlint, the DCO sign-off check, Dependency
Review, SonarCloud, Trivy and a Docker build; CodeQL runs on pull requests to `main`. CI builds the GraalVM native image when a pull request
touches `pom.xml`, `src/main/**`, the native integration tests or
`.github/workflows/ci.yml`, then runs the `*IT` integration tests against that
binary. To build a
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
- Every commit is signed off (see
  [Developer Certificate of Origin](#developer-certificate-of-origin)).

## Coding style

Java code follows the
[Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
Spotless formats it with google-java-format on every compile, and the `format`
job in CI runs `spotless:check`, which fails on any file that is not formatted.
There are no formatter exceptions in the code base; do not add `spotless:off`
markers. The dashboard is TypeScript compiled with `strict` on, and the
workflows are checked by actionlint, which also runs shellcheck on their
scripts.

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

## Developer Certificate of Origin

Every commit in a pull request must carry a `Signed-off-by:` line. By adding
it you certify that you wrote the change, or otherwise have the right to submit
it under the project's license, as set out in the
[Developer Certificate of Origin](https://developercertificate.org/). The line
must use the same email address as the commit author. `git commit -s` adds it
for you:

```text
Signed-off-by: Your Name <you@example.com>
```

The `dco` check examines every non-merge commit between the pull request's
base and head. A cryptographic commit signature does not replace the sign-off.
When squash-merging, keep a sign-off matching the resulting commit's author in
the squash message. Do not assume sign-offs on the original commits will be
preserved.

To sign off your own commits on an unpublished topic branch, use
`git rebase --signoff <base-branch>`. Do not rewrite shared release branches
or add another author's certification on their behalf. A missing sign-off in
shared history needs the maintainer and the affected author to agree on its
remediation. Commits that Dependabot or GitHub Actions author in pull requests
they open are exempt; a later human-authored release pull request checks those
commits again.

### GitHub squash commits and release history

Use the same email for local commits, sign-offs and the final squash author. If
GitHub uses your private noreply address, configure this repository's Git
`user.email` to that address before running `git commit -s`. Check the final
squash message for the matching trailer; a trailer in the PR description alone
is not sufficient.

The DCO check can also establish certification from a merged source PR. It
requires a GitHub-signed squash, an exact match between its SHA and the source
PR's merge SHA, and a reconstructed merge tree identical to the squash tree.
Every original contribution must pass DCO. An unsigned source merge must be an
unchanged automatic merge; extra conflict-resolution changes need their own
certification. An existing trailer with a different email is accepted only
when a source commit ties that signed-off email to the same GitHub author,
authenticated by its signature or by that author opening the original PR.
DCO sign-offs on original commits do not require cryptographic signatures.
Account names, PR descriptions and a green historical check alone do not
establish certification. Unavailable evidence fails the check. A bot exemption carries forward only
through its authenticated original bot PR; a bot-shaped email alone never
exempts a commit in a human release PR.

For incomplete evidence in shared history, the affected author can add a new,
cryptographically signed and signed-off commit containing one trailer for each
full commit SHA they explicitly certify:

```text
DCO-Remediation: <full 40-character commit SHA>
Signed-off-by: Your Name <email-matching-this-new-commit>
```

The new commit must be in the checked PR range. GitHub must authenticate its
signature and associate its author with the same account as the commit being
certified. A maintainer cannot add someone else's certification. Do not rewrite
shared release history to repair a sign-off. The check prints the source PR or
remediation used for each accepted commit and lists any remaining gaps.

## Governance

The project's decision process, roles and continuity plan are in
[GOVERNANCE.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/GOVERNANCE.md).

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

Check any change to the review, verifier, summary check or repair prompts
(`PrReviewPrompts`, `FindingVerifierPrompts`, `SummaryVerifierPrompts`,
`ResponseRepairPrompts`) against the labeled corpus in
`src/test/resources/evalcorpus/` before it ships. Each case directory holds a
`case.json` spec and a `diff.txt` in the format the review pipeline sends to the
model, and pins a real outcome from reviews of this repository:

- A verifier case sends a candidate finding through the second-pass check and
  expects a verdict (`confirmed`, `downgraded` or `rejected`).
- A generator case runs the first-pass review prompt over the diff and expects a
  finding that matches the case's keywords on the target file (`must-find`), or
  no such finding (`must-not-find`).
- A summary case runs the summary prompt and checks where a description claim
  ends up (`claim-gapped` or `claim-not-gapped`).
- A summary verification case sends a written summary through the opt-in
  summary check with the diff, and expects the statements naming its keywords
  to be cut while those naming its kept keywords stay (`contradicted-cut`), or
  nothing to be cut (`nothing-cut`).
- A repair case sends a failed answer to the repair step. A review answer the
  parser refuses must come back with one finding per keyword, each titled by it
  (`repaired`); a summary answer whose `file_summaries` is a string must come
  back with a row for each keyword path and no other (`file-summaries-read`).

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

`-Deval.only=<part>,<part>` runs only the cases whose name contains one of the
parts. A change to the summary check prompt (`SummaryVerifierPrompts`) needs
only its cases: `-Deval.only=summary-verification`. A change to the repair prompt
(`ResponseRepairPrompts`) needs `-Deval.only=-repair`.

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
