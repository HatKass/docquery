package dev.docquery.api.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal rank fusion (Cormack, Clarke &amp; Büttcher, 2009): each list contributes
 * {@code 1 / (k + rank)} for every chunk it contains, and chunks are ordered by the sum.
 * <p>
 * Only ranks are used, never the raw scores, so a cosine similarity and a {@code ts_rank} value need
 * no calibration against each other. A chunk found by several searches beats one found by only one.
 * Several queries (the question as asked and its standalone rewrite) simply contribute more lists.
 */
final class RankFusion {

	private RankFusion() {
	}

	/**
	 * @param vectorHits ordered best first, with {@code similarity} set
	 * @param keywordHits ordered best first
	 * @return at most {@code limit} chunks, best first, with both ranks and the fused score set
	 */
	static List<RetrievedChunk> fuse(List<RetrievedChunk> vectorHits, List<RetrievedChunk> keywordHits, int k,
			int limit) {
		return fuseAll(List.of(vectorHits), List.of(keywordHits), k, limit);
	}

	/**
	 * Fuses any number of ranked lists. A chunk's reported vector / keyword rank is its best rank in
	 * any list of that kind, and its similarity the highest seen.
	 */
	static List<RetrievedChunk> fuseAll(List<List<RetrievedChunk>> vectorLists, List<List<RetrievedChunk>> keywordLists,
			int k, int limit) {
		Map<UUID, RetrievedChunk> byId = new LinkedHashMap<>();
		Map<UUID, Double> scores = new LinkedHashMap<>();
		for (List<RetrievedChunk> hits : vectorLists) {
			for (int i = 0; i < hits.size(); i++) {
				RetrievedChunk hit = hits.get(i);
				int rank = i + 1;
				byId.merge(hit.chunkId(), hit.withVectorRank(rank, hit.similarity()),
						(existing, ignored) -> existing.withVectorRank(min(existing.vectorRank(), rank),
								max(existing.similarity(), hit.similarity())));
				scores.merge(hit.chunkId(), 1.0 / (k + rank), Double::sum);
			}
		}
		for (List<RetrievedChunk> hits : keywordLists) {
			for (int i = 0; i < hits.size(); i++) {
				RetrievedChunk hit = hits.get(i);
				int rank = i + 1;
				byId.merge(hit.chunkId(), hit.withKeywordRank(rank),
						(existing, ignored) -> existing.withKeywordRank(min(existing.keywordRank(), rank)));
				scores.merge(hit.chunkId(), 1.0 / (k + rank), Double::sum);
			}
		}
		List<RetrievedChunk> fused = new ArrayList<>(byId.size());
		byId.forEach((id, chunk) -> fused.add(chunk.withScore(scores.get(id))));
		// Ties (same score) are broken deterministically: document order, so results are reproducible.
		fused.sort(Comparator.comparingDouble(RetrievedChunk::score)
			.reversed()
			.thenComparing(RetrievedChunk::documentName)
			.thenComparing(RetrievedChunk::chunkIndex)
			.thenComparing(RetrievedChunk::chunkId));
		return List.copyOf(fused.subList(0, Math.min(limit, fused.size())));
	}

	private static int min(Integer existing, int rank) {
		return (existing == null) ? rank : Math.min(existing, rank);
	}

	private static Double max(Double a, Double b) {
		if (a == null) {
			return b;
		}
		return (b == null) ? a : Math.max(a, b);
	}

}
