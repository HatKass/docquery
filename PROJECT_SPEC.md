# PROJECT_SPEC.md — DocQuery: AI Document Intelligence Platform

You are a senior Java / Angular / platform engineer helping me build a portfolio project that should impress recruiters (LinkedIn) and technical clients (Upwork). It must be production-style, well-tested, well-documented, and honest about its limitations.

## 1. The product

**DocQuery** lets a team upload documents (PDF, DOCX, TXT, Markdown) into a shared workspace and ask questions about them in plain language. Answers stream in word by word and **cite the exact document and page** they came from. If the answer is not in the documents, the assistant says so instead of guessing.

Elevator pitch for the README: *"Retrieval-augmented generation (RAG) platform built with Spring AI, Kafka, pgvector and Angular — multi-tenant, observable, and deployable to Kubernetes."*

## 2. Architecture (keep it to two backend services — event-driven, not microservice sprawl)

- **api-service** (Spring Boot): auth, workspaces, document upload/metadata, search, RAG chat (streaming), usage/quotas, admin.
- **ingestion-worker** (Spring Boot): consumes Kafka events and runs the pipeline: extract text → chunk → embed → index. Publishes status events back.
- **frontend** (Angular): workspace UI, document library with live processing status, chat with citations.
- **Infrastructure**: PostgreSQL 16 + pgvector, Kafka (KRaft mode, no ZooKeeper), Keycloak (OIDC), MinIO (S3-compatible object storage), Ollama (local LLM + embeddings for dev).

Document pipeline statuses: `UPLOADED → EXTRACTING → CHUNKING → EMBEDDING → READY` or `FAILED` (with reason). The UI shows these live.

## 3. Tech stack (use these choices)

- Java 21, Maven wrapper.
- Spring Boot: use the **latest stable version that is officially compatible with the latest stable Spring AI release**. Verify compatibility before choosing and tell me which versions you picked and why.
- Spring AI with provider abstraction:
  - **Local/dev:** Ollama (a small chat model + an embedding model such as nomic-embed-text).
  - **Production:** configurable cloud provider (OpenAI or Anthropic) via environment variables. No provider is hard-coded.
  - Store the embedding model name and vector dimension with each chunk; provide a re-index command for when the embedding model changes.
- PostgreSQL 16 with pgvector; Flyway for all schema changes.
- Spring Kafka; Apache Tika for text extraction (keep page numbers); MinIO via the AWS S3 SDK.
- Keycloak for OIDC login; Spring Security resource server (JWT validation).
- Angular (latest stable): standalone components, signals, Tailwind CSS, SSE/streaming for chat and status updates.
- Observability: Micrometer, OpenTelemetry (traces propagated **across Kafka**), Prometheus, Grafana (dashboards committed as JSON), structured JSON logs.
- Tests: JUnit 5, Mockito, Testcontainers (pgvector Postgres, Kafka), Playwright e2e, JaCoCo.
- Infra: Docker Compose for local; Helm chart for Kubernetes (tested on a local kind cluster); GitHub Actions CI with **pinned runner versions** (no `ubuntu-latest`).

## 4. Features

### 4.1 Auth & multi-tenancy
- Login via Keycloak (OIDC, authorization code + PKCE in Angular).
- Workspaces: a user can belong to several; roles per workspace: OWNER, MEMBER, VIEWER.
- **Strict tenant isolation**: every query — including vector search — is filtered by workspace. Add integration tests proving a user can never see another workspace's documents, chunks, chats or citations.
- Seed: a Keycloak realm export with demo users (e.g. `owner / demo123`, `viewer / demo123`) and one demo workspace.

### 4.2 Documents
- Upload PDF, DOCX, TXT, MD; validate type by content (not only extension), max size configurable; store files in MinIO under random keys.
- Document list with status, page count, chunk count, uploaded by/at; delete (removes file, chunks and vectors).
- Live status via SSE.

### 4.3 Ingestion pipeline (ingestion-worker)
- Kafka topics for `document.uploaded` and `document.status`; idempotent consumers (safe to process the same event twice).
- Retries with backoff and a **dead-letter topic**; failed documents show a readable reason.
- Chunking with overlap; keep document id, page number and position for citations.
- Batch embedding calls; record token usage.

### 4.4 Search & RAG chat
- **Hybrid retrieval**: pgvector similarity + PostgreSQL full-text search, merged (e.g. reciprocal rank fusion). Explain the choice in an ADR.
- Answers stream word by word (SSE) and include citations: document name, page, and the quoted snippet. Clicking a citation opens the document at that page.
- Grounding rules: answer only from retrieved context; if not enough evidence, say "I couldn't find this in your documents."
- **Prompt-injection hygiene**: document text is treated as data, never as instructions; add tests with a malicious document.
- Conversation history per user per workspace.

### 4.5 Usage, quotas & cost safety (important for a public demo)
- Track tokens and estimated cost per workspace and per user.
- Configurable daily question quota and upload limits; friendly error when exceeded.
- Admin page: usage charts, pipeline failures, dead-letter events with a "retry" action.

### 4.6 RAG evaluation
- A small evaluation set (questions + expected source pages) over the sample documents.
- A command that measures retrieval quality (e.g. hit rate / recall@k) and writes a report.
- CI runs the evaluation with a deterministic stub chat model so it is fast and free; real-model evaluation is documented to run locally.

## 5. Sample data
- `/sample-data` with a few documents I can legally publish: write original sample documents (e.g. a fictional company handbook, a fictional product manual, a fictional policy) — no copyrighted material.

## 6. Quality bar
- Package by feature; DTOs, no entities in the API; consistent ProblemDetail errors.
- Configuration via environment variables with safe local defaults; **no secrets committed**; `.env.example` only.
- Architecture Decision Records in `docs/adr/` (one per key decision: two services, Kafka, hybrid search, pgvector vs dedicated vector DB, Keycloak, provider abstraction).
- README: summary, demo video/GIF placeholder, architecture diagram (Mermaid), features, "Run locally in one command", demo credentials, design decisions, limitations, costs, and the note: "Built with AI-assisted development (Claude Code); all code reviewed and tested by me."

## 7. Phases (build one phase at a time, each on its own branch)

1. **Foundation**: monorepo (`/api-service`, `/ingestion-worker`, `/frontend`, `/infra`, `/docs`), Docker Compose with Postgres+pgvector, Kafka (KRaft), MinIO, Keycloak (realm import), Ollama (optional profile); health checks; baseline CI.
2. **Auth & workspaces**: Keycloak OIDC end to end, workspaces, roles, tenant isolation tests; Angular login and workspace switcher.
3. **Documents**: upload, validation, MinIO storage, metadata, status model, SSE; Angular document library.
4. **Ingestion pipeline**: Kafka events, worker, Tika extraction, chunking, embeddings, pgvector indexing, idempotency, retries, dead-letter topic.
5. **RAG chat**: hybrid retrieval, grounded answers, streaming, citations, prompt-injection tests; Angular chat UI with citation viewer.
6. **Usage & admin**: token/cost tracking, quotas, admin dashboard, dead-letter retry.
7. **Observability**: metrics, traces across Kafka, Grafana dashboards, JSON logs.
8. **Testing & evaluation**: fill test gaps, RAG evaluation, Playwright e2e, JaCoCo, full CI.
9. **Kubernetes & deployment**: Helm chart tested on kind; production deployment guide for a small VPS with Docker Compose + Caddy (HTTPS); cost notes.
10. **Docs**: README, ADRs, sample data check, demo script for a 2-minute video.

## 8. How to work (applies to every phase)

- Build **only the phase I ask for**, then STOP. Briefly explain key design decisions, list files created, and tell me exactly how to run and test what you built.
- If something is ambiguous, pick the most professional option and tell me what you chose.
- Run the relevant tests before saying a phase is done.

### STRICT RULES FOR GITHUB
- Do NOT run any `gh` command unless I explicitly allow a read-only command for a specific task.
- Do NOT run `git push`, `git pull`, `git fetch`, `git merge`, `git rebase`, `git reset`, or change any remote or branch.
- Commit **locally only**, and only when I ask. I review and push myself.
- If you think any GitHub action is needed, stop and ask me first.

## 9. Carry-over notes (decided during earlier phases)

Phase 4 (Ingestion):
- Use my native Windows Ollama (http://localhost:11434 from source, http://host.docker.internal:11434 from containers). Do not rely on the compose "ai" profile for local dev; keep it only as an optional fallback.

Phase 6 (Usage & admin):
- Limit how many workspaces a user can create (configurable, e.g. max 3 per user), with a friendly error when exceeded, and an admin override.

Phase 9 (Kubernetes & deployment):
- The production Keycloak realm must NOT include the dev-only docquery-dev-cli client. Use a separate production realm export.
- The public demo needs demo users, so do not simply set DEMO_SEED_ENABLED=false. Design a safe demo setup: demo users with limited rights, the daily question/upload quotas from 4.5, and a documented way to reset demo data.
- Document the local Postgres port conflict (POSTGRES_PORT in .env) in the deployment guide too.
- Live updates (SSE) only reach browsers connected to the replica that consumed the status event (ADR 0007). With more than one api-service replica, fan out status events to all replicas (e.g. per-replica Kafka consumer group or Postgres LISTEN/NOTIFY) and test it.

General:
- Every new workspace-scoped endpoint must be added to TenantIsolationTests (the test already enforces this).
- Never use "latest" tags for runners or images; pin versions.
