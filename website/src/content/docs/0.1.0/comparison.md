---
slug: 0.1.0/comparison
title: How it compares
description: Where ThrillhouseBot sits next to other AI code-review tools.
---

A quick, honest look at where ThrillhouseBot sits next to other AI code-review
tools. Facts verified June 2026 from the sources linked below; vendor
capabilities change, so check the source if a detail matters to you.

The table covers ThrillhouseBot, [CodeRabbit][cr], the open-source
[PR-Agent][pra], [GitHub Copilot code review][cop], the [PRSense][prs] CLI, and
[Kit][kit] (cased-kit). PR-Agent's commercial hosted sibling, Qodo Merge, is
left out because its self-hosting and model details sit behind enterprise sales
and could not be verified from public docs. PRSense and Kit are included because
they overlap on self-hosting and bring-your-own-model workflows; they differ in
how reviews are triggered and how cost visibility is surfaced.

| | ThrillhouseBot | CodeRabbit | PR-Agent | Copilot review | PRSense | Kit |
|---|---|---|---|---|---|---|
| License | Apache-2.0 | Proprietary | Apache-2.0 [^pra-lic] | Proprietary | Apache-2.0 [^prs-lic] | MIT [^kit-lic] |
| Self-host | Yes | Enterprise only, 500+ seats [^cr-sh] | Yes | Models no; self-hosted runners yes [^cop-run] | Yes (CLI) [^prs] | Yes (CLI / Actions) [^kit] |
| Bring your own model | Any OpenAI-compatible endpoint | Yes: OpenAI, Azure OpenAI, Bedrock, Anthropic [^cr-llm] | Yes, via OpenAI-compatible / LiteLLM | No, GitHub-managed | Yes [^prs] | Yes [^kit] |
| Local models (Ollama) | Yes | No [^cr-llm] | Yes, with config caveats | No | Yes [^prs] | Yes [^kit] |
| Cost / token dashboard | Built-in web UI | No | No | GitHub billing only | Token counts in CLI only [^prs] | Per-review cost in CLI [^kit] |
| Footprint | ~50 MB native binary | SaaS or self-hosted container | Python app / container | SaaS plus Actions minutes [^cop-bill] | Node.js CLI [^prs] | Python app [^kit] |
| Cost model | Free; you pay your own API usage | Freemium | Free | Paid [^cop-bill] | Free; you pay your own API usage | Free; you pay your own API usage |

## Where ThrillhouseBot fits

Among the tools in the table above, ThrillhouseBot is the only one that combines
a GitHub App (webhook-driven reviews with inline PR comments), a built-in web
dashboard for cost and token analytics, and a small native footprint, all under
an OSI-approved license. If you want reviews to run on your own infrastructure
against a local Ollama model so that no code leaves your network, and you want
ongoing cost visibility in a dashboard rather than per-run CLI output, that is
the niche it targets.

CodeRabbit and Copilot are more polished hosted products with broader reach.
ThrillhouseBot reviews GitHub pull requests only, with no GitLab or Bitbucket
support, and there is no managed hosting option: you run it yourself.

PR-Agent, PRSense, and Kit are the closest in spirit for self-hosting with your
own model. PR-Agent is the most established open-source GitHub review bot in this
set. PRSense emphasizes grounded findings tied to the diff and ships as a CLI.
Kit adds rich repository context, local diff review, and per-run cost output in
the terminal or CI logs. None of those three ship a GitHub App plus web
dashboard in one package the way ThrillhouseBot does.

Pick CodeRabbit or Copilot if you want a hosted product and don't need to bring
your own model. Pick PR-Agent, PRSense, or Kit if you prefer CLI or workflow
integration. Pick ThrillhouseBot if you want a GitHub App with automatic reviews,
a live dashboard, and a small native binary.

[cr]: https://www.coderabbit.ai/
[pra]: https://github.com/qodo-ai/pr-agent
[cop]: https://docs.github.com/en/copilot/concepts/agents/code-review
[prs]: https://prsense.org/
[kit]: https://kit.cased.com/pr-reviewer/

[^pra-lic]: PR-Agent was moved to a community-owned GitHub organization and
    relicensed under Apache-2.0 in April 2026.
[^cr-sh]: CodeRabbit docs: "The self-hosted option is only available for
    CodeRabbit Enterprise customers with 500 user seats or more."
    <https://docs.coderabbit.ai/self-hosted/github>
[^cr-llm]: Self-hosted CodeRabbit requires your own LLM credentials for OpenAI,
    Azure OpenAI, AWS Bedrock, or Anthropic; its docs do not list local models
    or Ollama. <https://docs.coderabbit.ai/self-hosted/github>
[^cop-run]: Copilot code review uses GitHub-managed models; you cannot supply
    your own. It can run on self-hosted or larger GitHub-hosted runners.
    <https://docs.github.com/en/copilot/concepts/agents/code-review>
[^cop-bill]: From June 1, 2026, Copilot code review is billed as AI credits for
    token use plus GitHub Actions minutes for the review infrastructure.
    <https://github.blog/changelog/2026-04-27-github-copilot-code-review-will-start-consuming-github-actions-minutes-on-june-1-2026/>
[^prs]: PRSense ships as the `@prsense/cli` npm package (Apache-2.0). Reviews
    run locally or in CI; output includes token counts but there is no web
    dashboard. Supports Ollama and cloud providers.
    <https://prsense.org/> · <https://www.npmjs.com/package/@prsense/cli>
[^prs-lic]: `@prsense/cli` is published under Apache-2.0 on npm.
[^kit]: Kit (cased-kit) is a MIT-licensed Python CLI and library. Reviews can
    run from the terminal, against local diffs, or from GitHub Actions; each run
    reports LLM cost in stdout, not in a separate dashboard.
    <https://kit.cased.com/pr-reviewer/> · <https://github.com/cased/kit>
[^kit-lic]: cased-kit is published under MIT on PyPI and GitHub.
