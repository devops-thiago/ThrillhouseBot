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
freeze the outgoing docs version (below), merge, then tag the merge commit
`vX.Y.Z` and push the tag.

## Freezing the docs version

The version picker on the docs site is driven by `website/versions.json`, and
each archived version is a snapshot under `website/src/content/docs/<slug>/`.
Neither is produced by CI, so **the freeze is a manual step in the release PR**.
Skipping it leaves the site serving the new version's docs under the old
version's label. It has been missed at four cuts so far (0.4.0, 0.5.0, 0.6.0 and
0.6.1), each time caught only at the following release.

Take the snapshot of the version being *replaced*, from that version's own tag,
because `archive-docs-version.mjs` expands the `remarkInclude` markers at archive
time and would otherwise capture the working tree:

```bash
git restore --source vX.Y.Z -- README.md CONTRIBUTING.md docs/ website/src/content/docs/
cd website && npm run docs:archive -- X.Y.Z
git restore --source HEAD -- README.md CONTRIBUTING.md docs/ website/src/content/docs/
```

Then set `current.label` in `website/versions.json` to the version being cut, and
confirm `npm run build` picks up the new pages.

Check `git status` after the second restore: a restore from a tag brings back any
file deleted since that tag, so anything outside the new archive directory and
`versions.json` is a file the release should not be resurrecting.

## Publishing the docs

`.github/workflows/docs.yml` deploys the live site from a release tag, so the
site tracks releases rather than `main`. Two things have to hold for that to
happen on its own, and through v0.6.2 neither did.

**The deploy is dispatched, not triggered.** `docs.yml` declares
`release: published`, but that event never fires for our releases: the `release`
job creates them with the default `GITHUB_TOKEN`, and GitHub does not start
workflow runs for events that token raises — the same recursion guard that keeps
CI from starting on the bump PR. In the repo's whole history the trigger has
never once fired, and every release through v0.6.2 published its docs by hand.
The `publish-docs` job in `release.yml` now dispatches `docs.yml` against the
tag after the release is created, gated on the release being the highest one so
a patch on an older line cannot republish the site from its tag.
`workflow_dispatch` is exempt from the recursion guard. The declared trigger is
kept because a release published by hand in the UI does fire it.

**The tag must be allowed to deploy.** The build runs **against the tag ref**,
so the deploy depends on one repo setting: the `github-pages` environment must
admit the tag.

Current policies, under **Settings → Environments → github-pages → Deployment
branches and tags**:

```
branch: main
tag: v*
```

Without the tag rule the deploy fails at the very last step with:

> Tag "vX.Y.Z" is not allowed to deploy to github-pages due to environment
> protection rules.

That is what happened to every release through v0.6.1, and the workaround —
dispatching the workflow from `main` — publishes `main`'s docs instead of the
release's, which is exactly what tracking releases exists to prevent. If a deploy
fails this way, fix the policy and re-run against the tag rather than dispatching
from `main`. Inspect the policies with:

```bash
gh api repos/<owner>/<repo>/environments/github-pages/deployment-branch-policies
```

To republish an already-released version by hand, for a docs hotfix on its tag:

```bash
gh workflow run docs.yml --ref vX.Y.Z
```

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
