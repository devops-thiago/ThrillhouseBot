---
slug: 0.7.1/providers
title: AI providers
description: Point the bot at any OpenAI-compatible chat endpoint.
---



ThrillhouseBot works with any endpoint that implements the OpenAI
chat-completions API. Set `AI_BASE_URL` and `AI_MODEL` for your provider:

| Provider | `AI_BASE_URL` | Example `AI_MODEL` |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| Alibaba Cloud (Model Studio) | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Ollama (local) | `http://localhost:11434/v1` | `llama3.2` |

Ollama's cloud endpoint (`https://ollama.com/v1`) limits how many requests one
account has in flight and refuses the rest with `timed out waiting for a
concurrent request slot`. Reviews of different pull requests run in parallel and
a large pull request sends its batches at once, so set `AI_MAX_CONCURRENT_CALLS`
to the number of concurrent requests your plan allows. Calls past it wait for a
slot instead of being refused, and a refusal that still gets through is retried
after 30 seconds.

DeepSeek is the default only because it is inexpensive. Nothing in the bot is
tied to it.



There is no provider-specific code in the bot — a new provider is just
configuration. See
[Adding an AI provider](/ThrillhouseBot/architecture/#adding-an-ai-provider)
in the architecture notes, and add a `thrillhousebot.ai.pricing.<model>.*` pair
(see [Configuration](/ThrillhouseBot/configuration/)) if you want cost tracking
for the model.
