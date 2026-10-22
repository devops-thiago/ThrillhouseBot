# How ThrillhouseBot compares
<!-- docs:comparison:start -->

This page compares ThrillhouseBot with other AI code-review tools. Each fact
about another tool comes from its public documentation, checked in October 2026,
and the source is linked in the notes below the table. Vendors change their
products often, so check the source if a detail matters to you. "Not
documented" means the public docs do not mention the capability; it does not
prove the tool lacks it.

The table covers ThrillhouseBot, [CodeRabbit][cr], the open-source
[PR-Agent][pra], [GitHub Copilot code review][cop], the [PRSense][prs] CLI, and
[Kit][kit] (cased-kit). Qodo's commercial product is outside the scope of this table. PRSense and Kit
are included as CLI alternatives that can run with your own model.

| Feature | ThrillhouseBot | CodeRabbit | PR-Agent | Copilot review | PRSense | Kit |
|---|---|---|---|---|---|---|
| License | Apache-2.0 | Proprietary [^cr-tos] | MIT [^pra-lic] | Proprietary [^cop-terms] | Apache-2.0 [^prs] | MIT [^kit] |
| Git platforms | GitHub | GitHub, GitLab, Bitbucket, Azure DevOps [^cr-plat] | GitHub, GitLab, Bitbucket, Azure DevOps, Gitea [^pra-plat] | GitHub; Azure DevOps in preview [^cop] | Local git; reads GitHub, GitLab and Codeberg PRs [^prs] | GitHub, local diffs [^kit] |
| How reviews run | GitHub App, on webhooks | GitHub App; also IDE and CLI [^cr-plat] | Self-hosted app or webhook, GitHub Action, CLI [^pra-plat] | Requested reviewer or ruleset; also IDEs and CLI [^cop] | CLI or pre-push hook, output to the terminal [^prs] | CLI or CI job, posts one PR comment [^kit] |
| Self-host | Yes, the only option | Enterprise, 500+ seats [^cr-sh] | Yes | Models no; runners yes [^cop-run] | Yes, local CLI [^prs] | Yes, CLI or your own CI [^kit] |
| Bring your own model | Any OpenAI-compatible endpoint | Self-hosted only; providers not listed [^cr-sh] | Yes, via LiteLLM or an OpenAI-compatible URL [^pra-model] | No [^cop-model] | Ollama, OpenAI, Google, Anthropic [^prs] | Anthropic, OpenAI, Google, Ollama, OpenAI-compatible [^kit] |
| Local models (Ollama) | Yes | Not documented | Yes; docs include a dated quality caveat [^pra-model] | No [^cop-model] | Yes, the default [^prs] | Yes [^kit] |
| Cost / token reporting | Built-in web dashboard | Usage dashboard without token or cost figures [^cr-dash] | Optional tokens and cost in the PR comment [^pra-cost] | Billing reports and usage metrics, no per-review breakdown [^cop-bill] | Token counts with `--stats` [^prs] | Dollar cost per review in CLI output [^kit] |
| Footprint | Native binary; versioned image measurements [^tb-size] | SaaS, or a self-hosted container image [^cr-sh] | Python package or Docker image [^pra-foot] | SaaS; the review runs on GitHub Actions [^cop-run] | Node.js CLI [^prs] | Python package [^kit] |
| Cost model | No software fee; hosting and model costs apply | Seat subscription with usage limits; public repositories free [^cr-price] | No software fee; hosting and model costs apply | Paid plans or organization-funded reviews [^cop-bill] | No software fee; hosting and model costs apply | No software fee; hosting and model costs apply |
| Deterministic secret scanning | Opt-in, added lines only | Yes, Betterleaks on by default [^cr-tools] | Not documented [^pra-sec] | Not documented [^cop-sec] | Not documented | Not documented |
| IaC / infrastructure checks | Opt-in, fixed rule set | Yes: Checkov, Trivy, TFLint, Hadolint, actionlint [^cr-tools] | Not documented [^pra-sec] | Not documented | Not documented | Not documented |
| Linked-issue / acceptance-criteria check | Opt-in, GitHub Issues in the same repository | Yes: GitHub, GitLab, Jira, Linear, Azure DevOps [^cr-issues] | Yes, on by default: GitHub and GitLab issues, Jira Cloud, Asana, Linear [^pra-ticket] | No built-in check; issue context through MCP servers [^cop-mcp] | Not documented | Not documented |
| CI-failure context in reviews | Opt-in: failed checks and test names; optional Actions log tail | Yes, reads the failure output [^cr-ci] | No built-in tool; the GitHub Action can add a CI output file [^pra-ci] | Not documented | Not documented | Not documented |
| Learning from maintainer feedback | Opt-in: declines with a reason, plus `/remember` | Yes, learnings from chat replies [^cr-learn] | No; static instruction files only [^pra-learn] | Repository facts with Copilot Memory (preview) [^cop-mem] | Not documented | Not documented; on the roadmap [^kit-road] |

## Where ThrillhouseBot fits

ThrillhouseBot runs as a self-hosted GitHub App. You configure an
OpenAI-compatible model endpoint; a local Ollama endpoint keeps model requests
on your network. The dashboard stores review history, token use and costs.
Large diffs are split into batches within a token budget, and files that do not
fit are listed as unreviewed.

In 0.8.0, the finding verifier can also revise suggested fixes and remove
contradicted description sentences. An optional summary-verification call
checks summary statements against the diff and findings. These model checks
can miss defects or retain unsupported claims; inspect suggestions before
applying them.

Secret and IaC scanning, linked-issue context, CI-failure context and review
learnings are off by default. The scanners match fixed patterns on added
lines. Linked issues come from GitHub Issues in the same repository.
ThrillhouseBot supports GitHub pull requests only and has no hosted service.

The table separates deployment and model choice from review context and
reporting. CodeRabbit and Copilot provide hosted reviews. PR-Agent supports
self-hosted integrations on several git platforms. PRSense and Kit provide
CLI workflows. Check the linked documentation for the platforms, model
providers and subscription terms your deployment needs.

[cr]: https://www.coderabbit.ai/
[pra]: https://github.com/The-PR-Agent/pr-agent
[cop]: https://docs.github.com/en/copilot/concepts/agents/code-review
[prs]: https://prsense.org/
[kit]: https://kit.cased.com/pr-reviewer/

[^tb-size]: The `linux/amd64` image for v0.6.9 is 57 MB compressed (55.7 MB
    for `linux/arm64`). The native binary inside it is about 138 MB
    uncompressed; the release tarball is 48 MB.
[^cr-tos]: CodeRabbit's terms reserve all intellectual property in the service
    and forbid reverse engineering. <https://www.coderabbit.ai/terms-of-service>
[^cr-plat]: GitHub.com, GitLab.com, Bitbucket Cloud and Azure DevOps, plus GitHub
    Enterprise Server, self-managed GitLab and Bitbucket Data Center. Reviews
    also run from IDE extensions and a CLI.
    <https://docs.coderabbit.ai/platforms/overview> · <https://docs.coderabbit.ai/>
[^cr-sh]: "The self-hosted option is available for CodeRabbit Enterprise
    customers with 500 or more user seats." It ships as a container image and
    connects to "your own large language model provider or account"; the page
    does not list providers or local models.
    <https://docs.coderabbit.ai/self-hosted/github>
[^cr-dash]: The Review Usage dashboard reports review events, rate limits and
    files reviewed, and "is not a billing ledger".
    <https://docs.coderabbit.ai/management/review-usage-dashboard>
[^cr-price]: Plans are priced per developer per month, with included review limits and
    optional usage-based reviews. There is a 14-day trial; public repositories
    are reviewed for free.
    <https://www.coderabbit.ai/pricing>
[^cr-tools]: Betterleaks "is enabled by default" and runs on changed files.
    The tool list includes TruffleHog, Checkov, Trivy, TFLint, Hadolint,
    actionlint and zizmor. <https://docs.coderabbit.ai/tools/betterleaks> ·
    <https://docs.coderabbit.ai/tools/list>
[^cr-issues]: Linked-issue assessment rates the pull request against the linked
    issue. It is on by default for private repositories and off for public ones.
    <https://docs.coderabbit.ai/guides/linked-issues>
[^cr-ci]: "When a CI/CD pipeline fails on a pull request, CodeRabbit reads the
    failure output and posts inline comments with suggested fixes." Supports
    GitHub Actions, GitLab CI/CD, CircleCI, Jenkins and Azure DevOps Pipelines.
    <https://docs.coderabbit.ai/tools/pipeline-remediation>
[^cr-learn]: CodeRabbit "learns your team's code-review preferences based on chat
    interactions". <https://docs.coderabbit.ai/knowledge-base/learnings>
[^pra-lic]: PR-Agent moved to the community-owned The-PR-Agent organization in
    April 2026. Its license changed from AGPL-3.0 to Apache-2.0 in May 2026 and
    to MIT in July 2026.
    <https://www.qodo.ai/blog/qodo-is-handing-pr-agent-over-to-the-community/> ·
    <https://github.com/The-PR-Agent/pr-agent/blob/main/LICENSE>
[^pra-plat]: The app or webhook server runs on GitHub, GitLab, Bitbucket, Azure
    DevOps and Gitea; the CLI and CI pipelines are also supported.
    <https://docs.pr-agent.ai/overview/supported_platforms/>
[^pra-model]: Models are reached through LiteLLM or an OpenAI-compatible
    `api_base`. Ollama and vLLM are supported. The page also carries a January 2025
    warning about local-model review quality; that is not a current benchmark.
    <https://docs.pr-agent.ai/usage-guide/changing_a_model/>
[^pra-cost]: `config.output_run_details` adds the model, token count and run
    time to the comment; `config.output_run_cost` adds an estimated cost. Both
    are off by default.
    <https://docs.pr-agent.ai/usage-guide/additional_configurations/>
[^pra-foot]: `pip install pr-agent` (Python 3.12 or later) or the
    `pragent/pr-agent` Docker image. <https://docs.pr-agent.ai/installation/>
[^pra-sec]: The optional security section of `/review` is written by the model;
    no deterministic scanner is documented. <https://docs.pr-agent.ai/tools/review/>
[^pra-ticket]: Ticket compliance is on by default
    (`require_ticket_analysis_review`) and rates the pull request against the
    ticket, including its acceptance criteria.
    <https://docs.pr-agent.ai/core-abilities/fetching_ticket_context/>
[^pra-ci]: The open-source tool list has no CI-feedback tool. The GitHub Action
    can inject a file from an earlier CI step, such as a test report, into the
    prompt. <https://docs.pr-agent.ai/installation/github/>
[^pra-learn]: Instructions come from static files (`extra_instructions`,
    `repo_context_files`). Learning from accepted suggestions belonged to Qodo's
    commercial product. <https://docs.pr-agent.ai/tools/improve/>
[^cop]: Supported in GitHub.com, GitHub CLI, GitHub Mobile, several IDEs and
    "Azure DevOps (public preview)". Reviews can be requested by hand or
    automatically through a ruleset.
    <https://docs.github.com/en/copilot/concepts/agents/code-review>
[^cop-terms]: Copilot is covered by GitHub's product-specific terms.
    <https://github.com/customer-terms/github-copilot-product-specific-terms>
[^cop-run]: The review runs on GitHub Actions, on standard, larger or
    self-hosted runners; Actions Runner Controller is the only supported
    self-hosted setup.
    <https://docs.github.com/en/copilot/how-tos/copilot-on-github/set-up-copilot/configure-runners>
[^cop-model]: "Model switching is not supported." Bring-your-own-key applies to
    Copilot Chat, not to pull request review; the Copilot CLI's local review
    can use your own provider, including Ollama.
    <https://docs.github.com/en/copilot/concepts/agents/code-review> ·
    <https://github.blog/changelog/2026-04-07-copilot-cli-now-supports-byok-and-local-models/>
[^cop-bill]: Copilot Free does not include code review. Organizations can also fund
    reviews for members without an individual Copilot license. Reviews use
    AI credits for tokens plus GitHub Actions minutes, and minutes are charged
    on private repositories only. Usage shows up in billing reports and the
    Copilot usage metrics; the model is not disclosed.
    <https://docs.github.com/en/copilot/concepts/agents/code-review> ·
    <https://docs.github.com/en/copilot/reference/copilot-billing/models-and-pricing> ·
    <https://github.blog/changelog/2026-04-27-github-copilot-code-review-will-start-consuming-github-actions-minutes-on-june-1-2026/>
[^cop-sec]: The code review docs describe no secret scanner. GitHub secret
    scanning and GitHub Code Quality are separate products.
    <https://docs.github.com/en/copilot/concepts/agents/code-review>
[^cop-mcp]: The review can pull issue-tracking context from configured MCP
    servers. <https://docs.github.com/en/copilot/concepts/agents/code-review>
[^cop-mem]: Copilot Memory is in public preview on all paid plans, and
    code review uses repository-level facts only. A 👎 on a comment does not
    stop the same comment on a re-review.
    <https://docs.github.com/en/copilot/concepts/agents/copilot-memory> ·
    <https://docs.github.com/en/copilot/how-tos/use-copilot-agents/request-a-code-review/use-code-review>
[^prs]: PRSense is the `@prsense/cli` npm package (Apache-2.0, Node.js 22 or
    later). It reviews a local branch or a PR URL and prints its findings; with
    `--stats` it adds token counts. Ollama is the default provider. The README
    calls it experimental. <https://github.com/navxio/prsense> ·
    <https://registry.npmjs.org/@prsense/cli>
[^kit]: Kit is the MIT-licensed `cased-kit` Python package. `kit review` takes a
    PR URL or a local diff, from a terminal or a CI job, and prints the review's
    cost. Its latest release, v3.5.1, is from January 2026.
    <https://kit.cased.com/pr-reviewer/> ·
    <https://kit.cased.com/pr-reviewer/configuration/> ·
    <https://github.com/cased/kit>
[^kit-road]: Review profiles store static guidelines. A feedback-learning system
    is listed under planned features.
    <https://kit.cased.com/pr-reviewer/profiles/> ·
    <https://github.com/cased/kit/blob/main/src/kit/pr_review/ROADMAP.md>
<!-- docs:comparison:end -->
