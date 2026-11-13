package dev.docquery.worker.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.stereotype.Component;

import dev.docquery.worker.ingestion.IngestionMessages.DocumentUploaded;

/**
 * Pipeline telemetry (ADR 0014).
 * <ul>
 * <li>{@code docquery.ingestion.step}: one observation per step (extract, chunk, embed, index), that
 * is a span inside the document's trace and a timer tagged with the step.</li>
 * <li>{@code docquery.ingestion.documents} and {@code docquery.ingestion.duration}: outcomes, and the
 * time from the upload (event creation) to READY or FAILED, retries included, so what a user waits.</li>
 * <li>{@code docquery.ingestion.pages}, {@code docquery.ingestion.chunks}, {@code docquery.embedding.tokens}.</li>
 * </ul>
 * Metric tags never carry tenant ids or document content; ids go on spans only.
 */
@Component
class IngestionMetrics {

	static final String READY = "ready";

	static final String FAILED = "failed";

	private final ObservationRegistry observations;

	private final MeterRegistry meters;

	private final DistributionSummary pages;

	private final DistributionSummary chunks;

	IngestionMetrics(ObservationRegistry observations, MeterRegistry meters) {
		this.observations = observations;
		this.meters = meters;
		this.pages = DistributionSummary.builder("docquery.ingestion.pages")
			.description("Pages per indexed document (documents without pages count as 0)")
			.register(meters);
		this.chunks = DistributionSummary.builder("docquery.ingestion.chunks")
			.description("Chunks per indexed document")
			.register(meters);
	}

	/** Runs one pipeline step in its own observation. */
	<T> T step(String step, DocumentUploaded event, Supplier<T> work) {
		return Observation.createNotStarted("docquery.ingestion.step", this.observations)
			.contextualName("ingestion " + step)
			.lowCardinalityKeyValue(KeyValue.of("step", step))
			.highCardinalityKeyValue(KeyValue.of("docquery.document.id", String.valueOf(event.documentId())))
			.highCardinalityKeyValue(KeyValue.of("docquery.workspace.id", String.valueOf(event.workspaceId())))
			.observe(work);
	}

	void indexed(DocumentUploaded event, Integer pageCount, int chunkCount, String model, long tokens,
			boolean tokensEstimated) {
		this.pages.record((pageCount != null) ? pageCount : 0);
		this.chunks.record(chunkCount);
		Counter.builder("docquery.embedding.tokens")
			.description("Embedding input tokens (estimated=true when the provider reported none)")
			.tag("model", model)
			.tag("estimated", Boolean.toString(tokensEstimated))
			.register(this.meters)
			.increment(tokens);
		finished(event, READY);
	}

	void failed(DocumentUploaded event) {
		finished(event, FAILED);
	}

	private void finished(DocumentUploaded event, String outcome) {
		Counter.builder("docquery.ingestion.documents")
			.description("Documents that finished ingestion, by outcome")
			.tag("outcome", outcome)
			.register(this.meters)
			.increment();
		if (event.occurredAt() != null) {
			Duration elapsed = Duration.between(event.occurredAt(), Instant.now());
			Timer.builder("docquery.ingestion.duration")
				.description("Upload (event creation) to READY or FAILED, including queueing and retries")
				.tag("outcome", outcome)
				.publishPercentileHistogram()
				.register(this.meters)
				.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
		}
	}

}
