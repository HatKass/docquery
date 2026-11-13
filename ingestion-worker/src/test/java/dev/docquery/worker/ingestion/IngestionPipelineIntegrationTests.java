package dev.docquery.worker.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.worker.StubEmbeddingModel;
import dev.docquery.worker.TestcontainersConfiguration;
import dev.docquery.worker.extraction.TextExtractor;
import dev.docquery.worker.ingestion.IngestionMessages.DocumentUploaded;
import dev.docquery.worker.support.TestDocuments;
import dev.docquery.worker.support.TopicRecorder;

/**
 * The whole pipeline against real Kafka, pgvector Postgres (api-service's schema) and S3:
 * events in on {@code document.uploaded}, statuses out on {@code document.status}, chunks in the
 * database, failures on the dead-letter topic. Tracing is on (in-memory exporter only), as in production.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureTracing
class IngestionPipelineIntegrationTests {

	static final String UPLOADED = "document.uploaded";

	static final String STATUS = "document.status";

	static final String DEAD_LETTER = "document.uploaded.dlt";

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	KafkaContainer kafkaContainer;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	S3Client s3;

	@Autowired
	JsonMapper json;

	@Autowired
	StubEmbeddingModel embeddingModel;

	@Autowired
	Tracer tracer;

	@Autowired
	InMemorySpanExporter spans;

	@Autowired
	MeterRegistry meters;

	TopicRecorder recorder;

	UUID userId;

	UUID workspaceId;

	@BeforeEach
	void setUp() {
		this.embeddingModel.reset();
		ensureBucket();
		this.recorder = new TopicRecorder(this.kafkaContainer.getBootstrapServers(), STATUS, DEAD_LETTER);
		this.userId = this.jdbc
			.sql("INSERT INTO app_user (subject, username, display_name) VALUES (:s, :s, 'Test User') RETURNING id")
			.param("s", "user-" + UUID.randomUUID())
			.query(UUID.class)
			.single();
		this.workspaceId = createWorkspace();
	}

	@AfterEach
	void tearDown() {
		this.recorder.close();
	}

	@Test
	void pdfIsIndexedWithPageNumbersAndEveryStatusIsAnnounced() {
		byte[] pdf = TestDocuments.pdf(List.of(List.of("Acme employees get 25 days of paid holiday per year."),
				List.of("Expense claims must include a receipt and be filed within 30 days.")));
		DocumentUploaded event = upload(pdf, TextExtractor.PDF);

		List<JsonNode> statuses = awaitStatus(event, "READY");

		assertThat(statuses).extracting(s -> s.get("status").asString())
			.containsExactly("EXTRACTING", "CHUNKING", "EMBEDDING", "READY");
		assertThat(statuses).allSatisfy(s -> {
			assertThat(s.get("sourceEventId").asString()).isEqualTo(event.eventId().toString());
			assertThat(s.get("workspaceId").asString()).isEqualTo(this.workspaceId.toString());
		});
		JsonNode ready = statuses.getLast();
		assertThat(ready.get("pageCount").asInt()).isEqualTo(2);
		assertThat(ready.get("chunkCount").asInt()).isEqualTo(2);

		List<Map<String, Object>> chunks = chunks(event.documentId());
		assertThat(chunks).extracting(c -> c.get("page_number")).containsExactly(1, 2);
		assertThat(chunks).extracting(c -> c.get("content"))
			.containsExactly("Acme employees get 25 days of paid holiday per year.",
					"Expense claims must include a receipt and be filed within 30 days.");
		assertThat(chunks).allSatisfy(c -> {
			assertThat(c.get("workspace_id")).isEqualTo(this.workspaceId);
			assertThat(c.get("embedding_model")).isEqualTo(StubEmbeddingModel.MODEL);
			assertThat(c.get("embedding_dimensions")).isEqualTo(StubEmbeddingModel.DIMENSIONS);
			assertThat(c.get("dims")).isEqualTo(StubEmbeddingModel.DIMENSIONS);
		});
		// The model's task prefix is sent to the provider but never stored.
		assertThat(this.embeddingModel.inputs()).allMatch(input -> input.startsWith("search_document: "));

		Map<String, Object> usage = this.jdbc
			.sql("SELECT model, input_tokens, estimated, request_count, user_id FROM embedding_usage WHERE document_id = :id")
			.param("id", event.documentId())
			.query()
			.singleRow();
		assertThat(usage).containsEntry("model", StubEmbeddingModel.MODEL)
			.containsEntry("estimated", true)
			.containsEntry("request_count", 1)
			.containsEntry("user_id", this.userId);
		assertThat((Long) usage.get("input_tokens")).isPositive();
	}

	@Test
	void unpaginatedDocumentsHaveNoPageNumbers() {
		DocumentUploaded event = upload(TestDocuments.docx(List.of("Travel policy", "Always book economy class.")),
				TextExtractor.DOCX);

		JsonNode ready = awaitStatus(event, "READY").getLast();

		assertThat(ready.get("pageCount").isNull()).isTrue();
		assertThat(chunks(event.documentId())).singleElement().satisfies(chunk -> {
			assertThat(chunk.get("page_number")).isNull();
			assertThat(chunk.get("content")).isEqualTo("Travel policy\n\nAlways book economy class.");
		});
	}

	@Test
	void redeliveredEventIsNotProcessedTwiceButItsOutcomeIsReannounced() {
		DocumentUploaded event = upload(TestDocuments.text("Remote work is allowed two days a week."),
				TextExtractor.TEXT);
		awaitStatus(event, "READY");
		List<UUID> chunkIds = chunkIds(event.documentId());
		int calls = this.embeddingModel.calls();

		send(event);

		this.recorder.await(STATUS, key(event),
				records -> records.stream().filter(r -> r.value().contains("\"READY\"")).count() == 2);
		assertThat(chunkIds(event.documentId())).isEqualTo(chunkIds);
		assertThat(this.embeddingModel.calls()).isEqualTo(calls);
		assertThat(count("embedding_usage", event.documentId())).isEqualTo(1);
	}

	@Test
	void reindexWithANewEventReplacesTheChunks() {
		DocumentUploaded first = upload(TestDocuments.text("Version one of the policy."), TextExtractor.TEXT);
		awaitStatus(first, "READY");
		List<UUID> before = chunkIds(first.documentId());

		DocumentUploaded reindex = new DocumentUploaded(UUID.randomUUID(), first.documentId(), first.workspaceId(),
				first.storageKey(), first.contentType(), first.filename(), first.uploadedBy(), "REINDEX", Instant.now());
		send(reindex);

		this.recorder.await(STATUS, key(first), records -> records.stream()
			.anyMatch(r -> r.value().contains(reindex.eventId().toString()) && r.value().contains("\"READY\"")));
		assertThat(chunkIds(first.documentId())).hasSize(1).doesNotContainAnyElementsOf(before);
		assertThat(count("embedding_usage", first.documentId())).isEqualTo(2);
	}

	@Test
	void damagedFileFailsAtOnceWithAReadableReasonAndGoesToTheDeadLetterTopic() {
		DocumentUploaded event = upload("%PDF-1.7\nnot really a pdf".getBytes(StandardCharsets.US_ASCII),
				TextExtractor.PDF);

		JsonNode failed = awaitStatus(event, "FAILED").getLast();

		assertThat(failed.get("failureReason").asString()).contains("could not be read");
		assertThat(this.embeddingModel.calls()).isZero();
		assertThat(chunkIds(event.documentId())).isEmpty();
		ConsumerRecord<String, String> dead = this.recorder.await(DEAD_LETTER, key(event), r -> !r.isEmpty()).getFirst();
		assertThat(dead.value()).contains(event.eventId().toString());
		assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-message").value(), StandardCharsets.UTF_8))
			.contains("could not be read");
		assertThat(processedOutcome(event)).isEqualTo("FAILED");
	}

	@Test
	void documentWithoutTextFailsWithAnExplanation() {
		DocumentUploaded event = upload(TestDocuments.pdf(List.of(List.of())), TextExtractor.PDF);

		assertThat(awaitStatus(event, "FAILED").getLast().get("failureReason").asString())
			.contains("No text was found");
	}

	@Test
	void transientEmbeddingFailureIsRetriedAndThenSucceeds() {
		this.embeddingModel.failNext(2);

		DocumentUploaded event = upload(TestDocuments.text("Parking is free for employees."), TextExtractor.TEXT);

		List<JsonNode> statuses = awaitStatus(event, "READY");
		assertThat(statuses).noneMatch(s -> s.get("status").asString().equals("FAILED"));
		assertThat(this.embeddingModel.calls()).isEqualTo(3);
		assertThat(chunkIds(event.documentId())).hasSize(1);
	}

	@Test
	void persistentEmbeddingFailureMarksTheDocumentFailedAfterAllAttempts() {
		this.embeddingModel.failNext(100);

		DocumentUploaded event = upload(TestDocuments.text("The cafeteria opens at 8."), TextExtractor.TEXT);

		JsonNode failed = awaitStatus(event, "FAILED").getLast();
		assertThat(failed.get("failureReason").asString()).contains("embedding service was unavailable")
			.contains("tried 3 times");
		assertThat(this.embeddingModel.calls()).isEqualTo(3);
		assertThat(this.recorder.await(DEAD_LETTER, key(event), r -> !r.isEmpty())).hasSize(1);
	}

	@Test
	void eventForADeletedDocumentIsSkipped() {
		DocumentUploaded event = upload(TestDocuments.text("Soon to be deleted."), TextExtractor.TEXT);
		awaitStatus(event, "READY");
		this.jdbc.sql("DELETE FROM document WHERE id = :id").param("id", event.documentId()).update();
		DocumentUploaded late = new DocumentUploaded(UUID.randomUUID(), event.documentId(), event.workspaceId(),
				event.storageKey(), event.contentType(), event.filename(), event.uploadedBy(), "REINDEX", Instant.now());

		send(late);

		Awaitility.await()
			.during(Duration.ofSeconds(2))
			.atMost(Duration.ofSeconds(5))
			.until(() -> this.recorder.poll(STATUS, key(event))
				.stream()
				.noneMatch(r -> r.value().contains(late.eventId().toString())));
		assertThat(chunkIds(event.documentId())).as("chunks are removed with their document").isEmpty();
	}

	@Test
	void malformedMessageGoesToTheDeadLetterTopicWithoutBlockingOthers() {
		String key = UUID.randomUUID().toString();
		this.kafka.send(UPLOADED, key, "{ this is not json").join();

		assertThat(this.recorder.await(DEAD_LETTER, key, r -> !r.isEmpty()).getFirst().value())
			.isEqualTo("{ this is not json");
		DocumentUploaded next = upload(TestDocuments.text("Still processing."), TextExtractor.TEXT);
		awaitStatus(next, "READY");
	}

	@Test
	void theDocumentsTraceContinuesThroughEveryStepAndStatusEvent() {
		double readyBefore = readyCount();
		DocumentUploaded event = store(TestDocuments.text("Traced through the pipeline."), TextExtractor.TEXT);
		// Plays api-service's outbox relay: the record is sent inside the upload's trace.
		Span upload = this.tracer.nextSpan().name("upload (test)").start();
		try (Tracer.SpanInScope scope = this.tracer.withSpan(upload)) {
			send(event);
		}
		finally {
			upload.end();
		}
		String traceId = upload.context().traceId();

		List<ConsumerRecord<String, String>> statuses = this.recorder.await(STATUS, key(event),
				records -> records.stream().anyMatch(r -> r.value().contains("\"READY\"")));

		// Every status event carries the trace back to api-service.
		assertThat(statuses).hasSize(4).allSatisfy(record -> {
			Header traceparent = record.headers().lastHeader("traceparent");
			assertThat(traceparent).isNotNull();
			assertThat(new String(traceparent.value(), StandardCharsets.UTF_8)).startsWith("00-" + traceId + "-");
		});
		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.until(() -> spanNames(traceId).containsAll(List.of("document.uploaded process", "ingestion extract",
					"ingestion chunk", "ingestion embed", "ingestion index")));
		List<SpanData> trace = this.spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).toList();
		SpanData process = trace.stream().filter(s -> s.getName().equals("document.uploaded process")).findFirst().orElseThrow();
		assertThat(trace).filteredOn(s -> s.getName().startsWith("ingestion "))
			.allSatisfy(step -> assertThat(step.getParentSpanId()).isEqualTo(process.getSpanId()))
			.allSatisfy(step -> assertThat(step.getAttributes().asMap().toString())
				.contains("docquery.document.id=" + event.documentId()));
		assertThat(readyCount()).isEqualTo(readyBefore + 1);
		assertThat(this.meters.get("docquery.ingestion.step").tag("step", "embed").timer().count()).isPositive();
	}

	@Test
	void schemaRejectsAChunkClaimingAnotherWorkspace() {
		DocumentUploaded event = upload(TestDocuments.text("Tenant A data."), TextExtractor.TEXT);
		awaitStatus(event, "READY");
		UUID otherWorkspace = createWorkspace();

		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> this.jdbc.sql("""
				INSERT INTO document_chunk (workspace_id, document_id, chunk_index, start_offset, end_offset, content,
				                            embedding, embedding_model, embedding_dimensions)
				VALUES (:ws, :doc, 99, 0, 1, 'x', '[1,0]', 'm', 2)
				""").param("ws", otherWorkspace).param("doc", event.documentId()).update());
	}

	// --- helpers ---

	private DocumentUploaded upload(byte[] content, String contentType) {
		DocumentUploaded event = store(content, contentType);
		send(event);
		return event;
	}

	/** Stores the file and the document row, and returns the event announcing them (not sent yet). */
	private DocumentUploaded store(byte[] content, String contentType) {
		UUID documentId = UUID.randomUUID();
		String key = "workspaces/" + this.workspaceId + "/documents/" + UUID.randomUUID();
		this.s3.putObject(r -> r.bucket(TestcontainersConfiguration.TEST_BUCKET).key(key), RequestBody.fromBytes(content));
		byte[] sha = new byte[32];
		ThreadLocalRandom.current().nextBytes(sha);
		this.jdbc.sql("""
				INSERT INTO document (id, workspace_id, filename, content_type, size_bytes, sha256, storage_key, uploaded_by)
				VALUES (:id, :ws, 'test-file', :type, :size, :sha, :key, :user)
				""")
			.param("id", documentId)
			.param("ws", this.workspaceId)
			.param("type", contentType)
			.param("size", content.length)
			.param("sha", HexFormat.of().formatHex(sha))
			.param("key", key)
			.param("user", this.userId)
			.update();
		DocumentUploaded event = new DocumentUploaded(UUID.randomUUID(), documentId, this.workspaceId, key,
				contentType, "test-file", this.userId, "UPLOAD", Instant.now());
		return event;
	}

	private List<String> spanNames(String traceId) {
		return this.spans.getFinishedSpanItems()
			.stream()
			.filter(s -> s.getTraceId().equals(traceId))
			.map(SpanData::getName)
			.toList();
	}

	private double readyCount() {
		var counter = this.meters.find("docquery.ingestion.documents").tag("outcome", "ready").counter();
		return (counter != null) ? counter.count() : 0;
	}

	private void send(DocumentUploaded event) {
		this.kafka.send(UPLOADED, key(event), this.json.writeValueAsString(event)).join();
	}

	private List<JsonNode> awaitStatus(DocumentUploaded event, String status) {
		return this.recorder
			.await(STATUS, key(event), records -> records.stream().anyMatch(r -> r.value().contains("\"" + status + "\"")))
			.stream()
			.map(r -> this.json.readTree(r.value()))
			.toList();
	}

	private List<Map<String, Object>> chunks(UUID documentId) {
		return this.jdbc.sql("""
				SELECT workspace_id, chunk_index, page_number, content, embedding_model, embedding_dimensions,
				       vector_dims(embedding) AS dims
				  FROM document_chunk WHERE document_id = :id ORDER BY chunk_index
				""").param("id", documentId).query().listOfRows();
	}

	private List<UUID> chunkIds(UUID documentId) {
		return this.jdbc.sql("SELECT id FROM document_chunk WHERE document_id = :id ORDER BY chunk_index")
			.param("id", documentId)
			.query(UUID.class)
			.list();
	}

	private int count(String table, UUID documentId) {
		return this.jdbc.sql("SELECT count(*) FROM " + table + " WHERE document_id = :id")
			.param("id", documentId)
			.query(Integer.class)
			.single();
	}

	private String processedOutcome(DocumentUploaded event) {
		return this.jdbc.sql("SELECT outcome FROM ingestion_processed_event WHERE event_id = :id")
			.param("id", event.eventId())
			.query(String.class)
			.single();
	}

	private UUID createWorkspace() {
		return this.jdbc.sql("INSERT INTO workspace (name, created_by) VALUES ('Test workspace', :user) RETURNING id")
			.param("user", this.userId)
			.query(UUID.class)
			.single();
	}

	private void ensureBucket() {
		try {
			this.s3.headBucket(r -> r.bucket(TestcontainersConfiguration.TEST_BUCKET));
		}
		catch (S3Exception ex) {
			this.s3.createBucket(r -> r.bucket(TestcontainersConfiguration.TEST_BUCKET));
		}
	}

	private static String key(DocumentUploaded event) {
		return event.documentId().toString();
	}

}
