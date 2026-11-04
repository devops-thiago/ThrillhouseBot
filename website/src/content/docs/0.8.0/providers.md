---
slug: 0.8.0/providers
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
| Hugging Face Inference Providers | `https://router.huggingface.co/v1` | `openai/gpt-oss-120b` |
| NVIDIA (build.nvidia.com) | `https://integrate.api.nvidia.com/v1` | `meta/llama-3.3-70b-instruct` |
| Ollama (local) | `http://localhost:11434/v1` | `llama3.2` |

For Hugging Face, `AI_API_KEY` is an access token with the "Make calls to
Inference Providers" permission. A free Hugging Face account includes no
monthly credits, so requests need purchased credits or a PRO, Team or
Enterprise plan; see the
[Inference Providers pricing](https://huggingface.co/docs/inference-providers/pricing).
NVIDIA has a free tier, useful for trying the bot. `AI_API_KEY` is a key from
build.nvidia.com, and the free tier is limited to about 40 requests per minute,
so set `AI_MAX_CONCURRENT_CALLS` low (for example `2`). Check each provider's
current terms; both change them.

Ollama's cloud endpoint (`https://ollama.com/v1`) limits how many requests one
account has in flight and refuses the rest with `timed out waiting for a
concurrent request slot`. Reviews of different pull requests run in parallel and
a large pull request sends its batches at once, so set `AI_MAX_CONCURRENT_CALLS`
to the number of concurrent requests your plan allows. Calls past it wait for a
slot instead of being refused, and a refusal that still gets through is retried
after 30 seconds.

DeepSeek is the default provider. Set `AI_BASE_URL`, `AI_MODEL` and `AI_API_KEY`
to use another provider.



Configure a provider through its OpenAI-compatible endpoint and model name. See
[Adding an AI provider](/ThrillhouseBot/architecture/#adding-an-ai-provider)
in the architecture notes, and add a `thrillhousebot.ai.pricing.<model>.*` pair
(see [Configuration](/ThrillhouseBot/configuration/)) if you want cost tracking
for the model.
