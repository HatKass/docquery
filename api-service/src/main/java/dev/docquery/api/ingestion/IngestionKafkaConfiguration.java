package dev.docquery.api.ingestion;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
@EnableScheduling
class IngestionKafkaConfiguration {

	/*
	 * Both services declare the topics they use (same settings), so either can start first; the
	 * broker never auto-creates topics. The worker also declares the dead-letter topic it writes.
	 */

	@Bean
	NewTopic documentUploadedTopic(IngestionProperties properties) {
		return topic(properties.topics().uploaded(), properties);
	}

	@Bean
	NewTopic documentStatusTopic(IngestionProperties properties) {
		return topic(properties.topics().status(), properties);
	}

	private static NewTopic topic(String name, IngestionProperties properties) {
		return TopicBuilder.name(name)
			.partitions(properties.topics().partitions())
			.replicas(properties.topics().replicas())
			.build();
	}

	/**
	 * Status events must not be lost: on a failure (database down...) the record is retried with
	 * exponential backoff, capped at 30 s, without giving up. Malformed records never get here, the
	 * listener skips them.
	 */
	@Bean
	DefaultErrorHandler statusErrorHandler() {
		ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
		backOff.setMaxInterval(30_000);
		return new DefaultErrorHandler(backOff);
	}

}
