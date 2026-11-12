package dev.docquery.api;

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

}
