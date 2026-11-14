# RAG evaluation

How well does DocQuery find the right passages, and how well does a given chat model answer from
them? This directory holds the evaluation set, its inputs and the results per model.

| Path | What it is |
|---|---|
| [`eval-set.json`](eval-set.json) | 32 questions over the sample documents, with the pages that answer them and checks on the answer |
| [`documents/`](documents) | Evaluation-only documents (a supplier note with a deliberate prompt injection) |
| [`fixtures/sample-data-chunks.json`](fixtures/sample-data-chunks.json) | The chunks the ingestion worker's real extractor and chunker make of every document (generated, see below) |
| [`results/`](results) | One JSON + Markdown report per live run, and the [comparison table](results/README.md) |

The documents are the fictional, original ones in [`/sample-data`](../sample-data) (an employee
handbook, a product manual, a data-retention policy and a CV) plus `documents/`.

## What is measured

**Retrieval** (26 answerable, self-contained questions), using the same hybrid search as the chat:

- **hit@1 / hit@5**: the first (top 5) search hit is an expected page;
- **MRR@10**: mean reciprocal rank of the first expected page;
- **context recall**: share of expected pages among the sources the model is actually given.

**Answers** (all 32 questions, asked through the real chat endpoint in a fresh conversation), with
deterministic string checks, not an LLM judge, so runs are reproducible and free:

| Check | Failure it catches |
|---|---|
| `mustInclude` (any-of groups; numbers match as whole numbers) | wrong or missing fact |
| plain decline + no citation on an answerable question | **false "not found"** |
| `expect: "refusal"` | guessing instead of "I couldn't find this in your documents" |
| `mustNotInclude` | **attribution** to the wrong person/company, leaked injected text, assessment sections in a factual answer |
| `maxWords` | **padded** answers |
| citations | no citation, or citing a document that does not hold the answer |

Categories cover the failure modes found in manual testing: `fact`, `keyword` (error codes),
`false-not-found` (paraphrased questions), `padded`, `attribution` (handbook vs CV), `multi-document`,
`evaluative` ("assess this CV"), `follow-up`, `unanswerable`, `injection`.

### Case schema

```json
{
  "id": "samira-notice",
  "category": "attribution",
  "conversation": ["optional earlier questions in the same conversation"],
  "question": "What is Samira Benali's notice period?",
  "sources": [{ "document": "cv-samira-benali.pdf", "pages": [1, 2] }],
  "answer": {
    "expect": "answer | refusal",
    "mustInclude": ["Leeds", ["2 months", "two months"]],
    "mustNotInclude": ["Samira Benali's notice period is"],
    "maxWords": 70
  }
}
```

`sources` is required for answerable cases and omitted for questions the documents do not answer.
`pages` is omitted for unpaginated documents (Markdown, text).

## Running it

### In CI: stub models (every build)

`RagEvaluationTests` (api-service) runs the whole set with offline models: a bag-of-words stub
embedding and an *extractive* stub chat model that answers with the best-matching source sentence.
It is fast, free and deterministic, and fails the build when retrieval or the answer pipeline
regresses below the recorded baseline. It cannot judge language-model behaviour (declining,
attribution, assessments); that is what the live runs are for.

```bash
cd api-service && ./mvnw test -Dtest=RagEvaluationTests
```

Report: `api-service/target/evaluation/ci-stub.md` (a CI artifact).

### Locally: real models

`LiveRagEvaluationTests` uses the models configured like the application (`application.yml`, the
repo's `.env`, environment variables). Postgres, Kafka and storage are Testcontainers (Docker must
run); the local stack's data is not touched. Only the chat model changes between runs; embeddings
stay `nomic-embed-text` via Ollama, so rows are comparable.

```bash
cd api-service
OLLAMA_CHAT_MODEL=llama3.2:3b ./mvnw test -Prag-evaluation
OLLAMA_CHAT_MODEL=qwen2.5:7b ./mvnw test -Prag-evaluation
```

Production cloud model (OpenAI; costs a few cents per run, ~32 questions of ~1,600 prompt tokens):

```bash
CHAT_PROVIDER=openai OPENAI_API_KEY=sk-... OPENAI_CHAT_MODEL=gpt-4.1-mini ./mvnw test -Prag-evaluation
```

(PowerShell: `$env:CHAT_PROVIDER='openai'; $env:OPENAI_API_KEY='sk-...'; ./mvnw test -Prag-evaluation`.)
`EVAL_LABEL=<name>` names the run; it defaults to the chat model. Each run writes
`results/<label>.json` and `.md` and rebuilds [`results/README.md`](results/README.md).
A 3B model takes ~5 minutes on a GTX 1650; qwen2.5:7b, which does not fit a 4 GB GPU entirely,
takes considerably longer.

## Keeping the inputs in sync

The sample PDFs are generated from `sample-data/source/*.txt`, and the chunk fixture from the
worker's real code. `EvaluationFixtureTests` (ingestion-worker) fails when either is stale. After
editing a document, or changing extraction or chunking:

```bash
cd ingestion-worker && ./mvnw test -Dtest=EvaluationFixtureTests -Devaluation.update=true
```

When you add or change cases, re-run the CI evaluation and adjust the baseline in
`RagEvaluationTests.Baseline` if the change is intended.
