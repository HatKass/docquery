# 0015. Testing and RAG evaluation: deterministic CI evaluation, live model comparison, real-stack e2e

- **Status:** Accepted
- **Date:** 2026-11-13

## Context

Unit and integration tests prove that DocQuery's code does what it says: retrieval is scoped by
workspace, prompts keep document text out of the rules, citations resolve. They cannot say whether
the *answers* are good, and answer quality depends mostly on things outside the code: which passages
retrieval finds, and which chat model writes the answer. Manual testing in Phase 5 found model
failures no unit test caught: facts attributed to the wrong entity (handbook policy presented as a
fact about the person in a CV), padded answers, false "not found" replies when the right passage was
retrieved, and weak handling of evaluative questions.

We need (1) a regression guard for retrieval and the answer pipeline that runs on every build, free
and deterministic; (2) a way to compare chat models on the same questions, to choose the production
model with numbers; (3) end-to-end confidence that the services, Keycloak, Kafka and the browser work
together; (4) a coverage floor.

## Decision

1. **One evaluation set** (`evaluation/eval-set.json`): 32 questions over original sample documents,
   each with the pages that answer it and string checks on the answer (required facts with
   alternatives, forbidden text, a word limit, expected refusal). Categories mirror the observed
   failure modes. No LLM judge: checks are deterministic, cheap and reviewable.
2. **Inputs produced by the real code.** The sample PDFs are generated from text sources, and the chunk
   fixture comes from the worker's own extractor and chunker with production settings
   (`EvaluationFixtureTests` fails when they are stale). The api-service evaluation therefore needs no
   worker yet measures exactly what an upload would produce.
3. **Two runs of the same harness** (`RagEvaluator`), which retrieves with the real `ChunkRetriever` and
   asks through the real chat endpoint (SSE, grounding prompt, citation extraction, usage ledger):
   - **CI** (`RagEvaluationTests`, every build): stub embeddings and an extractive stub chat model.
     It asserts a baseline (hit@5, MRR, context recall, answer pass rate) and publishes a report.
   - **Live** (`LiveRagEvaluationTests`, Maven profile `rag-evaluation`): the models configured like the
     application. Writes per-model reports and rebuilds a comparison table in `evaluation/results/`.
     Only the chat model varies between rows.
4. **Playwright e2e against the running stack** (`frontend/e2e`), desktop and phone projects, with axe
   accessibility scans (serious/critical WCAG 2.1 A/AA violations fail). Run locally; CI type-checks it.
5. **JaCoCo** in both services with a minimum line/branch coverage enforced by `mvn verify`, set just
   below the measured value so coverage cannot silently drop.

## Consequences

- Model choice is backed by a table, not impressions. The live run is manual (it needs Ollama or a
  paid API key) and must be repeated when prompts, retrieval or models change.
- The CI evaluation catches retrieval and pipeline regressions, not model behaviour; its extractive stub
  scores low on purpose-built model tests (refusals, attribution), which is expected and documented.
- String checks can misjudge a correct answer phrased unexpectedly (or accept a wrong one that happens
  to contain the expected number). Alternatives and whole-number matching reduce this; reports show
  every answer so a reviewer can verify.
- The e2e suite is not a merge gate yet: running it in CI needs a deterministic stub chat provider in
  api-service (or a paid model), a follow-up.
- The set is small (32 cases, ~16 chunks): retrieval numbers are optimistic compared with a large
  workspace, and differences of one case are 3 percentage points.
