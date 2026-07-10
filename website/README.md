# ThrillhouseBot docs site

Astro Starlight site published to GitHub Pages at
<https://devops-thiago.github.io/ThrillhouseBot/>.

## Versioning model

The live site tracks **GitHub Releases**, not `main` tip:

| Surface | What it shows |
|---|---|
| Default (`/`) | Docs for the release being cut (`versions.json` → `current.label`) |
| Version dropdown | Appears after the first `npm run docs:archive` (prior releases under `src/content/docs/<slug>/`) |
| CI deploy | `release: published` (and optional `workflow_dispatch`) — not every push |

Unreleased edits on `main` / `release/*` can land anytime; Pages only updates when a
version is published (or you manually re-run the Docs workflow).

`starlight-versions` needs at least one archived slug before it enables the
dropdown. Historical releases **v0.1.0–v0.3.1** (pre-Astro site) are already
archived under `src/content/docs/<slug>/` via `npm run docs:archive-historical`,
so the dropdown is available as soon as this branch deploys. When you cut
`v0.4.0`, archive it with `npm run docs:archive -- 0.4.0` before bumping
`current.label` for the next release.

## Local development

```bash
cd website
npm ci
npm run dev
```

`<!-- include: … -->` markers pull README / `docs/` / `CONTRIBUTING.md` sections at
build time so those files stay the source of truth for **current** docs.

## Cutting a release (from the second docs release onward)

When `current` is about to move to a new version and you still need the previous
one in the dropdown:

```bash
cd website
# Freeze current pages (expands includes so old versions cannot pick up later README edits)
npm run docs:archive -- 0.4.0
# Edit versions.json → set current.label to the next release (e.g. "v0.5.0")
```

Commit `src/content/docs/<slug>/`, `src/assets/<slug>/` (if any),
`src/content/versions/<slug>.json`, and `versions.json`.
Then publish the GitHub Release — the Docs workflow builds that tag and deploys Pages.

### Regenerating pre-site archives (v0.1.0–v0.3.1)

Those tags had no `website/` tree. Rebuild snapshots from git:

```bash
cd website
npm run docs:archive-historical
# or: npm run docs:archive-historical -- v0.3.0 v0.3.1
```

### First publish (`v0.4.0`)

`current.label` is `v0.4.0`; archived versions already include `0.1.0`–`0.3.1`.
Publishing the `v0.4.0` GitHub Release deploys current docs plus the version dropdown.
