---
slug: 0.3.1/commands
title: Commands
description: Drive the bot directly from a PR with comment commands.
---

Drive the bot directly from a PR by commenting one of these. Each also accepts the
mention form, e.g. `@Thrillhousebot review`.

| Command | What it does | Access |
|---|---|---|
| `/help` | List the available commands | anyone |
| `/review` | Run (or re-run) a full review of the PR | write |
| `/summary` | Post the PR summary if it isn't already on the PR — regenerates it if the comment was deleted, otherwise no-op | write |
| `/describe` | Suggest an improved PR title and description generated from the diff, as a comment to copy in (never overwrites the PR) | write |
| `/changelog` | Draft a CHANGELOG entry for the PR from the diff (Added/Changed/Fixed/Security…), as a comment to copy into `CHANGELOG.md` (never commits) | write |
| `/add-docs` | Generate docstrings/inline docs for the symbols changed in the PR, posted as committable suggestions (or a note with the drafted docs when a multi-line declaration can't be pinned to a single diff hunk) | write |
| `/resolve` | Resolve ThrillhouseBot's outstanding finding threads on the PR | write |
| `/pause` | Silence the bot on the PR | write |
| `/resume` | Re-enable the bot on a paused PR | write |

**Access** — every command except `/help` requires the commenter to hold write access to
the repository (or to be named in
`THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS`), since reviews spend the operator's
AI budget.

**Pause** — while a PR is paused, ThrillhouseBot skips automatic reviews on new commits,
ignores `/review`, `/summary`, `/describe`, `/changelog`, and `/add-docs`, and does not answer
`@thrillhousebot` mentions (it replies once to say it is paused). `/resume` lifts the pause.
`/help` and `/resolve` keep working while paused.

**`/add-docs`** — on demand, the bot reads the diff and proposes documentation comments for
the public symbols changed in the PR, honoring the repository instructions and each file's
language. Each suggestion is a committable `suggestion` block placed on the symbol's
declaration (spanning the whole signature when it wraps), so it only inserts docs without
rewriting code. When a multi-line declaration can't be pinned to a single diff hunk, the bot
posts a note with the drafted docs to add manually instead of a committable suggestion. It
spends AI budget per run; operators can turn it off with `REVIEW_ADD_DOCS_ENABLED=false`.
