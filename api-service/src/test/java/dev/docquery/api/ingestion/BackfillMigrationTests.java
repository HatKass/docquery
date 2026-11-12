package dev.docquery.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import dev.docquery.api.ingestion.IngestionMessages.DocumentUploaded;
import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.TestUser;
import dev.docquery.api.support.TopicRecorder;

/**
 * {@code V5__backfill_ingestion_requests.sql}: documents uploaded before the pipeline existed get
 * the {@code document.uploaded} event they never had; nothing else does.
 * <p>
 * Flyway already applied V5 to the (then empty) test database at startup. The tests re-run the same
 * SQL file against "legacy" rows inserted directly, as Phase 3 left them. The script runs inside a
 * transaction so its outbox rows can be inspected before the relay publishes them.
 */
class BackfillMigrationTests extends ApiIntegrationTest {

	static final String MIGRATION = "db/migration/V5__backfill_ingestion_requests.sql";

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	KafkaContainer kafkaContainer;

	TopicRecorder recorder;

	UUID userId;

	UUID workspaceId;

	@BeforeEach
	void setUp() {
		this.recorder = new TopicRecorder(this.kafkaContainer.getBootstrapServers(), "document.uploaded");
		TestUser owner = TestUser.random("backfill-owner");
		this.userId = this.api.signIn(owner);
		this.workspaceId = this.api.createWorkspace(owner, "Backfill");
	}

	@AfterEach
	void tearDown() {
		this.recorder.close();
	}

	@Test
	void flywayAppliedTheBackfill() {
		assertThat(this.jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '5'")
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void queuesOnlyDocumentsThatNeverGotAnEvent() {
		UUID legacy = insertDocument("legacy-handbook.pdf", "application/pdf", "UPLOADED");
		UUID ready = insertDocument("ready.txt", "text/plain", "READY");
		UUID processed = insertDocument("processed.txt", "text/plain", "UPLOADED");
		this.jdbc.sql("INSERT INTO ingestion_processed_event (event_id, document_id, outcome, page_count, chunk_count) "
				+ "VALUES (gen_random_uuid(), :id, 'READY', 1, 1)").param("id", processed).update();
		UUID pending = insertDocument("pending.txt", "text/plain", "UPLOADED");

		Map<UUID, Integer> outboxRows = this.transactions.execute(status -> {
			// A pending (not yet relayed) event, invisible to the relay until this transaction commits.
			this.jdbc.sql("INSERT INTO outbox_event (id, topic, message_key, payload) "
					+ "VALUES (gen_random_uuid(), 'document.uploaded', :key, '{}'::jsonb)")
				.param("key", pending.toString())
				.update();
			this.jdbc.sql(migrationSql()).update();
			return Map.of(legacy, outboxCount(legacy), ready, outboxCount(ready), processed, outboxCount(processed),
					pending, outboxCount(pending));
		});

		assertThat(outboxRows).containsEntry(legacy, 1)
			.containsEntry(ready, 0)
			.containsEntry(processed, 0)
			.containsEntry(pending, 1);
	}

	@Test
	void backfilledEventIsRelayedInTheMessageFormatTheWorkerReads() {
		UUID legacy = insertDocument("legacy-manual.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "UPLOADED");

		this.jdbc.sql(migrationSql()).update();

		String value = this.recorder.await("document.uploaded", legacy.toString(), records -> !records.isEmpty())
			.getFirst()
			.value();
		DocumentUploaded event = this.json.readValue(value, DocumentUploaded.class);
		assertThat(event.eventId()).isNotNull();
		assertThat(event.documentId()).isEqualTo(legacy);
		assertThat(event.workspaceId()).isEqualTo(this.workspaceId);
		assertThat(event.storageKey()).isEqualTo("workspaces/" + this.workspaceId + "/documents/legacy-" + legacy);
		assertThat(event.contentType())
			.isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		assertThat(event.filename()).isEqualTo("legacy-manual.docx");
		assertThat(event.uploadedBy()).isEqualTo(this.userId);
		assertThat(event.reason()).isEqualTo("BACKFILL");
		assertThat(event.occurredAt()).isNotNull();
	}

	@Test
	void runningItAgainBeforeTheRelayCaughtUpQueuesNothingNew() {
		UUID legacy = insertDocument("twice.pdf", "application/pdf", "UPLOADED");

		int rows = this.transactions.execute(status -> {
			this.jdbc.sql(migrationSql()).update();
			this.jdbc.sql(migrationSql()).update();
			return outboxCount(legacy);
		});

		assertThat(rows).isEqualTo(1);
	}

	// --- helpers ---

	/** A document row as Phase 3 created it: no outbox event, never processed. */
	private UUID insertDocument(String filename, String contentType, String status) {
		UUID id = UUID.randomUUID();
		byte[] sha = new byte[32];
		ThreadLocalRandom.current().nextBytes(sha);
		this.jdbc.sql("""
				INSERT INTO document (id, workspace_id, filename, content_type, size_bytes, sha256, storage_key, status,
				                      uploaded_by)
				VALUES (:id, :ws, :filename, :type, 100, :sha, :key, :status, :user)
				""")
			.param("id", id)
			.param("ws", this.workspaceId)
			.param("filename", filename)
			.param("type", contentType)
			.param("sha", HexFormat.of().formatHex(sha))
			.param("key", "workspaces/" + this.workspaceId + "/documents/legacy-" + id)
			.param("status", status)
			.param("user", this.userId)
			.update();
		return id;
	}

	private int outboxCount(UUID documentId) {
		return this.jdbc.sql("SELECT count(*) FROM outbox_event WHERE message_key = :key")
			.param("key", documentId.toString())
			.query(Integer.class)
			.single();
	}

	private static String migrationSql() {
		try {
			return new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
