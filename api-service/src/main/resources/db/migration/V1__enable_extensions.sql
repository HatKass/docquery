-- Baseline migration: enable the extensions the platform depends on.
-- pgvector stores and searches embedding vectors (Phase 4/5).
CREATE EXTENSION IF NOT EXISTS vector;
