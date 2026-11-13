package dev.docquery.api.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RankFusionTests {

	private static RetrievedChunk chunk(String name, int index, Double similarity) {
		return new RetrievedChunk(UUID.nameUUIDFromBytes((name + index).getBytes()), UUID.randomUUID(), name,
				"text/plain", null, index, "content " + name + index, similarity, null, null, 0);
	}

	@Test
	void chunkFoundByBothSearchesWinsOverChunksFoundByOne() {
		RetrievedChunk both = chunk("b", 0, 0.7);
		RetrievedChunk vectorOnly = chunk("v", 0, 0.9);
		RetrievedChunk keywordOnly = chunk("k", 0, null);

		List<RetrievedChunk> fused = RankFusion.fuse(List.of(vectorOnly, both), List.of(keywordOnly, both), 60, 10);

		assertThat(fused).extracting(RetrievedChunk::documentName).containsExactly("b", "k", "v");
		RetrievedChunk first = fused.getFirst();
		assertThat(first.vectorRank()).isEqualTo(2);
		assertThat(first.keywordRank()).isEqualTo(2);
		assertThat(first.similarity()).isEqualTo(0.7);
		assertThat(first.score()).isEqualTo(2.0 / 62);
	}

	@Test
	void usesRanksNotRawScores() {
		// A tiny similarity at rank 1 counts as much as a keyword hit at rank 1.
		RetrievedChunk vector = chunk("a", 0, 0.01);
		RetrievedChunk keyword = chunk("z", 0, null);

		List<RetrievedChunk> fused = RankFusion.fuse(List.of(vector), List.of(keyword), 60, 10);

		assertThat(fused).extracting(RetrievedChunk::score).containsExactly(1.0 / 61, 1.0 / 61);
		// Ties are broken deterministically (document name, then position).
		assertThat(fused).extracting(RetrievedChunk::documentName).containsExactly("a", "z");
	}

	@Test
	void fusesSeveralQueriesKeepingTheBestRankAndSimilarity() {
		// The same chunk found by the question as asked (rank 2) and by its rewrite (rank 1).
		RetrievedChunk asAsked = chunk("cv", 0, 0.65);
		RetrievedChunk rewritten = chunk("cv", 0, 0.8);
		RetrievedChunk other = chunk("other", 0, 0.9);

		List<RetrievedChunk> fused = RankFusion.fuseAll(List.of(List.of(other, asAsked), List.of(rewritten)),
				List.of(List.of(), List.of()), 60, 10);

		assertThat(fused.getFirst().documentName()).isEqualTo("cv");
		assertThat(fused.getFirst().vectorRank()).isEqualTo(1);
		assertThat(fused.getFirst().similarity()).isEqualTo(0.8);
		assertThat(fused.getFirst().score()).isEqualTo(1.0 / 62 + 1.0 / 61);
	}

	@Test
	void contextBudgetKeepsSourcesInOrderAndAlwaysTheFirst() {
		RetrievedChunk big = withContent("a", "x".repeat(900));
		RetrievedChunk medium = withContent("b", "y".repeat(300));
		RetrievedChunk small = withContent("c", "z".repeat(100));

		assertThat(ChunkRetriever.withinBudget(List.of(big, medium, small), 1000)).containsExactly(big, small);
		assertThat(ChunkRetriever.withinBudget(List.of(big), 10)).containsExactly(big);
	}

	private static RetrievedChunk withContent(String name, String content) {
		return new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), name, "text/plain", null, 0, content, 0.7,
				null, null, 0);
	}

	@Test
	void keepsAtMostTheLimitAndHandlesEmptyLists() {
		List<RetrievedChunk> vector = List.of(chunk("a", 0, 0.9), chunk("a", 1, 0.8), chunk("a", 2, 0.7));

		assertThat(RankFusion.fuse(vector, List.of(), 60, 2)).extracting(RetrievedChunk::chunkIndex)
			.containsExactly(0, 1);
		assertThat(RankFusion.fuse(List.of(), List.of(), 60, 5)).isEmpty();
	}

}
