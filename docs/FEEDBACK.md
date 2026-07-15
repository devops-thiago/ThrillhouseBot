# Finding feedback capture

<!-- docs:feedback:start -->

ThrillhouseBot records lightweight maintainer signals about review findings so a
future cross-review learnings store ([#38](https://github.com/devops-thiago/ThrillhouseBot/issues/38))
has training data. This is the precursor shipped for
[#324](https://github.com/devops-thiago/ThrillhouseBot/issues/324); it does **not**
yet inject preferences into review prompts.

## Why poll instead of a reaction webhook?

GitHub Apps do not receive a `reaction` webhook event. The bot therefore lists
👍 (`+1`) and 👎 (`-1`) on finding comments via the
[Reactions REST API](https://docs.github.com/en/rest/reactions/reactions) when:

1. A human **replies** on an inline review thread (`pull_request_review_comment`
   with `in_reply_to_id`), or
2. A **follow-up review** already loaded prior finding threads.

Capture is best-effort and never fails the webhook `200` or the review.

## Signals

| Signal | Source | Meaning |
|--------|--------|---------|
| `useful` | `reaction` (`+1`) | Maintainer marked the finding comment 👍 |
| `not_useful` | `reaction` (`-1`) | Maintainer marked the finding comment 👎 |
| `not_useful` | `reply_heuristic` | Reply body matched a conservative phrase (`not useful`, `false positive`, `noise`, 👎, `:-1:`) |

Only comments that carry the hidden `<!-- thrillhousebot:finding=N -->` marker are
eligible. The bot's own reactions (e.g. 👀 command ack) are ignored.

## Data model

Table `finding_feedback` (Hibernate schema-update; no Flyway):

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint | Panache / sequence PK |
| `repository` | string | `owner/repo` |
| `prNumber` | int | PR on that repository |
| `githubCommentId` | bigint | Finding root review-comment id |
| `findingIndex` | int (nullable) | 1-based index from the marker |
| `signal` | string | `useful` or `not_useful` |
| `source` | string | `reaction` or `reply_heuristic` |
| `reactorLogin` | string | GitHub login only (lower-cased) |
| `githubReactionId` | bigint (nullable) | Unique when present (idempotent re-poll) |
| `createdAt` | instant | Insert time |

Unique constraints:

- `githubReactionId` (when non-null) — reaction redeliveries / re-polls
- `(githubCommentId, reactorLogin, signal, source)` — one logical event per actor

## Privacy

Stored PII is limited to the **GitHub login** already present on webhook and API
payloads. No email, display name, IP, or reaction text beyond the fixed emoji
content codes (`+1` / `-1`) is persisted. Finding title/description are not
copied into this table.

## Retention

Rows are retained for the lifetime of the deployment database. There is no
automatic purge. Operators may `DELETE` rows or drop the table when
decommissioning an installation. Uninstalling the GitHub App does not currently
auto-delete feedback rows (same posture as `ReviewSession` history).

## Aggregation API (ContextProvider seam)

`FindingFeedbackService.summarize(repository)` and `summarizeAll()` return
per-repo `useful` / `not_useful` counts for a future `ContextProvider`. The
dashboard exposes the same aggregates at `GET /api/dashboard/feedback` (session
cookie required; optional `?repository=owner/repo`).

## Notable classes

- `FindingFeedback` / `FindingFeedbackRepository` / `FindingFeedbackService`
- `FindingFeedbackCaptureService` — poll + heuristics
- `GitHubReactionClient.listReviewCommentReactions`
- `WebhookController` — schedules capture on review-thread replies
- `ReviewOrchestrator` — capture pass on follow-up reviews

<!-- docs:feedback:end -->
