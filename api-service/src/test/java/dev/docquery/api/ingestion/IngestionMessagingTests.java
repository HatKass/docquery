package dev.docquery.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;

import dev.docquery.api.document.DocumentService;
import dev.docquery.api.ingestion.IngestionMessages.DocumentStatusChanged;
import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.TestUser;
import dev.docquery.api.support.TopicRecorder;

/**
 * api-service's side of the pipeline: uploads and re-index requests leave through the outbox as
 * {@code document.uploaded} events; {@code document.status} events (sent here in the worker's
 * place) drive the document's status.
 */
class IngestionMessagingTests extends ApiIntegrationTest {

	static final String UPLOADED = "document.uploaded";

	static final String STATUS = "document.status";

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	KafkaContainer kafkaContainer;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DocumentService documentService;

	TopicRecorder recorder;

	TestUser owner;

	UUID workspaceId;

	@BeforeEach
	void setUp() {
		this.recorder = new TopicRecorder(this.kafkaContainer.getBootstrapServers(), UPLOADED);
		this.owner = TestUser.random("ingestion-owner");
		this.api.signIn(this.owner);
		this.workspaceId = this.api.createWorkspace(this.owner, "Ingestion");
	}

	@AfterEach
	void tearDown() {
		this.recorder.close();
	}

	@Test
	void uploadIsAnnouncedOnDocumentUploadedThroughTheOutbox() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "handbook.md",
				SampleFiles.text("# Handbook"));

		JsonNode event = uploadedEvents(documentId).getFirst();

		assertThat(event.get("documentId").asString()).isEqualTo(documentId.toString());
		assertThat(event.get("workspaceId").asString()).isEqualTo(this.workspaceId.toString());
		assertThat(event.get("contentType").asString()).isEqualTo("text/markdown");
		assertThat(event.get("filename").asString()).isEqualTo("handbook.md");
		assertThat(event.get("reason").asString()).isEqualTo("UPLOAD");
		assertThat(event.get("storageKey").asString()).isEqualTo(storageKey(documentId))
			.startsWith("workspaces/" + this.workspaceId + "/documents/");
		assertThat(event.get("eventId").asString()).isNotBlank();
		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM outbox_event WHERE message_key = :key")
				.param("key", documentId.toString())
				.query(Integer.class)
				.single() == 0);
	}

	@Test
	void statusEventsFromTheWorkerUpdateTheDocument() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "a.txt", SampleFiles.text("a"));

		sendStatus(documentId, "EXTRACTING", null, null, null);
		sendStatus(documentId, "CHUNKING", null, 3, null);
		sendStatus(documentId, "EMBEDDING", null, 3, 7);
		sendStatus(documentId, "READY", null, 3, 7);

		JsonNode document = awaitStatus(documentId, "READY");
		assertThat(document.get("pageCount").asInt()).isEqualTo(3);
		assertThat(document.get("chunkCount").asInt()).isEqualTo(7);
		assertThat(document.get("failureReason").isNull()).isTrue();
	}

	@Test
	void repeatedOutOfOrderUnknownAndMalformedStatusEventsAreIgnored() {
		UUID ready = this.api.uploadDocument(this.owner, this.workspaceId, "ready.txt", SampleFiles.text("r"));
		UUID failing = this.api.uploadDocument(this.owner, this.workspaceId, "fail.txt", SampleFiles.text("f"));
		markReady(ready, 1, 2);

		sendStatus(ready, "EXTRACTING", null, null, null);
		sendStatus(ready, "READY", null, 1, 2);
		sendStatus(UUID.randomUUID(), "READY", null, 1, 1);
		this.kafka.send(STATUS, ready.toString(), "{ not json").join();
		this.kafka.send(STATUS, ready.toString(), "{\"documentId\":\"" + ready + "\",\"status\":\"BOGUS\"}").join();
		// The consumer is still alive after all of that, and failure reasons arrive intact.
		sendStatus(failing, "FAILED", "The document is password-protected.", null, null);

		JsonNode failed = awaitStatus(failing, "FAILED");
		assertThat(failed.get("failureReason").asString()).isEqualTo("The document is password-protected.");
		assertThat(this.api.body(document(ready)).get("status").asString()).isEqualTo("READY");
	}

	@Test
	void reindexResetsTheDocumentAndQueuesANewEvent() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "r.txt", SampleFiles.text("x"));
		markReady(documentId, 2, 4);
		String firstEventId = uploadedEvents(documentId).getFirst().get("eventId").asString();

		assertThat(this.api.post(this.owner, reindexUri(documentId), "")).hasStatus(HttpStatus.ACCEPTED)
			.bodyJson()
			.satisfies(json -> {
				json.assertThat().extractingPath("$.status").isEqualTo("UPLOADED");
				json.assertThat().extractingPath("$.pageCount").isNull();
				json.assertThat().extractingPath("$.chunkCount").isNull();
			});

		List<JsonNode> events = this.recorder
			.await(UPLOADED, documentId.toString(), records -> records.size() == 2)
			.stream()
			.map(this::json)
			.toList();
		assertThat(events.get(1).get("reason").asString()).isEqualTo("REINDEX");
		assertThat(events.get(1).get("eventId").asString()).isNotEqualTo(firstEventId);
	}

	@Test
	void reindexWhileProcessingIsRejected() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "busy.txt", SampleFiles.text("b"));

		assertThat(this.api.post(this.owner, reindexUri(documentId), "")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:document-busy");
	}

	@Test
	void documentStuckInTheQueueCanBeRequeuedOnce() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "stuck.txt", SampleFiles.text("s"));
		uploadedEvents(documentId);
		ageQueuedDocument(documentId, 11);
		Instant before = statusChangedAt(documentId);

		assertThat(this.api.post(this.owner, reindexUri(documentId), "")).hasStatus(HttpStatus.ACCEPTED)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("UPLOADED");

		assertThat(statusChangedAt(documentId)).as("the queue timer restarts").isAfter(before);
		JsonNode requeued = this.recorder.await(UPLOADED, documentId.toString(), records -> records.size() == 2)
			.stream()
			.map(this::json)
			.toList()
			.get(1);
		assertThat(requeued.get("reason").asString()).isEqualTo("REQUEUE");
		// Retrying again right away is refused: the timer was restarted.
		assertThat(this.api.post(this.owner, reindexUri(documentId), "")).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void documentQueuedForLessThanTheThresholdCannotBeRequeued() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "young.txt", SampleFiles.text("y"));
		ageQueuedDocument(documentId, 9);

		assertThat(this.api.post(this.owner, reindexUri(documentId), "")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("queued for more than 10 minutes");
	}

	@Test
	void onlyQueuedDocumentsCountAsStuckNotOnesInProgress() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "slow.txt", SampleFiles.text("e"));
		sendStatus(documentId, "EXTRACTING", null, null, null);
		awaitStatus(documentId, "EXTRACTING");
		this.jdbc.sql("UPDATE document SET status_changed_at = now() - interval '1 hour' WHERE id = :id")
			.param("id", documentId)
			.update();

		assertThat(this.api.post(this.owner, reindexUri(documentId), "")).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void viewersCannotRequeueAStuckDocument() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "vs.txt", SampleFiles.text("vs"));
		ageQueuedDocument(documentId, 60);
		Instant before = statusChangedAt(documentId);
		TestUser viewer = TestUser.random("stuck-viewer");
		this.api.addMember(this.owner, this.workspaceId, viewer, "VIEWER");

		assertThat(this.api.post(viewer, reindexUri(documentId), "")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(statusChangedAt(documentId)).isEqualTo(before);
	}

	@Test
	void outsidersCannotRequeueAnotherTenantsStuckDocument() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "t.txt", SampleFiles.text("t"));
		ageQueuedDocument(documentId, 60);
		Instant before = statusChangedAt(documentId);
		TestUser outsider = TestUser.random("stuck-outsider");
		this.api.signIn(outsider);
		UUID outsiderWorkspace = this.api.createWorkspace(outsider, "Elsewhere");

		assertThat(this.api.post(outsider, reindexUri(documentId), "")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.api.post(outsider,
				"/api/workspaces/" + outsiderWorkspace + "/documents/" + documentId + "/reindex", ""))
			.hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:document-not-found");
		assertThat(statusChangedAt(documentId)).isEqualTo(before);
		assertThat(this.recorder.poll(UPLOADED, documentId.toString())).hasSizeLessThanOrEqualTo(1);
	}

	@Test
	void viewersCannotReindex() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "v.txt", SampleFiles.text("v"));
		markReady(documentId, 1, 1);
		TestUser viewer = TestUser.random("ingestion-viewer");
		this.api.addMember(this.owner, this.workspaceId, viewer, "VIEWER");

		assertThat(this.api.post(viewer, reindexUri(documentId), "")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(this.api.body(document(documentId)).get("status").asString()).isEqualTo("READY");
	}

	@Test
	void modelChangeReindexQueuesOnlyDocumentsEmbeddedWithAnotherModel() {
		String currentModel = "model-" + UUID.randomUUID();
		UUID stale = this.api.uploadDocument(this.owner, this.workspaceId, "stale.txt", SampleFiles.text("s"));
		UUID current = this.api.uploadDocument(this.owner, this.workspaceId, "current.txt", SampleFiles.text("c"));
		markReady(stale, 1, 1);
		markReady(current, 1, 1);
		insertChunk(stale, "old-model");
		insertChunk(current, currentModel);

		int queued = this.documentService.reindexForEmbeddingModel(currentModel, false);

		assertThat(queued).isGreaterThanOrEqualTo(1);
		assertThat(this.api.body(document(stale)).get("status").asString()).isEqualTo("UPLOADED");
		assertThat(this.api.body(document(current)).get("status").asString()).isEqualTo("READY");
		this.recorder.await(UPLOADED, stale.toString(), records -> records.size() == 2);
	}

	@Test
	void deletingADocumentDeletesItsChunks() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "d.txt", SampleFiles.text("d"));
		markReady(documentId, 1, 1);
		insertChunk(documentId, "any-model");

		this.api.delete(this.owner, "/api/workspaces/" + this.workspaceId + "/documents/" + documentId);

		assertThat(this.jdbc.sql("SELECT count(*) FROM document_chunk WHERE document_id = :id")
			.param("id", documentId)
			.query(Integer.class)
			.single()).isZero();
	}

	// --- helpers ---

	private void markReady(UUID documentId, int pages, int chunks) {
		sendStatus(documentId, "EXTRACTING", null, null, null);
		sendStatus(documentId, "CHUNKING", null, pages, null);
		sendStatus(documentId, "EMBEDDING", null, pages, chunks);
		sendStatus(documentId, "READY", null, pages, chunks);
		awaitStatus(documentId, "READY");
	}

	private void sendStatus(UUID documentId, String status, String reason, Integer pages, Integer chunks) {
		DocumentStatusChanged event = new DocumentStatusChanged(UUID.randomUUID(), UUID.randomUUID(), documentId,
				this.workspaceId, status, reason, pages, chunks, Instant.now());
		this.kafka.send(STATUS, documentId.toString(), this.json.writeValueAsString(event)).join();
	}

	private JsonNode awaitStatus(UUID documentId, String status) {
		Awaitility.await()
			.atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofMillis(100))
			.until(() -> status.equals(this.api.body(document(documentId)).get("status").asString()));
		return this.api.body(document(documentId));
	}

	private org.springframework.test.web.servlet.assertj.MvcTestResult document(UUID documentId) {
		return this.api.get(this.owner, "/api/workspaces/" + this.workspaceId + "/documents/" + documentId);
	}

	private List<JsonNode> uploadedEvents(UUID documentId) {
		return this.recorder.await(UPLOADED, documentId.toString(), records -> !records.isEmpty())
			.stream()
			.map(this::json)
			.toList();
	}

	private JsonNode json(ConsumerRecord<String, String> record) {
		return this.json.readTree(record.value());
	}

	private String storageKey(UUID documentId) {
		return this.jdbc.sql("SELECT storage_key FROM document WHERE id = :id")
			.param("id", documentId)
			.query(String.class)
			.single();
	}

	private void insertChunk(UUID documentId, String model) {
		this.jdbc.sql("""
				INSERT INTO document_chunk (workspace_id, document_id, chunk_index, start_offset, end_offset, content,
				                            embedding, embedding_model, embedding_dimensions)
				VALUES (:ws, :doc, 0, 0, 1, 'x', '[1,0,0]', :model, 3)
				""").param("ws", this.workspaceId).param("doc", documentId).param("model", model).update();
	}

	/** Pretends the document entered the queue {@code minutes} ago (requeue-after defaults to 10 minutes). */
	private void ageQueuedDocument(UUID documentId, int minutes) {
		this.jdbc.sql("UPDATE document SET status_changed_at = now() - make_interval(mins => :minutes) WHERE id = :id")
			.param("minutes", minutes)
			.param("id", documentId)
			.update();
	}

	private Instant statusChangedAt(UUID documentId) {
		return this.jdbc.sql("SELECT status_changed_at FROM document WHERE id = :id")
			.param("id", documentId)
			.query(java.time.OffsetDateTime.class)
			.single()
			.toInstant();
	}

	private String reindexUri(UUID documentId) {
		return "/api/workspaces/" + this.workspaceId + "/documents/" + documentId + "/reindex";
	}

}
