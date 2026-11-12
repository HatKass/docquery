package dev.docquery.worker.ingestion;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import dev.docquery.worker.chunking.TextChunker;
import dev.docquery.worker.embedding.ChunkEmbedder;
import dev.docquery.worker.extraction.ExtractionException;
import dev.docquery.worker.extraction.TextExtractor;
import dev.docquery.worker.ingestion.IngestionMessages.MalformedMessageException;
import dev.docquery.worker.storage.DocumentStorage.StoredFileMissingException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
class IngestionConfiguration {

	// --- Topics: declared here because the broker never auto-creates topics (compose.yaml). ---

	@Bean
	NewTopic documentUploadedTopic(IngestionProperties properties) {
		return topic(properties.topics().uploaded(), properties);
	}

	@Bean
	NewTopic documentStatusTopic(IngestionProperties properties) {
		return topic(properties.topics().status(), properties);
	}

	@Bean
	NewTopic documentUploadedDeadLetterTopic(IngestionProperties properties) {
		return topic(properties.topics().deadLetter(), properties);
	}

	private static NewTopic topic(String name, IngestionProperties properties) {
		return TopicBuilder.name(name)
			.partitions(properties.topics().partitions())
			.replicas(properties.topics().replicas())
			.build();
	}

	// --- Pipeline steps ---

	@Bean
	TextExtractor textExtractor(IngestionProperties properties) {
		return new TextExtractor(properties.limits().maxCharacters());
	}

	@Bean
	TextChunker textChunker(IngestionProperties properties) {
		return new TextChunker(properties.chunking().maxCharacters(), properties.chunking().overlapCharacters());
	}

	@Bean
	ChunkEmbedder chunkEmbedder(EmbeddingModel embeddingModel, IngestionProperties properties) {
		IngestionProperties.Embedding embedding = properties.embedding();
		return new ChunkEmbedder(embeddingModel, embedding.model(), embedding.batchSize(), embedding.documentPrefix());
	}

	// --- Error handling: retries with exponential backoff, then dead-letter topic + FAILED. ---

	/** Picked up by Spring Boot's listener container factory. */
	@Bean
	DefaultErrorHandler ingestionErrorHandler(IngestionMessages messages, IngestionRepository repository,
			StatusPublisher status, KafkaOperations<?, ?> kafka, IngestionProperties properties) {
		IngestionProperties.Retry retry = properties.retry();
		ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts() - 1);
		backOff.setInitialInterval(retry.initialInterval().toMillis());
		backOff.setMultiplier(retry.multiplier());
		backOff.setMaxInterval(retry.maxInterval().toMillis());
		DefaultErrorHandler handler = new DefaultErrorHandler(
				new IngestionFailureRecoverer(messages, repository, status, kafka, properties), backOff);
		// Retrying cannot fix the content of a file or a malformed message.
		handler.addNotRetryableExceptions(ExtractionException.class, StoredFileMissingException.class,
				MalformedMessageException.class);
		return handler;
	}

}
