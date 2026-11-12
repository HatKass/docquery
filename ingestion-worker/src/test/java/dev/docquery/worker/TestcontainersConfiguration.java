package dev.docquery.worker;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Real infrastructure for the worker: Kafka (KRaft), pgvector Postgres with api-service's Flyway
 * migrations applied (the schema the worker runs against in production), and SeaweedFS for S3.
 * Embeddings come from {@link StubEmbeddingModel}, so tests need no Ollama and are deterministic
 * (selected in src/test/resources/config/application.properties).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Same images as compose.yaml. */
	static final String KAFKA_IMAGE = "apache/kafka:4.3.2";

	static final DockerImageName PGVECTOR_IMAGE = DockerImageName.parse("pgvector/pgvector:0.8.7-pg16-trixie")
		.asCompatibleSubstituteFor("postgres");

	static final String OBJECT_STORAGE_IMAGE = "chrislusf/seaweedfs:4.53";

	static final Path OBJECT_STORAGE_ENTRYPOINT = Path.of("..", "infra", "object-storage", "entrypoint.sh");

	/** api-service owns the schema; apply its migrations as they are in this commit. */
	static final Path MIGRATIONS = Path.of("..", "api-service", "src", "main", "resources", "db", "migration");

	static final int S3_PORT = 8333;

	public static final String TEST_BUCKET = "docquery-test";

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE);
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

	@Bean
	StubEmbeddingModel stubEmbeddingModel() {
		return new StubEmbeddingModel();
	}

	@Bean
	DynamicPropertyRegistrar workerTestProperties(GenericContainer<?> objectStorageContainer) {
		return registry -> {
			registry.add("spring.flyway.locations", () -> "filesystem:" + MIGRATIONS.toAbsolutePath());
			registry.add("docquery.storage.s3.endpoint", () -> "http://" + objectStorageContainer.getHost() + ":"
					+ objectStorageContainer.getMappedPort(S3_PORT));
			registry.add("docquery.storage.s3.access-key", () -> "test-access-key");
			registry.add("docquery.storage.s3.secret-key", () -> "test-secret-key");
			registry.add("docquery.storage.s3.bucket", () -> TEST_BUCKET);
		};
	}

}
