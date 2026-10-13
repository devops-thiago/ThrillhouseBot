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

## Dependency security

Mermaid 12.2.0 ships patched KaTeX 0.18.11 in its prebuilt browser chunks
([GHSA-238p-pmpm-9mq7](https://osv.dev/GHSA-238p-pmpm-9mq7)). An override of
KaTeX alone does not replace the older copy embedded in Mermaid 11/12.1.
The scoped `astro-mermaid` override binds its peer to the root Mermaid version;
its current 2.1.0 metadata still lists only Mermaid 10/11. The public integration
APIs are unchanged, and all current/archived diagrams plus math labels are checked.
Remove this override when the integration declares Mermaid 12 support. Its optional
ELK layout package is not installed; enabling it requires a Mermaid-12-compatible
release and separate layout checks.

The selector parser override requires 7.1.6 or newer
([GHSA-rj75-hqrm-r3gf](https://osv.dev/GHSA-rj75-hqrm-r3gf)); Expressive Code's
PostCSS nesting dependency still requests the vulnerable 6.x range. Remove the
override when that parent requires a patched parser. Astro accepts
`http-cache-semantics` 4.3.0 through its existing range, so only its lockfile
version changed ([GHSA-ch52-4w7c-c8xp](https://osv.dev/GHSA-ch52-4w7c-c8xp)).

Run `npm ci`, `npm audit`, and `npm run build` after dependency changes;
check nested CSS, rendered Mermaid diagrams and math when either family changes.

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
