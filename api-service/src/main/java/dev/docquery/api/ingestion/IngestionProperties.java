package dev.docquery.api.ingestion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka side of the ingestion pipeline ({@code docquery.ingestion.*}); defaults in application.yml.
 *
 * @param topics topic names; must match the ingestion worker's
 * @param outbox how queued events are relayed to Kafka
 */
@ConfigurationProperties("docquery.ingestion")
public record IngestionProperties(Topics topics, Outbox outbox) {

	/**
	 * @param deadLetter where the worker puts {@code document.uploaded} events it gave up on; consumed
	 * into the admin page's dead-letter inbox
	 */
	public record Topics(String uploaded, String status, String deadLetter, int partitions, int replicas) {
	}

	/**
	 * @param pollInterval pause between relay runs when the outbox is empty
	 * @param batchSize events relayed per transaction
	 * @param sendTimeout how long to wait for Kafka to acknowledge one event
	 */
	public record Outbox(Duration pollInterval, int batchSize, Duration sendTimeout) {
	}

}
