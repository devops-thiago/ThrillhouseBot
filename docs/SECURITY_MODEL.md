# Security model

This document describes the bot's access controls, trust boundaries and
security limits. To report a vulnerability, see
[SECURITY.md](../SECURITY.md). Class names below are under
`src/main/java/dev/thiagogonzaga/thrillhousebot/`.

## What you can expect

- Only GitHub can trigger a review. Every webhook request must carry a valid
  `X-Hub-Signature-256` HMAC for the configured webhook secret, or it is
  rejected with 401 before the body is parsed.
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
- The dashboard is limited to the GitHub App's owner and collaborators on the
  repositories the App is installed on, and each user sees only the
  repositories they can access.
- Features that send more data to the model or to a third party are off by
  default (CI-failure context, linked-issue context, review learnings, outgoing
  notifications, the security scan).

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
- Only the latest release gets security fixes.

## Threat model

Assets:

- The GitHub App private key and the installation tokens minted from it, which
  can write reviews, comments, check runs and labels on installed repositories.
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
- A commenter without write access who tries to trigger paid reviews.
- A model endpoint that returns malformed, oversized or hostile output.
- A web visitor trying to reach the dashboard or ride on a signed-in session.

## Trust boundaries

| Boundary | Data crossing it | Control |
|----------|------------------|---------|
| GitHub to `POST /api/webhook` | Event payloads | HMAC-SHA256 signature check, delivery-ID deduplication, Jackson parsing into typed records |
| Bot to GitHub API | Reads of PR data, writes of reviews and comments | Short-lived installation tokens over HTTPS, App permissions limited to what the bot uses |
| Bot to model endpoint | Prompts with untrusted PR text out, model text in | Untrusted text fenced in the prompt, model output parsed as data and verified before posting |
| Browser to dashboard | OAuth login, session cookie, API calls | GitHub OAuth with a `state` check, per-request access checks |
| Bot to notification webhook | Review outcome payload | HTTPS required by default, optional HMAC signature |
| Bot to PostgreSQL | Review sessions, feedback, learnings | Hibernate with bound parameters, credentials from the environment |

## Secure design principles

### Least privilege

The App manifest (`manifest.json`) asks for `contents: read`, `actions: read`,
`statuses: read`, `checks: write`, `pull_requests: write` and `issues: write`.
Issues write is needed because pull request conversation comments, labels and
reactions go through the Issues API; linked issues are only read. Every
workflow declares its token permissions, read-only unless a workflow or job
needs a write scope, such as the image push or the Dependency Review comment.
The container images run as an unprivileged user (UID 1001 on UBI9-micro,
`nonroot` on distroless) and the binary is installed read-only (`0555`).

### Fail-safe defaults

`WebhookVerifier` rejects a request when the signature header is missing or
malformed and when no secret is configured. The dashboard refuses every login
when the App owner cannot be resolved. Optional context and outgoing
notifications are off until enabled, and a manual `/review` needs write access
to the repository (`webhook/ManualReviewAuthorizer`).

### Complete mediation

Every dashboard API call validates the session and checks repository access
through `dashboard/DashboardAccessChecker`. The WebSocket closes a connection
that does not present a valid single-use ticket from `POST /api/auth/socket-ticket`,
reads nothing identifying from the endpoint configuration that every handshake
shares, and only forwards events for repositories the session may read
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
| Forged webhooks (CWE-345) and timing attacks on the signature (CWE-208) | HMAC-SHA256 over the raw body, compared with `MessageDigest.isEqual` | `webhook/WebhookVerifier` |
| Replayed deliveries | A recently seen `X-GitHub-Delivery` ID is dropped | `webhook/WebhookDeduplicator` |
| Prompt injection from PR content | All model-visible external text is wrapped in fences built from a per-call random value and labelled as untrusted data; findings go through a second verification call | `review/PromptTemplateEscaper`, AGENTS.md "Untrusted data" |
| Log injection (CWE-117) and secrets in logs (CWE-532) | Model and GitHub text is flattened with `LogSafe.oneLine`, enforced by `LogSafeInvariantTest`; the security scan never repeats a matched secret, and a notification URL is logged as scheme, host and port only | `LogSafe`, `review/SecurityScan`, `notification/` |
| CSRF on login (CWE-352) | Random OAuth `state` in an `HttpOnly`, `Secure`, `SameSite=Lax` cookie, checked on callback | `dashboard/AuthResource` |
| Guessable session IDs (CWE-330) | 256-bit IDs from `SecureRandom`, 8-hour expiry | `dashboard/DashboardSessionStore` |
| SQL injection (CWE-89) | Panache and JPQL queries with bound parameters only | `dashboard/`, `review/FindingFeedbackService` |
| Improper certificate validation (CWE-295) | No custom `TrustManager`, `HostnameVerifier` or trust-all setting; the JDK and Vert.x defaults verify certificates and host names | `config/HttpClientProducer`, `application.properties` |
| Unchecked input (CWE-20) | Webhook event types and dashboard parameters are matched against fixed lists, page sizes are clamped, configuration ranges are checked at startup | `webhook/WebhookController`, `dashboard/DashboardResource`, `config/StartupConfigValidator` |
| Vulnerable dependencies (CWE-1395) | Dependabot, Dependency Review on every pull request, Trivy on the source tree and images, weekly scheduled scans | `.github/dependabot.yml`, `.github/workflows/` |

CI also runs CodeQL for Java and TypeScript, SpotBugs at maximum effort and the
lowest threshold, and the SonarCloud quality gate, which requires an A security
rating on new code.

Release images and binaries are signed with keyless cosign and carry build
provenance attestations; the README explains how to verify them.
