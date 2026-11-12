package dev.docquery.worker.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import dev.docquery.worker.StubEmbeddingModel;
import dev.docquery.worker.chunking.Chunk;

class ChunkEmbedderTests {

	@Test
	void embedsInBatchesWithThePrefixAndKeepsOrder() {
		StubEmbeddingModel model = new StubEmbeddingModel();
		ChunkEmbedder embedder = new ChunkEmbedder(model, "nomic-embed-text", 2, "search_document: ");
		List<Chunk> chunks = chunks(5);

		EmbeddedChunks embedded = embedder.embed(chunks);

		assertThat(model.calls()).isEqualTo(3);
		assertThat(embedded.requestCount()).isEqualTo(3);
		assertThat(model.inputs()).containsExactly("search_document: chunk 0", "search_document: chunk 1",
				"search_document: chunk 2", "search_document: chunk 3", "search_document: chunk 4");
		assertThat(embedded.vectors()).hasSize(5);
		assertThat(embedded.vectors().get(3)).isEqualTo(StubEmbeddingModel.vector("search_document: chunk 3"));
		assertThat(embedded.dimensions()).isEqualTo(StubEmbeddingModel.DIMENSIONS);
		assertThat(embedded.model()).isEqualTo("nomic-embed-text");
	}

	@Test
	void estimatesTokensWhenTheProviderReportsNone() {
		ChunkEmbedder embedder = new ChunkEmbedder(new StubEmbeddingModel(), "m", 10, "");

		EmbeddedChunks embedded = embedder.embed(List.of(new Chunk(0, 1, 0, 16, "sixteen chars!!!")));

		assertThat(embedded.tokensEstimated()).isTrue();
		assertThat(embedded.inputTokens()).isEqualTo(4);
	}

	@Test
	void usesReportedTokenUsage() {
		EmbeddingModel model = mock(EmbeddingModel.class);
		when(model.embedForResponse(anyList()))
			.thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[] { 1, 0 }, 0)),
					new EmbeddingResponseMetadata("m", new DefaultUsage(42, 0))));
		ChunkEmbedder embedder = new ChunkEmbedder(model, "m", 10, "");

		EmbeddedChunks embedded = embedder.embed(chunks(1));

		assertThat(embedded.tokensEstimated()).isFalse();
		assertThat(embedded.inputTokens()).isEqualTo(42);
		verify(model, times(1)).embedForResponse(anyList());
	}

	@Test
	void rejectsAResponseWithMissingVectors() {
		EmbeddingModel model = mock(EmbeddingModel.class);
		when(model.embedForResponse(anyList()))
			.thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[] { 1, 0 }, 0))));
		ChunkEmbedder embedder = new ChunkEmbedder(model, "m", 10, "");

		assertThatIllegalStateException().isThrownBy(() -> embedder.embed(chunks(2)));
	}

	private static List<Chunk> chunks(int count) {
		return IntStream.range(0, count).mapToObj(i -> {
			String text = "chunk " + i;
			return new Chunk(i, 1, 0, text.length(), text);
		}).toList();
	}

}
