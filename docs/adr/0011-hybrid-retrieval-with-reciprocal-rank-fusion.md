# 0011. Hybrid retrieval: pgvector + PostgreSQL full-text search, merged with reciprocal rank fusion

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The chat must find the passages that answer a question. Each kind of search misses things on its own:

- **Vector similarity** (embeddings) handles paraphrases ("time off" vs. "vacation") but blurs exact
  tokens: product codes, names, numbers, rare words.
- **Full-text search** matches exact terms (with stemming) but knows nothing about meaning.

The two produce scores on unrelated scales (cosine similarity vs. `ts_rank_cd`), so a weighted sum
would need per-corpus calibration. Every search must also stay filtered by workspace (ADR 0004).

## Decision

**Run both searches in Postgres and merge them with reciprocal rank fusion (RRF).**

- Vector search: the top 20 chunks by cosine distance, only among chunks embedded with the configured
  model (vectors from different models are not comparable). The query uses the expression and
  predicate of the partial HNSW index from ADR 0008. Because the workspace filter is applied while
  scanning the index, the query enables pgvector's iterative scan (`hnsw.iterative_scan =
  relaxed_order`), so a small workspace in a large table still gets its 20 rows.
- Full-text search: a generated, GIN-indexed `tsvector` column (`english` configuration) on
  `document_chunk`. The worker did not change. The question's words are **OR**-ed and ranked with
  `ts_rank_cd`. `plainto_tsquery` ANDs every word, so a natural-language question would rarely
  match at all.
- Fusion: `score = Σ 1 / (60 + rank)` over both lists. The 5 best chunks go to the model. Only ranks
  are used, so nothing needs calibrating, and a chunk found by both searches beats one found by
  only one.
- Relevance floor: vector hits below cosine similarity 0.6 are dropped (nomic-embed-text scores
  related passages about 0.7 and unrelated ones about 0.5). Keyword hits always count: they share a
  word with the question. If nothing is left, the chat replies "I couldn't find this in your
  documents." **without calling the model**, which is honest and costs nothing.
- If the embedding provider is down, retrieval falls back to full-text search only instead of failing.

**For chat answers** (added after manual testing, see ADR 0012):

- **Follow-up questions are rewritten** into a standalone search query from the recent conversation
  ("what do you think about this CV?" → "Hatim Kassim CV assessment junior developer"). This is a
  short, non-streamed call at temperature 0, skipped for the first question. Both the question as
  asked and the rewrite are searched, and their four ranked lists are fused, so a poor rewrite cannot
  hide what the original question would have found. The answer prompt always uses the original
  question. The rewrite is stored with the answer (`chat_message.search_query`) and logged. If the
  call fails, only the original question is searched.
- **Focus on one document:** a question is about one document when its words match exactly one
  document's file name ("this CV" → `Hatim_Kassim_CV.pdf`, English stemming, extension ignored).
  For an evaluative question (assess, summarize, opinion… in English or French), it is also about
  one document when at least 60% of the fused sources come from that document. Up to 4 more of that
  document's chunks are then added, most similar first (`RETRIEVAL_FOCUS_EXTRA_CHUNKS`, 0 disables
  it). Plain factual questions are not widened by majority: in testing, extra passages only produced
  padded answers. Matching by file name is deterministic, so it works even when a small model's
  rewrite drops the reference.
- **Context budget:** source text is capped at 8,000 characters (about 2,000 tokens,
  `RETRIEVAL_MAX_CONTEXT_CHARACTERS`), so rules, sources, shortened history and the answer fit
  `OLLAMA_CHAT_NUM_CTX=4096`. Ollama silently drops the start of a prompt that is too long, and the
  start is where the rules are.

`GET /api/workspaces/{id}/search?q=` exposes the same retrieval without generation, with each hit's
vector and keyword rank, for debugging and for the Phase 8 evaluation.

Rejected: weighted score sums (need calibration); a separate search engine such as OpenSearch (a
second store to keep consistent and tenant-filter, for no gain at this scale); a cross-encoder
re-ranker (another model to host; can be added after RRF later if evaluation shows a need).

## Consequences

- One SQL round trip per half, inside the same database and tenant filter as everything else.
- Stemming is English. Documents in other languages are still found by vector search, but keyword
  matching for them is weaker. A per-workspace language would need a `regconfig` column.
- The similarity floor depends on the embedding model and is configurable
  (`RETRIEVAL_MIN_SIMILARITY`). Phase 8's evaluation set is where it gets tuned properly.
- A follow-up costs one extra small model call (its tokens are added to the answer's usage).
  A rewrite can still be wrong. Searching the original question too limits the damage, and
  the stored `search_query` makes it visible when debugging.
