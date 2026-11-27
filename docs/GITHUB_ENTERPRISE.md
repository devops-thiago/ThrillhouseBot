# GitHub Enterprise
<!-- docs:enterprise:start -->

This guide covers what an organization on GitHub Enterprise has to set up on
GitHub, on top of
[GitHub App setup](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#github-app-setup).

## Which products work

| Your GitHub address | Product | Supported |
|---|---|---|
| `github.com/your-org` | Enterprise Cloud | Yes |
| `github.yourcompany.com` | Enterprise Server | Yes, with the settings below. Not tested on a real instance |
| `yourcompany.ghe.com` | Enterprise Cloud with data residency | The same settings. Not tested on a real instance |

If the `.env.example` of the release you run does not list `GITHUB_URL`, that
release works with `github.com` only.

## Point the bot at your GitHub

Skip this for Enterprise Cloud on `github.com`. Otherwise set all three:

| Setting | Enterprise Server | Data residency |
|---|---|---|
| `GITHUB_URL` | `https://<host>` | `https://<subdomain>.ghe.com` |
| `GITHUB_API_URL` | `https://<host>/api/v3` | `https://api.<subdomain>.ghe.com` |
| `GITHUB_GRAPHQL_URL` | `https://<host>/api/graphql` | `https://api.<subdomain>.ghe.com/graphql` |

Each one defaults to `github.com` on its own, so a missed one keeps calling
`github.com`. Use `https` addresses, because the bot sends its credentials to
them. Dashboard sign-in follows `GITHUB_URL` unless `GITHUB_OAUTH_URL` is set.

Support for these addresses is still being completed, tracked in
[#1487](https://github.com/devops-thiago/ThrillhouseBot/issues/1487):

- a linked issue written as a full URL is recognized only for `github.com`, so
  write it as `#123` or `owner/repo#123`;
- the pull request links in the dashboard's session list lead to `github.com`;
- no way to add an internal certificate authority to the published images is
  documented yet.

## Register the app under the organization

The installer registers the app under **your personal account** unless you say
otherwise. For an app owned by the organization, fill in the **Organization**
field on the install page. On Enterprise Server or data residency, check the
**GitHub address** field too: the page your bot serves starts from `GITHUB_URL`,
the pages in the repository and on this project's site start from `github.com`.

If the page you use has no such fields, register the app by hand at
`<GITHUB_URL>/organizations/<ORG>/settings/apps/new`
(`https://github.com/organizations/<ORG>/settings/apps/new` on `github.com`),
with the settings, permissions and events in
[manual registration](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#option-b-manual-registration).

The app asks for an organization permission, so an organization owner has to
install it. The same applies later: when the app's permissions change, an owner
has to accept the new ones on each installation.

## What each credential is for

| Setting | Used for | Needed |
|---|---|---|
| `GITHUB_APP_ID`, `GITHUB_PRIVATE_KEY` | The calls the bot makes as the app | Always |
| `GITHUB_WEBHOOK_SECRET` | Checking the signature of each delivery | Always |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | Dashboard sign-in only: starting it, exchanging the code and revoking the token at sign-out | Only for the dashboard |

Leave both client settings unset to run without the dashboard. Reviews and
commands do not use them.

## Choose who can use it

GitHub limits a
[private app](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/making-a-github-app-public-or-private)
owned by an organization to that organization: it installs only there, and only
its members can authorize it, which is what dashboard sign-in does.

Make the app public if it has to serve other accounts. Anyone can install a
public app, so set `GITHUB_ALLOWED_ACCOUNTS` to the accounts you serve.

With Enterprise Managed Users on Enterprise Cloud, GitHub restricts who can
install apps and how far an app can be shared. Check
[the restrictions on managed user accounts](https://docs.github.com/en/enterprise-cloud@latest/admin/managing-iam/understanding-iam-for-enterprises/abilities-and-restrictions-of-managed-user-accounts)
with your enterprise administrator.

## Tell the bot its login

App names are unique across a GitHub, so on `github.com` your app will probably
not get the slug the bot expects by default. The slug is the last part of the
app's public page address, shown in its settings.

```bash
GITHUB_BOT_LOGINS=<slug>[bot]
```

The bot uses this value to recognize its own comments and reviews, and to build
the mention it answers to (`@<slug>`). It accepts several logins separated by
commas, and the bot uses the first one in its messages.

## Allow the bot through an IP allow list

If the organization or enterprise has an IP allow list, add the address the bot
calls GitHub from. You can add it to the allow list yourself, or add it to the
[app's own allow list](https://docs.github.com/en/apps/maintaining-github-apps/managing-allowed-ip-addresses-for-a-github-app)
and let the organization take it from installed apps.

## Open the network both ways

GitHub must reach `https://<your-host>/api/webhook` over HTTPS. A proxy must
pass the request body through unchanged, or the signature check fails.

If a firewall filters sources, allow the ones for your product:

- `github.com`: the ranges under `hooks` in <https://api.github.com/meta>;
- data residency: the egress ranges for your region in
  [Network details for GHE.com](https://docs.github.com/en/enterprise-cloud@latest/admin/data-residency/network-details-for-ghecom);
- Enterprise Server: the address of your own instance.

For what the bot connects to, see
[outbound connections](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#outbound-connections),
which names the `github.com` addresses.

## Define required checks in rulesets

[CI gating](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#ci-gating)
holds an approval while a required check is pending or failing. The bot looks
for required checks in two places:

| Source | Permission GitHub asks for | Readable with the app as documented |
|---|---|---|
| Rulesets (repository and organization) | `Metadata: Read` | Yes |
| Classic branch protection | `Administration: Read` | No |

Define required status checks in **rulesets**. Otherwise:

- if no ruleset applies to the branch, the bot cannot tell which checks are
  required and treats every check on the commit as required;
- if a ruleset applies but the required checks are only in classic branch
  protection, the bot does not see them and does not wait for them.

## Before you require the bot's check or approval

The check run is named `ThrillhouseBot Review`. Before you make it a required
check:

- GitHub counts a
  [neutral conclusion as passing](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks),
  and the bot concludes neutral when it holds an approval for pending CI;
- a required check that is never created blocks the merge, so try the pull
  requests your draft, label, author and branch filters skip.

If your branch rules require approving reviews, confirm on a test pull request
how the bot's approval is counted before you rely on it either way.

## Check the installation

1. In the app's settings, **Advanced → Recent Deliveries** shows successful
   deliveries.
2. A small test pull request gets the `ThrillhouseBot Review` check and a
   review.
3. `/help` gets an answer.
4. `@<slug> review` starts a review, which confirms `GITHUB_BOT_LOGINS`.

Enterprise Server and data residency are not tested on a real instance. If you
run one of them, report what worked and what failed on
[#1487](https://github.com/devops-thiago/ThrillhouseBot/issues/1487), leaving
out host names and secrets.

If no review appears, see
[troubleshooting](https://github.com/devops-thiago/ThrillhouseBot/blob/main/README.md#pr-opened-but-no-review-posted).

<!-- docs:enterprise:end -->
