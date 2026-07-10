import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { defineCollection } from "astro:content";
import { docsLoader } from "@astrojs/starlight/loaders";
import { docsSchema } from "@astrojs/starlight/schema";
import { docsVersionsLoader } from "starlight-versions/loader";

const websiteRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
/** @type {{ versions?: unknown[] }} */
const docVersions = JSON.parse(
  readFileSync(resolve(websiteRoot, "versions.json"), "utf8"),
);

/** @type {Record<string, ReturnType<typeof defineCollection>>} */
const collections = {
  docs: defineCollection({ loader: docsLoader(), schema: docsSchema() }),
};

// starlight-versions is only wired when ≥1 archive exists (see astro.config.mjs).
if ((docVersions.versions?.length ?? 0) > 0) {
  collections.versions = defineCollection({ loader: docsVersionsLoader() });
}

export { collections };
