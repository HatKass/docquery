package dev.docquery.api.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Unit tests of what the database-backed {@link HybridSearchTests} cannot easily show. */
class ChunkRetrieverTests {

	static final UUID WORKSPACE = UUID.randomUUID();

	static final UUID DOCUMENT = UUID.randomUUID();

	RetrievalRepository repository = mock(RetrievalRepository.class);

	EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

	ChunkRetriever retriever;

	@BeforeEach
	void retriever() {
		RetrievalProperties properties = new RetrievalProperties("nomic-embed-text", "search_query: ", 20, 20, 5, 0.6,
				60, 4, 0.6, 8000);
		this.retriever = new ChunkRetriever(this.repository, this.embeddingModel, properties,
				ObservationRegistry.NOOP);
		when(this.repository.nearest(any(), any(), anyString(), anyInt())).thenReturn(List.of());
		when(this.repository.keywordMatches(any(), anyString(), anyInt())).thenReturn(List.of());
	}

	@Test
	void queriesAreEmbeddedWithThePrefixAndWithoutRequestOptions() {
		when(this.embeddingModel.call(any(EmbeddingRequest.class)))
			.thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[] { 0.6f, 0.8f }, 0))));

		this.retriever.retrieve(WORKSPACE, "leave policy", 5);

		// No options: the provider applies all of its configured ones. Spring AI's generic options would
		// make Ollama drop keep_alive, and every question after 5 idle minutes paid a model cold start.
		verify(this.embeddingModel).call(argThat(request -> request.getOptions() == null
				&& request.getInstructions().equals(List.of("search_query: leave policy"))));
		verify(this.repository).nearest(eq(WORKSPACE), argThat(v -> v.length == 2), eq("nomic-embed-text"), eq(20));
	}

	@Test
	void anEmbeddingOutageFallsBackToFullTextSearch() {
		when(this.embeddingModel.call(any(EmbeddingRequest.class))).thenThrow(new IllegalStateException("down"));
		RetrievedChunk keywordHit = new RetrievedChunk(UUID.randomUUID(), DOCUMENT, "Handbook.pdf", "application/pdf",
				2, 0, "Employees receive 25 days of leave.", null, null, null, 0);
		when(this.repository.keywordMatches(WORKSPACE, "leave", 20)).thenReturn(List.of(keywordHit));

		List<RetrievedChunk> found = this.retriever.retrieve(WORKSPACE, "leave", 5);

		assertThat(found).singleElement().satisfies(hit -> {
			assertThat(hit.chunkId()).isEqualTo(keywordHit.chunkId());
			assertThat(hit.keywordRank()).isEqualTo(1);
			assertThat(hit.vectorRank()).isNull();
		});
		verify(this.repository, never()).nearest(any(), any(), anyString(), anyInt());
	}

	@Test
	void anEmptyEmbeddingCountsAsAnOutage() {
		when(this.embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of()));

		assertThat(this.retriever.retrieve(WORKSPACE, "leave", 5)).isEmpty();
		verify(this.repository, never()).nearest(any(), any(), anyString(), anyInt());
	}

}
