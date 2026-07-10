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
dropdown. The first docs-bearing release (`v0.4.0`) therefore ships as a single
site; archive `0.4.0` when you start docs work for the *next* release so the
selector can offer `v0.4.0` alongside the new current label.

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

Commit `src/content/docs/<slug>/`, `src/assets/<slug>/` (if any), and `versions.json`.
Then publish the GitHub Release — the Docs workflow builds that tag and deploys Pages.

### First publish (`v0.4.0`)

No archive step: `current.label` is already `v0.4.0` and `versions` is empty
(no dropdown yet). Publishing the `v0.4.0` GitHub Release deploys the site.
