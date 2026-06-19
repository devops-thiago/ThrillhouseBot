# ThrillhouseBot review guidance

This repository **is** ThrillhouseBot — a GitHub App that reviews pull requests,
built with Quarkus (Java 25, GraalVM native) plus a Next.js dashboard. Apply the
repo-specific facts and heuristics below in addition to the general review. Keep
findings grounded in the diff; these notes exist to prevent recurring false
positives and recurring misses, not to lower the bar.

## GitHub platform facts (do not re-derive or contradict these)

- **Review threads are flat.** Every reply in a pull-request review thread carries
  `in_reply_to_id` equal to the thread's **root** comment id — never an
  intermediate reply. There is no deeper nesting to "miss"; filtering by the root
  id already captures the whole thread. Do **not** raise findings about unhandled
  nested replies.
- **List endpoints default to 30 items per page.** REST list calls
  (`.../pulls/{n}/comments`, `.../reviews`, `.../files`, `.../issues`, ...) and
  GraphQL connections return only the first page unless explicitly paginated.

## Review focus for this codebase

- **Pagination / truncation.** When a change lists a GitHub collection, confirm it
  paginates — REST: an explicit `per_page` plus a page walk until a short page;
  GraphQL: `pageInfo { hasNextPage endCursor }` plus `after` — or carries an
  explicit reason one page is enough. A single-page fetch that is then searched,
  counted, or used to drive an action (e.g. `/resolve`) silently drops everything
  past the first page; flag it, scaling severity by what is lost.
- **Comment / command parsers.** Comment-command and `@mention` detection runs on
  raw markdown. A parser must ignore tokens that appear inside fenced code blocks,
  inline code, or blockquotes — otherwise quoting or documenting a command
  executes it. Check new command/trigger regexes against quoted input.
- **Config validation completeness.** Startup/config validators should check a
  value's **format and semantics**, not just presence — e.g. a GitHub App id must
  be numeric, a duration must parse. A presence-only check that lets a malformed
  value through defeats the project's "fail fast at boot" intent.
- **New config keys must be documented.** A new environment variable or
  `thrillhousebot.*` property in a diff should also appear in the README config
  table **and** `.env.example`. Flag an added key with no documentation entry.
- **Fail-soft boundaries.** Webhook handling and review work run off the 200-ack
  thread and must fail soft (log and continue). A handler that throws out to the
  caller can drop a delivery or leave half-applied state — call that out.

## House conventions

- The bot's own account login(s) are configurable via
  `thrillhousebot.github.bot-logins`. Identify the bot's own comments through
  `TriggerDetector.isBotComment(...)`, never a hardcoded `"...[bot]"` literal.
- Before declaring two places inconsistent, confirm both are in the provided
  material and quote them; GitHub payload fields like `head`/`base` can be null,
  so a deref must be guarded the same way every consumer guards it.
