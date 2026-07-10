---
slug: 0.2.1/providers
title: AI providers
description: Point the bot at any OpenAI-compatible chat endpoint.
---

ThrillhouseBot talks to any endpoint that implements the OpenAI chat-completions
API. Point `AI_BASE_URL` and `AI_MODEL` at your provider of choice:

| Provider | `AI_BASE_URL` | Example `AI_MODEL` |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| Alibaba Cloud (Model Studio) | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Ollama (local) | `http://localhost:11434/v1` | `llama3.2` |

The default is DeepSeek, used only because it is inexpensive; nothing in the bot
is tied to it.

There is no provider-specific code in the bot — a new provider is configuration.
See [Architecture](/ThrillhouseBot/0.2.1/architecture) and
[Configuration](/ThrillhouseBot/0.2.1/configuration) for details available in this release.
