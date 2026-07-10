---
slug: 0.1.1/architecture
title: Architecture
description: One-page overview of how the bot is structured and how a review flows through it.
---

One-page overview of how the bot is structured and how a review flows through it.

ThrillhouseBot is a Quarkus application that runs as a GitHub App. A webhook
arrives when a pull request changes, the bot builds a review with an
OpenAI-compatible model, and it posts the result back as a PR review plus a
check run. A dashboard streams what is happening live.

## Components

```mermaid
flowchart TB
    subgraph GH[GitHub.com]
        IN[PR push, /review, @mention]
        OUT[PR reviews · check runs · comments]
    end

    subgraph BOT[ThrillhouseBot · Quarkus]
        WH[webhook/<br/>WebhookController]
        RO[review/<br/>ReviewOrchestrator]
        AI[review/ai/<br/>AiReviewService]
        GHC[github/<br/>REST clients]
        DB[dashboard/ + frontend/]

        WH --> RO --> AI
        RO --> GHC
        AI -. live tokens .-> DB
    end

    IN -->|POST /api/webhook<br/>HMAC-verified| WH
    GHC --> OUT
```

The `github/` clients wrap the GitHub REST surface the bot uses: installation
tokens, pull diffs and prior reviews, check runs, PR reviews with inline
comments, issue comments, and the instructions-file fallback chain
(`.github/thrillhousebot.md`, `.github/copilot-instructions.md`, `CLAUDE.md`,
`AGENTS.md`, `AGENT.md`).

## Request flow

```mermaid
flowchart TD
    GH[GitHub: PR opened / synced / comment] -->|webhook| WH[webhook/]
    WH -->|verify HMAC, detect trigger| RO[review/ ReviewOrchestrator]
    RO -->|fetch diff, instructions, prior findings| GHC[github/ API clients]
    RO -->|build prompt, stream tokens| AI[review/ai/ LangChain4j]
    AI -->|parse findings, verify| RO
    RO -->|post review + check run| GHC --> GH
    AI -. live tokens .-> DB[dashboard/ broadcaster]
    DB -->|WebSocket| FE[frontend/ Next.js UI]
    RO -->|persist session, cost, tokens| PG[(H2 / PostgreSQL)]
    AI -->|traces, token & cost metrics| OT[(OpenTelemetry)]
```

## Review lifecycle

### First review (PR opened)

```mermaid
sequenceDiagram
    actor Dev
    participant GH as GitHub
    participant TB as ThrillhouseBot
    participant AI as AI Provider

    Dev->>GH: git push (PR opened)
    GH->>TB: POST /api/webhook (pull_request: opened)

    Note over TB: Verify HMAC → JWT → install token

    TB->>GH: POST check-runs → status: queued
    TB->>GH: PATCH check-run → status: in_progress

    par Fetch context
        TB->>GH: GET /pulls/{pr}/files (diff)
        TB->>GH: GET /compare/{base}...{head} (regression context)
    end

    TB->>GH: GET /pulls/{pr}/reviews (check if already reviewed this SHA)
    GH-->>TB: no prior reviews → first run

    TB->>AI: POST chat (diff + base comparison + review prompt)
    AI-->>TB: findings + risk levels + suggestions

    alt AI fails
        TB->>GH: PATCH check-run → conclusion: failure
        TB->>GH: POST comment: retry hint (no internal details)
    else AI succeeds + issues found
        TB->>GH: POST PR review (REQUEST_CHANGES or COMMENT) with inline suggestions
        TB->>GH: PATCH check-run → conclusion: failure (critical/high) or neutral
        TB->>GH: POST comment: PR summary (risk table + key findings)
    else AI succeeds + zero issues
        TB->>GH: POST PR review → APPROVE (no body)
        TB->>GH: PATCH check-run → conclusion: success
        TB->>GH: POST comment: PR summary (celebration inside)
    end
```

### Follow-up review (new push)

```mermaid
sequenceDiagram
    actor Dev
    participant GH as GitHub
    participant TB as ThrillhouseBot
    participant AI as AI Provider

    Dev->>GH: git push (PR synchronize)
    GH->>TB: POST /api/webhook (pull_request: synchronize)

    Note over TB: Verify → auth → create check run (in_progress)

    par Fetch context
        TB->>GH: GET /pulls/{pr}/files (diff)
        TB->>GH: GET /compare/{base}...{head}
    and Fetch prior review
        TB->>GH: GET /pulls/{pr}/reviews (find ThrillhouseBot's last review)
        GH-->>TB: previous findings + thread status
    end

    Note over TB: Prompt includes diff + prior findings + "check if each was addressed"

    TB->>AI: POST chat (diff + prior findings + follow-up prompt)
    AI-->>TB: resolved / unresolved / new findings

    alt AI fails
        Note over TB: Same sanitized error path as first review
    else AI succeeds
        TB->>GH: POST PR review (suggestions for unresolved + new issues)
        TB->>GH: PATCH check-run → conclusion based on risk
        Note over TB: No summary comment on follow-up (only on first run)
    end
```

### Manual trigger (`/review` or `@Thrillhousebot review`)

```mermaid
sequenceDiagram
    actor Dev
    participant GH as GitHub
    participant TB as ThrillhouseBot

    Dev->>GH: Comments "/review"
    GH->>TB: POST /api/webhook (issue_comment: created)

    Note over TB: Verify → parse trigger → auth → check run (in_progress)
    Note over TB: Fetch diff, compare, and prior reviews
    Note over TB: Full re-review even if this SHA was already reviewed
```

## Packages

| Package | Responsibility | Notable classes |
|---|---|---|
| `webhook/` | Receives GitHub events, verifies the HMAC signature, decides whether an event triggers a review | `WebhookController`, `WebhookVerifier`, `TriggerDetector` |
| `review/` | Orchestrates a review: formats the diff, calls the AI layer, maps findings to a risk level and review state, writes the summary comment | `ReviewOrchestrator`, `ReviewDispatcher`, `ReviewDiffFormatter`, `FollowUpAnalyzer`, `PrSummaryGenerator` |
| `review/ai/` | The LangChain4j layer: streams the model response, parses it into findings, and runs a second pass to verify them | `PrReviewer`, `AiReviewService`, `FindingVerifier`, `FindingVerificationService`, `ReviewResponseParser` |
| `github/` | Talks to the GitHub REST and GraphQL APIs: app auth, pull requests, reviews, check runs, comments, and reading the repo instructions file | `GitHubAuthClient`, `GitHubReviewClient`, `GitHubCheckRunClient`, `InstructionsResolver` |
| `dashboard/` | The live UI backend: OAuth login (in-memory sessions), WebSocket broadcaster, and review session persistence | `AuthResource`, `DashboardSessionStore`, `SessionEventBroadcaster`, `ReviewSessionRepository` |
| `config/` | Wiring: the outbound HTTP client, the review thread pool, and typed config | `HttpClientProducer`, `ReviewExecutorProducer`, `ThrillhouseConfig` |
| `frontend/` | The Next.js dashboard, built to a static export and served by Quarkus | — |

## Notes

PR reviews carry inline comments and suggestions; check runs carry pass/fail
status for branch protection (no inline annotations on the check run itself).

Each AI call is bounded by `AI_TIMEOUT` (LangChain4j) and
`thrillhousebot.review.ai-timeout-seconds`. Cost and token metrics come from
OpenTelemetry. OAuth login sessions are opaque IDs in cookies with tokens kept
server-side; review history persists in the database. See [SECURITY.md](https://github.com/devops-thiago/ThrillhouseBot/blob/v0.1.1/SECURITY.md)
for the reporting process.

## Adding an AI provider

There is no provider-specific code. The model is reached through LangChain4j's
OpenAI-compatible client, so a new provider is configuration: point `AI_BASE_URL`
and `AI_MODEL` at it, and add a `thrillhousebot.ai.pricing.<model>.*` pair if you
want cost tracking for that model. See the provider table in the
[README](/ThrillhouseBot/0.1.1/providers).
