package dev.docquery.worker.health;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
class KafkaHealthConfiguration {

	/**
	 * Long-lived admin client dedicated to health checks, closed on shutdown. Reuses
	 * the Boot-configured connection properties (bootstrap servers, security).
	 */
	@Bean(destroyMethod = "close")
	Admin kafkaHealthAdmin(KafkaAdmin kafkaAdmin, @Value("${docquery.health.kafka.timeout:3s}") Duration timeout) {
		Map<String, Object> props = new HashMap<>(kafkaAdmin.getConfigurationProperties());
		props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
		props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
		props.put(AdminClientConfig.CLIENT_ID_CONFIG, "ingestion-worker-health");
		return Admin.create(props);
	}

	/** Bean name "kafka" becomes the health component name: /actuator/health/kafka. */
	@Bean
	KafkaHealthIndicator kafka(Admin kafkaHealthAdmin, @Value("${docquery.health.kafka.timeout:3s}") Duration timeout) {
		return new KafkaHealthIndicator(kafkaHealthAdmin, timeout);
	}

}
