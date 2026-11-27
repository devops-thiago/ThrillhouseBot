# Security model

This document describes the bot's access controls, trust boundaries and
security limits. To report a vulnerability, see
[SECURITY.md](../SECURITY.md). Class names below are under
`src/main/java/dev/thiagogonzaga/thrillhousebot/`.

## What you can expect

- Only GitHub can trigger a review. Every webhook request must carry a valid
  `X-Hub-Signature-256` HMAC for the configured webhook secret, or it is
  rejected with 400 before the body is parsed.
- The bot reads pull requests; it never checks out, builds or runs their code.
  Diffs, titles, descriptions, comments, linked issues and CI logs are passed to
  the model as text.
- Credentials (`GITHUB_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `AI_API_KEY`,
  `GITHUB_CLIENT_SECRET`, `NOTIFICATIONS_WEBHOOK_SECRET`) are read from the
  environment. They are not stored in the database and are not written to logs.
- Outbound connections to GitHub, the model endpoint and the notification
  webhook use HTTPS by default, with the JDK's certificate and hostname
  verification. The notification webhook refuses a plain `http://` URL unless
  `NOTIFICATIONS_WEBHOOK_ALLOW_HTTP=true` is set.
- The dashboard is limited to users who can read a repository where the App is
  installed, and each user sees only the repositories GitHub lets them read,
  asked with their own sign-in. Installation-wide counters go to the account
  itself or, for an organization, its members.
- Features that send more data to the model or to a third party are off by
  default (CI-failure context, linked-issue context, review learnings, outgoing
  notifications, the security scan).
- When earlier reviews are stored for a pull request and none of them can be
  read, a review that finds nothing does not approve. It ends as `COMMENT` and
  says why, because the bot cannot confirm that what those reviews raised is
  closed. This lasts one review: the held review is stored and can be read, so
  the next one is judged against it and what the unreadable reviews raised is
  no longer tracked.
- A review that does not approve dismisses the approvals the bot gave earlier
  on the same pull request, so an approval of an older head does not go on
  counting for code the bot declined to approve. Only the bot's own reviews are
  dismissed.

## What you cannot expect

- Review findings come from a language model. They can be wrong, and a review
  can miss real defects, including security defects.
- The diff and the surrounding context are sent to the model endpoint you
  configure. What that provider keeps or logs is outside the bot's control.
- The HTTP listener on port 8080 is plain HTTP. Put a TLS-terminating reverse
  proxy in front of it; dashboard session cookies are marked `Secure` and need
  HTTPS to work.
- OAuth sessions and some review state live in memory in a single process (see
  Known limitations in the README). A restart signs dashboard users out.
- The bot's approval is not withdrawn at the moment commits are pushed. It is
  dismissed when the review of the new head finishes without approving, and it
  stays if that review never runs or fails, if the bot could not read the pull
  request's reviews, or if a branch protection rule stops the App from
  dismissing reviews. The bot logs a warning in each of the last two cases:
  one when the reviews could not be listed, saying that earlier approvals were
  not checked, and one for each approval it could not dismiss. A refusal for
  want of permission (a 403 that is not a rate limit) ends the attempt for
  that round. Any other failure (a timeout, a server error, a rate limit, or a
  404 or 422 for one review) leaves that approval in place and the bot goes on
  to its older ones. The bot reads at most 1,000 reviews per pull request,
  oldest first, and logs a warning when it reaches that limit, since an
  approval past it is not seen. A push inside `AUTO_REVIEW_MIN_INTERVAL` or
  past a full repository queue is reviewed late, not skipped, so a pull
  request author cannot time a push to avoid the review. That later review is
  held in memory: it never runs if the bot restarts while it waits, if 10,000
  pull requests already have one waiting, or if the repository's queue stays
  full for an hour. If the bot's
  approval counts towards a required review, turn on "Dismiss stale pull
  request approvals when new commits are pushed" for the branch.
- Only the latest release gets security fixes.

## Threat model

Assets:

- The GitHub App private key and the installation tokens minted from it, which
  can write reviews, comments, check runs and labels on installed repositories.
  A token minted for work on one repository reaches that repository only.
- The model API key, which costs money to use.
- The webhook secret, the OAuth client secret and dashboard session tokens.
- Review history and cost data in PostgreSQL.
- Private source code in the diffs the bot reads.

Attackers considered:

- Anyone on the internet who can reach the webhook endpoint and send forged
  requests.
- A pull request author, including on a fork, who controls the diff, title,
  description, comments and any text the bot reads from the PR. They may try
  to inject instructions into the model prompt, make the bot leak data, approve
  a bad change, or run up the model bill.
- A commenter without write access who tries to trigger paid reviews, to make
  the bot post, or to get a finding closed or declined so that the bot approves.
- A model endpoint that returns malformed, oversized or hostile output.
- A web visitor trying to reach the dashboard or ride on a signed-in session.

## Trust boundaries

| Boundary | Data crossing it | Control |
|----------|------------------|---------|
| GitHub to `POST /api/webhook` | Event payloads | HMAC-SHA256 signature check, delivery-ID deduplication, Jackson parsing into typed records |
| Bot to GitHub API | Reads of PR data, writes of reviews and comments | Short-lived installation tokens over HTTPS, limited to the repository being worked on where the bot mints them itself, App permissions limited to what the bot uses; model text is posted with mentions, raw HTML and `suggestion` fences neutralized |
| Bot to model endpoint | Prompts with untrusted PR text out, model text in | Untrusted text fenced in the prompt, model output parsed as data and verified before posting |
| Browser to dashboard | OAuth login, session cookie, API calls | GitHub OAuth with a `state` check, per-request access checks |
| Bot to notification webhook | Review outcome payload | HTTPS required by default, optional HMAC signature |
| Bot to PostgreSQL | Review sessions, feedback, learnings, finding state, webhook delivery ids | Hibernate with bound parameters, credentials from the environment |

## Secure design principles

### Least privilege

The App manifest (`manifest.json`) asks for `contents: write`, `actions: read`,
`statuses: read`, `checks: write`, `pull_requests: write` and `issues: write`.
Issues write is needed because pull request conversation comments, labels and
reactions go through the Issues API; linked issues are only read. Pull requests
write also covers dismissing the bot's own earlier approval.
Contents write is needed only because GitHub refuses `resolveReviewThread`
without it. The bot writes no file, commit or branch, but an installation token
carries the permission, so a leaked token or private key could; an operator who
sets Contents to read gives up thread resolution and nothing else. Every
workflow declares its token permissions, read-only unless a workflow or job
needs a write scope, such as the image push or the Dependency Review comment.
The container images run as an unprivileged user (UID 1001 on UBI9-micro,
`nonroot` on distroless) and the binary is installed read-only (`0555`).

Reviews, commands, replies and reactions run on an installation token minted
for the one repository they concern (`github/GitHubAuthClient`), so a leaked
token does not open the installation's other repositories. Two paths still use
installation-wide tokens: the dashboard backfill, which lists an installation's
repositories, and the GitHub App extension's own client, used to read
repository settings and instructions, check permissions, read linked issues
and decide dashboard access.

### Fail-safe defaults

`WebhookEvents` runs behind the Quarkiverse GitHub App extension's route, which rejects a
request whose signature header is missing or does not match the body, and the application does
not start without a webhook secret. It starts with a short one, and logs a warning when the
secret has fewer than 16 characters: whoever learns the secret can send deliveries the bot
accepts, so use a long random value. With `GITHUB_ALLOWED_ACCOUNTS` set,
an event from an installation on any other account is ignored before any work,
so a public App installed by a stranger never spends the operator's budget. The dashboard refuses a login it
has never been able to check with GitHub, keeps a known login's last answer for
at most 30 minutes while GitHub is unreachable, and ends the session when GitHub
rejects its token.
Organization membership is granted only on GitHub's explicit answer. Optional context and outgoing
notifications are off until enabled, and a manual `/review` needs write access
to the repository (`webhook/ManualReviewAuthorizer`). That check runs before the
bot writes anything for a command: a commenter it refuses gets no 👀 reaction,
no paused or closed notice and no reply. `/help` is the one command open to
anyone, answered for a commenter without write access once per pull request per
hour (`webhook/HelpReplyLimiter`).

A pull request author needs no write access to start automatic reviews, so
their cost is bounded with no setting. One review spends at most
`REVIEW_MAX_TOKENS_PER_REVIEW` tokens (default 1,000,000). A repository runs at
most `AUTO_REVIEW_MAX_CONCURRENT_PER_REPO` automatic reviews at once (default 4)
with at most `AUTO_REVIEW_MAX_QUEUED_PER_REPO` waiting (default 50), and the
process sends at most `AI_MAX_CONCURRENT_CALLS` model calls at once (default
16). A pull request is not reviewed again within `AUTO_REVIEW_MIN_INTERVAL` of
its last automatic review (default one minute). A reopen of an unchanged head
and a repeated ready-for-review of the head just reviewed are skipped; a push
is reviewed when the window closes, the latest head only, so pushes cost at
most one review per window. These
bound how fast spend can accrue, not its total: an author who keeps pushing new
commits to several pull requests keeps the repository's four slots busy. Use
`WEBHOOK_EXCLUDED_AUTHORS`, the label gates or `GITHUB_ALLOWED_ACCOUNTS`, and a
spending limit at the model provider, where that is not acceptable. Setting a
limit to `0` turns it off.

Closing a finding is a maintainer's decision. A reply on a finding's thread and
an `@thrillhousebot resolved` or `declined` directive count only when GitHub's
collaborator-permission answer for the author is write, maintain or admin
(`review/VerifiedWritersResolver`, asked once per author per review). The
comment's `author_association` is a first check that spares the call and never
grants anything: an organization member or invited collaborator with read or
triage access is not a maintainer. A finding the model reports as declined
stays open unless such a reply or directive is behind it, whatever any other
reply on the thread says. When the permission cannot be read, nobody is
confirmed and the finding stays open.

### Complete mediation

Every dashboard API call validates the session and checks repository access
through `dashboard/DashboardAccessChecker`, which asks GitHub with the user's own
token and caches the answer for five minutes. The WebSocket closes a connection
that does not present a valid single-use ticket from `POST /api/auth/socket-ticket`,
reads nothing identifying from the endpoint configuration that every handshake
shares, only forwards events for repositories the session may read, and
re-checks that access on a worker thread, closing the connection once it is gone
(`dashboard/DashboardWebSocket`).

### Economy of mechanism

One process with no plugin system and no code execution. Configuration is
checked at startup (`config/StartupConfigValidator`), and the bot refuses to
start when a required value is missing or out of range.

### Separation of credentials

Secrets come only from the environment, so they can be supplied by a secret
manager and rotated with a restart, without rebuilding.

## Common weaknesses countered

| Weakness | Countermeasure | Where |
|----------|----------------|-------|
| Forged webhooks (CWE-345) and timing attacks on the signature (CWE-208) | HMAC-SHA256 over the raw body, compared with `MessageDigest.isEqual` | the Quarkiverse GitHub App extension's route (`quarkus.github-app.webhook-secret`) |
| Replayed deliveries | An `X-GitHub-Delivery` ID seen within `WEBHOOK_DEDUP_TTL` is dropped, across restarts and instances: the ids are stored in the database, and the first instance to insert one handles the delivery. A database failure falls back to the in-memory check. The header is not covered by the signature, so this stops redeliveries, not a replay of a signed body under a new ID | `webhook/WebhookDeduplicator`, `webhook/SeenDeliveries` |
| Prompt injection from PR content | All model-visible external text is wrapped in fences built from a per-call random value and labelled as untrusted data; findings go through a second verification call. The opt-in agent-prompt block (`REVIEW_AGENT_PROMPT_ENABLED`, off by default) repeats model text derived from the PR; treat it as untrusted input to any agent it is pasted into | `review/PromptTemplateEscaper`, AGENTS.md "Untrusted data" |
| Log injection (CWE-117) and secrets in logs (CWE-532) | Model and GitHub text is flattened with `LogSafe.oneLine`, enforced by `LogSafeInvariantTest`; the security scan never repeats a matched secret, and a notification URL is logged as scheme, host and port only | `LogSafe`, `review/SecurityScan`, `notification/` |
| CSRF on login (CWE-352) | Random OAuth `state` in an `HttpOnly`, `Secure`, `SameSite=Lax` cookie, checked on callback | `dashboard/AuthResource` |
| Guessable session IDs (CWE-330) | 256-bit IDs from `SecureRandom`, 8-hour expiry | `dashboard/DashboardSessionStore` |
| SQL injection (CWE-89) | Panache and JPQL queries with bound parameters only | `dashboard/`, `review/FindingFeedbackService` |
| Improper certificate validation (CWE-295) | No custom `TrustManager`, `HostnameVerifier` or trust-all setting; the JDK and Vert.x defaults verify certificates and host names | `config/HttpClientProducer`, `application.properties` |
| Slow or failing parsers on hostile text (CWE-1333, CWE-400) | The patterns run on a pull request's description and added lines read a bounded stretch of text from each place they can start, and an added line over 1,000 characters is not scanned for parser-like code. Ignore and path-scope globs from a repository's settings are matched without backtracking, in time proportional to the glob's length times the path's, and a glob over 500 characters is dropped. A hunk header whose line number fits no `int` opens no hunk instead of failing the review. The screens that look in the model's findings and description gaps for quoted passages, URLs and file names read a run of letters, digits, quotes or punctuation once rather than again from each of its characters | `review/HeuristicCodeDetector`, `review/BugFixContextResolver`, `review/LinearGlob`, `review/PatchIndex`, `review/QuotedPassages`, `review/PathMentions`, `review/DescriptionGapScreen` |
| Unchecked input (CWE-20) | Webhook event types and dashboard parameters are matched against fixed lists, page sizes are clamped, configuration ranges are checked at startup | `webhook/WebhookEvents`, `dashboard/DashboardResource`, `config/StartupConfigValidator` |
| Vulnerable dependencies (CWE-1395) | Dependabot, Dependency Review on every pull request, Trivy on the source tree and images, weekly scheduled scans | `.github/dependabot.yml`, `.github/workflows/` |

CI also runs CodeQL for Java and TypeScript, SpotBugs at maximum effort and the
lowest threshold, and the SonarCloud quality gate, which requires an A security
rating on new code.

Release images and binaries are signed with keyless cosign and carry build
provenance attestations; the README explains how to verify them. The release
workflow publishes only a signed tag on `main`, and only images whose provenance
shows CI built them on `main` from that commit; it scans, tags and signs them by
digest. [RELEASING.md](RELEASING.md) lists these checks and the repository
settings they depend on.
