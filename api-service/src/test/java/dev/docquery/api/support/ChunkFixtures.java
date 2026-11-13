package dev.docquery.api.support;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plays the ingestion worker's part: writes chunks (with stub embeddings) for an uploaded document
 * and marks it READY, exactly as the worker's repository does.
 */
public final class ChunkFixtures {

	private final JdbcClient jdbc;

	public ChunkFixtures(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Replaces the document's chunks with these texts (one chunk per text, on the given page). */
	public void index(UUID workspaceId, UUID documentId, Integer page, String... contents) {
		this.jdbc.sql("DELETE FROM document_chunk WHERE workspace_id = :ws AND document_id = :doc")
			.param("ws", workspaceId)
			.param("doc", documentId)
			.update();
		int offset = 0;
		for (int i = 0; i < contents.length; i++) {
			String content = contents[i];
			this.jdbc.sql("""
					INSERT INTO document_chunk (workspace_id, document_id, chunk_index, page_number, start_offset,
					                            end_offset, content, embedding, embedding_model, embedding_dimensions)
					VALUES (:ws, :doc, :index, :page, :start, :end, :content, CAST(:embedding AS vector), :model, :dims)
					""")
				.param("ws", workspaceId)
				.param("doc", documentId)
				.param("index", i)
				.param("page", page, java.sql.Types.INTEGER)
				.param("start", offset)
				.param("end", offset + content.length())
				.param("content", content)
				.param("embedding", literal(StubEmbeddingModel.vector(content)))
				.param("model", StubEmbeddingModel.MODEL)
				.param("dims", StubEmbeddingModel.DIMENSIONS)
				.update();
			offset += content.length();
		}
		this.jdbc.sql("""
				UPDATE document SET status = 'READY', failure_reason = NULL, chunk_count = :count, status_changed_at = now()
				 WHERE workspace_id = :ws AND id = :doc
				""")
			.param("count", contents.length)
			.param("ws", workspaceId)
			.param("doc", documentId)
			.update();
	}

	private static String literal(float[] vector) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < vector.length; i++) {
			sb.append(i == 0 ? "" : ",").append(vector[i]);
		}
		return sb.append(']').toString();
	}

}
