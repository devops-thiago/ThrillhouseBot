---
title: AI providers
description: Point the bot at any OpenAI-compatible chat endpoint.
---

<!-- Content is included from the README between the docs:providers markers,
     so the README stays the single source of truth. -->

<!-- include: ../../../../README.md#providers -->

There is no provider-specific code in the bot — a new provider is just
configuration. See
[Adding an AI provider](/ThrillhouseBot/architecture/#adding-an-ai-provider)
in the architecture notes, and add a `thrillhousebot.ai.pricing.<model>.*` pair
(see [Configuration](/ThrillhouseBot/configuration/)) if you want cost tracking
for the model.
