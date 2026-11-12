package dev.docquery.worker.ingestion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pipeline settings ({@code docquery.ingestion.*}); defaults live in application.yml.
 */
@ConfigurationProperties("docquery.ingestion")
public record IngestionProperties(Topics topics, Limits limits, Chunking chunking, Embedding embedding, Retry retry) {

	/**
	 * @param uploaded consumed: one event per document to (re-)index
	 * @param status produced: pipeline progress, consumed by api-service
	 * @param deadLetter events that could not be processed, with the error in the headers
	 */
	public record Topics(String uploaded, String status, String deadLetter, int partitions, int replicas) {
	}

	/**
	 * @param maxCharacters documents with more extracted text are rejected (protects memory and cost)
	 * @param maxChunks upper bound on chunks per document (and therefore on embedding calls)
	 */
	public record Limits(int maxCharacters, int maxChunks) {
	}

	public record Chunking(int maxCharacters, int overlapCharacters) {
	}

	/**
	 * @param model name of the configured embedding model, stored with every chunk
	 * @param batchSize chunks per embedding request
	 * @param documentPrefix task prefix some models expect before document text (nomic-embed-text:
	 * {@code "search_document: "}); empty for models without one
	 */
	public record Embedding(String model, int batchSize, String documentPrefix) {
	}

	/**
	 * Retries for transient failures (embedding provider, storage, database or Kafka briefly
	 * unavailable). After {@code maxAttempts} the event goes to the dead-letter topic and the
	 * document is marked FAILED. Content problems (damaged file...) are never retried.
	 */
	public record Retry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {
	}

}
