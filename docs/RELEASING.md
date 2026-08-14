# Releasing

How a tagged release flows through CI, and how the post-release version bump
stays automated.

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
`chore/bump-<next>-SNAPSHOT` branch and opens a PR to merge it into `main` with
the default `GITHUB_TOKEN`.

This relies on one repo setting: **Settings → Actions → General → Workflow
permissions → Allow GitHub Actions to create and approve pull requests**
(already enabled on this repo). It lets the default `GITHUB_TOKEN` open the bump
PR and stores no token or key — nothing extra for a compromised action to
exfiltrate, which is why it is preferred over a stored PAT/App-token secret.

If `gh pr create` fails anyway (e.g. the setting was turned off), the job
**fails loudly** instead of masking the error — and because the bump branch is
already pushed, you can open the PR by hand from it.

## Merging the bump PR

The bump PR still needs a human to merge it, and two `main` ruleset constraints
shape how:

- **CI does not start on its own.** GitHub's workflow-recursion guard suppresses
  workflow runs for events the default `GITHUB_TOKEN` triggers, so the required
  checks (`format`, `test`, `frontend`, `trivy`) stay pending. Re-trigger them by
  **closing and reopening the PR** (preferred — it keeps your approval, whereas
  pushing a commit dismisses it under `dismiss_stale_reviews_on_push`).
- **Merge with squash or a merge commit, not rebase.** The bump commit is made by
  `github-actions[bot]` and is unsigned; squash/merge produce a GitHub-signed
  commit that satisfies the `required_signatures` rule, while rebase replays the
  unsigned commit and is rejected.

If you would rather the checks run automatically (no close/reopen), open the PR
with a GitHub App token or a PAT instead of the default `GITHUB_TOKEN` — a PR
authored by a non-`GITHUB_TOKEN` identity does fire `pull_request` CI. That
trades the one-time setting for a stored credential; the workflow deliberately
keeps no release secret.
