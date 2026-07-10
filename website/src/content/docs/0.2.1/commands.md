---
slug: 0.2.1/commands
title: Commands
description: Drive the bot directly from a PR with comment commands.
---

Drive the bot directly from a PR by commenting one of these. Each also accepts the
mention form, e.g. `@Thrillhousebot review`.

| Command | What it does | Access |
|---|---|---|
| `/help` | List the available commands | anyone |
| `/review` | Run (or re-run) a full review of the PR | write |
| `/summary` | Post the PR summary, but only if one has not been generated yet (otherwise no-op) | write |
| `/resolve` | Resolve ThrillhouseBot's outstanding finding threads on the PR | write |
| `/pause` | Silence the bot on the PR | write |
| `/resume` | Re-enable the bot on a paused PR | write |

**Access** — every command except `/help` requires the commenter to hold write access to
the repository (or to be named in
`THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS`), since reviews spend the operator's
AI budget.

**Pause** — while a PR is paused, ThrillhouseBot skips automatic reviews on new commits,
ignores `/review` and `/summary`, and does not answer `@thrillhousebot` mentions (it replies
once to say it is paused). `/resume` lifts the pause. `/help` and `/resolve` keep working while
paused.
