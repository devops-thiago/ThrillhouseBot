# Versioning and support
<!-- docs:versioning:start -->

This policy defines release numbers, compatibility, deprecation and support.
Upgrade instructions are in
[Upgrading](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#upgrading)
in the README.

## Version numbers

Releases are numbered `MAJOR.MINOR.PATCH` and tagged `vX.Y.Z`. The release
workflow refuses a tag in any other form, and a tag that does not match the
version in `pom.xml`. Between releases the version ends in `-SNAPSHOT`, and CI
publishes snapshot images from `main`; these are not releases.

ThrillhouseBot is still at 0.x. Semantic versioning allows any change before
1.0.0, so the project uses these rules instead:

- A **minor** release (0.8.0) can add settings, change defaults, change the
  database schema, need new GitHub App permissions or events, and change or
  remove anything in the public contract below. Each such change is listed at
  the start of the release's notes, with what an existing installation has to
  do.
- A **patch** release (0.8.1) fixes bugs and security issues. It does not
  remove or rename a setting, command or image tag, does not need a new GitHub
  App permission or webhook event, and does not remove a table or column. It
  can add a setting when a fix needs one, and a fix can change behaviour you
  notice, such as which files are reviewed by default. The notes list both at
  the start.

From 1.0.0 on, a change that breaks the public contract goes only into a major
release.

Releases before 0.8.0 did not always follow the patch rule: 0.6.8, a patch
release, needed two new webhook events (**Check suite** and **Status**). Patch
releases have also changed defaults, as 0.6.8 did for `GITHUB_WRITE_MAX_WAIT`
and 0.7.1 for `THRILLHOUSEBOT_REVIEW_IGNORED_FILES`, so read the notes of every
release you upgrade across.

## The public contract

These are the parts an existing installation depends on. A release keeps them
working, or says at the start of its notes what changed and what to do.

| Part | What it covers |
|---|---|
| Settings | The environment variables in the README's [Configuration](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#configuration) section and the `thrillhousebot.*` properties they set: names, meaning and defaults |
| Repository files | The keys of `.github/thrillhousebot.yml` (or `.yaml`) and the use of `.github/thrillhousebot.md` as review instructions |
| Commands | The slash commands and `@thrillhousebot` mentions listed under [Commands](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#commands), and who may run them |
| GitHub App | The webhook path `/api/webhook`, and the events and permissions in `manifest.json` |
| Container images | `ghcr.io/devops-thiago/thrillhousebot` with the tags `vX.Y.Z`, `vX.Y.Z-distroless`, `latest` and `latest-distroless`, for `linux/amd64` and `linux/arm64`, listening on port 8080 |
| Database | The PostgreSQL schema the bot keeps its review sessions, paused pull requests, finding feedback and review learnings in |
| Operations | The health endpoints under `/q/health`, `GET /metrics` when `PROMETHEUS_METRICS_ENABLED` is on, and the metric names in [Observability](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#observability) |

Three notes on that table:

- New permissions or events have to be granted by hand on an app registered
  before the release, so the notes always say whether a release needs any.
  The 0.6.8 notes list the two events it needed; the 0.7.0 notes say that no
  new permission is needed.
- Schema changes are Flyway migrations that run on startup. A released
  migration is never edited, and a database from before v0.9.0 is baselined
  at the v0.8.0 schema. Going back to an older release after its schema was
  migrated is not supported, so back up the database before you upgrade.
- Quarkus's own `quarkus.*` properties follow Quarkus and can change when the
  Quarkus version does. Only the ones the README documents are covered.

Not part of the contract, and free to change in any release:

- The wording of reviews, summaries, check runs and replies, and which
  findings a review raises. These change with almost every release.
- The prompts and the calls made to the model.
- Log messages.
- The `/api/*` endpoints behind the dashboard. They serve the bundled
  dashboard, not other clients.
- Images that are not releases: the snapshot images from `main` (`snapshot`,
  `v<version>-<short-sha>-snapshot`, `<full-sha>` and their `-distroless`
  variants) and the `test-*` images built to try out a branch or pull request.

The hidden markers the bot writes into its comments are internal too, but a
release keeps reading the ones earlier releases wrote, so an upgrade does not
raise again a finding that was declined or resolved on an open pull request.
When it cannot, the notes say so. 0.7.1 changed how the secret-scan marker is
keyed: it still matches declines recorded under the old marker, and its notes
say which case raises a declined secret finding once more.

## Deprecation and removal

No setting, command or image tag has been renamed or removed so far.

To remove or rename one:

1. A minor release marks it as deprecated. Its notes and the README name it,
   say what replaces it, and say from which release it may be removed.
2. It keeps working until it is removed. For a renamed setting, both names are
   read until then.
3. It is removed in a later minor release, never in a patch, and that
   release's notes say so at the start.

The bot does not yet warn at startup when it reads a deprecated setting. Until
it does, the release notes and the README are where a deprecation is
announced.

## Supported versions

Only the latest release gets fixes, including security fixes. A fix ships in
the next patch or minor release; it is not backported to older lines. To get a
fix, upgrade to the latest release.

Security fixes are listed under `### Security` in the release's section of
[CHANGELOG.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/CHANGELOG.md),
and a fix for a reported vulnerability is also published as a GitHub security
advisory. How a report is handled is in
[SECURITY.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/SECURITY.md).

Snapshot images and builds from `main` or a release branch get no support.
<!-- docs:versioning:end -->
