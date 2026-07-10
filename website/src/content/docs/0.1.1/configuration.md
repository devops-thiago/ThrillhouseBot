---
slug: 0.1.1/configuration
title: Configuration
description: Every environment variable the bot reads, with defaults.
---

Configuration is read from environment variables (see `.env.example`). The AI
variables are the ones you will change per provider:

| Variable | Purpose | Default |
|---|---|---|
| `AI_API_KEY` | API key for the AI provider | _(required)_ |
| `AI_BASE_URL` | OpenAI-compatible base URL | `https://api.deepseek.com/v1` |
| `AI_MODEL` | Chat model name | `deepseek-chat` |
| `AI_PROVIDER` | Provider label for telemetry (`gen_ai.provider.name`); derived from `AI_BASE_URL` when unset | _(derived)_ |
| `AI_TIMEOUT` | Per-request timeout | `300s` |
| `GITHUB_APP_ID` | GitHub App ID | _(required)_ |
| `GITHUB_PRIVATE_KEY` | GitHub App private key (PEM) | _(required)_ |
| `GITHUB_WEBHOOK_SECRET` | Webhook HMAC secret | _(required)_ |
| `WEBHOOK_DEDUP_TTL` | Webhook deduplication time-to-live for GitHub redeliveries | `24h` |
| `THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS` | Comma-separated allowlist of logins permitted to trigger manual `/review` without repo access | _(empty)_ |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | OAuth credentials for dashboard login | _(required for dashboard)_ |
| `DASHBOARD_URL` | Public dashboard URL (OAuth callback base) | `http://localhost:8080` |
| `DATASOURCE_DB_KIND` | `h2` or `postgresql` | `h2` (dev), `postgresql` (`%prod`) |
| `HTTP_CONNECT_TIMEOUT` | Outbound HTTP connect timeout (GitHub API, OAuth) | `10s` |
| `HTTP_REQUEST_TIMEOUT` | Outbound HTTP request timeout (GitHub API, OAuth) | `10s` |
| `WEBSOCKET_KEEPALIVE_MS` | Dashboard WebSocket keepalive interval in ms; `0` or negative disables it (and stale replay-buffer eviction) | `25000` |

Cost tracking uses per-model pricing keyed by the model name, for example:

```properties
thrillhousebot.ai.pricing.deepseek-chat.input-per-1k=0.00014
thrillhousebot.ai.pricing.deepseek-chat.output-per-1k=0.00028
```

If you switch to a different `AI_MODEL`, add a matching
`thrillhousebot.ai.pricing.<model>.*` pair so the dashboard can compute cost.
