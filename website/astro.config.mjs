// @ts-check
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
import starlightLinksValidator from "starlight-links-validator";
import mermaid from "astro-mermaid";
import remarkInclude from "./plugins/remark-include.mjs";

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
      plugins: [starlightLinksValidator()],
    }),
  ],
});
