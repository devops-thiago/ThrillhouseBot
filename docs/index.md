<!-- This page is built into the docs site (https://devops-thiago.github.io/ThrillhouseBot/).
     The features list is included from the README between the mkdocs:features markers. -->

# ThrillhouseBot

> **"Everything's coming up Thrillhouse!"**

A self-hosted, GraalVM-native PR review bot, built as a GitHub App with Quarkus.
It reviews pull requests using any OpenAI-compatible chat API, so the review is
language-agnostic and you can pick the provider that suits you — including a
local Ollama model, so no code has to leave your network.

![ThrillhouseBot approving a clean pull request](assets/pr-approval.png)

## Features

{%
  include-markdown "../README.md"
  start="<!-- mkdocs:features:start -->"
  end="<!-- mkdocs:features:end -->"
%}

## Where to go next

- **[Getting started](getting-started.md)** — create the GitHub App with the
  [hosted installer](install.html) and run the bot with Docker Compose.
- **[Commands](commands.md)** — drive the bot from a PR: `/review`, `/describe`,
  `/changelog`, `/add-docs`, and more.
- **[Configuration](configuration.md)** — every environment variable, with defaults.
- **[AI providers](providers.md)** — point the bot at the OpenAI-compatible
  endpoint of your choice.
- **[Architecture](ARCHITECTURE.md)** — how a review flows through the system.
- **[How it compares](COMPARISON.md)** — an honest look at where ThrillhouseBot
  sits next to other AI code-review tools.
- **[Contributing](contributing.md)** — development setup and the CI bar.

## Dashboard

The built-in dashboard (Next.js, served by the bot itself) shows summary cards,
a live activity feed that streams the model's output as a review runs, cost
charts by model, token breakdowns, and a paginated session history:

![Dashboard Overview with summary cards, live model-output panel, and recent activity](assets/live-streaming.png)

## Community and license

Questions and setup help belong in
[GitHub Discussions](https://github.com/devops-thiago/ThrillhouseBot/discussions);
bugs and feature requests in
[Issues](https://github.com/devops-thiago/ThrillhouseBot/issues/new/choose).

Licensed under the
[Apache License 2.0](https://github.com/devops-thiago/ThrillhouseBot/blob/main/LICENSE)
(SPDX: `Apache-2.0`).
