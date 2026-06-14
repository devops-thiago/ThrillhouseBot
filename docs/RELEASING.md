# Releasing

How a tagged release flows through CI, and how to keep the post-release version
bump fully automated.

## The release flow

Releases are driven by `.github/workflows/release.yml`, triggered by pushing a
semver tag (`v[0-9]+.[0-9]+.[0-9]+`) or re-running it via `workflow_dispatch`
against an existing tag. The jobs run in order:

1. **verify** — validates the tag is semver, checks it matches `pom.xml`, and
   confirms the CI-built images for that commit already exist in GHCR.
2. **scan** — Trivy-scans both image variants, gating on CRITICAL/HIGH.
3. **promote** — retags the commit images to `:vX.Y.Z` (and `:latest` when the
   tag is the highest release), signs them with cosign, and attests provenance.
4. **release** — extracts native binaries, signs the tarballs, pulls notes from
   `CHANGELOG.md`, and creates the GitHub release.
5. **bump-version** — opens a PR moving `main` to the next `-SNAPSHOT` version.

To cut a release: update `CHANGELOG.md`, set the release version in `pom.xml`,
merge, then tag the merge commit `vX.Y.Z` and push the tag.

## Automated version bump

After a release that updates `:latest`, the `bump-version` job pushes a
`chore/bump-<next>-SNAPSHOT` branch and opens a PR to merge it into `main`.

By default, **GitHub Actions cannot open pull requests**: `gh pr create` fails
with `GitHub Actions is not permitted to create or approve pull requests
(createPullRequest)`. The job handles this gracefully — it still pushes the
branch and, when it cannot open the PR, opens a tracking issue
(`Release follow-up: open bump PR for <next>`) so the bump is never lost. You
then open the PR manually with the command in that issue.

### Setup — let releases open the bump PR

In **Settings → Actions → General → Workflow permissions**, enable
**Allow GitHub Actions to create and approve pull requests**. That is the only
configuration needed: it lets the default `GITHUB_TOKEN` open the bump PR, and
it stores no token or key (nothing extra for a compromised action to exfiltrate).

### Merging the bump PR

The bump PR still needs a human to merge it, and two `main` ruleset constraints
shape how:

- **CI does not start on its own.** GitHub's workflow-recursion guard suppresses
  workflow runs for events the default `GITHUB_TOKEN` triggers, so the required
  checks (`format`, `test`, `frontend`, `trivy`) stay pending. Re-trigger them by
  **closing and reopening the PR** (preferred — it keeps your approval, whereas
  pushing a commit dismisses it under `dismiss_stale_reviews_on_push`).
- **Merge with squash or a merge commit, not rebase.** The bump commit is made by
  `github-actions[bot]` and is unsigned; squash/merge produce a GitHub-signed
  commit that satisfies the `required_signatures` rule, while rebase would replay
  the unsigned commit and be rejected.

If you would rather the checks run automatically (no close/reopen), open the PR
with a GitHub App token or a PAT instead of the default `GITHUB_TOKEN` — a
PR authored by a non-`GITHUB_TOKEN` identity does fire `pull_request` CI. That
trades the one-time setting for a stored credential; see
[`actions/create-github-app-token`](https://github.com/actions/create-github-app-token)
and the guidance in `peter-evans/create-pull-request` / `release-please`. The
workflow does not wire this up — it deliberately keeps no release secret.

If the setting above is left disabled, the job degrades gracefully: it pushes
the branch and opens the tracking issue, so the bump is never lost.
