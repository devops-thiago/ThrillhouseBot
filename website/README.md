# ThrillhouseBot docs site

Astro Starlight site at <https://devops-thiago.github.io/ThrillhouseBot/>.

## Versioning

| Surface | Content |
|---|---|
| Default (`/`) | Docs for the release in progress (`versions.json` → `current.label`) |
| Version dropdown | Prior releases under `src/content/docs/<slug>/` |
| Deploy | GitHub `release: published` (or manual `workflow_dispatch`) |

Pages updates when a release is published. Edits on `main` / `release/*` can land anytime.

Archived versions already include **v0.1.0–v0.3.1**. Before starting docs for the release after `v0.4.0`, freeze current pages:

```bash
cd website
npm run docs:archive -- 0.4.0
# then set versions.json current.label to the next version
```

## Local development

```bash
cd website
npm ci
npm run dev
```

`<!-- include: … -->` markers pull README / `docs/` / `CONTRIBUTING.md` at build time.

## Release checklist

```bash
cd website
npm run docs:archive -- 0.4.0   # when freezing the previous current
# edit versions.json current.label
```

Commit `src/content/docs/<slug>/`, `src/assets/<slug>/` if needed,
`src/content/versions/<slug>.json`, and `versions.json`. Publish the GitHub Release
to deploy.

Rebuild pre-Astro archives (v0.1.0–v0.3.1) with:

```bash
cd website
npm run docs:archive-historical
```
