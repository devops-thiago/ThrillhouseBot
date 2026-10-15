# Governance

ThrillhouseBot has a single maintainer, Thiago Gonzaga
([@devops-thiago](https://github.com/devops-thiago)), who owns the repository
and makes the final decision on changes, releases and the roadmap.

## How decisions are made

Changes are proposed in public, as an issue, a discussion or a pull request.
For anything larger than a small fix, open an issue first so the approach can
be agreed before code is written. [CONTRIBUTING.md](CONTRIBUTING.md) lists what
a pull request needs before it can be merged.

The maintainer decides whether a change is accepted, which milestone it belongs
to and when a release is cut, and writes the reason on the issue or pull
request. Planned work is tracked in the
[milestones](https://github.com/devops-thiago/ThrillhouseBot/milestones), which
are the project's roadmap.

A pull request needs both the static CI checks and ThrillhouseBot's own review
to pass. When they disagree, the maintainer decides and records the reason on
the pull request, as described in
[When the gates disagree](CONTRIBUTING.md#when-the-gates-disagree).

If you disagree with a decision, say so on the issue or pull request and explain
why. The maintainer can reopen or change a decision when new information comes
up. Security reports are the exception: they are handled privately, as
[SECURITY.md](SECURITY.md) describes, until a fix is released.

## Roles

| Role | Who | Responsibilities |
|------|-----|------------------|
| Maintainer | @devops-thiago | Triage issues and discussions, review and merge pull requests, keep CI, dependencies and the docs site working, set the roadmap |
| Release manager | @devops-thiago | Prepare release branches, sign and push release tags, and check the release workflow, following [docs/RELEASING.md](docs/RELEASING.md) |
| Security contact | @devops-thiago | Receive private vulnerability reports and handle them as [SECURITY.md](SECURITY.md) describes |
| Code owner | @devops-thiago | Required reviewer for `.github/workflows/` and `CODEOWNERS` (see [.github/CODEOWNERS](.github/CODEOWNERS)) |
| Contributor | Anyone | Open issues, discussions and pull requests that follow [CONTRIBUTING.md](CONTRIBUTING.md), with each commit signed off under the Developer Certificate of Origin |

## Adding maintainers

The project has one maintainer today, so its bus factor is 1. Co-maintainers
are welcome. A contributor with a record of accepted pull requests and useful
reviews can ask to become one, or the maintainer may invite them. A new
maintainer is added to this file and to `CODEOWNERS` in a pull request, and
gets write access to the repository.

## Continuity

The project must be able to keep handling issues, merging pull requests and
publishing releases if the maintainer can no longer do so.

- A successor is designated in the maintainer's GitHub account settings, using
  GitHub's
  [successor feature](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/maintaining-ownership-continuity-of-your-personal-accounts-repositories).
  GitHub lets a successor archive the maintainer's public repositories or
  transfer them to their own account or to an organization. Once the repository
  is transferred, the successor owns it and can manage issues, merge pull
  requests and push release tags.
- If the maintainer decides to step down, they transfer the repository or add a
  new maintainer directly, without going through GitHub.
- Releases do not depend on a private key held by one person. The release
  workflow signs images and binaries with keyless cosign, using the GitHub
  Actions OIDC identity, so there is no signing key to hand over.
- Everything needed to build, test and release is in this repository and runs
  in GitHub Actions.
- After a transfer, the new owner updates the places that name the current
  account: the image name `ghcr.io/devops-thiago/thrillhousebot` in the
  workflows, the docs-site URL, the certificate identity in the README's
  release-verification commands, `CODEOWNERS`, and the repository rulesets.
