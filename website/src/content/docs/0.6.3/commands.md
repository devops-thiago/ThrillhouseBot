---
slug: 0.6.3/commands
title: Commands
description: Drive the bot directly from a PR with comment commands.
---



Drive the bot directly from a PR by commenting one of these. Each also accepts the
mention form, e.g. `@Thrillhousebot review`. The bot acknowledges every command
instantly with a 👀 reaction on your comment while the work runs in the background;
a conversational `@thrillhousebot` mention (no command word) gets an answer instead,
not a reaction.

| Command | What it does | Access |
|---|---|---|
| `/help` | List the available commands | anyone |
| `/review` | Run (or re-run) a full review of the PR | write |
| `/summary` | Post the PR summary if it isn't already on the PR — regenerates it if the comment was deleted, otherwise no-op | write |
| `/describe` | Suggest an improved PR title and description generated from the diff, as a comment to copy in (never overwrites the PR) | write |
| `/changelog` | Draft a CHANGELOG entry for the PR from the diff (Added/Changed/Fixed/Security…), as a comment to copy into `CHANGELOG.md` (never commits) | write |
| `/add-docs` | Generate docstrings/inline docs for the symbols changed in the PR, posted as committable suggestions (or a note with the drafted docs when a multi-line declaration can't be pinned to a single diff hunk) | write |
| `/improve` | Run a whole-PR improvement pass over the diff and post the improvements as committable suggestions (with copy-paste blocks for the ones that can't be pinned to the diff) | write |
| `/generate-tests` | Propose unit tests for the code the PR changed, as a comment with one ready-to-paste code block per test file (never commits) | write |
| `/resolve` | Resolve ThrillhouseBot's outstanding finding threads on the PR | write |
| `/pause` | Silence the bot on the PR | write |
| `/resume` | Re-enable the bot on a paused PR | write |
| `@thrillhousebot resolved <path>:<line> — <title>` | Close a previous finding that has no review thread to reply on, so it stops holding approval (see **Clearing a finding with no thread** under Configuration) | write |

**Access** — every slash command except `/help` requires the commenter to hold write access
to the repository, or to be named in `THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS`,
since reviews spend the operator's AI budget. The allowlist covers the slash commands only:
the `@thrillhousebot resolved` directive always requires write access, as described below.

**`@thrillhousebot resolved`** — a directive, not a slash command: it has no `/resolved`
form, and it is read by the *next* review rather than acted on immediately. The bot replies
straight away to say what that review will evaluate — and, when the comment names no
`path:line` at all, to say plainly that nothing will be cleared, so a mistyped locator shows
up immediately instead of as a review that changes nothing. It is a statement, never a
question: `@thrillhousebot resolved?` is asking, not deciding, and clears nothing. Do not
confuse it with `/resolve`, which resolves GitHub review threads and does nothing to a
finding that never opened one. Its access check is the commenter's GitHub
`author_association` on that comment — `OWNER`, `MEMBER` or `COLLABORATOR` — and
`THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS` does *not* extend it.

**Pause** — while a PR is paused, ThrillhouseBot skips automatic reviews on new commits,
ignores `/review`, `/summary`, `/describe`, `/changelog`, `/add-docs`, `/improve` and
`/generate-tests`, and does not answer `@thrillhousebot` mentions (it replies once to say it is
paused). `/resume` lifts the pause.
`/help` and `/resolve` keep working while paused.

**`/describe` and `/changelog`** — both read the whole change set rather than the first
`REVIEW_MAX_DIFF_LINES` of it. The changed files are packed into batches that each fit
`REVIEW_MAX_INPUT_TOKENS`, one model call per batch, and the per-batch results are then reduced
to a single answer: `/describe` composes the partial descriptions into one coherent title and
description, `/changelog` merges the candidate entries into one entry. That reduce step costs one
extra model call, reserved out of `REVIEW_MAX_AI_CALLS` and spent only when the PR actually needed
more than one batch, so a run never exceeds the same ceiling as one review. Any file the budget
could not cover is named in a partial-coverage note under the suggestion.

**`/add-docs`** — on demand, the bot reads the diff and proposes documentation comments for
the public symbols changed in the PR, honoring the repository instructions and each file's
language. Each suggestion is a committable `suggestion` block placed on the symbol's
declaration (spanning the whole signature when it wraps), so it only inserts docs without
rewriting code. When a multi-line declaration can't be pinned to a single diff hunk, the bot
posts a note with the drafted docs to add manually instead of a committable suggestion. It
spends AI budget per run; operators can turn it off with `REVIEW_ADD_DOCS_ENABLED=false`.

**`/improve`** — on demand, the bot runs an improvement pass over the whole PR and proposes
concrete changes the author can commit: clearer naming, dead or duplicated code, simpler
control flow, missing error handling, avoidable work in loops, and gaps in the tests covering
the change. It is deliberately separate from `/review`: `/review` looks for defects,
`/improve` proposes better code even when nothing is broken. Like a review, the pass is
token-budgeted rather than line-capped: the changed files are packed into batches that each fit
`REVIEW_MAX_INPUT_TOKENS`, one model call per batch, so a long diff only loses coverage once it
exceeds the whole budget. Each improvement whose quoted code anchors onto the diff is posted as
an inline committable `suggestion` block on the lines it replaces; the rest are listed as
copy-paste blocks in the run's summary comment, together with a partial-coverage note naming
any file the budget could not cover. Nothing is ever committed for you. It spends AI budget per
run — at most `REVIEW_MAX_AI_CALLS` model calls, the same ceiling as one review — and operators
can turn it off with `REVIEW_IMPROVE_ENABLED=false`.

**`/generate-tests`** — on demand, the bot reads the diff and proposes unit tests for the
behavior the PR added or changed, in the test framework the project already uses. A proposed
test is usually a whole new file, which has no diff line for GitHub to anchor a committable
`suggestion` block to, so each one is posted as a code block headed by the path it belongs at
— ready to paste into a new file, or to merge into an existing test file. Nothing is committed
and no file is edited. Like a review, the pass is token-budgeted rather than line-capped: the
changed files are packed into batches that each fit `REVIEW_MAX_INPUT_TOKENS`, one model call
per batch, and the per-batch proposals are unioned by path — so "nothing here warrants a test"
is never a verdict on code that was too far down a long diff to be read. Any file the budget
could not cover is named in a partial-coverage note. It spends AI budget per run — at most
`REVIEW_MAX_AI_CALLS` model calls, the same ceiling as one review — and operators can turn it
off with `REVIEW_GENERATE_TESTS_ENABLED=false`.


