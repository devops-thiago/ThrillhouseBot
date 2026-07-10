// @ts-check
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
import starlightLinksValidator from "starlight-links-validator";
import starlightVersions from "starlight-versions";
import mermaid from "astro-mermaid";
import remarkInclude from "./plugins/remark-include.mjs";

const websiteRoot = dirname(fileURLToPath(import.meta.url));
/** @type {{ current: { label: string }, versions: { slug: string, label?: string }[] }} */
const docVersions = JSON.parse(
  readFileSync(resolve(websiteRoot, "versions.json"), "utf8"),
);

// Enable starlight-versions only when versions.json lists ≥1 archived slug.
const versioningPlugins =
  docVersions.versions.length > 0
    ? [
        starlightVersions({
          current: docVersions.current,
          versions: docVersions.versions,
        }),
      ]
    : [];

export default defineConfig({
  site: "https://devops-thiago.github.io",
  base: "/ThrillhouseBot",
  markdown: {
    remarkPlugins: [remarkInclude],
  },
  integrations: [
    // astro-mermaid must come before starlight so mermaid code fences are
    // taken over before Expressive Code processes code blocks.
    mermaid({ autoTheme: true }),
    starlight({
      title: "ThrillhouseBot",
      description:
        "Self-hosted AI pull-request reviewer — a GraalVM-native GitHub App built with Quarkus",
      logo: { src: "./src/assets/icon.png", alt: "ThrillhouseBot" },
      favicon: "/icon.png",
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/devops-thiago/ThrillhouseBot",
        },
      ],
      editLink: {
        baseUrl:
          "https://github.com/devops-thiago/ThrillhouseBot/edit/main/website/",
      },
      sidebar: [
        { label: "Home", slug: "index" },
        { label: "Getting started", slug: "getting-started" },
        { label: "Commands", slug: "commands" },
        { label: "Configuration", slug: "configuration" },
        { label: "AI providers", slug: "providers" },
        { label: "Architecture", slug: "architecture" },
        { label: "How it compares", slug: "comparison" },
        { label: "Review-quality evaluation", slug: "review-eval" },
        { label: "Contributing", slug: "contributing" },
      ],
      plugins: [...versioningPlugins, starlightLinksValidator()],
    }),
  ],
});
