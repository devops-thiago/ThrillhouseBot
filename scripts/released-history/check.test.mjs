import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import {
  Git, archiveViolations, archivedVersion, changelogSections, changelogViolations, check,
  releasedVersions, repeatedSections, versionsViolations,
} from './check.mjs';

const CHANGELOG = `# Changelog

## [Unreleased]

## [1.1.0] - 2026-02-01

- Added b.

## [1.0.0] - 2026-01-01

- Added a.
`;

test('only plain vX.Y.Z tags are releases', () => {
  assert.deepEqual([...releasedVersions(['v1.0.0', 'v1.1.0-rc.1', 'nightly', 'v2.0'])], ['1.0.0']);
});

test('a changelog splits into its sections without trailing blank lines', () => {
  const sections = changelogSections(CHANGELOG);
  assert.deepEqual([...sections.keys()], ['Unreleased', '1.1.0', '1.0.0']);
  assert.equal(sections.get('1.1.0'), '## [1.1.0] - 2026-02-01\n\n- Added b.');
});

test('a section heading inside a fenced code block is text of its section', () => {
  const text = '## [1.1.0] - 2026-02-01\n\n```md\n## [Unreleased]\n```\n\n- After the example.\n\n~~~~\n```\n## [0.0.1]\n~~~\n~~~~\n\n## [1.0.0] - 2026-01-01\n\n- Added a.\n';
  const sections = changelogSections(text);
  assert.deepEqual([...sections.keys()], ['1.1.0', '1.0.0']);
  assert.ok(sections.get('1.1.0').endsWith('~~~~'));
  assert.deepEqual(changelogViolations(text, text.replace('- After the example.', '- Edited.'), new Set(['1.1.0', '1.0.0'])),
    ['CHANGELOG.md: the released section [1.1.0] was changed']);
});

test('a fence left open hides no released section', () => {
  const base = '## [Unreleased]\n\n```sh\nexample\n\n## [1.0.0] - 2026-01-01\n\n- Added a.\n';
  assert.deepEqual([...changelogSections(base).keys()], ['Unreleased', '1.0.0']);
  assert.deepEqual(changelogViolations(base, base.replace('- Added a.', '- Edited.'), new Set(['1.0.0'])),
    ['CHANGELOG.md: the released section [1.0.0] was changed']);
  // Closing the fence in the head would otherwise hide the edit behind a section that now exists.
  assert.deepEqual(
    changelogViolations(base, base.replace('example\n', 'example\n```\n').replace('- Added a.', '- Edited.'), new Set(['1.0.0'])),
    ['CHANGELOG.md: the released section [1.0.0] was changed']);
  // A head that opens a fence over a released section still loses nothing.
  const closed = '## [Unreleased]\n\n## [1.0.0] - 2026-01-01\n\n- Added a.\n';
  assert.deepEqual(changelogViolations(closed, closed.replace('## [Unreleased]\n', '## [Unreleased]\n\n```\n'), new Set(['1.0.0'])), []);
  assert.deepEqual(
    changelogViolations(closed, closed.replace('## [Unreleased]\n', '## [Unreleased]\n\n```\n').replace('- Added a.', '- Edited.'), new Set(['1.0.0'])),
    ['CHANGELOG.md: the released section [1.0.0] was changed']);
});

test('a new release section and unreleased edits pass', () => {
  const head = CHANGELOG.replace('## [Unreleased]\n', '## [Unreleased]\n\n- Fixed c.\n\n## [1.2.0] - 2026-03-01\n\n- Added c.\n');
  assert.deepEqual(changelogViolations(CHANGELOG, head, new Set(['1.0.0', '1.1.0'])), []);
});

test('releasing the unreleased section passes before its tag exists', () => {
  const base = CHANGELOG.replace('## [Unreleased]\n', '## [Unreleased]\n\n- Fixed c.\n');
  const head = CHANGELOG.replace('## [Unreleased]\n', '## [Unreleased]\n\n## [1.2.0] - 2026-03-01\n\n- Fixed c.\n');
  assert.deepEqual(changelogViolations(base, head, new Set(['1.0.0', '1.1.0'])), []);
});

test('an edited, removed or renamed released section fails', () => {
  const released = new Set(['1.0.0', '1.1.0']);
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace('- Added a.', '- Added a, fixed.'), released),
    ['CHANGELOG.md: the released section [1.0.0] was changed']);
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace(/## \[1\.0\.0\][^]*$/, ''), released),
    ['CHANGELOG.md: the released section [1.0.0] was removed or renamed']);
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace('2026-02-01', '2026-02-02'), released),
    ['CHANGELOG.md: the released section [1.1.0] was changed']);
});

test('a section whose version has no tag may still change', () => {
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace('- Added b.', '- Added b and d.'), new Set(['1.0.0'])), []);
});

test('a second section under a released heading fails wherever it is put', () => {
  const released = new Set(['1.0.0', '1.1.0']);
  const original = '## [1.0.0] - 2026-01-01\n\n- Added a.\n';
  const edited = '## [1.0.0] - 2026-01-01\n\n- Rewritten.\n';
  const repeated = ['CHANGELOG.md: the released section [1.0.0] appears more than once'];
  // An edited copy above the original: the last section of a name used to be the only one read.
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace(original, `${edited}\n${original}`), released), repeated);
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace(original, `${original}\n${edited}`), released), repeated);
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace(original, `${original}\n${original}`), released), repeated);
  // Away from the original, under another release.
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace('## [1.1.0]', `${edited}\n## [1.1.0]`), released), repeated);
  // A heading in a fenced example is not a second section; it is an edit of the section it sits in.
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace('- Added b.', '- Added b.\n\n```md\n## [1.0.0] - 2026-01-01\n```'), released),
    ['CHANGELOG.md: the released section [1.1.0] was changed']);
});

test('a repeated section keeps the text of every copy', () => {
  const text = '## [Unreleased]\n\n- One.\n\n## [1.0.0] - 2026-01-01\n\n- Added a.\n\n## [Unreleased]\n\n- Two.\n';
  assert.deepEqual([...repeatedSections(text)], ['Unreleased']);
  assert.deepEqual([...repeatedSections(CHANGELOG)], []);
  assert.equal(changelogSections(text).get('Unreleased'), '## [Unreleased]\n\n- One.\n\n## [Unreleased]\n\n- Two.');
  // An unreleased name may repeat, and a release that already repeats is held to all of its text.
  assert.deepEqual(changelogViolations(text, text, new Set(['1.0.0', 'Unreleased'])), []);
  assert.deepEqual(changelogViolations(CHANGELOG, CHANGELOG.replace('## [Unreleased]\n', '## [Unreleased]\n\n## [Unreleased]\n'), new Set(['1.0.0', '1.1.0'])), []);
  assert.deepEqual(changelogViolations(text, text.replace('- Two.', '- Three.'), new Set(['Unreleased'])),
    ['CHANGELOG.md: the released section [Unreleased] was changed']);
});

test('archive paths map to their version', () => {
  assert.equal(archivedVersion('website/src/content/docs/0.9.2/architecture.md'), '0.9.2');
  assert.equal(archivedVersion('website/src/assets/0.9.2/pr-approval.png'), '0.9.2');
  assert.equal(archivedVersion('website/src/content/versions/0.9.2.json'), '0.9.2');
  assert.equal(archivedVersion('website/src/content/docs/architecture.md'), null);
  assert.equal(archivedVersion('website/src/assets/icon.png'), null);
});

test('only an existing archive of a released version is locked', () => {
  const changes = [
    { status: 'modified', path: 'website/src/content/docs/0.9.2/architecture.md' },
    { status: 'added', path: 'website/src/content/docs/0.9.3/architecture.md' },
    { status: 'deleted', path: 'website/src/assets/0.9.2/pr-approval.png' },
    { status: 'modified', path: 'website/src/content/docs/architecture.md' },
    { status: 'added', path: 'website/src/content/docs/0.9.4/index.md' },
  ];
  assert.deepEqual(archiveViolations(changes, new Set(['0.9.2', '0.9.3']), new Set(['0.9.2', '0.9.4'])), [
    'website/src/content/docs/0.9.2/architecture.md: an archived docs file of a released version was modified',
    'website/src/assets/0.9.2/pr-approval.png: an archived docs file of a released version was deleted',
  ]);
});

test('versions.json may gain entries and change current, but not lose or change one', () => {
  const base = JSON.stringify({ current: { label: 'v1.1.0' }, versions: [{ slug: '1.0.0', label: 'v1.0.0' }] });
  const added = JSON.stringify({ current: { label: 'v1.2.0' }, versions: [{ slug: '1.1.0', label: 'v1.1.0' }, { slug: '1.0.0', label: 'v1.0.0' }] });
  assert.deepEqual(versionsViolations(base, added), []);
  assert.equal(versionsViolations(base, JSON.stringify({ versions: [{ slug: '1.0.0', label: 'v1.0' }] })).length, 1);
  assert.equal(versionsViolations(base, null).length, 1);
  assert.deepEqual(versionsViolations(null, added), []);
});

function repo() {
  const dir = mkdtempSync(join(tmpdir(), 'released-history-'));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const write = (path, text) => { mkdirSync(dirname(join(dir, path)), { recursive: true }); writeFileSync(join(dir, path), text); };
  git('init', '-q', '-b', 'main');
  git('config', 'user.email', 'test@example.com');
  git('config', 'user.name', 'Test');
  git('config', 'commit.gpgsign', 'false');
  git('config', 'tag.gpgsign', 'false');
  const commit = (message) => { git('add', '-A'); git('commit', '-q', '-m', message); return git('rev-parse', 'HEAD'); };
  return { dir, git, write, commit, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('the whole check against a real repository', () => {
  const r = repo();
  try {
    r.write('CHANGELOG.md', CHANGELOG);
    r.write('website/src/content/docs/1.0.0/index.md', 'v1.0.0 docs\n');
    r.write('website/versions.json', JSON.stringify({ versions: [{ slug: '1.0.0', label: 'v1.0.0' }] }));
    const base = r.commit('release');
    r.git('tag', 'v1.0.0');
    r.git('tag', 'v1.1.0');

    r.write('CHANGELOG.md', CHANGELOG.replace('## [Unreleased]\n', '## [Unreleased]\n\n- Fixed c.\n'));
    r.write('website/src/content/docs/1.1.0/index.md', 'v1.1.0 docs\n');
    r.write('website/src/content/docs/index.md', 'current docs\n');
    const allowed = r.commit('ok');
    assert.deepEqual(check(new Git(r.dir), base, allowed), []);

    r.write('CHANGELOG.md', CHANGELOG.replace('- Added a.', '- Added a (corrected).'));
    r.write('website/src/content/docs/1.0.0/index.md', 'edited\n');
    const edited = r.commit('rewrite history');
    assert.deepEqual(check(new Git(r.dir), base, edited), [
      'website/src/content/docs/1.0.0/index.md: an archived docs file of a released version was modified',
      'CHANGELOG.md: the released section [1.0.0] was changed',
    ]);
  } finally {
    r.cleanup();
  }
});

test('a file name with spaces is read whole', () => {
  const r = repo();
  try {
    r.write('website/src/content/docs/1.0.0/a b.md', 'x\n');
    const base = r.commit('release');
    r.git('tag', 'v1.0.0');
    r.write('website/src/content/docs/1.0.0/a b.md', 'y\n');
    const head = r.commit('edit');
    assert.deepEqual(check(new Git(r.dir), base, head),
      ['website/src/content/docs/1.0.0/a b.md: an archived docs file of a released version was modified']);
  } finally {
    r.cleanup();
  }
});

test('every changed file is listed once, in order, with its status', () => {
  const r = repo();
  try {
    for (let i = 0; i < 5; i++) r.write(`f${i}.txt`, 'x\n');
    const base = r.commit('base');
    r.git('tag', 'v1.0.0');
    r.write('f0.txt', 'y\n');
    r.git('rm', '-q', 'f1.txt');
    r.write('f9.txt', 'new\n');
    const head = r.commit('change');
    assert.deepEqual(new Git(r.dir).changes(base, head), [
      { status: 'modified', path: 'f0.txt' },
      { status: 'deleted', path: 'f1.txt' },
      { status: 'added', path: 'f9.txt' },
    ]);
  } finally {
    r.cleanup();
  }
});

test('a branch cut before an archive existed cannot add to it once released', () => {
  const r = repo();
  try {
    r.write('README.md', 'x\n');
    const root = r.commit('root');
    r.write('website/src/content/docs/1.0.0/index.md', 'v1.0.0 docs\n');
    const main = r.commit('archive 1.0.0');
    r.git('tag', 'v1.0.0');
    r.git('checkout', '-q', '-b', 'stale', root);
    r.write('website/src/content/docs/1.0.0/extra.md', 'sneaked in\n');
    const head = r.commit('add to the archive');
    assert.deepEqual(check(new Git(r.dir), main, head),
      ['website/src/content/docs/1.0.0/extra.md: an archived docs file of a released version was added']);
  } finally {
    r.cleanup();
  }
});

test('a repository with no release tag fails closed', () => {
  const r = repo();
  try {
    r.write('CHANGELOG.md', CHANGELOG);
    const base = r.commit('release');
    r.write('CHANGELOG.md', CHANGELOG.replace('- Added a.', '- Rewritten.'));
    const head = r.commit('edit');
    assert.deepEqual(check(new Git(r.dir), base, head),
      ['No vX.Y.Z release tag was found, so nothing can be checked; fetch the tags first']);
  } finally {
    r.cleanup();
  }
});
