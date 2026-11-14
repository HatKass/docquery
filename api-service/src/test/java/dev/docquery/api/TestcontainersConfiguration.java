package dev.docquery.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

import dev.docquery.api.support.StubChatModel;
import dev.docquery.api.support.StubEmbeddingModel;

/**
 * Real infrastructure ({@link InfrastructureContainers}) with offline, deterministic models.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(InfrastructureContainers.class)
public class TestcontainersConfiguration {

	/**
	 * Offline, deterministic models: tests need no Ollama and no API key. {@code @Primary} because the
	 * provider auto-configuration still creates its (never called) beans: its conditions are evaluated
	 * before {@link DynamicPropertyRegistrar}s could switch it off.
	 */
	@Bean
	@Primary
	StubChatModel stubChatModel() {
		return new StubChatModel();
	}

	@Bean
	@Primary
	StubEmbeddingModel stubEmbeddingModel() {
		return new StubEmbeddingModel();
	}

	@Bean
	DynamicPropertyRegistrar aiProperties() {
		return registry -> {
			registry.add("docquery.chat.model", () -> StubChatModel.MODEL);
			registry.add("docquery.retrieval.embedding-model", () -> StubEmbeddingModel.MODEL);
			registry.add("docquery.retrieval.query-prefix", () -> "");
			// Bag-of-words stub vectors: one shared word already gives a clear similarity.
			registry.add("docquery.retrieval.min-similarity", () -> "0.2");
			// Prices for the stub models, so cost estimates are exercised (USD per 1M tokens).
			registry.add("docquery.usage.prices", () -> StubChatModel.MODEL + "=1/2;" + StubEmbeddingModel.MODEL + "=0.5/0");
		};
	}

}
