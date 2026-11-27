# Releasing

How a release is prepared on a release branch, how the tag flows through CI, and
how the version bump after it works.

## Preparing a release

A minor or major release is prepared on its own branch, `release/vX.Y.Z`, cut
from `main`. A patch release does not need one: its pull requests are
squash-merged straight into `main`.

Pull requests are squash-merged into `main` and into release branches. The only
rebase merge is the release pull request from `release/vX.Y.Z` into `main`, so
that each change keeps its own commit on `main`. Preserve a DCO sign-off
matching the resulting squash commit's author. GitHub's verified signature
alone does not satisfy DCO. Check the complete release range before marking
the release pull request ready: the DCO job checks its history again, including
commits from bot-authored pull requests.

1. Create `release/vX.Y.Z` from `main`. The `release` ruleset protects every
   `release/**` branch: changes go in through pull requests, squash-merged,
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
   requires `format`, `test`, `frontend`, `trivy`, `dependency-review` and
   `dco` to pass, one approving review from a code owner, and every review thread
   resolved.
6. Merge it with a rebase merge, which puts each squashed pull request from
   the release branch on `main` as its own commit.
7. Wait for CI on `main` to finish. It publishes the images that the release
   workflow promotes, tagged with the commit SHA, and attaches the provenance
   the release workflow asks for. Do not re-run that CI run's `docker` job once
   it has passed: a re-run rebuilds the images under the same tags.
8. Tag the last commit the rebase put on `main` as `vX.Y.Z` with a signed
   tag and push it. That starts the release workflow. The workflow refuses a
   tag that is unsigned, lightweight, or on a commit that is not on `main`.

   ```bash
   git tag -s vX.Y.Z -m vX.Y.Z <last-release-commit>
   git tag -v vX.Y.Z
   git push origin vX.Y.Z
   ```

## Verifying a release tag

Release tags from v0.6.8 on are signed with the maintainer's SSH signing key,
except v0.7.0 and v0.7.1, which were pushed unsigned. GitHub publishes the
key, so anyone can check a tag:

```bash
gh api users/devops-thiago/ssh_signing_keys \
  --jq '.[] | "* " + .key' > allowed_signers
git -c gpg.ssh.allowedSignersFile=allowed_signers tag -v vX.Y.Z
```

The release workflow makes the same check before it publishes anything. It
reads the SSH signing keys GitHub lists for the accounts named in
`RELEASE_TAG_SIGNERS` in `release.yml`, and accepts a tag only when one of
those keys signed it. To let another maintainer cut releases, add their login
there; the key has to be registered on their account as a signing key. A GPG
signature is not accepted. Removing a key from the account makes the tags it
signed fail this check from then on.

If a tag was pushed unsigned, the release stops in `verify` and publishes
nothing. Delete the tag, sign it and push it again; with the tag ruleset
described under [Repository settings](#repository-settings) only an
administrator can delete it.

The images and binaries a release publishes are signed separately with cosign;
see [Verifying a release](../README.md#verifying-a-release).

## The release workflow

`.github/workflows/release.yml` runs when a tag matching `v[0-9]+.[0-9]+.[0-9]+`
is pushed. It can also be re-run with `workflow_dispatch` against an existing
tag. Its jobs:

1. `verify` decides whether the tag may be released and fixes what is
   released. It runs `scripts/release/check-tag.mjs` from the workflow's own
   ref, which checks that the tag is `vX.Y.Z`, is a signed annotated tag (see
   [Verifying a release tag](#verifying-a-release-tag)), points at a commit on
   `main` and matches the `pom.xml` version. It builds nothing from the tagged
   tree. It then resolves the two images CI pushed for the commit to their
   digests and checks each one's provenance with `gh attestation verify`: signed
   by `ci.yml`, on `refs/heads/main`, from exactly the tagged commit. An image
   that something else pushed under the commit's tag has no such provenance and
   stops the release. If an image or its provenance is missing, wait for `main`
   CI to finish and re-run.
2. `scan` runs Trivy on both images, by digest, and fails on CRITICAL or HIGH
   findings. It
   uploads nothing to code scanning, because code scanning judges a category's
   freshness against `main`, and a category that only tags update shows as
   stale in the Security tab (#869). The SARIF reports are kept as the run's
   `trivy-release-sarif` artifact, and a failed gate names the findings there.
   The Security tab's image results come from `ci.yml` (`trivy-image`,
   `trivy-image-distroless`) and `security-scan.yml`, which scan `main`.
3. `promote` tags those digests as `:vX.Y.Z`, signs them with cosign and
   attests provenance. It never moves a version tag that already names other
   images. It also moves `:latest` when the tag is the highest
   `vX.Y.Z` release; a pre-release or floating tag such as `v0.6.8-rc1` or
   `nightly` does not count. The decision and the digests are written to the
   run summary.
4. `release` extracts the native binaries from the same digest, signs the
   tarballs, takes the notes from `CHANGELOG.md` and creates the GitHub release.
5. `publish-docs` deploys the docs site from the tag (see
   [Publishing the docs](#publishing-the-docs)).
6. `bump-version` opens a pull request moving `main` to the next `-SNAPSHOT`
   version.

`publish-docs` and `bump-version` run only when `promote` moved `:latest`.

`promote` and `release` run in the `release` environment, so that environment's
protection rules apply to the two jobs that publish. With required reviewers
set, each of the two waits for an approval.

### Running a release again

A failed run can be re-run from the Actions page, or started again with
`workflow_dispatch` and the tag as `version`. Start a manual run from `main` or
from the tag itself; the workflow refuses any other ref. A run from `main` uses
`main`'s workflow file and signs as `release.yml@refs/heads/main`, which the
verification commands in the README accept.

A second run repeats the same checks and publishes the same digests: the
version tags stay where they are, the images get another signature and
attestation, and the release assets and notes are replaced.

Two cases stop a second run:

- The images for the commit changed since the first run, because the `docker`
  job of `main` CI was run again and rebuilt them. `promote` refuses to move
  `:vX.Y.Z` to the new images. The published images stay as they are, and
  they are what users have already pulled and verified, so cut a new patch
  release instead of replacing them. GHCR has no way to drop one tag: deleting
  the package version removes every tag on it, `:latest` included when it
  points there.
- The release is v0.9.3 or older. Those images were built before CI attached
  provenance, so `verify` cannot tell who built them and stops. They cannot be
  released again by the workflow. Their published images, signatures and
  binaries are unaffected.

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

The workflow builds the ref it was started on and takes no other ref as input,
so these two policies decide what can reach the live site.

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
- Squash-merge it. The bump commit is made by `github-actions[bot]`. It carries
  a `Signed-off-by` trailer for that address, which the DCO check accepts, but
  no cryptographic signature. A squash merge creates a commit that GitHub signs,
  which satisfies the `required_signatures` rule.

To have the checks start without closing and reopening, open the pull request
with a GitHub App token or a PAT instead of the default `GITHUB_TOKEN`; a pull
request from any other identity starts `pull_request` CI. That trades the
repository setting for a stored credential, and the workflow deliberately keeps
no release secret.

## Repository settings

The workflows check what they can. The rest is configuration that only a
repository administrator can set, and the checks above assume it.

- **Tag ruleset for `v*`** (Settings → Rules → Rulesets, target: tags). Restrict
  creations, updates and deletions to the maintainers who cut releases. Without
  it, anyone with write access can push a `v*` tag, and a tag carries its own
  copy of the workflow files: the `release` and `github-pages` environments
  both accept `v*` tags. A ruleset cannot require a tag to be signed, which is
  why `verify` checks the signature.
- **`release` environment** (Settings → Environments). GitHub creates it
  without protection the first time the workflow runs. Add required reviewers,
  and under "Deployment branches and tags" allow the branch `main` and the tag
  pattern `v*` only. The workflow file at a tag or on `main` is then the only
  one that can run the publishing jobs in this environment. Turn off "Allow
  administrators to bypass configured protection rules" if a second approver is
  wanted even for an administrator.
- **`github-pages` environment.** Keep the `main` and `v*` policies described
  under [The tag must be allowed to deploy](#the-tag-must-be-allowed-to-deploy).
- **GHCR package access** (the package's settings → Manage Actions access).
  Only this repository should have write access. Any workflow in a repository
  with write access can push or retag images, whatever branch it runs from. The
  release workflow no longer trusts a tag in the registry, but `:snapshot` and
  the test tags are still only as trustworthy as the set of writers.
- **"Allow GitHub Actions to create and approve pull requests"** (Settings →
  Actions → General). The version bump needs it to open its pull request (see
  [The version bump](#the-version-bump)). It also lets a workflow approve a
  pull request, so keep the `main` rule that requires a code owner's review:
  a workflow's approval is not a code owner's.
- **Code-owner review on `main`.** `.github/CODEOWNERS` now names an owner for
  every path, so the required review covers the whole repository, not only the
  workflows.

Tags created before these checks existed keep their old workflow files. Anyone
who can start a workflow on such a tag runs the old `release.yml` or `docs.yml`
from it, without the checks added since. The `release` environment does not
gate those runs, because the old file does not name it. Required reviewers on
`github-pages` and the write-access list are what limit this.
