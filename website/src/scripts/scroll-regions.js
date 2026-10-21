// Keyboard access and unique names for the page's horizontally scrolling areas.
//
// Starlight shows markdown tables as scrolling blocks. A table that overflows
// gets tabindex="0" so a keyboard user can focus it and scroll it with the
// arrow keys (axe scrollable-region-focusable). It keeps its table role.
//
// Expressive Code makes an overflowing code block focusable with
// role="region" but no name, so a page with several of them has identical
// landmarks (axe landmark-unique). Each such block is named "Code block N",
// followed by its title or language when it has one.
//
// Both follow the overflow as the viewport changes. Only the attributes this
// script added are removed again, marked by data-scroll-region.

const MARK = "data-scroll-region";
const TABLES = ".sl-markdown-content table";
const CODE = ".expressive-code pre";

function syncTable(table) {
  const overflows = table.scrollWidth > table.clientWidth;
  if (overflows && !table.hasAttribute("tabindex")) {
    table.setAttribute("tabindex", "0");
    table.setAttribute(MARK, "");
  } else if (!overflows && table.hasAttribute(MARK)) {
    table.removeAttribute("tabindex");
    table.removeAttribute(MARK);
  }
}

// The pages are rendered at build time, so the code blocks and their order
// are fixed once the module runs. Each block's number is looked up, not
// searched for, so naming every block stays linear.
const blocks = Array.from(document.querySelectorAll(CODE));
const ordinals = new Map(blocks.map((pre, i) => [pre, i + 1]));

function codeLabel(pre) {
  const title = pre.closest("figure")?.querySelector("figcaption .title")?.textContent?.trim();
  const detail = title || pre.dataset.language;
  const label = `Code block ${ordinals.get(pre)}`;
  return detail ? `${label}, ${detail}` : label;
}

function syncCode(pre) {
  const isRegion = pre.getAttribute("role") === "region";
  if (isRegion && !pre.hasAttribute("aria-label")) {
    pre.setAttribute("aria-label", codeLabel(pre));
    pre.setAttribute(MARK, "");
  } else if (!isRegion && pre.hasAttribute(MARK)) {
    pre.removeAttribute("aria-label");
    pre.removeAttribute(MARK);
  }
}

const resizes = new ResizeObserver((entries) => {
  for (const entry of entries) syncTable(entry.target);
});
for (const table of document.querySelectorAll(TABLES)) resizes.observe(table);

const roles = new MutationObserver((records) => {
  for (const record of records) {
    if (record.target.matches(CODE)) syncCode(record.target);
  }
});
roles.observe(document.body, { subtree: true, attributes: true, attributeFilter: ["role"] });
for (const pre of blocks) syncCode(pre);
