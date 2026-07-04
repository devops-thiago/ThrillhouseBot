# Getting started

Everything you need to go from zero to a bot reviewing your pull requests:
create the GitHub App, configure the bot, and start it with Docker Compose.

## Prerequisites

- [Docker & Docker Compose](https://docs.docker.com/compose/install/)
- An API key for any [OpenAI-compatible provider](providers.md)
- A public hostname where GitHub can reach the bot (for local development,
  a [Smee.io](https://smee.io/) channel works)

## 1. Create the GitHub App

The bot authenticates as a GitHub App, so the app must exist before the bot
starts. The easiest path is the **[hosted installer](install.html)**: type the
public hostname where the bot will run and click
**Create ThrillhouseBot GitHub App** — the page builds the app manifest for you
and sends it to GitHub. No local server needed.

After GitHub creates the app:

1. Note the **App ID** (app settings → About).
2. Generate a **private key** (downloads a `.pem` file).
3. Set a **webhook secret**.
4. Copy the **Client ID** and **Client secret** from *Identifying and
   authorizing users* (needed for dashboard login).
5. Install the app on your account or organization.

Alternatively, grab the `code` query parameter GitHub appends to the
post-creation redirect and generate `.env` automatically from the manifest
conversion response:

```bash
gh api --method POST /app-manifests/<code>/conversions \
  | java scripts/GenEnv.java --host <your-host>
```

!!! note "Smee setups"

    The hosted installer registers Smee webhooks at the channel root and the
    OAuth callback at `http://localhost:8080/api/auth/callback`. If you use
    `GenEnv.java`, don't pass the Smee URL as `--host` (that writes
    `DASHBOARD_URL=https://<host>`); run it without `--host` and then set
    `DASHBOARD_URL=http://localhost:8080` in the generated `.env` — dashboard
    login only works when `DASHBOARD_URL` matches the registered callback.

??? note "Manual registration instead"

    Prefer to register the app by hand? Create it at
    <https://github.com/settings/apps/new> with these settings:

    | Setting | Value |
    |---|---|
    | Webhook URL | `https://<your-host>/api/webhook` |
    | Webhook Secret | Random string |
    | Repository Permissions | Pull Requests: R/W, Checks: R/W, Contents: Read, Issues: R/W, Actions: Read, Commit Statuses: Read |
    | Subscribe to Events | Pull Request, Issue comment, Pull request review comment |
    | Identifying & authorizing users | Enabled (for dashboard login) |
    | Callback URL | `https://<your-host>/api/auth/callback` |

    For a Smee channel, register the channel URL itself as the Webhook URL (no
    `/api/webhook` suffix — Smee delivers only at the channel root) and
    `http://localhost:8080/api/auth/callback` as the Callback URL, since OAuth
    redirects happen in your browser against the local bot.

!!! tip "Re-registering later"

    Once the bot is running, `install.html` on the bot's own URL
    (`https://<your-host>/install.html` behind a reverse proxy, or
    `http://localhost:8080/install.html` when hitting it directly) builds the
    manifest from the detected host automatically — handy for adding the app to
    another account. Smee-based dev setups should re-register through the
    [hosted installer](install.html) instead: the bot's own page registers the
    origin it's served from, without the Smee webhook-root handling.

## 2. Clone and configure

```bash
git clone https://github.com/devops-thiago/ThrillhouseBot.git && cd ThrillhouseBot
cp .env.example .env
```

Edit `.env` with the credentials from step 1:

| Variable | Value |
|---|---|
| `GITHUB_APP_ID` | From GitHub App settings → About |
| `GITHUB_PRIVATE_KEY` | Downloaded when you generated a private key |
| `GITHUB_WEBHOOK_SECRET` | The webhook secret you set |
| `GITHUB_CLIENT_ID` | From app settings → Identifying and authorizing users |
| `GITHUB_CLIENT_SECRET` | From app settings → Identifying and authorizing users |
| `AI_API_KEY` | Your AI provider's API key |

## 3. Start the bot

```bash
docker compose up -d
```

The bot is running on `http://localhost:8080`. Point your reverse proxy at it
and you're done — the next pull request on an installed repository gets
reviewed automatically.

## Next steps

- Tune the bot with the [configuration reference](configuration.md) — auto-review
  triggers, labels, timeouts, and more.
- Drive it from a PR with the [comment commands](commands.md).
- Customize reviews per repository with a `.github/thrillhousebot.md`
  instructions file (fallback chain: `.github/copilot-instructions.md` →
  `CLAUDE.md` → `AGENTS.md` → `AGENT.md`).
