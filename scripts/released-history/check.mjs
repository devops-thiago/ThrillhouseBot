import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

// A released version is one with a plain vX.Y.Z tag. Its CHANGELOG section and its archived
// docs are a record of what shipped: a correction goes into a later release (an Errata entry),
// never into the released text.
const RELEASE_TAG = /^v(\d+\.\d+\.\d+)$/;
const SECTION = /^## \[([^\]]+)\]/;
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

const FENCE = /^ {0,3}(`{3,}|~{3,})/;

/**
 * Each `## [name]` section of a changelog, keyed by name, with trailing blank lines dropped. A
 * `## [name]` line inside a fenced code block is text of the section around it, not a heading. A
 * fence that is never closed hides nothing: every heading then counts, so a stray fence cannot
 * take the released sections after it out of the check. A name that heads more than one section
 * keeps the text of all of them, in file order, so no copy stands in for another.
 */
export function changelogSections(text) {
  return parse(text).sections;
}

/** The section names of a changelog that head more than one section. */
export function repeatedSections(text) {
  return parse(text).repeated;
}

function parse(text) {
  const lines = text.split(/\r?\n/);
  return sectionsOf(lines, true) ?? sectionsOf(lines, false);
}

/** The sections of {@code lines}, or null when fences are honoured and one is left open. */
function sectionsOf(lines, honourFences) {
  const sections = new Map();
  const repeated = new Set();
  let name = null;
  let body = [];
  let fence = null;
  const close = () => {
    if (name === null) return;
    const text = body.join('\n').trimEnd();
    if (sections.has(name)) repeated.add(name);
    sections.set(name, sections.has(name) ? `${sections.get(name)}\n\n${text}` : text);
  };
  for (const line of lines) {
    const marker = honourFences ? FENCE.exec(line)?.[1] : undefined;
    if (fence === null) {
      const match = SECTION.exec(line);
      if (match) { close(); name = match[1]; body = []; }
      if (marker) fence = marker;
    } else if (marker && marker[0] === fence[0] && marker.length >= fence.length && line.trim() === marker) {
      fence = null;
    }
    if (name !== null) body.push(line);
  }
  if (fence !== null) return null;
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
