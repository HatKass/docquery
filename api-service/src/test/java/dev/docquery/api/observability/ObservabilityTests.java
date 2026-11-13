package dev.docquery.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.kafka.KafkaContainer;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.TestUser;
import dev.docquery.api.support.TopicRecorder;

/**
 * Phase 7 (ADR 0014): one trace per document across the outbox and Kafka, quiet background work, and
 * a scrapeable metrics endpoint.
 */
class ObservabilityTests extends ApiIntegrationTest {

	static final String UPLOADED = "document.uploaded";

	static final String STATUS = "document.status";

	@Autowired
	KafkaContainer kafkaContainer;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	InMemorySpanExporter spans;

	@Autowired
	Tracer tracer;

	TopicRecorder recorder;

	TestUser owner;

	UUID workspaceId;

	@BeforeEach
	void setUp() {
		this.recorder = new TopicRecorder(this.kafkaContainer.getBootstrapServers(), UPLOADED);
		this.owner = TestUser.random("observability-owner");
		this.api.signIn(this.owner);
		this.workspaceId = this.api.createWorkspace(this.owner, "Observability");
	}

	@AfterEach
	void tearDown() {
		this.recorder.close();
	}

	@Test
	void uploadTraceContinuesThroughTheOutboxIntoKafka() {
		String traceId = randomHex(16);
		String callerSpanId = randomHex(8);

		MvcTestResult result = this.mvc.post()
			.uri("/api/workspaces/" + this.workspaceId + "/documents")
			.multipart()
			.file(new MockMultipartFile("file", "traced.md", MediaType.APPLICATION_OCTET_STREAM_VALUE,
					SampleFiles.text("# Traced upload")))
			.header("traceparent", "00-" + traceId + "-" + callerSpanId + "-01")
			.with(this.owner.auth())
			.exchange();
		assertThat(result).hasStatus(HttpStatus.CREATED);
		String documentId = this.json.readTree(result.getResponse().getContentAsByteArray()).get("id").asString();

		// The message the worker receives belongs to the caller's trace, although the outbox relay sent it later
		// from a scheduler thread.
		ConsumerRecord<String, String> uploaded = this.recorder.await(UPLOADED, documentId, r -> !r.isEmpty())
			.getFirst();
		assertThat(traceparent(uploaded)).startsWith("00-" + traceId + "-");

		List<SpanData> trace = awaitSpans(traceId, "outbox relay", "document.uploaded send");
		SpanData server = trace.stream().filter(s -> s.getParentSpanId().equals(callerSpanId)).findFirst().orElseThrow();
		SpanData relay = named(trace, "outbox relay");
		SpanData send = named(trace, "document.uploaded send");
		assertThat(server.getName()).isEqualTo("http post /api/workspaces/{workspaceId}/documents");
		assertThat(relay.getParentSpanId()).isEqualTo(server.getSpanId());
		assertThat(send.getParentSpanId()).isEqualTo(relay.getSpanId());
		assertThat(traceparent(uploaded)).contains(send.getSpanId());
		assertThat(relay.getAttributes().asMap().toString()).contains("messaging.destination.name=document.uploaded");
	}

	@Test
	void statusEventsAreProcessedInTheTraceOfTheirSender() {
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "status.txt", SampleFiles.text("s"));
		String status = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"documentId\":\"" + documentId
				+ "\",\"workspaceId\":\"" + this.workspaceId + "\",\"status\":\"EXTRACTING\"}";

		Span worker = this.tracer.nextSpan().name("worker pipeline (test)").start();
		try (Tracer.SpanInScope scope = this.tracer.withSpan(worker)) {
			this.kafka.send(STATUS, documentId.toString(), status).join();
		}
		finally {
			worker.end();
		}

		List<SpanData> trace = awaitSpans(worker.context().traceId(), "document.status process");
		assertThat(named(trace, "document.status process").getParentSpanId())
			.isEqualTo(named(trace, "document.status send").getSpanId());
	}

	@Test
	void healthChecksAndScrapesAreNotTraced() {
		assertThat(this.mvc.get().uri("/actuator/health").exchange()).hasStatusOk();
		assertThat(this.mvc.get().uri("/actuator/prometheus").exchange()).hasStatusOk();
		// A traced request after them: once its span is exported, theirs would have been too.
		assertThat(this.api.get(this.owner, "/api/me")).hasStatusOk();

		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.until(() -> this.spans.getFinishedSpanItems().stream().anyMatch(s -> s.getName().equals("http get /api/me")));
		assertThat(this.spans.getFinishedSpanItems()).extracting(SpanData::getName)
			.noneMatch(name -> name.contains("/actuator"))
			// The outbox relay's polling runs (every 500 ms) are not traced either.
			.noneMatch(name -> name.toLowerCase().contains("pending"));
	}

	@Test
	void prometheusEndpointIsPublicAndCountsBusinessErrorsByCode() throws Exception {
		// SampleFiles.text is unique per call: upload the very same bytes twice.
		byte[] content = SampleFiles.text("same content");
		this.api.uploadDocument(this.owner, this.workspaceId, "twice.txt", content);
		assertThat(this.api.upload(this.owner, this.workspaceId, "twice-again.txt", content))
			.hasStatus(HttpStatus.CONFLICT);

		MvcTestResult scrape = this.mvc.get().uri("/actuator/prometheus").exchange();

		assertThat(scrape).hasStatusOk();
		String metrics = scrape.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(metrics).contains("docquery_api_problems_total{application=\"api-service\",code=\"duplicate-document\",status=\"409\"}")
			.contains("docquery_documents_uploaded_total{application=\"api-service\",type=\"text\"}")
			.contains("docquery_outbox_pending{application=\"api-service\"}")
			.contains("http_server_requests_seconds_bucket");
		// No tenant or user ids in metric labels.
		assertThat(metrics).doesNotContain(this.workspaceId.toString());
	}

	private List<SpanData> awaitSpans(String traceId, String... names) {
		Awaitility.await()
			.atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofMillis(100))
			.until(() -> spansOf(traceId).stream().map(SpanData::getName).toList().containsAll(List.of(names)));
		return spansOf(traceId);
	}

	private List<SpanData> spansOf(String traceId) {
		return this.spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).toList();
	}

	private static SpanData named(List<SpanData> spans, String name) {
		return spans.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
	}

	private static String traceparent(ConsumerRecord<String, String> record) {
		Header header = record.headers().lastHeader("traceparent");
		assertThat(header).as("traceparent header").isNotNull();
		return new String(header.value(), StandardCharsets.UTF_8);
	}

	private static String randomHex(int bytes) {
		byte[] value = new byte[bytes];
		ThreadLocalRandom.current().nextBytes(value);
		value[0] |= 1; // never all zeros (an invalid id)
		return HexFormat.of().formatHex(value);
	}

}
