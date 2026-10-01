# Finding feedback capture

<!-- docs:feedback:start -->

ThrillhouseBot records a maintainer's 👍 or 👎 on a finding comment, and a
"not useful" style reply, as feedback on that finding
([#324](https://github.com/devops-thiago/ThrillhouseBot/issues/324)). The
feedback is counted and shown on the dashboard. It does not change later
reviews.

Review learnings ([#38](https://github.com/devops-thiago/ThrillhouseBot/issues/38),
see "Review learnings" in the README) are a separate, opt-in store. A learning
comes from a maintainer's decline that gives a reason and survives the decline
re-check, or from `/remember`. A reaction or a "not useful" reply has no reason,
so it never becomes a learning.

## When feedback is collected

GitHub Apps do not receive a `reaction` webhook event, so the bot polls the
[Reactions REST API](https://docs.github.com/en/rest/reactions/reactions) for
👍 (`+1`) and 👎 (`-1`) on finding comments at two points:

1. When someone replies on an inline review thread
   (`pull_request_review_comment` with `in_reply_to_id`).
2. After a review of a pull request that already has bot finding comments, for
   those comments from every earlier round, newest first, up to 40 per review.

Capture is best-effort. A failure is logged and never fails the webhook
response or the review.

## Signals

| Signal | Source | Meaning |
|--------|--------|---------|
| `useful` | `reaction` (`+1`) | A 👍 on the finding comment |
| `not_useful` | `reaction` (`-1`) | A 👎 on the finding comment |
| `not_useful` | `reply_heuristic` | A reply containing `not useful`, `false positive`, `not a bug`, `not a real bug`, `not a real issue`, `noise`, 👎 or `:-1:` |

Only comments with the hidden `<!-- thrillhousebot:finding=N -->` marker count,
and only reactions and replies from people with write access to the repository.
The bot's own reactions, such as the 👀 it adds to acknowledge a command, are
ignored.

## Data model

Rows go in the `finding_feedback` table. Hibernate creates and updates the
schema; there are no migration scripts.

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint | Primary key from a sequence |
| `repository` | string | `owner/repo` |
| `prNumber` | int | Pull request number in that repository |
| `githubCommentId` | bigint | ID of the finding's root review comment |
| `findingIndex` | int, nullable | 1-based index from the marker |
| `signal` | string | `useful` or `not_useful` |
| `source` | string | `reaction` or `reply_heuristic` |
| `reactorLogin` | string | GitHub login, lower-cased |
| `githubReactionId` | bigint, nullable | Unique when set, so polling the same reaction again adds nothing |
| `createdAt` | instant | Insert time |

Two unique constraints keep the table free of duplicates:

- `githubReactionId`, when set, covers redelivered and re-polled reactions.
- `(githubCommentId, reactorLogin, signal, source)` keeps one event per person,
  signal and source on a comment.

## Privacy

The only personal data stored is the GitHub login, which is already in webhook
and API payloads. No email, display name, IP address or reply text is kept; a
reaction is stored only as its `+1` or `-1` code. The finding's title and
description are not copied into the table.

## Retention

Rows stay for the life of the database; nothing purges them automatically. An
operator can delete rows or drop the table when retiring an installation.
Uninstalling the GitHub App does not delete feedback rows, the same as review
session history.

## Dashboard API

`GET /api/dashboard/feedback` returns `useful` and `not_useful` counts per
repository, for the repositories the signed-in user can access. With
`?repository=owner/repo` it returns that repository's counts and its 50 most
recent events. It needs a dashboard session cookie, and a repository the user
cannot access gets `403`.

## Main classes

- `FindingFeedback`, `FindingFeedbackRepository`, `FindingFeedbackService`:
  the entity, its queries and the per-repository counts
  (`summarize(repository)`, `summarizeAll()`).
- `FindingFeedbackCaptureService`: reaction polling and the reply heuristic.
- `GitHubReactionClient.listReviewCommentReactions`: the Reactions API call.
- `WebhookController`: schedules capture when someone replies on a review
  thread.
- `ReviewOrchestrator`: runs capture on earlier findings during a follow-up
  review.

<!-- docs:feedback:end -->
