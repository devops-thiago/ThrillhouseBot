# Releasing

How a release is prepared on a release branch, how the tag flows through CI, and
how the version bump after it works.

## Preparing a release

A release is prepared on its own branch, `release/vX.Y.Z`, cut from `main`.

1. Create `release/vX.Y.Z` from `main`. The `release` ruleset protects every
   `release/**` branch: changes go in through pull requests, merged by rebase,
   with signed commits, and the branch cannot be deleted or force-pushed.
2. Open the milestone's pull requests against `release/vX.Y.Z`. CI and
   Dependency Review run on them as they do for `main`. The ruleset has no
   required checks, so hold each one to the `main` bar before merging: CI
   green and the ThrillhouseBot review approved on the final head.
3. Open a release-preparation pull request into the release branch. It sets
   the release version in `pom.xml`, renames `## [Unreleased]` in
   `CHANGELOG.md` to `## [X.Y.Z] — YYYY-MM-DD` with an empty `## [Unreleased]`
   left above it, and freezes the outgoing docs version (see
   [Freezing the docs version](#freezing-the-docs-version)).
4. Documentation updates for the release also go into the release branch,
   before the release pull request is opened.
5. Open the release pull request from `release/vX.Y.Z` into `main`. `main`
   requires `format`, `test`, `frontend`, `trivy` and `dependency-review` to
   pass, one approving review from a code owner, and every review thread
   resolved.
6. Merge it. The repository and the `main` ruleset currently allow squash
   merges only, which puts the whole release on `main` as one commit. To keep
   each pull request's commit on `main`, enable merge commits for this merge.
7. Wait for CI on `main` to finish. It publishes the images that the release
   workflow promotes, tagged with the commit SHA.
8. Tag the merge commit on `main` as `vX.Y.Z` and push the tag. That starts the
   release workflow.

## The release workflow

`.github/workflows/release.yml` runs when a tag matching `v[0-9]+.[0-9]+.[0-9]+`
is pushed. It can also be re-run with `workflow_dispatch` against an existing
tag. Its jobs:

1. `verify` checks that the tag is semver and matches the `pom.xml` version,
   and that CI has already published both images for the tagged commit to GHCR.
   If an image is missing, wait for `main` CI to finish and re-run.
2. `scan` runs Trivy on both images and fails on CRITICAL or HIGH findings. It
   uploads nothing to code scanning, because code scanning judges a category's
   freshness against `main`, and a category that only tags update shows as
   stale in the Security tab (#869). The SARIF reports are kept as the run's
   `trivy-release-sarif` artifact, and a failed gate names the findings there.
   The Security tab's image results come from `ci.yml` (`trivy-image`,
   `trivy-image-distroless`) and `security-scan.yml`, which scan `main`.
3. `promote` retags the commit's images as `:vX.Y.Z`, signs them with cosign and
   attests provenance. It also moves `:latest` when the tag is the highest
   `vX.Y.Z` release; a pre-release or floating tag such as `v0.6.8-rc1` or
   `nightly` does not count. The decision is written to the run summary either
   way.
4. `release` extracts the native binaries, signs the tarballs, takes the notes
   from `CHANGELOG.md` and creates the GitHub release.
5. `publish-docs` deploys the docs site from the tag (see
   [Publishing the docs](#publishing-the-docs)).
6. `bump-version` opens a pull request moving `main` to the next `-SNAPSHOT`
   version.

`publish-docs` and `bump-version` run only when `promote` moved `:latest`.

## Freezing the docs version

The docs site's version picker reads `website/versions.json`, and each archived
version is a snapshot under `website/src/content/docs/<slug>/`. CI produces
neither, so the release-preparation pull request has to freeze the outgoing
version by hand. Without it, the site shows the new version's docs under the old
version's label. This step was missed at 0.4.0, 0.5.0, 0.6.0 and 0.6.1.

Archive the version being replaced (here `A.B.C`, the previous release) from
its own tag. `archive-docs-version.mjs` expands the include markers when it
runs, so archiving from the working tree would capture the new docs instead:

```bash
git restore --source vA.B.C -- README.md CONTRIBUTING.md docs/ website/src/content/docs/
cd website && npm run docs:archive -- A.B.C
cd .. && git restore --source HEAD -- README.md CONTRIBUTING.md docs/ website/src/content/docs/
```

Then set `current.label` in `website/versions.json` to `vX.Y.Z`, the version
being released, and check that `npm run build` picks up the new pages.

Run `git status` after the second restore. Restoring from a tag brings back any
file deleted since that tag, so a change outside the new archive directories and
`versions.json` is a file the release should not bring back.

## Publishing the docs

`.github/workflows/docs.yml` deploys the live site from a release tag, so the
site follows releases, not `main`. Two things make that work.

### The release workflow dispatches the deploy

`docs.yml` declares a `release: published` trigger, but it does not fire for
releases the `release` job creates. That job uses the default `GITHUB_TOKEN`, and
GitHub starts no workflow runs for events raised with that token. The same rule
keeps CI from starting on the bump pull request. Every release through v0.6.2
had its docs published by hand for this reason. The trigger stays because a
release published by hand in the UI does fire it.

The `publish-docs` job in `release.yml` dispatches `docs.yml` against the tag
after the release is created; `workflow_dispatch` is exempt from the rule. It
runs only for the highest release, so a patch on an older line cannot replace
the site with its docs. Only `vX.Y.Z` tags count towards "highest": `sort -V`
ranks `v0.6.8-rc1` and `nightly` above `v0.6.8`, and would otherwise make a real
release look older and skip the deploy. When `publish-docs` is skipped, the
`promote` job writes the decision and the version that outranked the tag to the
run summary and to a notice annotation.

`gh workflow run` returns once GitHub accepts the dispatch, so the job then
waits for the dispatched run and fails if it fails. The dispatch returns no run
ID, so the job polls for a `docs.yml` run on the tag created at or after the
dispatch. That also keeps an earlier manual republish of the same tag from
being taken for it.

### The tag must be allowed to deploy

The build runs against the tag, so the `github-pages` environment must accept
tags. Under Settings → Environments → github-pages → Deployment branches and
tags, the policies are:

```
branch: main
tag: v*
```

Without the tag rule, the deploy fails at its last step with:

> Tag "vX.Y.Z" is not allowed to deploy to github-pages due to environment
> protection rules.

That happened to every release through v0.6.1. Do not work around it by
dispatching the workflow from `main`: that publishes `main`'s docs, not the
release's. Fix the policy and re-run against the tag. To list the policies:

```bash
gh api repos/<owner>/<repo>/environments/github-pages/deployment-branch-policies
```

To republish a released version by hand, for example after a docs fix on its
tag:

```bash
gh workflow run docs.yml --ref vX.Y.Z
```

## The version bump

After a release that moves `:latest`, the `bump-version` job pushes a
`chore/bump-<next>-SNAPSHOT` branch, where `<next>` is the next patch version,
and opens a pull request into `main` with the default `GITHUB_TOKEN`. It skips
the bump when `main` is already at that version or later.

Opening the pull request depends on one repository setting: Settings → Actions
→ General → Workflow permissions → "Allow GitHub Actions to create and approve
pull requests", which is enabled on this repository. With it, the job needs no
stored token or key, so a compromised action has no extra secret to steal. That
is why the setting is preferred over a stored PAT or App token.

If `gh pr create` fails, for example because the setting was turned off, the job
fails. The branch is already pushed, so you can open the pull request from it by
hand.

## Merging the bump pull request

A maintainer merges the bump pull request. Two `main` rules affect how:

- CI does not start by itself. GitHub starts no workflow runs for events raised
  with the default `GITHUB_TOKEN`, so the required checks (`format`, `test`,
  `frontend`, `trivy`, `dependency-review`) stay pending. Close and reopen the
  pull request to start them. Pushing a commit also works but dismisses your
  approval (`dismiss_stale_reviews_on_push`).
- Squash-merge it. The bump commit is made by `github-actions[bot]` and is
  unsigned. A squash merge creates a commit that GitHub signs, which satisfies
  the `required_signatures` rule.

To have the checks start without closing and reopening, open the pull request
with a GitHub App token or a PAT instead of the default `GITHUB_TOKEN`; a pull
request from any other identity starts `pull_request` CI. That trades the
repository setting for a stored credential, and the workflow deliberately keeps
no release secret.
