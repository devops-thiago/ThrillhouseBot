#!/usr/bin/env node
/**
 * Build starlight-versions archives for already-published tags that predate
 * the Astro docs site (no website/ tree, no <!-- docs:* --> include markers).
 *
 * Usage (from website/):
 *   node scripts/archive-historical-releases.mjs
 *   node scripts/archive-historical-releases.mjs v0.3.0 v0.3.1
 *
 * Default tags: v0.1.0 … v0.3.1. Content is taken from each git tag via
 * `git show` / `git archive`. Pages that did not exist yet (e.g. Commands
 * before v0.2.0) are omitted from that version's sidebar.
 */
import { execFileSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const websiteRoot = resolve(__dirname, "..");
const repoRoot = resolve(websiteRoot, "..");
const docsDir = join(websiteRoot, "src/content/docs");
const assetsDir = join(websiteRoot, "src/assets");
const versionsMetaDir = join(websiteRoot, "src/content/versions");
const versionsPath = join(websiteRoot, "versions.json");

const DEFAULT_TAGS = ["v0.1.0", "v0.1.1", "v0.2.0", "v0.2.1", "v0.3.0", "v0.3.1"];

const PAGE_META = {
  index: {
    title: "ThrillhouseBot",
    description:
      "Self-hosted AI pull-request reviewer — a GraalVM-native GitHub App built with Quarkus.",
  },
  "getting-started": {
    title: "Getting started",
    description: "Create the GitHub App, configure the bot, and start it.",
  },
  commands: {
    title: "Commands",
    description: "Drive the bot directly from a PR with comment commands.",
  },
  configuration: {
    title: "Configuration",
    description: "Every environment variable the bot reads, with defaults.",
  },
  providers: {
    title: "AI providers",
    description: "Point the bot at any OpenAI-compatible chat endpoint.",
  },
  architecture: {
    title: "Architecture",
    description: "One-page overview of how the bot is structured and how a review flows through it.",
  },
  comparison: {
    title: "How it compares",
    description: "Where ThrillhouseBot sits next to other AI code-review tools.",
  },
  "review-eval": {
    title: "Review-quality evaluation",
    description: "Probe and score review quality against collected failure cases.",
  },
  contributing: {
    title: "Contributing",
    description: "Development setup and the CI bar for contributions.",
  },
};

const SIDEBAR_ORDER = [
  { label: "Home", slug: "index" },
  { label: "Getting started", slug: "getting-started" },
  { label: "Commands", slug: "commands" },
  { label: "Configuration", slug: "configuration" },
  { label: "AI providers", slug: "providers" },
  { label: "Architecture", slug: "architecture" },
  { label: "How it compares", slug: "comparison" },
  { label: "Review-quality evaluation", slug: "review-eval" },
  { label: "Contributing", slug: "contributing" },
];

const tags = process.argv.slice(2);
const selected = (tags.length > 0 ? tags : DEFAULT_TAGS).map((t) =>
  t.startsWith("v") ? t : `v${t}`,
);

function gitShow(tag, path) {
  try {
    return execFileSync("git", ["show", `${tag}:${path}`], {
      encoding: "utf8",
      cwd: repoRoot,
      maxBuffer: 10 * 1024 * 1024,
    });
  } catch {
    return null;
  }
}

function gitPathExists(tag, path) {
  try {
    execFileSync("git", ["cat-file", "-e", `${tag}:${path}`], { cwd: repoRoot, stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}

function extractSection(md, heading) {
  const re = new RegExp(`^## ${escapeRegExp(heading)}\\s*$`, "m");
  const match = md.match(re);
  if (!match || match.index === undefined) return null;
  const start = match.index + match[0].length;
  const rest = md.slice(start);
  const next = rest.search(/^## /m);
  return (next === -1 ? rest : rest.slice(0, next)).trim();
}

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function stripH1(md) {
  return md.replace(/^#[^\n]*\n+/, "").trim();
}

function stripDocsMarkers(md) {
  return md
    .replace(/<!--\s*docs:[\w-]+:(?:start|end)\s*-->\n?/g, "")
    .trim();
}

function githubBlob(tag, path, hash = "") {
  const frag = hash ? `#${hash.replace(/^#/, "")}` : "";
  return `https://github.com/devops-thiago/ThrillhouseBot/blob/${tag}/${path}${frag}`;
}

function githubReadmeAnchor(tag, hash) {
  const frag = hash.replace(/^#/, "");
  return `https://github.com/devops-thiago/ThrillhouseBot/blob/${tag}/README.md#${frag}`;
}

/**
 * Old release markdown used repo-relative links (SECURITY.md, README.md#…,
 * docs/ARCHITECTURE.md). Map those onto GitHub blob URLs for the tag or onto
 * versioned docs pages so starlight-links-validator stays happy.
 */
function rewriteRepoLinks(md, tag, slug) {
  let out = md;

  // Hosted installer (old README pointed at the local helper port).
  out = out.replace(
    /\]\(https?:\/\/localhost:8081\/install\.html\)/g,
    "](/ThrillhouseBot/install.html)",
  );
  out = out.replace(
    /https?:\/\/localhost:8081\/install\.html/g,
    "/ThrillhouseBot/install.html",
  );

  // In-page hash that lived on the README, not on the extracted getting-started page.
  out = out.replace(
    /\]\(#provider-support\)/g,
    `](/ThrillhouseBot/${slug}/providers/)`,
  );

  const repoFile = (path, hash = "") => githubBlob(tag, path, hash);

  const replacements = [
    [/\]\(\.\.\/SECURITY\.md\)/g, `](${repoFile("SECURITY.md")})`],
    [/\]\(SECURITY\.md\)/g, `](${repoFile("SECURITY.md")})`],
    [/\]\(CODE_OF_CONDUCT\.md\)/g, `](${repoFile("CODE_OF_CONDUCT.md")})`],
    [/\]\(\.\.\/CODE_OF_CONDUCT\.md\)/g, `](${repoFile("CODE_OF_CONDUCT.md")})`],
    [/\]\(docs\/ARCHITECTURE\.md\)/g, `](/ThrillhouseBot/${slug}/architecture/)`],
    [/\]\(\.\.\/docs\/ARCHITECTURE\.md\)/g, `](/ThrillhouseBot/${slug}/architecture/)`],
    [
      /\]\(\.\.\/README\.md#provider-support\)/g,
      `](/ThrillhouseBot/${slug}/providers/)`,
    ],
    [/\]\(README\.md#provider-support\)/g, `](/ThrillhouseBot/${slug}/providers/)`],
    [
      /\]\(\.\.\/README\.md#quick-start\)/g,
      `](/ThrillhouseBot/${slug}/getting-started/)`,
    ],
    [/\]\(README\.md#quick-start\)/g, `](/ThrillhouseBot/${slug}/getting-started/)`],
    [
      /\]\(\.\.\/README\.md(#[^)]+)?\)/g,
      (_m, hash = "") => `](${githubReadmeAnchor(tag, hash || "#")})`,
    ],
    [
      /\]\(README\.md(#[^)]+)?\)/g,
      (_m, hash = "") => `](${githubReadmeAnchor(tag, hash || "#")})`,
    ],
  ];

  for (const [re, repl] of replacements) {
    out = out.replace(re, repl);
  }

  return out;
}

function versionInternalLinks(md, slug) {
  // Site-absolute docs links → versioned paths (leave install.html / external alone).
  return md.replace(
    /\]\(\/ThrillhouseBot\/(?!install\.html)([^)#?\s]+)(\/?)(#[^)]*)?\)/g,
    (_m, path, _slash, hash = "") => {
      const clean = path.replace(/^\/+|\/+$/g, "");
      if (clean === slug || clean.startsWith(`${slug}/`)) {
        return `](/ThrillhouseBot/${clean}/${hash})`.replace(/\/+(#|\))/g, "$1");
      }
      return `](/ThrillhouseBot/${slug}/${clean}/${hash})`.replace(/\/+(#|\))/g, "$1");
    },
  );
}

function injectVersionSlug(markdown, versionSlug, pageKey) {
  const pageSlug = pageKey === "index" ? versionSlug : `${versionSlug}/${pageKey}`;
  if (markdown.startsWith("---\n")) {
    if (/^---\n(?:[\s\S]*?\n)?slug:\s/m.test(markdown)) {
      return markdown.replace(/^---\n([\s\S]*?\n)?slug:\s*.*/m, `---\n$1slug: ${pageSlug}`);
    }
    return markdown.replace(/^---\n/, `---\nslug: ${pageSlug}\n`);
  }
  return `---\nslug: ${pageSlug}\n---\n${markdown}`;
}

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

function frontmatter(pageKey) {
  const meta = PAGE_META[pageKey];
  return `---\ntitle: ${meta.title}\ndescription: ${meta.description}\n---\n\n`;
}

function writePage(tag, slug, pageKey, body) {
  const dest = join(docsDir, slug, `${pageKey}.md`);
  mkdirSync(dirname(dest), { recursive: true });
  let md = frontmatter(pageKey) + body.trim() + "\n";
  md = rewriteRepoLinks(md, tag, slug);
  md = versionAssetPaths(md, slug);
  md = versionInternalLinks(md, slug);
  md = injectVersionSlug(md, slug, pageKey);
  writeFileSync(dest, md);
}

function copyTagAssets(tag, slug) {
  const destAssets = join(assetsDir, slug);
  mkdirSync(destAssets, { recursive: true });

  if (gitPathExists(tag, "docs/assets")) {
    const tmp = mkdtempSync(join(tmpdir(), "thb-docs-assets-"));
    try {
      const archive = execFileSync("git", ["archive", tag, "docs/assets"], {
        cwd: repoRoot,
        maxBuffer: 50 * 1024 * 1024,
      });
      writeFileSync(join(tmp, "assets.tar"), archive);
      execFileSync("tar", ["-xf", join(tmp, "assets.tar"), "-C", tmp]);
      const extracted = join(tmp, "docs/assets");
      if (existsSync(extracted)) {
        cpSync(extracted, destAssets, { recursive: true });
      }
    } finally {
      rmSync(tmp, { recursive: true, force: true });
    }
  }

  const icon = join(assetsDir, "icon.png");
  if (existsSync(icon) && !existsSync(join(destAssets, "icon.png"))) {
    cpSync(icon, join(destAssets, "icon.png"));
  }
  for (const name of ["pr-approval.png", "live-streaming.png"]) {
    const fallback = join(assetsDir, name);
    if (!existsSync(join(destAssets, name)) && existsSync(fallback)) {
      cpSync(fallback, join(destAssets, name));
    }
  }
}

function buildPages(tag, slug) {
  const readme = gitShow(tag, "README.md");
  if (!readme) throw new Error(`No README.md at ${tag}`);

  const features = extractSection(readme, "Features");
  const providers = extractSection(readme, "Provider support");
  const commands = extractSection(readme, "Commands");
  const quickStart = extractSection(readme, "Quick start");
  const appSetup = extractSection(readme, "GitHub App setup");
  const configuration = extractSection(readme, "Configuration");
  const prLabels = extractSection(readme, "PR labels");

  const architecture = gitShow(tag, "docs/ARCHITECTURE.md");
  const comparison = gitShow(tag, "docs/COMPARISON.md");
  const reviewEval = gitShow(tag, "docs/REVIEW_EVAL.md");
  const contributing = gitShow(tag, "CONTRIBUTING.md");

  const present = new Set(["index", "getting-started", "providers", "architecture", "comparison", "review-eval", "contributing"]);

  // Home
  const nextLinks = [
    `- **[Getting started](/ThrillhouseBot/${slug}/getting-started/)** — create the GitHub App and run the bot`,
    commands
      ? `- **[Commands](/ThrillhouseBot/${slug}/commands/)** — drive the bot from a PR`
      : null,
    `- **[Configuration](/ThrillhouseBot/${slug}/configuration/)** — environment variables and defaults`,
    `- **[AI providers](/ThrillhouseBot/${slug}/providers/)** — OpenAI-compatible endpoints`,
    `- **[Architecture](/ThrillhouseBot/${slug}/architecture/)** — how a review flows through the system`,
    `- **[How it compares](/ThrillhouseBot/${slug}/comparison/)** — vs other AI code-review tools`,
    `- **[Contributing](/ThrillhouseBot/${slug}/contributing/)** — development setup`,
  ]
    .filter(Boolean)
    .join("\n");

  writePage(
    tag,
    slug,
    "index",
    `> **"Everything's coming up Thrillhouse!"**

A self-hosted, GraalVM-native PR review bot, built as a GitHub App with Quarkus.
Documentation snapshot for **${tag}**.

![ThrillhouseBot approving a clean pull request](../../assets/pr-approval.png)

## Features

${features ?? "_Features list not found in this release's README._"}

## Where to go next

${nextLinks}

## Dashboard

The built-in dashboard shows summary cards, a live activity feed, cost charts,
token breakdowns, and session history:

![Dashboard Overview](../../assets/live-streaming.png)
`,
  );

  // Getting started = Quick start (+ GitHub App setup when present)
  const gettingStartedBody = [
    quickStart ? `## Quick start\n\n${quickStart}` : null,
    appSetup ? `## GitHub App setup\n\n${appSetup}` : null,
  ]
    .filter(Boolean)
    .join("\n\n");
  writePage(
    tag,
    slug,
    "getting-started",
    gettingStartedBody ||
      "_Getting-started content was not present in this release's README._",
  );

  if (commands) {
    present.add("commands");
    writePage(tag, slug, "commands", commands);
  }

  const configBody = [configuration, prLabels ? `## PR labels\n\n${prLabels}` : null]
    .filter(Boolean)
    .join("\n\n");
  if (configBody) {
    present.add("configuration");
    writePage(tag, slug, "configuration", configBody);
  }

  writePage(
    tag,
    slug,
    "providers",
    `${providers ?? "_Provider table not found in this release's README._"}

There is no provider-specific code in the bot — a new provider is configuration.
See [Architecture](/ThrillhouseBot/${slug}/architecture/) and
[Configuration](/ThrillhouseBot/${slug}/configuration/) for details available in this release.
`,
  );

  if (architecture) {
    writePage(tag, slug, "architecture", stripDocsMarkers(stripH1(architecture)));
  } else {
    present.delete("architecture");
  }

  if (comparison) {
    writePage(tag, slug, "comparison", stripDocsMarkers(stripH1(comparison)));
  } else {
    present.delete("comparison");
  }

  if (reviewEval) {
    writePage(tag, slug, "review-eval", stripDocsMarkers(stripH1(reviewEval)));
  } else {
    present.delete("review-eval");
  }

  if (contributing) {
    writePage(tag, slug, "contributing", stripDocsMarkers(stripH1(contributing)));
  } else {
    present.delete("contributing");
  }

  return present;
}

function writeVersionConfig(slug, present) {
  const sidebar = SIDEBAR_ORDER.filter((item) => present.has(item.slug));
  mkdirSync(versionsMetaDir, { recursive: true });
  writeFileSync(
    join(versionsMetaDir, `${slug}.json`),
    `${JSON.stringify({ sidebar }, null, 2)}\n`,
  );
}

// --- main ---
const versions = JSON.parse(readFileSync(versionsPath, "utf8"));
const archived = [];

for (const tag of selected) {
  const slug = tag.replace(/^v/, "");
  const destDocs = join(docsDir, slug);

  console.log(`\n=== archiving ${tag} → ${slug} ===`);
  // Allow regenerate: wipe prior snapshot for this slug.
  if (existsSync(destDocs)) rmSync(destDocs, { recursive: true, force: true });
  const versionCfg = join(versionsMetaDir, `${slug}.json`);
  if (existsSync(versionCfg)) rmSync(versionCfg, { force: true });
  const present = buildPages(tag, slug);
  copyTagAssets(tag, slug);
  writeVersionConfig(slug, present);
  archived.push({ slug, label: tag.startsWith("v") ? tag : `v${slug}` });
  console.log(`pages: ${[...present].join(", ")}`);
}

// Newest archived release first in the dropdown list
const bySlugDesc = (a, b) =>
  a.slug.localeCompare(b.slug, undefined, { numeric: true, sensitivity: "base" }) * -1;

const merged = [...archived, ...(versions.versions ?? [])];
const seen = new Set();
versions.versions = merged
  .filter((v) => {
    if (seen.has(v.slug)) return false;
    seen.add(v.slug);
    return true;
  })
  .sort(bySlugDesc);

writeFileSync(versionsPath, `${JSON.stringify(versions, null, 2)}\n`);
console.log(`\nUpdated versions.json (${versions.versions.length} archived version(s)).`);
console.log("Run: cd website && npm run build");
