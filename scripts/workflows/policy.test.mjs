import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { changelogSections, fenceReader, repeatedSections, withoutContainers } from '../released-history/check.mjs';

// Rules the workflows and the files around them are held to. They are read as text, the way a
// reviewer reads them, so a rule that stops holding fails here with the file and the line.

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => readFileSync(join(root, path), 'utf8');
const workflowDir = '.github/workflows';
const workflows = readdirSync(join(root, workflowDir)).filter((name) => name.endsWith('.yml')).map((name) => `${workflowDir}/${name}`);
const actionDirs = readdirSync(join(root, '.github/actions')).filter((name) => existsSync(join(root, '.github/actions', name, 'action.yml')));
const actionFiles = actionDirs.map((name) => `.github/actions/${name}/action.yml`);

/** The jobs of a workflow as `name -> text`, split on the two-space keys under `jobs:`. */
function jobsOf(path) {
  const lines = read(path).split('\n');
  const jobs = new Map();
  let name = null;
  for (const line of lines.slice(lines.indexOf('jobs:') + 1)) {
    const key = line.match(/^ {2}([A-Za-z0-9_-]+):\s*$/);
    if (key) { name = key[1]; jobs.set(name, ''); } else if (name) jobs.set(name, `${jobs.get(name)}${line}\n`);
  }
  return jobs;
}

/** The steps of a job, each as its own text, split on the list items under `steps:`. */
function stepsOf(job) {
  const body = job.slice(job.indexOf('    steps:\n') + '    steps:\n'.length);
  return body.split(/^(?= {6}- )/m).filter((step) => step.trim() !== '');
}

const usesIn = (text) => [...text.matchAll(/^\s*(?:- )?uses:\s*(\S+)(?:\s+#\s*(\S+))?/gm)].map((match) => ({ target: match[1], comment: match[2] }));

test('every action is pinned to a full commit SHA with a version comment', () => {
  for (const path of [...workflows, ...actionFiles]) {
    for (const { target, comment } of usesIn(read(path))) {
      if (target.startsWith('./')) continue;
      assert.match(target, /@[0-9a-f]{40}$/, `${path}: ${target} is not pinned to a commit`);
      assert.match(comment ?? '', /^v\d/, `${path}: ${target} has no version comment`);
    }
  }
});

test('an action is pinned to the same commit wherever it is used', () => {
  const pins = new Map();
  for (const path of [...workflows, ...actionFiles]) {
    for (const { target } of usesIn(read(path))) {
      if (target.startsWith('./')) continue;
      const [name, sha] = target.split('@');
      const repository = name.split('/').slice(0, 2).join('/');
      if (!pins.has(repository)) pins.set(repository, new Map());
      pins.get(repository).set(sha, path);
    }
  }
  for (const [repository, shas] of pins) {
    assert.equal(shas.size, 1, `${repository} is pinned to ${shas.size} commits: ${[...shas].map(([sha, path]) => `${sha} in ${path}`).join(', ')}`);
  }
});

test('Dependabot watches the workflows and every composite action directory', () => {
  const dependabot = read('.github/dependabot.yml');
  const block = dependabot.slice(dependabot.indexOf('package-ecosystem: "github-actions"')).split(/^ {2}- package-ecosystem:/m)[0];
  assert.match(block, /^\s+- "\/"$/m);
  for (const name of actionDirs) assert.ok(block.includes(`- "/.github/actions/${name}"`), `.github/actions/${name} is not in dependabot.yml`);
});

test('CODEOWNERS starts with a default owner for every path', () => {
  const patterns = read('.github/CODEOWNERS').split('\n').filter((line) => line.trim() !== '' && !line.startsWith('#'));
  assert.match(patterns[0], /^\* (@\S+)( @\S+)*$/);
  assert.equal(patterns.filter((line) => line.startsWith('* ')).length, 1);
});

/** The restore keys of a workflow: the lines that hold a cache key prefix and nothing else. */
const restoreKeysIn = (text) => text.split('\n').map((line) => line.trim().replace(/^restore-keys:\s*/, '')).filter((line) => line.startsWith('${{ runner.os }}'));

/** A restore key that ends at `-m2-` matches the Maven cache of every job that shares what precedes it. */
const sharedMavenPrefixes = (text) => restoreKeysIn(text).filter((key) => key.endsWith('-m2-'));

test('no Maven cache is restored through a prefix shared by every job', () => {
  for (const path of workflows) {
    assert.deepEqual(sharedMavenPrefixes(read(path)), [], `${path}: a restore key matches the Maven cache of any job`);
  }
});

test('the shared Maven prefix is found in a key with a second expression too', () => {
  const text = [
    '          restore-keys: ${{ runner.os }}-sonar',
    '          restore-keys: ${{ runner.os }}-${{ matrix.arch }}-m2-',
    '          restore-keys: |',
    '            ${{ runner.os }}-m2-',
    '            ${{ runner.os }}-${{ matrix.arch }}-m2-  ',
    '            ${{ runner.os }}-${{ matrix.arch }}-m2-native-',
    '            ${{ runner.os }}-m2-release-',
  ].join('\n');
  assert.deepEqual(sharedMavenPrefixes(text), ['${{ runner.os }}-${{ matrix.arch }}-m2-', '${{ runner.os }}-m2-', '${{ runner.os }}-${{ matrix.arch }}-m2-']);
});

test('pull request runs of CI never save the Maven cache', () => {
  for (const [name, job] of jobsOf('.github/workflows/ci.yml')) {
    for (const step of stepsOf(job).filter((text) => text.includes('actions/cache/save@'))) {
      assert.match(step, /if: .*github\.event_name != 'pull_request'/, `ci.yml ${name} saves a cache on pull requests`);
    }
  }
});

test('a test image built from a pull request saves no cache', () => {
  const path = '.github/workflows/docker-test-image.yml';
  const text = read(path);
  let saves = 0;
  for (const [name, job] of jobsOf(path)) {
    for (const step of stepsOf(job)) {
      if (step.includes('actions/cache/save@')) {
        saves += 1;
        assert.match(step, /if: .*inputs\.pr_number == ''/, `${name} saves the Maven cache for a pull request build`);
      }
      // setup-node writes the npm cache in its post step whenever `cache` is set.
      const npmCache = step.match(/^\s+cache: (.*)$/m);
      if (npmCache) assert.match(npmCache[1], /inputs\.pr_number == '' &&/, `${name} saves the npm cache for a pull request build`);
      const layerCache = step.match(/^\s+cache-to: (.*)$/m);
      if (layerCache) assert.match(layerCache[1], /inputs\.pr_number == '' &&/, `${name} saves the Docker layer cache for a pull request build`);
    }
  }
  assert.ok(saves > 0 && text.includes('cache-to:'), 'the workflow no longer has the cache steps this test guards');
  assert.doesNotMatch(text, /\bactions\/cache@/, 'actions/cache saves in its post step with no condition; use cache/restore and cache/save');
});

test('the test image job that can write to the registry runs no build from the commit', () => {
  const jobs = jobsOf('.github/workflows/docker-test-image.yml');
  const privileged = [...jobs].filter(([, job]) => /^\s+(packages|pull-requests|contents|id-token): write$/m.test(job));
  assert.deepEqual(privileged.map(([name]) => name), ['build-push']);
  const job = jobs.get('build-push');
  assert.doesNotMatch(job, /mvnw|\bnpm\b|setup-java|setup-node|uses: \.\//, 'build-push runs build tooling or a local action');
  assert.doesNotMatch(job, /gh pr checkout/);
  // A Dockerfile's RUN steps are the commit's code too: the image is built where nothing can be written.
  assert.doesNotMatch(job, /actions\/checkout@|docker\/build-push-action@|docker build|buildx/, 'build-push checks out or builds the commit');
  assert.match(jobs.get('image-build'), /push: false/);
  assert.doesNotMatch(jobs.get('image-build'), /docker\/login-action|skopeo login|secrets\./, 'image-build holds a registry credential');
  for (const [name, text] of jobs) {
    for (const step of stepsOf(text).filter((s) => s.includes('actions/checkout@'))) {
      assert.match(step, /persist-credentials: false/, `${name} leaves the token in the checkout`);
    }
    if (name !== 'build-push' && /mvnw|npm /.test(text)) assert.doesNotMatch(text, /docker\/login-action/, `${name} builds and logs in to the registry`);
  }
});

test('a gh command in a job with no checkout names the repository', () => {
  for (const path of workflows) {
    for (const [name, job] of jobsOf(path)) {
      if (job.includes('actions/checkout@') || /^\s+GH_REPO:/m.test(job)) continue;
      for (const line of job.split('\n').filter((text) => /(?:^|[\s(])gh (?:pr|issue|release|run|workflow|label|cache) /.test(text) && !text.trim().startsWith('#'))) {
        assert.match(line, / (?:--repo|-R) "\$(?:REPO|GITHUB_REPOSITORY|\{GITHUB_REPOSITORY\})"/, `${path} job ${name}: '${line.trim()}' has no git remote to read the repository from`);
      }
    }
  }
  const comment = stepsOf(jobsOf('.github/workflows/docker-test-image.yml').get('build-push')).find((step) => step.includes('name: Comment on PR'));
  assert.match(comment, /^\s+gh pr comment "\$PR" --repo "\$GITHUB_REPOSITORY" /m);
});

test('the released-history check reads the test merge of the head it was started for', () => {
  const path = '.github/workflows/released-history.yml';
  const text = read(path);
  assert.match(text, /^ {2}pull_request_target:$/m);
  assert.match(text, /^permissions:\n {2}contents: read\n\njobs:/m, 'the workflow token can do more than read');
  const job = jobsOf(path).get('released-history');
  assert.doesNotMatch(job, /^\s+permissions:/m);
  // Only the default checkout: the pull request reaches the job as git objects, never as files.
  const checkouts = stepsOf(job).filter((step) => step.includes('actions/checkout@'));
  assert.equal(checkouts.length, 1);
  assert.doesNotMatch(checkouts[0], /^\s+(?:ref|repository):/m, 'the checkout names a ref');
  assert.doesNotMatch(job, /git (?:checkout|switch|worktree|merge|pull|reset)\b|gh pr checkout|npm |\bnpx\b/, 'the job checks out or runs something from the pull request');
  const fetch = stepsOf(job).find((step) => step.includes('refs/pull/${PR_NUMBER}/merge'));
  assert.match(fetch, /^\s+PR_HEAD_SHA: \$\{\{ github\.event\.pull_request\.head\.sha \}\}$/m);
  assert.doesNotMatch(fetch.slice(fetch.indexOf('run: |')), /\$\{\{/, 'the script interpolates an expression');
  assert.match(fetch, /merged_head=\$\(git rev-parse --verify --quiet "refs\/remotes\/pull\/merge\^2"/);
  const guard = fetch.indexOf('if [ "$merged_head" != "$PR_HEAD_SHA" ]; then');
  assert.ok(guard > 0 && fetch.slice(guard).search(/\n\s+exit 1\n/) > 0, 'a test merge of another head is not refused');
  assert.ok(guard < fetch.indexOf('>> "$GITHUB_ENV"'), 'the merge is handed on before its head is compared');
  assert.match(job, /run: BASE_SHA="\$MERGE_BASE_SHA" HEAD_SHA="\$MERGE_SHA" node scripts\/released-history\/check\.mjs$/m);
});

// An ATX heading of level three: up to three spaces, `###`, then its text or the end of the line.
const SUBHEADING = /^ {0,3}###(?:[ \t]+(.*))?$/;

/**
 * The level-three headings of a changelog section, leaving out the ones inside a fenced code
 * block. A heading may start up to three spaces in and may end with a closing run of `#`. A
 * heading or a fence that opens a block quote or a list item is read as one, since GitHub shows
 * a heading there too. A fence that is never closed hides nothing: every heading then counts,
 * so a stray fence cannot take the headings after it out of the check. Fences and the block quote
 * and list markers are read by the released-history check's own helpers, so both read a changelog
 * the same way.
 */
function subheadingsOf(section) {
  const lines = section.split('\n');
  return subheadingsIn(lines, true) ?? subheadingsIn(lines, false);
}

/** The headings of {@code lines}, or null when fences are honoured and one is left open. */
function subheadingsIn(lines, honourFences) {
  const headings = [];
  const fences = honourFences ? fenceReader() : null;
  for (const line of lines) {
    if (!fences?.inFence(line)) headings.push(...subheadingOf(withoutContainers(line)));
  }
  return fences?.isOpen() ? null : headings;
}

/** The heading text of {@code line} in a list of one, or an empty list when it is no heading. */
function subheadingOf(line) {
  const match = SUBHEADING.exec(line.trimEnd());
  return match ? [(match[1] ?? '').replace(/(?:^|[ \t]+)#+$/, '').trim()] : [];
}

const repeatedIn = (values) => [...new Set(values.filter((value, index) => values.indexOf(value) !== index))];

test('the changelog names each version once, and each kind of change once in the unreleased section', () => {
  const changelog = read('CHANGELOG.md');
  assert.deepEqual([...repeatedSections(changelog)], [], 'CHANGELOG.md has two sections for one version');
  // Released sections stay as they shipped, so only the unreleased one is held to this.
  assert.deepEqual(repeatedIn(subheadingsOf(changelogSections(changelog).get('Unreleased'))), [], 'CHANGELOG.md: [Unreleased] has a heading twice; merge the two lists');
});

test('a repeated changelog heading is found outside fenced examples only', () => {
  const section = ['## [Unreleased]', '', '### Fixed', '- a', '', '```md', '### Fixed', '```', '', '~~~', '```', '### Security', '~~~', '', '### Security', '- b', '', '### Fixed', '- c'].join('\n');
  assert.deepEqual(subheadingsOf(section), ['Fixed', 'Security', 'Fixed']);
  assert.deepEqual(repeatedIn(subheadingsOf(section)), ['Fixed']);
});

test('an unclosed fence hides no changelog heading', () => {
  const heading = '### Security';
  const section = ['## [Unreleased]', '', heading, '- a', '', '```', '', heading, '- b'].join('\n');
  assert.deepEqual(subheadingsOf(section), ['Security', 'Security']);
  assert.deepEqual(repeatedIn(subheadingsOf(section)), ['Security']);
  // A fence that is closed after all still hides what is inside it.
  assert.deepEqual(subheadingsOf(`${section}\n\`\`\``), ['Security']);
});

test('a line that only looks like a code fence hides no changelog heading', () => {
  // A backtick fence has no backtick in its info string, and only spaces or tabs follow a closing
  // fence: the same rules the released-history check reads a changelog by.
  assert.deepEqual(subheadingsOf(['### Fixed', '``` a`b', '### Fixed', '```', '``` a`b', '```'].join('\n')), ['Fixed', 'Fixed']);
  assert.deepEqual(subheadingsOf(['```', '```\u00a0', '### Fixed', '```', '### Security'].join('\n')), ['Security']);
  assert.deepEqual(subheadingsOf(['~~~ a`b', '### Fixed', '~~~\t', '### Security'].join('\n')), ['Security']);
  // A fence in a list item ends with the item, and a marked line closes no fence outside one.
  assert.deepEqual(subheadingsOf(['- ```', '### Fixed', '```', '```', '### Fixed'].join('\n')), ['Fixed', 'Fixed']);
  assert.deepEqual(subheadingsOf(['```', '- ```', '### Fixed', '```', '### Security'].join('\n')), ['Security']);
});

test('a changelog heading counts with up to three spaces before it', () => {
  const section = ['### Fixed', ' ### Fixed', '  ### Added', '   ### Security', '    ### Changed', '\t### Changed', '#### Removed', '###Removed',
    '  #1318): wrapped text', '###\tSecurity', '### Added ###', '### Fixed\r', '###'].join('\n');
  assert.deepEqual(subheadingsOf(section), ['Fixed', 'Fixed', 'Added', 'Security', 'Security', 'Added', 'Fixed', '']);
  assert.deepEqual(repeatedIn(subheadingsOf(section)), ['Fixed', 'Security', 'Added']);
});

test('a changelog heading counts inside a block quote or a list item', () => {
  const section = ['### Fixed', '> ### Fixed', '- ### Security', '1. ### Security', '> - ### Added', '* text', '- #1318): text', '>### Changed'].join('\n');
  assert.deepEqual(subheadingsOf(section), ['Fixed', 'Fixed', 'Security', 'Security', 'Added', 'Changed']);
  assert.deepEqual(repeatedIn(subheadingsOf(section)), ['Fixed', 'Security']);
});

test('a fence that opens a list item or a block quote hides the headings inside it', () => {
  const section = ['### Fixed', '- ```md', '  ### Fixed', '  ```', '> ~~~', '> ### Security', '> ~~~', '### Security'].join('\n');
  assert.deepEqual(subheadingsOf(section), ['Fixed', 'Security']);
  assert.deepEqual(repeatedIn(subheadingsOf(section)), []);
});

test('the Maven wrapper checks the distribution it downloads', () => {
  assert.match(read('.mvn/wrapper/maven-wrapper.properties'), /^distributionSha256Sum=[0-9a-f]{64}$/m);
});

test('the docs workflow builds only the ref it runs on', () => {
  const text = read('.github/workflows/docs.yml');
  assert.doesNotMatch(text, /inputs\./, 'docs.yml takes an input');
  for (const step of stepsOf(jobsOf('.github/workflows/docs.yml').get('build')).filter((s) => s.includes('actions/checkout@'))) {
    assert.doesNotMatch(step, /^\s+ref:/m, 'docs.yml checks out a ref other than its own');
    assert.match(step, /persist-credentials: false/);
  }
  assert.match(text, /npm ci --ignore-scripts/);
  assert.doesNotMatch(text, /npm ci\s*$/m);
});

test('a workflow that runs with the base repository\'s rights checks out no pull request ref', () => {
  for (const path of workflows) {
    const text = read(path);
    if (!/^\s+(?:pull_request_target|workflow_run):/m.test(text)) continue;
    for (const [name, job] of jobsOf(path)) {
      for (const step of stepsOf(job).filter((s) => s.includes('actions/checkout@'))) {
        assert.doesNotMatch(step, /^\s+ref:.*github\.event\.(?:pull_request|workflow_run)/m, `${path} job ${name} checks out a pull request ref`);
      }
    }
  }
});

test('main CI attests the images the release workflow promotes', () => {
  const docker = jobsOf('.github/workflows/ci.yml').get('docker');
  assert.match(docker, /^\s+id-token: write$/m);
  assert.match(docker, /^\s+attestations: write$/m);
  const attested = stepsOf(docker).filter((step) => step.includes('actions/attest-build-provenance@'));
  assert.deepEqual(attested.map((step) => step.match(/subject-digest: (.*)$/m)[1]),
    ['${{ steps.default.outputs.digest }}', '${{ steps.distroless.outputs.digest }}']);
  for (const step of attested) assert.match(step, /push-to-registry: true/);
});

test('the release workflow judges the tag before anything else and with its own checker', () => {
  const verify = jobsOf('.github/workflows/release.yml').get('verify');
  assert.doesNotMatch(verify, /mvnw|setup-java|actions\/cache/, 'verify runs the tagged build');
  assert.match(verify, /ref: refs\/tags\/\$\{\{ inputs\.version \|\| github\.ref_name \}\}/);
  assert.match(verify, /fetch-depth: 0/);
  assert.match(verify, /run: node \.workflow-ref\/scripts\/release\/check-tag\.mjs/);
  // A tag push runs on the tag: a checkout without a ref would take the checker from it.
  const scripts = stepsOf(verify).find((step) => step.includes('path: .workflow-ref'));
  assert.match(scripts, /^\s+ref: refs\/heads\/main$/m, 'the release checker is not taken from main');
  assert.match(verify, /RELEASE_TAG_SIGNERS: \S+/);
  const check = verify.slice(verify.indexOf('gh attestation verify'));
  for (const flag of ['--signer-workflow "${GITHUB_REPOSITORY}/.github/workflows/ci.yml"', '--source-ref refs/heads/main', '--source-digest "$SHA"', '--deny-self-hosted-runners']) {
    assert.ok(check.includes(flag), `the CI provenance check lacks ${flag}`);
  }
});

test('the release workflow scans, promotes, signs and extracts by digest', () => {
  const jobs = jobsOf('.github/workflows/release.yml');
  for (const name of ['scan', 'promote', 'release']) {
    const job = jobs.get(name);
    assert.match(job, /needs\.verify\.outputs\.digest/, `${name} does not use the verified digest`);
    assert.doesNotMatch(job, /:\$\{\{ needs\.verify\.outputs\.(sha|tag) \}\}/, `${name} names an image by a tag`);
    assert.doesNotMatch(job, /ref: \$\{\{ needs\.verify\.outputs\.tag \}\}/, `${name} checks out the tag by name`);
    for (const line of job.split('\n').filter((text) => /\b(docker (pull|create)|cosign sign|crane tag) /.test(text) && !text.trim().startsWith('#') && !text.includes('echo'))) {
      assert.match(line, /\$\{IMAGE\}@\$\{?(DIGEST|DIGEST_DISTROLESS|digest)\}?"/, `${name}: '${line.trim()}' does not name the image by digest`);
    }
  }
  assert.doesNotMatch(jobs.get('promote'), /crane digest "\$\{IMAGE\}:\$\{\{/);
});

test('the publishing jobs of the release workflow run in the release environment', () => {
  const jobs = jobsOf('.github/workflows/release.yml');
  for (const name of ['promote', 'release']) assert.match(jobs.get(name), /^ {4}environment: release$/m, `${name} is not gated`);
  for (const [name, job] of jobs) {
    if (/^\s+(packages|id-token|attestations): write$/m.test(job)) {
      assert.match(job, /^ {4}environment: release$/m, `${name} can publish or sign outside the release environment`);
    }
  }
});

test('crane is checked against a pinned SHA-256 before it is unpacked', () => {
  const text = read('.github/workflows/release.yml');
  const installs = text.split(/^(?= {6}- )/m).filter((step) => step.includes('go-containerregistry'));
  assert.ok(installs.length > 0);
  for (const step of installs) {
    assert.match(step, /CRANE_SHA256: "[0-9a-f]{64}"/);
    assert.ok(step.indexOf('sha256sum -c') > 0 && step.indexOf('sha256sum -c') < step.indexOf('tar -x'), 'crane is unpacked before its checksum is checked');
    assert.doesNotMatch(step, /curl[^\n]*\\\n[^\n]*\n\s*\| tar/, 'crane is piped from curl into tar');
  }
});

test('the native builder image is pinned to one digest in the pom and the multistage Dockerfile', () => {
  const pom = read('pom.xml').match(/<quarkus\.native\.builder-image\s*>([^<]+)</)?.[1];
  assert.match(pom ?? '', /^quay\.io\/quarkus\/ubi9-quarkus-mandrel-builder-image@sha256:[0-9a-f]{64}$/);
  const dockerfile = read('src/main/docker/Dockerfile.multistage').match(/^FROM (\S+) AS build$/m)?.[1];
  assert.equal(dockerfile, pom);
});

test('the README verification commands name the release workflow and anchor the identity', () => {
  const readme = read('README.md');
  const section = readme.slice(readme.indexOf('## Verifying a release'), readme.indexOf('## Operations'));
  const patterns = [...section.matchAll(/--certificate-identity-regexp='([^']+)'/g)].map((match) => match[1]);
  assert.ok(patterns.length > 0);
  for (const pattern of patterns) {
    assert.ok(pattern.startsWith('^') && pattern.endsWith('$'), `cosign matches ${pattern} anywhere in the identity`);
    const identity = (ref) => `https://github.com/devops-thiago/ThrillhouseBot/.github/workflows/${ref}`;
    const version = pattern.match(/tags\/(v\d+\\\.\d+\\\.\d+)/)[1].replaceAll('\\', '');
    const accepts = (value) => new RegExp(pattern).test(value);
    assert.ok(accepts(identity(`release.yml@refs/tags/${version}`)));
    assert.ok(accepts(identity('release.yml@refs/heads/main')));
    for (const other of [`docker-test-image.yml@refs/tags/${version}`, 'release.yml@refs/heads/feature', `release.yml@refs/tags/${version}-evil`, 'release.yml@refs/tags/v0.0.1']) {
      assert.ok(!accepts(identity(other)), `${pattern} accepts ${other}`);
    }
    assert.ok(!accepts(`https://github.com/devops-thiago/ThrillhouseBot-fork/.github/workflows/release.yml@refs/heads/main`));
  }
  const attestations = section.split('gh attestation verify').slice(1);
  assert.equal(attestations.length, 2);
  for (const command of attestations) {
    assert.match(command, /--signer-workflow devops-thiago\/ThrillhouseBot\/\.github\/workflows\/release\.yml/);
    assert.match(command, /--source-ref refs\/tags\/v\d+\.\d+\.\d+/);
  }
});
