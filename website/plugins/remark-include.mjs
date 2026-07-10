import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { visit } from "unist-util-visit";

/**
 * Includes Markdown from another file, so repo files like README.md stay the
 * single source of truth for content that also renders on the docs site.
 *
 * Usage in a page (must be an HTML comment on its own line):
 *   <!-- include: ../../../../README.md#commands -->
 *
 * With `#section`, only the content between these markers in the source file
 * is included:
 *   <!-- docs:section:start --> ... <!-- docs:section:end -->
 * Without `#section`, the whole file is included.
 */
const INCLUDE_RE = /^<!--\s*include:\s*(\S+?)(?:#([\w-]+))?\s*-->$/;

export default function remarkInclude() {
  const processor = this;
  return (tree, file) => {
    visit(tree, "html", (node, index, parent) => {
      const match = node.value.trim().match(INCLUDE_RE);
      if (!match || !parent || index === undefined) return;
      const [, relPath, section] = match;
      const sourcePath = resolve(dirname(file.path), relPath);
      let content = readFileSync(sourcePath, "utf8");
      if (section) {
        const start = `<!-- docs:${section}:start -->`;
        const end = `<!-- docs:${section}:end -->`;
        const startIdx = content.indexOf(start);
        const endIdx = content.indexOf(end);
        if (startIdx === -1 || endIdx === -1 || endIdx < startIdx) {
          throw new Error(
            `remark-include: markers for section "${section}" not found in ${sourcePath}`,
          );
        }
        content = content.slice(startIdx + start.length, endIdx);
      }
      const subtree = processor.parse(content);
      parent.children.splice(index, 1, ...subtree.children);
      // Re-visit from the first spliced node so nested includes also resolve.
      return index;
    });
  };
}
