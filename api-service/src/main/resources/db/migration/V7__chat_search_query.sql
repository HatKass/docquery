-- Phase 5 follow-up: follow-up questions are rewritten into standalone search queries before
-- retrieval (QueryRewriter). Store what was actually searched with each answer, for debugging
-- retrieval and for the Phase 8 evaluation. NULL for answers stored before this migration.
ALTER TABLE chat_message ADD COLUMN search_query text;
