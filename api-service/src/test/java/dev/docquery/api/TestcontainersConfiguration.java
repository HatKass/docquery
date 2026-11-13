package dev.docquery.api;

import java.nio.file.Path;
import java.time.Duration;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import dev.docquery.api.support.StubChatModel;
import dev.docquery.api.support.StubEmbeddingModel;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Same image as compose.yaml so tests exercise the real pgvector extension. */
	static final DockerImageName PGVECTOR_IMAGE = DockerImageName.parse("pgvector/pgvector:0.8.7-pg16-trixie")
		.asCompatibleSubstituteFor("postgres");

	/** Same image and start script as compose.yaml: a real S3 API, not a mock. */
	static final String OBJECT_STORAGE_IMAGE = "chrislusf/seaweedfs:4.53";

	static final Path OBJECT_STORAGE_ENTRYPOINT = Path.of("..", "infra", "object-storage", "entrypoint.sh");

	static final int S3_PORT = 8333;

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE);
	}

	/** Same Kafka as compose.yaml (KRaft). No worker runs in api tests: they play its part on the topics. */
	@Bean
	@ServiceConnection
	public KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.2"));
	}

	@Bean
	GenericContainer<?> objectStorageContainer() {
		return new GenericContainer<>(OBJECT_STORAGE_IMAGE)
			.withEnv("S3_ACCESS_KEY", "test-access-key")
			.withEnv("S3_SECRET_KEY", "test-secret-key")
			.withCopyFileToContainer(MountableFile.forHostPath(OBJECT_STORAGE_ENTRYPOINT, 0755),
					"/etc/seaweedfs/entrypoint.sh")
			.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "/etc/seaweedfs/entrypoint.sh"))
			.withExposedPorts(S3_PORT)
			.waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT).withStartupTimeout(Duration.ofMinutes(2)));
	}

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

	@Bean
	DynamicPropertyRegistrar objectStorageProperties(GenericContainer<?> objectStorageContainer) {
		return registry -> {
			registry.add("docquery.storage.s3.endpoint", () -> "http://" + objectStorageContainer.getHost() + ":"
					+ objectStorageContainer.getMappedPort(S3_PORT));
			registry.add("docquery.storage.s3.access-key", () -> "test-access-key");
			registry.add("docquery.storage.s3.secret-key", () -> "test-secret-key");
			registry.add("docquery.storage.s3.bucket", () -> "docquery-test");
			registry.add("docquery.storage.s3.create-bucket", () -> "true");
			// Small limit so tests can exceed it cheaply.
			registry.add("docquery.documents.max-file-size", () -> "1MB");
		};
	}

	/** Finished spans, for assertions on traces (added to the SDK next to the disabled OTLP exporter). */
	@Bean
	InMemorySpanExporter inMemorySpanExporter() {
		return InMemorySpanExporter.create();
	}

}
