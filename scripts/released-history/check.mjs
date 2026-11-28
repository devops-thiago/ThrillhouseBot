import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

// A released version is one with a plain vX.Y.Z tag. Its CHANGELOG section and its archived
// docs are a record of what shipped: a correction goes into a later release (an Errata entry),
// never into the released text.
const RELEASE_TAG = /^v(\d+\.\d+\.\d+)$/;
const ARCHIVE_PATHS = [
  /^website\/src\/content\/docs\/(\d+\.\d+\.\d+)\//,
  /^website\/src\/assets\/(\d+\.\d+\.\d+)\//,
  /^website\/src\/content\/versions\/(\d+\.\d+\.\d+)\.json$/,
];
const CHANGELOG = 'CHANGELOG.md';
const VERSIONS = 'website/versions.json';
const safe = (text) => String(text).replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A');

/** The released versions among {@code tags}, without the leading v. */
export function releasedVersions(tags) {
  return new Set(tags.map((tag) => RELEASE_TAG.exec(tag)?.[1]).filter(Boolean));
}

// The changelog is read line by line with CommonMark's own rules for the blocks that matter here,
// so a line GitHub shows as a heading is one here too, and a line it shows as text is not taken
// for a code fence. The policy test (scripts/workflows/policy.test.mjs) reads the unreleased
// section's `###` headings with the same helpers.

// A line ending is a line feed, a carriage return, or both.
const LINE_END = /\r\n|\n|\r/;
// What opens a block quote or a list item: `>`, or a bullet or a number with a space, a tab or
// the end of the line after it, each up to three spaces in.
const CONTAINER = /^ {0,3}(?:(>)[ \t]?|(?:[-*+]|\d{1,9}[.)])(?=[ \t]|$))/;
// A code fence opens up to three spaces in with three or more backticks or tildes. A backtick
// fence has no backtick in the rest of its line; such a line is a paragraph with inline code.
const FENCE_OPEN = /^ {0,3}(?:(`{3,})[^`]*|(~{3,})[^]*)$/;
// A closing fence has nothing after it but spaces and tabs.
const FENCE_CLOSE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/;
// A level-two heading written with hashes: up to three spaces, `##`, spaces or tabs, the name.
// A closing run of hashes comes after the name, so it needs no rule here.
const SECTION = /^ {0,3}##[ \t]+\[([^\]]+)\]/;
// A level-two heading written as a paragraph with a line of dashes under it. A `[name]:` line is
// a link definition, not a paragraph.
const UNDERLINED_SECTION = /^ {0,3}\[([^\]]+)\](?!:)/;
const UNDERLINE = /^ {0,3}-+[ \t]*$/;
// The same with equals signs makes a level-one heading.
const TITLE_UNDERLINE = /^ {0,3}=+[ \t]*$/;
const BLANK = /^[ \t]*$/;
const QUOTE = /^ {0,3}>[ \t]?/;
const INDENTED = /^(?: {4}| {0,3}\t)/;
// What ends a paragraph without a blank line, besides a code fence: a heading, a block quote, a
// list item with text (numbered from 1), or a thematic break.
const PARAGRAPH_END = /^ {0,3}(?:#{1,6}(?:[ \t]|$)|>|[-*+][ \t]+\S|1[.)][ \t]+\S|([*_])(?:[ \t]*\1){2,}[ \t]*$)/;

/**
 * {@code line} without the block quote and list markers it starts with, however many are nested.
 * A list marker takes the spaces and tabs after it when they are one to four columns wide. With
 * five or more the item's text starts one column after the marker and the rest of the line is an
 * indented code block, so it is returned with that indentation and reads as no heading or fence.
 * A quote marker takes one column after it, and a tab there is as wide as the column it starts in
 * makes it, so the text is returned with the columns left over as spaces.
 */
export function withoutContainers(line) {
  return containers(line).text;
}

/**
 * {@code line} without its markers, as {@link withoutContainers} gives it, and how many of its
 * characters the markers and the spaces and tabs counted with them take.
 */
function containers(line) {
  let text = line;
  let column = 0;
  let taken = 0;
  for (let match = CONTAINER.exec(text); match; match = CONTAINER.exec(text)) {
    const marker = match[0].trim();
    const after = column + indentOf(match[0], column) + marker.length;
    const space = /^[ \t]*/.exec(text.slice(match[0].trimEnd().length))[0];
    const gap = indentOf(space, after);
    const rest = text.slice(match[0].trimEnd().length + space.length);
    if (match[1]) {
      if (!space.includes('\t')) {
        // Spaces alone: the marker takes the first, and the rest are read with what follows.
        taken += match[0].length;
        text = text.slice(match[0].length);
        column = after + (match[0].length - match[0].trimEnd().length);
        continue;
      }
      taken += match[0].trimEnd().length + space.length;
      text = ' '.repeat(gap - 1) + rest;
      column = after + 1;
      continue;
    }
    if (rest !== '' && gap >= 5) return { text: ' '.repeat(gap - 1) + rest, taken: taken + match[0].length };
    taken += match[0].length + space.length;
    column = after + gap;
    text = rest;
  }
  return { text, taken };
}

/** {@code line} without the block quote markers it starts with. */
function withoutQuotes(line) {
  let text = line;
  for (let match = QUOTE.exec(text); match; match = QUOTE.exec(text)) text = text.slice(match[0].length);
  return text;
}

/**
 * How many columns the spaces and tabs at the start of {@code line} take when the line starts in
 * column {@code from}, a tab reaching the next multiple of four.
 */
function indentOf(line, from = 0) {
  let column = from;
  for (const character of line) {
    if (character === ' ') column += 1;
    else if (character === '\t') column += 4 - (column % 4);
    else break;
  }
  return column - from;
}

/** The run of backticks or tildes {@code line} opens a code fence with, or undefined. */
function fenceOpener(line) {
  const match = FENCE_OPEN.exec(line);
  return match ? match[1] ?? match[2] : undefined;
}

/** Whether {@code line} closes the fence {@code opener} opened: the same character, at least as long. */
function closesFence(line, opener) {
  const run = FENCE_CLOSE.exec(line)?.[1];
  return run !== undefined && run[0] === opener[0] && run.length >= opener.length;
}

/**
 * The code fence {@code line} opens, or null. A fence that opens a block quote or a list item
 * belongs to it: {@code leftBy} tells whether a later line is outside the quote or the item, which
 * ends the fence with no closing line (a blank line is outside a quote and inside an item), and {@code within} gives a later line as the quote or the
 * item holds it. A fence behind both kinds of marker, or behind a marker followed by a tab, is not
 * read as a fence, so nothing after it is hidden.
 */
function fenceAt(line) {
  const { text, taken } = containers(line);
  const run = fenceOpener(text);
  if (run === undefined) return null;
  const prefix = line.slice(0, taken);
  if (prefix === '') return { run, leftBy: () => false, within: (next) => next };
  if (/^[ >]*$/.test(prefix)) return { run, leftBy: (next) => !QUOTE.test(next), within: withoutQuotes };
  if (/[>\t]/.test(prefix)) return null;
  const width = prefix.length;
  return {
    run,
    leftBy: (next) => !BLANK.test(next) && indentOf(next) < width,
    within: (next) => ' '.repeat(Math.max(0, indentOf(next) - width)) + next.replace(/^[ \t]*/, ''),
  };
}

/**
 * Tells, for the lines of a Markdown text given in order, which belong to a fenced code block.
 * A fence opens and closes by the CommonMark rules: up to three spaces in, a backtick fence with
 * no backtick in its info string, a closing run of the same character at least as long with only
 * spaces and tabs after it. A fence opened inside a block quote or a list item also ends where
 * the quote or the item does.
 */
export function fenceReader() {
  let fence = null;
  return {
    /** Whether {@code line} opens, is inside or closes a fenced code block. */
    inFence(line) {
      if (fence?.leftBy(line)) fence = null;
      if (fence === null) {
        fence = fenceAt(line);
        return fence !== null;
      }
      if (closesFence(fence.within(line), fence.run)) fence = null;
      return true;
    },
    /** Whether a fence is open after the last line read. */
    isOpen: () => fence !== null,
  };
}

/**
 * Each level-two `[name]` section of a changelog, keyed by name, with trailing blank lines
 * dropped. The heading may be written any way Markdown reads as one: `## [name]` up to three
 * spaces in, with spaces or tabs after the hashes, at the start of a block quote or a list item,
 * or a paragraph starting `[name]` with a line of dashes under it. A heading inside a fenced code
 * block is text of the section around it, not a heading. A fence that is never closed hides
 * nothing: every heading then counts, so a stray fence cannot take the released sections after it
 * out of the check. A name that heads more than one section keeps the text of all of them, in
 * file order, so no copy stands in for another.
 */
export function changelogSections(text) {
  return parse(text).sections;
}

/** The section names of a changelog that head more than one section. */
export function repeatedSections(text) {
  return parse(text).repeated;
}

function parse(text) {
  const lines = text.split(LINE_END);
  return sectionsOf(lines, true) ?? sectionsOf(lines, false);
}

/** Whether {@code line} starts a block of its own, so it cannot continue a paragraph. */
function endsParagraph(line) {
  return PARAGRAPH_END.test(line) || fenceOpener(line) !== undefined;
}

/**
 * The name of the section a paragraph starting at {@code lines[start]} heads, or undefined. The
 * paragraph is a heading when a line of dashes follows its last line.
 */
function underlinedSection(lines, start) {
  const name = UNDERLINED_SECTION.exec(lines[start])?.[1];
  if (name === undefined) return undefined;
  for (let index = start + 1; index < lines.length; index++) {
    if (UNDERLINE.test(lines[index])) return name;
    if (BLANK.test(lines[index]) || TITLE_UNDERLINE.test(lines[index]) || endsParagraph(lines[index])) return undefined;
  }
  return undefined;
}

/** The sections of {@code lines}, or null when fences are honoured and one is left open. */
function sectionsOf(lines, honourFences) {
  const sections = new Map();
  const repeated = new Set();
  let name = null;
  let body = [];
  const fences = honourFences ? fenceReader() : null;
  // Whether the line before is paragraph text, which the next line then continues. The text of a
  // list item or a block quote is continued by an unmarked line too.
  let paragraph = false;
  const close = () => {
    if (name === null) return;
    const text = body.join('\n').trimEnd();
    if (sections.has(name)) repeated.add(name);
    sections.set(name, sections.has(name) ? `${sections.get(name)}\n\n${text}` : text);
  };
  for (const [index, line] of lines.entries()) {
    if (fences?.inFence(line)) {
      paragraph = false;
    } else {
      const text = withoutContainers(line);
      const starts = !paragraph && !INDENTED.test(line);
      const heading = SECTION.exec(text)?.[1] ?? (starts ? underlinedSection(lines, index) : undefined);
      if (heading !== undefined) { close(); name = heading; body = []; }
      // A line of dashes or, under paragraph text, of equals signs ends the paragraph it follows.
      const underline = UNDERLINE.test(line) || (paragraph && TITLE_UNDERLINE.test(line));
      // The text after a marker starts a paragraph unless it is an indented code block.
      const begins = text === line ? paragraph || starts : !INDENTED.test(text);
      paragraph = !BLANK.test(text) && !endsParagraph(text) && !underline && begins;
    }
    if (name !== null) body.push(line);
  }
  if (fences?.isOpen()) return null;
  close();
  return { sections, repeated };
}

/**
 * The released sections of {@code base} that {@code head} removed, changed or repeated. A second
 * section under a released heading is a change to that release wherever it is put, so it is
 * reported even when one of the copies still matches the base.
 */
export function changelogViolations(base, head, released) {
  const before = parse(base);
  const after = parse(head);
  const violations = [];
  for (const [name, text] of before.sections) {
    if (!released.has(name)) continue;
    if (!after.sections.has(name)) violations.push(`CHANGELOG.md: the released section [${name}] was removed or renamed`);
    else if (after.sections.get(name) !== text) {
      violations.push(after.repeated.has(name) && !before.repeated.has(name)
        ? `CHANGELOG.md: the released section [${name}] appears more than once`
        : `CHANGELOG.md: the released section [${name}] was changed`);
    }
  }
  return violations;
}

/** The released version an archive path belongs to, or null. */
export function archivedVersion(path) {
  for (const pattern of ARCHIVE_PATHS) {
    const match = pattern.exec(path);
    if (match) return match[1];
  }
  return null;
}

/**
 * Changes to an archived docs version that existed at the base and is released. A new archive (a
 * version with no files at the base) may be added; once it exists, it is not edited.
 */
export function archiveViolations(changes, released, archivedAtBase) {
  return changes
    .filter(({ path }) => {
      const version = archivedVersion(path);
      return version !== null && released.has(version) && archivedAtBase.has(version);
    })
    .map(({ status, path }) => `${path}: an archived docs file of a released version was ${status}`);
}

/** Entries of the base's versions.json that the head removed or changed. */
export function versionsViolations(base, head) {
  if (base === null) return [];
  const entries = (text) => JSON.parse(text).versions ?? [];
  const after = new Set((head === null ? [] : entries(head)).map((entry) => JSON.stringify(entry)));
  return entries(base)
    .filter((entry) => !after.has(JSON.stringify(entry)))
    .map((entry) => `${VERSIONS}: the archived version entry ${JSON.stringify(entry)} was removed or changed`);
}

const STATUS = { M: 'modified', D: 'deleted', A: 'added', T: 'retyped' };

export class Git {
  constructor(cwd = process.cwd()) { this.cwd = cwd; }
  run(...args) {
    return execFileSync('git', args, { cwd: this.cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] });
  }
  tags() { return this.run('tag', '--list', 'v*').split('\n').filter(Boolean); }
  mergeBase(base, head) { return this.run('merge-base', base, head).trim(); }
  changes(base, head) {
    const fields = this.run('diff', '--name-status', '--no-renames', '-z', base, head).split('\0').filter(Boolean);
    const out = [];
    for (let index = 0; index + 1 < fields.length; index += 2) {
      out.push({ status: STATUS[fields[index][0]] ?? fields[index], path: fields[index + 1] });
    }
    return out;
  }
  show(commit, path) {
    try { return this.run('show', `${commit}:${path}`); } catch { return null; }
  }
  archivedVersions(commit) {
    const listed = this.run('ls-tree', '-r', '--name-only', commit, '--', 'website/src/content/docs', 'website/src/assets', 'website/src/content/versions');
    return new Set(listed.split('\n').map(archivedVersion).filter(Boolean));
  }
}

export function check(git, baseSha, headSha) {
  const base = git.mergeBase(baseSha, headSha);
  const released = releasedVersions(git.tags());
  // No release tag at all means the tags were not fetched, not that nothing was released: every
  // rule but the versions.json one would then pass whatever the pull request changed.
  if (released.size === 0) return ['No vX.Y.Z release tag was found, so nothing can be checked; fetch the tags first'];
  const changes = git.changes(base, headSha);
  // Whether an archive already exists is judged at the base branch's tip, not the merge base:
  // a branch cut before the archive landed would otherwise add to it as if it were new.
  const violations = archiveViolations(changes, released, git.archivedVersions(baseSha));
  if (changes.some(({ path }) => path === CHANGELOG)) {
    violations.push(...changelogViolations(git.show(base, CHANGELOG) ?? '', git.show(headSha, CHANGELOG) ?? '', released));
  }
  if (changes.some(({ path }) => path === VERSIONS)) {
    violations.push(...versionsViolations(git.show(base, VERSIONS), git.show(headSha, VERSIONS)));
  }
  return violations;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { BASE_SHA, HEAD_SHA } = process.env;
  if (!BASE_SHA || !HEAD_SHA) {
    console.error('BASE_SHA and HEAD_SHA are required');
    process.exit(2);
  }
  const violations = check(new Git(), BASE_SHA, HEAD_SHA);
  for (const violation of violations) console.log(`::error::${safe(violation)}`);
  if (violations.length > 0) {
    console.log(`::error::${safe(`${violations.length} change(s) to released history. A released CHANGELOG section and an archived docs version are a record of what shipped; put a correction in the next release instead (an Errata entry; see CONTRIBUTING.md).`)}`);
    process.exit(1);
  }
  console.log('No released CHANGELOG section or archived docs version was changed.');
}
