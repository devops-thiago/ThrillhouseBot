import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const SHA = /^[0-9a-f]{40}$/;
const BOTS = new Set(['dependabot[bot]', 'github-actions[bot]']);
const lower = (value) => value.toLowerCase();
const emailOf = (trailer) => trailer.match(/^[^<>\r\n]+<([^<>\s]+@[^<>\s]+)>$/)?.[1];
const safe = (text) => String(text).replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A');

export class Git {
  constructor(cwd = process.cwd(), token = '') { this.cwd = cwd; this.token = token; }
  run(...args) {
    return execFileSync('git', args, { cwd: this.cwd, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  }
  range(base, head) { return this.run('rev-list', '--no-merges', `${base}..${head}`).split('\n').filter(Boolean); }
  allRange(base, head) { return this.run('rev-list', `${base}..${head}`).split('\n').filter(Boolean); }
  details(sha) {
    const values = (key) => this.run('log', '-1', `--format=%(trailers:key=${key},valueonly)`, sha).split('\n').filter(Boolean);
    return { email: this.run('log', '-1', '--format=%ae', sha), signoffs: values('Signed-off-by'), remediation: values('DCO-Remediation') };
  }
  parents(sha) { return this.run('show', '-s', '--format=%P', sha).split(' ').filter(Boolean); }
  tree(sha) { return this.run('rev-parse', `${sha}^{tree}`); }
  mergeTree(parent, head) { return this.run('merge-tree', '--write-tree', parent, head).split('\n')[0]; }
  fetchPull(number, expected) {
    // Git objects are content-addressed. A locally present API-pinned head already
    // supplies exactly the same evidence; never replace it with a moving branch tip.
    try { this.run('cat-file', '-e', `${expected}^{commit}`); return; }
    catch { /* Fetch the PR ref only when its pinned objects are missing locally. */ }
    // The remote is deliberately fixed by the workflow; never use a PR-supplied clone URL.
    const env = { ...process.env, GIT_TERMINAL_PROMPT: '0' };
    if (this.token) {
      env.GIT_CONFIG_COUNT = '1';
      env.GIT_CONFIG_KEY_0 = 'http.https://github.com/.extraheader';
      env.GIT_CONFIG_VALUE_0 = `AUTHORIZATION: basic ${Buffer.from(`x-access-token:${this.token}`).toString('base64')}`;
    }
    execFileSync('git', ['fetch', '--no-tags', 'origin', `refs/pull/${number}/head`], { cwd: this.cwd, env, timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] });
    if (this.run('rev-parse', 'FETCH_HEAD') !== expected) throw new Error('Source PR head changed while evidence was read');
  }
}

export class GitHub {
  constructor(repository, token = '', fetcher = fetch) {
    if (!/^[A-Za-z0-9][A-Za-z0-9-]*\/[A-Za-z0-9_.-]+$/.test(repository)) throw new Error('Invalid repository');
    this.repository = repository; this.token = token; this.fetcher = fetcher; this.cache = new Map();
  }
  async request(path) {
    const response = await this.fetcher(`https://api.github.com/repos/${this.repository}/${path}`, {
      headers: { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}) },
      signal: AbortSignal.timeout(30000), redirect: 'error',
    });
    if (!response.ok) throw new Error(`GitHub evidence unavailable (HTTP ${response.status})`);
    return response.json();
  }
  async commit(sha) {
    if (!SHA.test(sha)) throw new Error('Invalid commit SHA');
    if (!this.cache.has(sha)) this.cache.set(sha, this.request(`commits/${sha}`));
    const commit = await this.cache.get(sha);
    if (commit.sha !== sha) throw new Error('GitHub returned a different commit');
    return commit;
  }
  async pulls(sha) {
    const rows = [];
    for (let page = 1; ; page++) {
      const batch = await this.request(`commits/${sha}/pulls?per_page=100&page=${page}`);
      if (!Array.isArray(batch)) throw new Error('Invalid source PR response');
      rows.push(...batch);
      if (batch.length < 100) return rows;
    }
  }
}

// A verified signature by a different contributor does not establish the author's identity.
export function authenticatedAuthor(commit) {
  return Boolean(commit.author?.id && commit.commit?.verification?.verified
    && (commit.author.id === commit.committer?.id || commit.committer?.login === 'web-flow'));
}

export class DcoChecker {
  constructor(git, github, repository, log = console.log) {
    this.git = git; this.github = github; this.repository = repository; this.log = log;
    this.evidence = []; this.remediations = new Map();
  }
  exact(details) { return details.signoffs.some((s) => lower(emailOf(s) || '') === lower(details.email)); }
  async bot(sha, opener) {
    if (!BOTS.has(opener?.login)) return false;
    const c = await this.github.commit(sha);
    return c.author?.type === 'Bot' && c.author.id === opener.id && c.author.login === opener.login
      && authenticatedAuthor(c);
  }
  async remediated(sha) {
    for (const remediation of this.remediations.get(sha) || []) {
      const [target, author] = await Promise.all([this.github.commit(sha), this.github.commit(remediation)]);
      if (authenticatedAuthor(author) && target.author?.id && author.author.id === target.author.id) {
        this.evidence.push({ sha, method: 'author-remediation', remediation });
        return true;
      }
    }
    return false;
  }
  automaticMerge(sha, parents) {
    if (parents.length !== 2) return false;
    try { return this.git.mergeTree(parents[0], parents[1]) === this.git.tree(sha); }
    catch { return false; } // A conflict or unreadable tree needs certification.
  }
  async validate(sha, opener, stack = new Set()) {
    if (!SHA.test(sha)) throw new Error('Invalid commit SHA');
    const details = this.git.details(sha);
    if (this.exact(details)) return { sha, method: 'matching-signoff' };
    if (await this.remediated(sha)) return { sha, method: 'author-remediation' };
    const parents = this.git.parents(sha);
    if (parents.length > 1) {
      if (this.automaticMerge(sha, parents)) return { sha, method: 'unchanged-automatic-merge' };
      throw new Error('Merge lacks author certification and unchanged automatic-merge evidence');
    }
    if (await this.bot(sha, opener)) return { sha, method: 'authenticated-bot-pr' };
    if (stack.has(sha) || stack.size >= 20) throw new Error('Circular or excessive source-PR history');
    const next = new Set(stack).add(sha);
    const commit = await this.github.commit(sha);
    // The fallback is only for a GitHub-signed squash of an actually merged PR.
    if (!authenticatedAuthor(commit) || commit.committer?.login !== 'web-flow') throw new Error('No authenticated GitHub squash provenance');
    if (parents.length !== 1) throw new Error('Source-PR fallback requires a single-parent squash commit');
    const pulls = (await this.github.pulls(sha)).filter((p) => p.merged_at && p.merge_commit_sha === sha
      && p.base?.repo?.full_name === this.repository);
    if (pulls.length !== 1) throw new Error('No unique merged source PR for this exact squash SHA');
    const pr = pulls[0];
    if (!Number.isSafeInteger(pr.number) || pr.number <= 0 || !SHA.test(pr.head?.sha || '')) throw new Error('Invalid source PR identity');
    this.git.fetchPull(pr.number, pr.head.sha);
    if (this.git.mergeTree(parents[0], pr.head.sha) !== this.git.tree(sha)) throw new Error('Source PR does not reproduce the squash tree');
    const sources = this.git.range(parents[0], pr.head.sha);
    if (!sources.length) throw new Error('No original contributions found');
    // Apply the same merge certification rule to both histories.
    const merges = [];
    for (const source of this.git.allRange(parents[0], pr.head.sha)) {
      if (this.git.parents(source).length > 1) merges.push(await this.validate(source, pr.user, next));
    }
    const certifications = [];
    for (const source of sources) certifications.push(await this.validate(source, pr.user, next));
    if (details.signoffs.length) {
      // An existing mismatched trailer must itself be traced to the squash author's signed source.
      let alias = false;
      for (const source of sources) {
        const original = this.git.details(source);
        const ownSignoff = this.exact(original)
          && details.signoffs.some((s) => lower(emailOf(s) || '') === lower(original.email));
        const botSource = certifications.find((c) => c.sha === source)?.method === 'authenticated-bot-pr'
          && details.signoffs.some((s) => original.signoffs.some((o) => lower(o) === lower(s)));
        if (!ownSignoff && !botSource) continue;
        const identity = await this.github.commit(source);
        // DCO does not require a cryptographic signature on each original commit.
        // An authenticated PR opened by that same author also binds their signed-off
        // source contribution to the GitHub-signed squash; a third-party PR does not.
        if (identity.author?.id === commit.author.id
          && (authenticatedAuthor(identity) || pr.user?.id === commit.author.id)) alias = true;
      }
      if (!alias) throw new Error('Mismatched trailer has no verified same-author source sign-off');
    }
    const evidence = { sha, method: details.signoffs.length ? 'verified-source-alias' : 'signed-source-contributions', pull: pr.number, head: pr.head.sha, sources: certifications, merges };
    this.evidence.push(evidence);
    return evidence;
  }
  async check(base, head, opener) {
    if (!SHA.test(base) || !SHA.test(head)) throw new Error('Base and head must be full commit SHAs');
    const commits = this.git.allRange(base, head); // git failures are fatal, never an empty passing range.
    this.remediations.clear();
    for (const sha of commits) {
      const d = this.git.details(sha);
      if (!this.exact(d)) continue;
      for (const target of d.remediation) {
        if (!SHA.test(target) || target === sha) continue;
        const entries = this.remediations.get(target) || [];
        this.remediations.set(target, [...entries, sha]);
      }
    }
    const failures = []; const accepted = [];
    for (const sha of commits) {
      try {
        const result = await this.validate(sha, opener);
        accepted.push(result);
        this.log(`ok ${sha}: ${result.method}${result.pull ? ` (source PR #${result.pull})` : ''}`);
      } catch (error) {
        failures.push({ sha, reason: error.message });
        this.log(`::error::DCO ${sha}: ${safe(error.message)}. Supply complete source evidence or an author-signed DCO-Remediation commit; do not rewrite shared history.`);
      }
    }
    return { accepted, failures, evidence: this.evidence };
  }
}

async function main() {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const repository = process.env.GITHUB_REPOSITORY;
  const token = process.env.GITHUB_TOKEN || '';
  const checker = new DcoChecker(new Git(process.cwd(), token), new GitHub(repository, token), repository);
  const result = await checker.check(process.env.BASE_SHA, process.env.HEAD_SHA, event.pull_request.user);
  if (process.env.DCO_EVIDENCE_FILE) writeFileSync(process.env.DCO_EVIDENCE_FILE, JSON.stringify(result, null, 2) + '\n');
  console.log(`Checked ${result.accepted.length + result.failures.length} commit(s); ${result.failures.length} require remediation.`);
  if (result.failures.length) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => { console.error(`::error::DCO evidence check failed: ${safe(error.message)}`); process.exitCode = 1; });
}
