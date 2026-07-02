<!-- Rendered at https://devops-thiago.github.io/ThrillhouseBot/providers/.
     Content is included from the README between the mkdocs:providers markers,
     so the README stays the single source of truth. -->

# AI providers

{%
  include-markdown "../README.md"
  start="<!-- mkdocs:providers:start -->"
  end="<!-- mkdocs:providers:end -->"
%}

There is no provider-specific code in the bot — a new provider is just
configuration. See [Adding an AI provider](ARCHITECTURE.md#adding-an-ai-provider)
in the architecture notes, and add a `thrillhousebot.ai.pricing.<model>.*` pair
(see [Configuration](configuration.md)) if you want cost tracking for the model.
