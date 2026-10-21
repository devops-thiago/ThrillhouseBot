---
slug: 0.7.1/commands
title: Commands
description: Drive the bot directly from a PR with comment commands.
---



Comment one of these on a pull request. Each also has a mention form, such as
`@Thrillhousebot review`. The bot reacts with 👀 to acknowledge a command and
does the work in the background. A plain `@thrillhousebot` mention with no
command word gets a written answer instead.

| Command | What it does | Access |
|---|---|---|
| `/help` | List the commands | anyone |
| `/review` | Run (or re-run) a full review | write |
| `/summary` | Post the PR summary when the PR has none, for example after it was deleted. If one is already there, the bot replies that it did nothing. | write |
| `/describe` | Suggest a PR title and description, as a comment to copy in. The PR is never edited. | write |
| `/changelog` | Draft a CHANGELOG entry (Added, Changed, Fixed, Security…), as a comment to copy in | write |
| `/add-docs` | Suggest doc comments for the symbols the PR changed, as committable suggestions | write |
| `/improve` | Suggest improvements across the whole PR, as committable suggestions | write |
| `/generate-tests` | Propose unit tests for the changed code, one code block per test file | write |
| `/resolve` | Resolve the bot's open finding threads | write |
| `/pause` | Silence the bot on the PR | write |
| `/resume` | Re-enable the bot on a paused PR | write |
| `/learnings` | List what the bot remembers about this repository, with ids and source links | write |
| `/remember <text>` | Remember a convention for later reviews of this repository | write |
| `/forget <id>` | Retract one remembered learning | write |
| `@thrillhousebot resolved <path>:<line> — <title>` | Close an earlier finding that has no review thread (see **Clearing a finding with no thread** under Configuration), or, replied on a finding's own thread, close that finding and resolve the thread | write |
| `@thrillhousebot declined <path>:<line> — <title>` | Decline an earlier finding that has no review thread, with the reason on the following lines (see **Declining a finding with no thread** under Configuration) | write |

**Access.** Every slash command except `/help` needs write access to the
repository, or a login listed in
`THRILLHOUSEBOT_REVIEW_MANUAL_TRIGGER_ALLOWED_LOGINS`, because the commands spend
the operator's AI budget. The `resolved` and `declined` directives always need
write access, judged by the comment's GitHub `author_association` (`OWNER`,
`MEMBER` or `COLLABORATOR`); the allowlist does not extend to them.

**Directives.** `@thrillhousebot resolved` and `@thrillhousebot declined` are
not slash commands. They have no `/` form and are applied by the next review,
not straight away. The bot replies at once to say what that review will
evaluate, and when the comment names no `path:line` it says that nothing will
be cleared. A trailing question mark (`@thrillhousebot resolved?`) makes it a
question, which changes nothing. Do not confuse `resolved` with `/resolve`,
which resolves review threads and does nothing to a finding that never opened
one.

**Learnings.** `/learnings`, `/remember` and `/forget` work only when the
deployment sets `REVIEW_LEARNINGS_ENABLED=true`; otherwise the bot replies that
learnings are off. `/learnings` lists up to 30 entries.

**Pause.** While a PR is paused, pushes are not reviewed and mentions get no
answer. `/review`, `/summary`, `/describe`, `/changelog`, `/add-docs`,
`/improve` and `/generate-tests` reply with a short paused notice and do
nothing else. `/help`, `/resolve`, `/resume` and the learnings commands keep
working.

**Batching.** `/describe`, `/changelog`, `/add-docs`, `/improve` and
`/generate-tests` read the whole change set, not the first
`REVIEW_MAX_DIFF_LINES` of it. The changed files are packed into batches that
each fit `REVIEW_MAX_INPUT_TOKENS`, with one model call per batch, and a run
never makes more than `REVIEW_MAX_AI_CALLS` calls. `/describe` and `/changelog`
merge the per-batch results with one extra call, which they spend only when the
PR needed more than one batch. `/add-docs`, `/improve` and `/generate-tests`
merge their results locally. Any file the budget could not cover is named in a
partial-coverage note. Nothing is ever committed.

**`/add-docs`** proposes doc comments for the public symbols the PR changed, in
each file's language and following the repository instructions. Each one is a
`suggestion` block on the symbol's declaration (spanning the whole signature
when it wraps), so applying it only inserts docs. When a multi-line declaration
cannot be pinned to one diff hunk, the bot posts the drafted docs as a note
instead. Turn it off with `REVIEW_ADD_DOCS_ENABLED=false`.

**`/improve`** proposes better code even when nothing is broken: clearer
naming, dead or duplicated code, simpler control flow, missing error handling,
avoidable work in loops, and gaps in the tests for the change. `/review` looks
for defects; `/improve` does not. Each improvement whose quoted code matches
the diff is posted inline as a `suggestion` block; the rest are listed as
copy-paste blocks in the run's summary comment. Turn it off with
`REVIEW_IMPROVE_ENABLED=false`.

**`/generate-tests`** proposes unit tests in the framework the project already
uses. A new test file has no diff line to anchor a suggestion to, so each test
file is posted as a code block headed by its path. Proposals from different
batches are merged by path. Turn it off with
`REVIEW_GENERATE_TESTS_ENABLED=false`.


