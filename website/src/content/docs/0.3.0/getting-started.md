---
slug: 0.3.0/getting-started
title: Getting started
description: Create the GitHub App, configure the bot, and start it.
---

## Quick start

### Prerequisites

- [Docker & Docker Compose](https://docs.docker.com/compose/install/)
- An API key for any [OpenAI-compatible provider](/ThrillhouseBot/0.3.0/providers)

### 1. Create the GitHub App

Follow the [GitHub App setup](#github-app-setup) section below (2 minutes).
You'll get an App ID, private key, webhook secret, and OAuth client ID/secret.

### 2. Clone and configure

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

### 3. Start the bot

```bash
docker compose up -d
```

The bot is running on `http://localhost:8080`. Point your reverse proxy at it and
you're done.

## GitHub App setup

Create a GitHub App before starting the bot; you'll need its credentials for `.env`.

### Option A: manifest install (recommended)

1. Edit `manifest.json` in the repo root and replace every `<your-host>` with your
   public hostname (no trailing slash). For local dev with [Smee.io](https://smee.io/),
   use your Smee channel URL host.
2. Serve the repo root locally:

   ```bash
   java -m jdk.httpserver -p 8081
   ```

3. Open [/ThrillhouseBot/install.html](/ThrillhouseBot/install.html) and click
   **Create ThrillhouseBot GitHub App**. GitHub creates the app from your manifest.
4. On the confirmation page, note the **App ID**, generate a **private key**, and create a
   **webhook secret**. Copy the **Client ID** and **Client secret** from the app's
   *Identifying and authorizing users* settings (needed for dashboard login).
5. Install the app on your account or organization, then copy the values into `.env`.

   Alternatively, generate `.env` automatically from the manifest conversion response:

   ```bash
   gh api --method POST /app-manifests/<code>/conversions \
     | java scripts/GenEnv.java --host <your-host>
   ```

> Once the bot is running, `http://<your-host>:8080/install.html` auto-detects the URL
> and builds the manifest dynamically, with no file editing or local server needed.

### Option B: manual registration

| Setting | Value |
|---|---|
| Webhook URL | `https://<your-host>/api/webhook` |
| Webhook Secret | Random string |
| Repository Permissions | Pull Requests: R/W, Checks: R/W, Contents: Read, Issues: R/W, Actions: Read, Commit Statuses: Read |
| Subscribe to Events | Pull Request, Issue comment, Pull request review comment |
| Identifying & authorizing users | Enabled (for dashboard login) |
| Callback URL | `https://<your-host>/api/auth/callback` |
