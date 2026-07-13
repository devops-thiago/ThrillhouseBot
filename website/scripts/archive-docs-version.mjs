#!/usr/bin/env node
/**
 * Freeze the current docs tree as a starlight-versions archive.
 *
 * Usage:
 * node scripts/archive-docs-version.mjs <slug> [label]
 * npm run docs:archive -- 0.4.0
 *
 * Expands include markers, copies assets, writes versions/<slug>.json,
 * and prepends the slug in versions.json. Then bump current.label for the next release.
 */
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const websiteRoot = resolve(__dirname, "..");
const docsDir = join(websiteRoot, "src/content/docs");
const assetsDir = join(websiteRoot, "src/assets");
const versionsMetaDir = join(websiteRoot, "src/content/versions");
const versionsPath = join(websiteRoot, "versions.json");

// Keep in sync with the sidebar in astro.config.mjs (starlight-versions stores
// a copy per archived release).
const SIDEBAR = [
  { label: "Home", slug: "index" },
  { label: "Getting started", slug: "getting-started" },
  { label: "Commands", slug: "commands" },
  { label: "Configuration", slug: "configuration" },
  { label: "AI providers", slug: "providers" },
  { label: "Architecture", slug: "architecture" },
  { label: "How it compares", slug: "comparison" },
  { label: "Contributing", slug: "contributing" },
];

const INCLUDE_RE = /<!--\s*include:\s*(\S+?)(?:#([\w-]+))?\s*-->/g;

const slug = process.argv[2];
if (!slug || !/^[0-9]+(\.[0-9]+)*$/.test(slug)) {
  console.error("Usage: node scripts/archive-docs-version.mjs <slug> [label]");
  console.error("  slug must look like 0.4.0");
  process.exit(1);
}
const label = process.argv[3] ?? `v${slug}`;
const destDocs = join(docsDir, slug);
const destAssets = join(assetsDir, slug);
const destVersionConfig = join(versionsMetaDir, `${slug}.json`);

if (existsSync(destDocs) || existsSync(destVersionConfig)) {
  console.error(
    `Refusing to overwrite existing archive: ${relative(websiteRoot, destDocs)} or ${relative(websiteRoot, destVersionConfig)}`,
  );
  process.exit(1);
}

function expandIncludes(markdown, filePath) {
  return markdown.replace(INCLUDE_RE, (full, relPath, section) => {
    const sourcePath = resolve(dirname(filePath), relPath);
    let content = readFileSync(sourcePath, "utf8");
    if (section) {
      const start = `<!-- docs:${section}:start -->`;
      const end = `<!-- docs:${section}:end -->`;
      const startIdx = content.indexOf(start);
      const endIdx = content.indexOf(end);
      if (startIdx === -1 || endIdx === -1 || endIdx < startIdx) {
        throw new Error(
          `markers for section "${section}" not found in ${sourcePath} (from ${filePath})`,
        );
      }
      content = content.slice(startIdx + start.length, endIdx);
    }
    return `\n${content}\n`;
  });
}

/** Match starlight-versions asset rewrite: insert slug before the filename and add one `../`. */
function versionAssetPaths(markdown, versionSlug) {
  return markdown.replace(
    /(\]\(|src=["'])((?:\.\.\/)+assets\/)([^)"']+)(["')])/g,
    (_m, open, prefix, file, close) => {
      const segments = `${prefix}${file}`.split("/");
      segments.splice(-1, 0, versionSlug);
      return `${open}../${segments.join("/")}${close}`;
    },
  );
}

/** starlight-versions expects index → `<slug>`, other pages → `<slug>/<page>`. */
function injectVersionSlug(markdown, versionSlug, relPath) {
  const base = relPath.replace(/\.mdx?$/, "").replaceAll("\\", "/");
  const pageSlug =
    base === "index" || base.endsWith("/index")
      ? versionSlug
      : `${versionSlug}/${base.replace(/\/index$/, "")}`;
  if (markdown.startsWith("---\n")) {
    if (/^---\n(?:[\s\S]*?\n)?slug:\s/m.test(markdown)) {
      return markdown.replace(/^---\n([\s\S]*?\n)?slug:\s*.*/m, `---\n$1slug: ${pageSlug}`);
    }
    return markdown.replace(/^---\n/, `---\nslug: ${pageSlug}\n`);
  }
  return `---\nslug: ${pageSlug}\n---\n${markdown}`;
}

function listMarkdownFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (/^[0-9]+(\.[0-9]+)*$/.test(entry.name)) return [];
      return listMarkdownFiles(path);
    }
    return entry.name.endsWith(".md") || entry.name.endsWith(".mdx") ? [path] : [];
  });
}

mkdirSync(destDocs, { recursive: true });
for (const src of listMarkdownFiles(docsDir)) {
  const rel = relative(docsDir, src);
  const out = join(destDocs, rel);
  mkdirSync(dirname(out), { recursive: true });
  const expanded = injectVersionSlug(
    versionAssetPaths(expandIncludes(readFileSync(src, "utf8"), src), slug),
    slug,
    rel,
  );
  writeFileSync(out, expanded);
  console.log(`archived ${rel}`);
}

if (existsSync(assetsDir)) {
  mkdirSync(destAssets, { recursive: true });
  for (const entry of readdirSync(assetsDir, { withFileTypes: true })) {
    if (entry.isDirectory() && /^[0-9]+(\.[0-9]+)*$/.test(entry.name)) continue;
    cpSync(join(assetsDir, entry.name), join(destAssets, entry.name), { recursive: true });
  }
  console.log(`copied assets → src/assets/${slug}/`);
}

mkdirSync(versionsMetaDir, { recursive: true });
writeFileSync(destVersionConfig, `${JSON.stringify({ sidebar: SIDEBAR }, null, 2)}\n`);
console.log(`wrote ${relative(websiteRoot, destVersionConfig)}`);

const versions = JSON.parse(readFileSync(versionsPath, "utf8"));
if ((versions.versions ?? []).some((v) => v.slug === slug)) {
  console.error(`slug ${slug} already listed in versions.json`);
  process.exit(1);
}
versions.versions = [{ slug, label }, ...(versions.versions ?? [])];
writeFileSync(versionsPath, `${JSON.stringify(versions, null, 2)}\n`);
console.log(`recorded ${label} in versions.json`);
console.log(`
Next:
  1. Set versions.json current.label to the next release (e.g. v0.5.0)
  2. Commit src/content/docs/${slug}/, src/content/versions/${slug}.json, assets, versions.json
  3. Keep editing src/content/docs/ for the next release
  4. Publishing a GitHub Release deploys the docs site
`);
