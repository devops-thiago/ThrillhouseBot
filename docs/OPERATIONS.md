# Operations
<!-- docs:operations:start -->

How to run ThrillhouseBot day to day: what it keeps and where, how to back it
up and upgrade it, what a restart does, and how to wire its health checks into
Docker Compose or Kubernetes. There are no migration scripts, so instead of
migrate and rollback commands the page covers backups and restoring them. The
commands assume the `docker-compose.yml` in the repository, with the services
`thrillhousebot` and `db`.

## What is stored, and what is not

The bot keeps four tables in PostgreSQL:

| Table | Contents |
|---|---|
| `reviewsession` | One row per review: status, tokens, cost, model output and findings, shown on the dashboard |
| `pausedpr` | Pull requests silenced with `/pause` |
| `finding_feedback` | 👍, 👎 and "not useful" replies on findings ([Finding feedback](https://github.com/devops-thiago/ThrillhouseBot/blob/main/docs/FEEDBACK.md)) |
| `review_learning` | Review learnings, when `REVIEW_LEARNINGS_ENABLED` is on |

Everything else lives in the process and is lost on a restart:

- dashboard login sessions, so users sign in again;
- the dashboard's WebSocket replay buffer;
- the per-PR auto-review window and the webhook delivery ids used to drop
  redeliveries;
- the findings a superseded review hands to the review that replaces it;
- verdicts held on pending CI, so a held pull request needs a manual `/review`;
- the claims the verifier rejected on a head commit;
- outgoing notifications not yet sent.

The bot's state on GitHub (its reviews, summary comments, check runs and
finding threads) stays on GitHub. A follow-up review reads it back from there,
so a restart does not lose track of the findings on open pull requests.

## Scaling

Run exactly one replica. The in-memory state above is not shared, and on
startup the bot marks every review still `in_progress` in the database as
failed (see [Restarts and shutdown](#restarts-and-shutdown)). A second replica
starting next to a running one would mark that replica's reviews failed while
they are still running.

For the same reason, replace the bot by stopping the old process before the new
one starts. `docker compose up -d` does this. In Kubernetes, use
`replicas: 1` with the `Recreate` deployment strategy, not `RollingUpdate`.

Within the one process there is no review queue or worker limit: each review
runs on its own virtual thread. Model calls are not limited either unless
`AI_MAX_CONCURRENT_CALLS` sets a cap, so the model provider is what limits how
many reviews run at once.

## Health endpoints

The bot serves the SmallRye Health endpoints on its HTTP port (8080, or
`PORT`):

| Endpoint | Checks | Use it for |
|---|---|---|
| `/q/health/live` | None: `200` while the process serves HTTP | Liveness |
| `/q/health/ready` | The PostgreSQL connection: `503` with `"status": "DOWN"` while the database cannot be reached | Readiness |
| `/q/health/started` | None: `200` once the application has started | Startup |
| `/q/health` | All of the above together | Manual checks |

The bot does not start without its database. When PostgreSQL cannot be reached
at boot, Hibernate cannot update the schema and the process exits with
`Failed to start application`. With `restart: unless-stopped` Compose starts it
again, and the bot service waits for the `db` health check on the first start.

### Docker Compose

The images have no `curl` or `wget`. The default UBI9-micro image has `bash`,
which can make the request itself:

```yaml
services:
  thrillhousebot:
    healthcheck:
      test:
        - CMD
        - bash
        - -c
        - >-
          exec 3<>/dev/tcp/127.0.0.1/8080 &&
          printf 'GET /q/health/ready HTTP/1.0\r\n\r\n' >&3 &&
          read -r status <&3 && [[ $$status == *" 200 "* ]]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
```

`$$` keeps Compose from reading `$status` as one of its own variables. The
`-distroless` image has no shell, so this check does not work there; check it
from outside the container, or use Kubernetes probes.

### Kubernetes

Kubernetes probes make the request from the kubelet, so they work with both
images:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: thrillhousebot
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: thrillhousebot
  template:
    metadata:
      labels:
        app: thrillhousebot
    spec:
      terminationGracePeriodSeconds: 40
      containers:
        - name: thrillhousebot
          image: ghcr.io/devops-thiago/thrillhousebot:v0.7.1
          ports:
            - containerPort: 8080
          envFrom:
            - secretRef:
                name: thrillhousebot
          startupProbe:
            httpGet:
              path: /q/health/started
              port: 8080
            periodSeconds: 5
            failureThreshold: 24
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: 8080
            periodSeconds: 30
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: 8080
            periodSeconds: 10
```

The secret holds the same variables as `.env`, including `DATABASE_URL`,
`QUARKUS_DATASOURCE_USERNAME` and `QUARKUS_DATASOURCE_PASSWORD`, which Compose
sets for you.

## Restarts and shutdown

Reviews run in the bot process and are not queued anywhere else. On `SIGTERM`
the bot stops taking new work and gives running reviews up to 30 seconds to
finish, then interrupts them.

Docker sends `SIGKILL` 10 seconds after `SIGTERM` unless told otherwise, so set
`stop_grace_period: 40s` on the `thrillhousebot` service to use the full 30
seconds. In Kubernetes the default `terminationGracePeriodSeconds` is 30; the
example above sets 40.

A review that does not finish is not resumed. Depending on where it was
stopped, it either records itself as failed and posts the usual failure notice,
or leaves its session and its check run on GitHub in progress. A crash or a
`SIGKILL` always leaves them in progress. On the next start the bot marks every
session still in progress as failed with "Review interrupted before it finished
(bot restart or crash)", keeping the tokens and cost it had spent, and logs how
many it marked. The check run is not updated. Comment `/review` on the pull
request to review it again.

Webhooks that arrive while the bot is down fail on GitHub's side and are not
sent again by themselves. To replay one, redeliver it from the GitHub App's
settings (Advanced, Recent Deliveries), or comment `/review` on the pull
request.

## Backups

Back up the PostgreSQL database and your `.env`. The database holds the review
history and the data above; `.env` holds the GitHub App's private key, the
webhook secret and the model API key, and the bot cannot run without them. Keep
the backup of `.env` somewhere as safe as the secrets themselves.

With the bundled Compose file, the database is in the `pgdata` volume. To take
a backup while the bot runs:

```bash
docker compose exec -T db pg_dump -U thrillhouse -d thrillhouse -Fc > thrillhouse-$(date +%F).dump
```

To restore one, stop the bot first, so no review writes to the database during
the restore:

```bash
docker compose stop thrillhousebot
docker compose exec -T db pg_restore -U thrillhouse -d thrillhouse --clean --if-exists < thrillhouse-2026-10-18.dump
docker compose start thrillhousebot
```

Use your `DB_USER` in place of `thrillhouse` after `-U` if you changed it.

## Upgrades and database changes

There are no migration scripts. On startup the production profile lets
Hibernate update the schema in place (`schema-management.strategy=update`): it
creates missing tables and columns and never drops or renames them. On a new,
empty database the first start logs warnings such as
`constraint "uq_pausedpr_repository_pr" of relation "pausedpr" does not exist,
skipping`; they are expected.

To upgrade:

1. Read the release's section in
   [CHANGELOG.md](https://github.com/devops-thiago/ThrillhouseBot/blob/main/CHANGELOG.md),
   and those of any release you skip. Each starts with what changes for an
   existing installation.
2. Back up the database as above.
3. Set `IMAGE_TAG` in `.env` to the new release, if you pin one, and run
   `docker compose pull && docker compose up -d`.
4. Check the log for the `started in` line and `/q/health/ready` for `UP`.

There is no command to roll a schema change back. To go back to the previous
release, stop the bot, restore the backup taken before the upgrade, set
`IMAGE_TAG` to the previous release and start it again. Running an older release
against a database a newer one has updated is not supported.
<!-- docs:operations:end -->
