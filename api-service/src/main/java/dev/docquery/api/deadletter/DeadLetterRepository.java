package dev.docquery.api.deadletter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.docquery.api.deadletter.DeadLetterDtos.DeadLetterResponse;
import dev.docquery.api.deadletter.DeadLetterDtos.DeadLetterStatus;

@Repository
class DeadLetterRepository {

	record NewDeadLetter(String dltTopic, int dltPartition, long dltOffset, String originalTopic,
			Integer originalPartition, Long originalOffset, String key, String payload, UUID eventId, UUID documentId,
			UUID workspaceId, String exceptionClass, String exceptionMessage, Instant failedAt) {
	}

	private static final String SELECT = """
			SELECT e.*, d.filename, d.status AS document_status, w.name AS workspace_name,
			       r.display_name AS resolved_by_name
			  FROM dead_letter_event e
			  LEFT JOIN document d ON d.id = e.document_id
			  LEFT JOIN workspace w ON w.id = e.workspace_id
			  LEFT JOIN app_user r ON r.id = e.resolved_by
			""";

	private final JdbcClient jdbc;

	DeadLetterRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return {@code false} if this Kafka record was stored before (redelivery) */
	boolean insert(NewDeadLetter e) {
		return this.jdbc.sql("""
				INSERT INTO dead_letter_event (dlt_topic, dlt_partition, dlt_offset, original_topic, original_partition,
				                               original_offset, message_key, payload, event_id, document_id, workspace_id,
				                               exception_class, exception_message, failed_at)
				VALUES (:dltTopic, :dltPartition, :dltOffset, :originalTopic, :originalPartition, :originalOffset, :key,
				        :payload, :eventId, :documentId, :workspaceId, :exceptionClass, :exceptionMessage, :failedAt)
				ON CONFLICT (dlt_topic, dlt_partition, dlt_offset) DO NOTHING
				""")
			.param("dltTopic", e.dltTopic())
			.param("dltPartition", e.dltPartition())
			.param("dltOffset", e.dltOffset())
			.param("originalTopic", e.originalTopic(), Types.VARCHAR)
			.param("originalPartition", e.originalPartition(), Types.INTEGER)
			.param("originalOffset", e.originalOffset(), Types.BIGINT)
			.param("key", e.key(), Types.VARCHAR)
			.param("payload", e.payload(), Types.VARCHAR)
			.param("eventId", e.eventId(), Types.OTHER)
			.param("documentId", e.documentId(), Types.OTHER)
			.param("workspaceId", e.workspaceId(), Types.OTHER)
			.param("exceptionClass", e.exceptionClass(), Types.VARCHAR)
			.param("exceptionMessage", e.exceptionMessage(), Types.VARCHAR)
			.param("failedAt", OffsetDateTime.ofInstant(e.failedAt(), ZoneOffset.UTC))
			.update() == 1;
	}

	/** Newest first; {@code status} {@code null} for all. */
	List<DeadLetterResponse> findAll(DeadLetterStatus status, int limit) {
		return this.jdbc.sql(SELECT + """
				 WHERE CAST(:status AS text) IS NULL OR e.status = :status
				 ORDER BY e.failed_at DESC, e.id
				 LIMIT :limit
				""")
			.param("status", (status != null) ? status.name() : null, Types.VARCHAR)
			.param("limit", limit)
			.query(DeadLetterRepository::map)
			.list();
	}

	Optional<DeadLetterResponse> find(UUID id) {
		return this.jdbc.sql(SELECT + " WHERE e.id = :id").param("id", id).query(DeadLetterRepository::map).optional();
	}

	int countPending() {
		return this.jdbc.sql("SELECT count(*) FROM dead_letter_event WHERE status = 'PENDING'")
			.query(Integer.class)
			.single();
	}

	/** @return {@code false} if it was no longer pending (resolved concurrently) */
	boolean resolve(UUID id, DeadLetterStatus status, UUID resolvedBy) {
		return this.jdbc.sql("""
				UPDATE dead_letter_event SET status = :status, resolved_by = :resolvedBy, resolved_at = now()
				 WHERE id = :id AND status = 'PENDING'
				""").param("status", status.name()).param("resolvedBy", resolvedBy).param("id", id).update() == 1;
	}

	private static DeadLetterResponse map(ResultSet rs, int rowNum) throws SQLException {
		DeadLetterStatus status = DeadLetterStatus.valueOf(rs.getString("status"));
		String documentStatus = rs.getString("document_status");
		OffsetDateTime resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class);
		String exceptionClass = rs.getString("exception_class");
		String exceptionMessage = rs.getString("exception_message");
		return new DeadLetterResponse(rs.getObject("id", UUID.class), status, rs.getObject("event_id", UUID.class),
				rs.getObject("document_id", UUID.class), rs.getString("filename"), documentStatus,
				rs.getObject("workspace_id", UUID.class), rs.getString("workspace_name"),
				ErrorSummary.of(exceptionClass, exceptionMessage), exceptionClass, exceptionMessage,
				rs.getString("original_topic"),
				rs.getObject("original_partition", Integer.class), rs.getObject("original_offset", Long.class),
				rs.getObject("failed_at", OffsetDateTime.class).toInstant(),
				(resolvedAt != null) ? resolvedAt.toInstant() : null, rs.getString("resolved_by_name"),
				status == DeadLetterStatus.PENDING && documentStatus != null, rs.getString("payload"));
	}

}
