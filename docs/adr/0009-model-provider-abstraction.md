# 0009. Model providers behind Spring AI: Ollama locally, a cloud provider in production

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Local development must be free and offline-capable; production needs a reliable hosted model. The
code must not hard-code a provider, and switching must not require code changes.

## Decision

Application code depends only on Spring AI's interfaces (`EmbeddingModel` now; `ChatModel` from
Phase 5). The provider is chosen by configuration:

- `EMBEDDING_PROVIDER=ollama` (default): a **native** Ollama on the developer's machine
  (`OLLAMA_BASE_URL`, default `http://localhost:11434`; containers use
  `http://host.docker.internal:11434`). Native rather than containerized, so Ollama can use the
  local GPU on Windows/macOS. The compose `ai` profile remains an optional fallback.
- `EMBEDDING_PROVIDER=openai`: OpenAI embeddings (`OPENAI_API_KEY`, `OPENAI_EMBEDDING_MODEL`), with
  `dimensions=768` so the same HNSW index applies. Anthropic offers no embedding API; it will be a
  chat option in Phase 5.

Both starters are on the classpath; `spring.ai.model.*` enables exactly one embedding model and no
chat/image/audio models in the worker. Models are never pulled at startup
(`pull-model-strategy: never`), so nothing large is downloaded unexpectedly. Spring AI's own retries
are short (2 attempts); longer outages are handled by the pipeline's Kafka retries (ADR 0007).

Model-specific details are configuration too: nomic-embed-text expects a `search_document: ` task
prefix (`EMBEDDING_DOCUMENT_PREFIX`, empty for other models). The prefix is sent to the model but
never stored.

## Consequences

- Tests use a deterministic stub `EmbeddingModel`: fast, free and reproducible in CI.
- Each chunk records the model that embedded it; changing the model is a config change plus one
  re-index command.
- Token usage comes from the provider when reported, otherwise it is estimated (≈ 4 characters per
  token) and flagged as estimated in `embedding_usage`.
