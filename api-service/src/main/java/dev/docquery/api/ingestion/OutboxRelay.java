package dev.docquery.api.ingestion;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.docquery.api.ingestion.OutboxRepository.OutboxEvent;

/**
 * Relays outbox rows to Kafka, oldest first, and deletes each once Kafka acknowledged it.
 * <p>
 * Delivery is <em>at least once</em>: if the process dies after Kafka acknowledged an event but
 * before the delete committed, the event is sent again; the worker is idempotent by event id. On
 * the first failure the run stops (keeping the order) and retries on the next poll, so a Kafka
 * outage only delays processing; uploads keep working.
 */
@Component
class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxRepository outbox;

	private final KafkaTemplate<String, String> kafka;

	private final TransactionTemplate transactions;

	private final int batchSize;

	private final Duration sendTimeout;

	OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate transactions,
			IngestionProperties properties) {
		this.outbox = outbox;
		this.kafka = kafka;
		this.transactions = transactions;
		this.batchSize = properties.outbox().batchSize();
		this.sendTimeout = properties.outbox().sendTimeout();
	}

	@Scheduled(fixedDelayString = "${docquery.ingestion.outbox.poll-interval}")
	void relayPending() {
		int relayed;
		do {
			relayed = relayBatch();
		}
		while (relayed == this.batchSize);
	}

	/** @return events relayed; less than a full batch means the outbox is drained (or Kafka failed) */
	int relayBatch() {
		Integer relayed = this.transactions.execute(status -> {
			List<OutboxEvent> events = this.outbox.lockOldest(this.batchSize);
			int sent = 0;
			for (OutboxEvent event : events) {
				try {
					this.kafka.send(event.topic(), event.key(), event.payload())
						.get(this.sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					this.outbox.recordFailure(event.id(), "interrupted");
					return -1;
				}
				catch (ExecutionException | TimeoutException ex) {
					Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
					log.warn("Could not relay outbox event {} to '{}', will retry: {}", event.id(), event.topic(),
							cause.toString());
					this.outbox.recordFailure(event.id(), cause.toString());
					return -1;
				}
				this.outbox.delete(event.id());
				sent++;
			}
			return sent;
		});
		return (relayed != null) ? relayed : 0;
	}

}
