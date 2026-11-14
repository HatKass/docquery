package dev.docquery.worker.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import dev.docquery.worker.chunking.Chunk;

/**
 * The worker's real Ollama embedding model, configured from {@code application.yml} and called the way
 * {@link ChunkEmbedder} calls it, asks Ollama to keep the model loaded between documents. Without it, a document arriving more than 5 minutes after
 * the previous one paid a ~14 s cold start (see the embedding note in application.yml).
 * <p>
 * Runs against a minimal fake of Ollama's {@code /api/embed}: no Ollama needed.
 */
class OllamaEmbeddingKeepAliveTests {

	private final List<String> requests = new CopyOnWriteArrayList<>();

	private HttpServer ollama;

	@BeforeEach
	void fakeOllama() throws IOException {
		this.ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.ollama.createContext("/api/embed", exchange -> {
			this.requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = """
					{"model":"nomic-embed-text","embeddings":[[0.6,0.8]],"prompt_eval_count":3}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.ollama.start();
	}

	@AfterEach
	void stop() {
		this.ollama.stop(0);
	}

	@Test
	void embeddingRequestsAskOllamaToKeepTheModelLoaded() {
		contextRunner().run(context -> {
			embedder(context.getBean(EmbeddingModel.class)).embed(List.of(new Chunk(0, 1, 0, 5, "hello")));

			assertThat(this.requests).singleElement()
				.satisfies(body -> assertThat(body).contains("\"keep_alive\":\"30m\"")
					.contains("\"model\":\"nomic-embed-text\""));
		});
	}

	@Test
	void theKeepAliveCanBeChangedWithAnEnvironmentVariable() {
		contextRunner().withPropertyValues("OLLAMA_EMBEDDING_KEEP_ALIVE=-1").run(context -> {
			embedder(context.getBean(EmbeddingModel.class)).embed(List.of(new Chunk(0, 1, 0, 5, "hello")));

			assertThat(this.requests).singleElement().satisfies(body -> assertThat(body).contains("\"keep_alive\":\"-1\""));
		});
	}

	private static ChunkEmbedder embedder(EmbeddingModel model) {
		return new ChunkEmbedder(model, "nomic-embed-text", 32, "search_document: ");
	}

	private ApplicationContextRunner contextRunner() {
		PropertySource<?> applicationYml = applicationYml();
		return new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().getPropertySources().addLast(applicationYml))
			.withPropertyValues("spring.ai.model.embedding=ollama",
					"spring.ai.ollama.base-url=http://127.0.0.1:" + this.ollama.getAddress().getPort(),
					// Isolate from a developer's environment variables (the yml default is what is tested).
					"OLLAMA_EMBEDDING_KEEP_ALIVE=30m", "OLLAMA_EMBEDDING_MODEL=nomic-embed-text")
			.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class,
					SpringAiRetryAutoConfiguration.class, OllamaApiAutoConfiguration.class,
					OllamaEmbeddingAutoConfiguration.class));
	}

	/** The main application.yml (not the test overrides), as Spring Boot loads it. */
	private static PropertySource<?> applicationYml() {
		try {
			return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
				.getFirst();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
