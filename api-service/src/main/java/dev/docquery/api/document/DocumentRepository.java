package dev.docquery.api.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.docquery.api.document.DocumentDtos.DocumentResponse;

/**
 * Document metadata. Every query that a user can trigger takes the workspace id and filters by it, so
 * a document id from another workspace simply matches nothing.
 */
@Repository
class DocumentRepository {

	/** A new row, before it is read back with the uploader's name. */
	record NewDocument(UUID id, UUID workspaceId, String filename, String contentType, long sizeBytes, String sha256,
			String storageKey, UUID uploadedBy) {
	}

	/** What the status model needs to validate a transition. */
	record StatusRow(UUID workspaceId, DocumentStatus status) {
	}

	/** What the ingestion worker needs to find and parse the stored file. */
	record IngestionSource(UUID id, UUID workspaceId, String storageKey, String contentType, String filename,
			UUID uploadedBy) {
	}

	private static final String SELECT_INGESTION_SOURCE = "SELECT d.id, d.workspace_id, d.storage_key, d.content_type, "
			+ "d.filename, d.uploaded_by FROM document d ";

	private static final String SELECT = """
			SELECT d.id, d.workspace_id, d.filename, d.content_type, d.size_bytes, d.status, d.failure_reason,
			       d.page_count, d.chunk_count, d.uploaded_by, u.display_name AS uploaded_by_name,
			       d.uploaded_at, d.status_changed_at
			  FROM document d
			  LEFT JOIN app_user u ON u.id = d.uploaded_by
			""";

	private final JdbcClient jdbc;

	DocumentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(NewDocument doc) {
		this.jdbc.sql("""
				INSERT INTO document (id, workspace_id, filename, content_type, size_bytes, sha256, storage_key, uploaded_by)
				VALUES (:id, :workspaceId, :filename, :contentType, :sizeBytes, :sha256, :storageKey, :uploadedBy)
				""")
			.param("id", doc.id())
			.param("workspaceId", doc.workspaceId())
			.param("filename", doc.filename())
			.param("contentType", doc.contentType())
			.param("sizeBytes", doc.sizeBytes())
			.param("sha256", doc.sha256())
			.param("storageKey", doc.storageKey())
			.param("uploadedBy", doc.uploadedBy())
			.update();
	}

	List<DocumentResponse> findAll(UUID workspaceId) {
		return this.jdbc.sql(SELECT + " WHERE d.workspace_id = :workspaceId ORDER BY d.uploaded_at DESC, d.id")
			.param("workspaceId", workspaceId)
			.query(DocumentRepository::map)
			.list();
	}

	Optional<DocumentResponse> find(UUID workspaceId, UUID documentId) {
		return this.jdbc.sql(SELECT + " WHERE d.workspace_id = :workspaceId AND d.id = :id")
			.param("workspaceId", workspaceId)
			.param("id", documentId)
			.query(DocumentRepository::map)
			.optional();
	}

	Optional<String> findStorageKey(UUID workspaceId, UUID documentId) {
		return this.jdbc.sql("SELECT storage_key FROM document WHERE workspace_id = :workspaceId AND id = :id")
			.param("workspaceId", workspaceId)
			.param("id", documentId)
			.query(String.class)
			.optional();
	}

	Optional<UUID> findIdBySha256(UUID workspaceId, String sha256) {
		return this.jdbc.sql("SELECT id FROM document WHERE workspace_id = :workspaceId AND sha256 = :sha256")
			.param("workspaceId", workspaceId)
			.param("sha256", sha256)
			.query(UUID.class)
			.optional();
	}

	Optional<IngestionSource> findIngestionSource(UUID workspaceId, UUID documentId) {
		return this.jdbc.sql(SELECT_INGESTION_SOURCE + "WHERE d.workspace_id = :workspaceId AND d.id = :id")
			.param("workspaceId", workspaceId)
			.param("id", documentId)
			.query(DocumentRepository::mapIngestionSource)
			.optional();
	}

	/** Across workspaces: platform-admin operations only, never reachable with a member's request. */
	Optional<IngestionSource> findIngestionSourceById(UUID documentId) {
		return this.jdbc.sql(SELECT_INGESTION_SOURCE + "WHERE d.id = :id")
			.param("id", documentId)
			.query(DocumentRepository::mapIngestionSource)
			.optional();
	}

	/**
	 * Finished documents (READY or FAILED) across all workspaces that need re-indexing for
	 * {@code currentModel}: any chunk embedded with another model, or, with {@code all}, every one.
	 * Maintenance only, never reachable from a user request.
	 */
	List<IngestionSource> findReindexCandidates(String currentModel, boolean all) {
		return this.jdbc.sql(SELECT_INGESTION_SOURCE + """
				 WHERE d.status IN ('READY', 'FAILED')
				   AND (:all OR EXISTS (SELECT 1 FROM document_chunk c
				                         WHERE c.workspace_id = d.workspace_id AND c.document_id = d.id
				                           AND c.embedding_model <> :model))
				 ORDER BY d.uploaded_at, d.id
				""")
			.param("all", all)
			.param("model", currentModel)
			.query(DocumentRepository::mapIngestionSource)
			.list();
	}

	private static IngestionSource mapIngestionSource(ResultSet rs, int rowNum) throws SQLException {
		return new IngestionSource(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
				rs.getString("storage_key"), rs.getString("content_type"), rs.getString("filename"),
				rs.getObject("uploaded_by", UUID.class));
	}

	/**
	 * Restarts the queue timer of a document that has been waiting in UPLOADED for longer than
	 * {@code olderThanSeconds}. A single conditional update, so two concurrent retries cannot both win.
	 *
	 * @return whether the document was stuck (and is now touched)
	 */
	boolean touchIfQueuedLongerThan(UUID workspaceId, UUID documentId, long olderThanSeconds) {
		return this.jdbc.sql("""
				UPDATE document
				   SET status_changed_at = now()
				 WHERE workspace_id = :workspaceId AND id = :id AND status = 'UPLOADED'
				   AND status_changed_at < now() - make_interval(secs => :seconds)
				""")
			.param("workspaceId", workspaceId)
			.param("id", documentId)
			.param("seconds", olderThanSeconds)
			.update() == 1;
	}

	/** @return the deleted row's storage key, if it existed in this workspace */
	Optional<String> delete(UUID workspaceId, UUID documentId) {
		return this.jdbc.sql("DELETE FROM document WHERE workspace_id = :workspaceId AND id = :id RETURNING storage_key")
			.param("workspaceId", workspaceId)
			.param("id", documentId)
			.query(String.class)
			.optional();
	}

	/** Row-locks the document for the rest of the transaction (serializes concurrent status changes). */
	Optional<StatusRow> lockStatus(UUID documentId) {
		return this.jdbc.sql("SELECT workspace_id, status FROM document WHERE id = :id FOR UPDATE")
			.param("id", documentId)
			.query((rs, i) -> new StatusRow(rs.getObject("workspace_id", UUID.class),
					DocumentStatus.valueOf(rs.getString("status"))))
			.optional();
	}

	/**
	 * Sets the new status. Counts are only overwritten when given ({@code COALESCE}), so a later step
	 * does not erase what an earlier step reported; going back to UPLOADED (re-index) clears them.
	 */
	void updateStatus(UUID documentId, DocumentStatus status, String failureReason, Integer pageCount,
			Integer chunkCount) {
		boolean reset = status == DocumentStatus.UPLOADED;
		this.jdbc.sql("""
				UPDATE document
				   SET status = :status,
				       failure_reason = :failureReason,
				       page_count = CASE WHEN :reset THEN NULL ELSE COALESCE(:pageCount, page_count) END,
				       chunk_count = CASE WHEN :reset THEN NULL ELSE COALESCE(:chunkCount, chunk_count) END,
				       status_changed_at = now()
				 WHERE id = :id
				""")
			.param("status", status.name())
			.param("failureReason", failureReason)
			.param("reset", reset)
			.param("pageCount", pageCount, java.sql.Types.INTEGER)
			.param("chunkCount", chunkCount, java.sql.Types.INTEGER)
			.param("id", documentId)
			.update();
	}

	private static DocumentResponse map(ResultSet rs, int rowNum) throws SQLException {
		return new DocumentResponse(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
				rs.getString("filename"), rs.getString("content_type"), rs.getLong("size_bytes"),
				DocumentStatus.valueOf(rs.getString("status")), rs.getString("failure_reason"),
				rs.getObject("page_count", Integer.class), rs.getObject("chunk_count", Integer.class),
				rs.getObject("uploaded_by", UUID.class), rs.getString("uploaded_by_name"),
				rs.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
				rs.getObject("status_changed_at", OffsetDateTime.class).toInstant());
	}

}
