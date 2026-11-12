package dev.docquery.worker.ingestion;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import dev.docquery.worker.chunking.Chunk;
import dev.docquery.worker.embedding.EmbeddedChunks;
import dev.docquery.worker.ingestion.IngestionMessages.DocumentUploaded;

/**
 * The worker's database access. It reads {@code document} rows (owned by api-service) and writes
 * chunks, processed-event records and embedding usage. Every statement is scoped by workspace id.
 */
@Repository
class IngestionRepository {

	/** Outcome of an event that was already handled. */
	record ProcessedEvent(UUID eventId, String outcome, String failureReason, Integer pageCount, Integer chunkCount) {
	}

	private static final String INSERT_CHUNK = """
			INSERT INTO document_chunk (workspace_id, document_id, chunk_index, page_number, start_offset, end_offset,
			                            content, embedding, embedding_model, embedding_dimensions)
			VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, ?)
			""";

	private static final int[] INSERT_CHUNK_TYPES = { Types.OTHER, Types.OTHER, Types.INTEGER, Types.INTEGER,
			Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.INTEGER };

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactions;

	IngestionRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.transactions = transactions;
	}

	Optional<ProcessedEvent> findProcessed(UUID eventId) {
		return this.jdbc.sql("""
				SELECT event_id, outcome, failure_reason, page_count, chunk_count
				  FROM ingestion_processed_event WHERE event_id = :eventId
				""")
			.param("eventId", eventId)
			.query((rs, i) -> new ProcessedEvent(rs.getObject("event_id", UUID.class), rs.getString("outcome"),
					rs.getString("failure_reason"), rs.getObject("page_count", Integer.class),
					rs.getObject("chunk_count", Integer.class)))
			.optional();
	}

	boolean documentExists(UUID workspaceId, UUID documentId) {
		return this.jdbc.sql("SELECT count(*) FROM document WHERE workspace_id = :workspaceId AND id = :id")
			.param("workspaceId", workspaceId)
			.param("id", documentId)
			.query(Integer.class)
			.single() > 0;
	}

	/**
	 * Atomically replaces the document's chunks with the new ones, records the event as processed and
	 * stores the embedding usage. Replacing (not appending) makes re-indexing and redelivered events
	 * safe: the end state is the same however often this runs.
	 *
	 * @return {@code false} if nothing was written because the document no longer exists or this
	 * event was already processed (e.g. by a concurrent redelivery)
	 */
	boolean saveIndex(DocumentUploaded event, List<Chunk> chunks, EmbeddedChunks embedded, Integer pageCount) {
		Boolean saved = this.transactions.execute(status -> {
			// FOR SHARE blocks a concurrent delete until commit, so chunks never outlive their document.
			boolean exists = this.jdbc
				.sql("SELECT id FROM document WHERE workspace_id = :workspaceId AND id = :id FOR SHARE")
				.param("workspaceId", event.workspaceId())
				.param("id", event.documentId())
				.query(UUID.class)
				.optional()
				.isPresent();
			if (!exists || !markProcessed(event, "READY", null, pageCount, chunks.size())) {
				return false;
			}
			this.jdbc.sql("DELETE FROM document_chunk WHERE workspace_id = :workspaceId AND document_id = :documentId")
				.param("workspaceId", event.workspaceId())
				.param("documentId", event.documentId())
				.update();
			List<Object[]> rows = new ArrayList<>(chunks.size());
			for (int i = 0; i < chunks.size(); i++) {
				Chunk chunk = chunks.get(i);
				rows.add(new Object[] { event.workspaceId(), event.documentId(), chunk.index(), chunk.pageNumber(),
						chunk.startOffset(), chunk.endOffset(), chunk.content(), vectorLiteral(embedded.vectors().get(i)),
						embedded.model(), embedded.dimensions() });
			}
			this.jdbcTemplate.batchUpdate(INSERT_CHUNK, rows, INSERT_CHUNK_TYPES);
			this.jdbc.sql("""
					INSERT INTO embedding_usage (workspace_id, user_id, document_id, model, input_tokens, estimated, request_count)
					SELECT :workspaceId, u.id, :documentId, :model, :tokens, :estimated, :requests
					  FROM (SELECT 1) AS one LEFT JOIN app_user u ON u.id = :userId
					""")
				.param("workspaceId", event.workspaceId())
				.param("documentId", event.documentId())
				.param("model", embedded.model())
				.param("tokens", embedded.inputTokens())
				.param("estimated", embedded.tokensEstimated())
				.param("requests", embedded.requestCount())
				.param("userId", event.uploadedBy(), Types.OTHER)
				.update();
			return true;
		});
		return Boolean.TRUE.equals(saved);
	}

	/** Records a permanent failure, so a redelivered event only re-announces it. */
	void recordFailure(DocumentUploaded event, String reason) {
		markProcessed(event, "FAILED", reason, null, null);
	}

	private boolean markProcessed(DocumentUploaded event, String outcome, String reason, Integer pageCount,
			Integer chunkCount) {
		return this.jdbc.sql("""
				INSERT INTO ingestion_processed_event (event_id, document_id, outcome, failure_reason, page_count, chunk_count)
				VALUES (:eventId, :documentId, :outcome, :reason, :pageCount, :chunkCount)
				ON CONFLICT (event_id) DO NOTHING
				""")
			.param("eventId", event.eventId())
			.param("documentId", event.documentId())
			.param("outcome", outcome)
			.param("reason", reason, Types.VARCHAR)
			.param("pageCount", pageCount, Types.INTEGER)
			.param("chunkCount", chunkCount, Types.INTEGER)
			.update() == 1;
	}

	/** pgvector's text form, e.g. {@code [0.1,-0.2,0.3]}. */
	static String vectorLiteral(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		return sb.append(']').toString();
	}

}
