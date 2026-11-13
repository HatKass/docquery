package dev.docquery.api.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Hybrid retrieval settings (see ADR 0011).
 *
 * @param embeddingModel the model questions are embedded with; must be the model the ingestion worker
 * embeds chunks with, since only chunks of this model are compared by vector similarity
 * @param queryPrefix task prefix some embedding models expect before a search query
 * ({@code "search_query: "} for nomic-embed-text); empty for none
 * @param vectorCandidates nearest chunks fetched by vector similarity before fusion
 * @param keywordCandidates best chunks fetched by full-text search before fusion
 * @param maxSources chunks kept after fusion (what the model sees and can cite)
 * @param minSimilarity vector matches below this cosine similarity are dropped as unrelated; keyword
 * matches always count, because they share at least one word with the question
 * @param rrfK reciprocal rank fusion constant: score = Σ 1 / (k + rank). 60 is the usual choice; a
 * larger k flattens the difference between the top ranks
 * @param focusExtraChunks when a question targets one document (see {@code focusMinShare}), up to this
 * many more of its chunks are added, so summaries and assessments see more than a few passages; 0
 * disables it
 * @param focusMinShare a document is the question's target when at least this share of the fused
 * sources comes from it
 * @param maxContextCharacters total characters of source text sent to the model; sources beyond it are
 * dropped (fused order first). Keeps rules, sources, history and the answer inside the model's
 * context window (≈ 4 characters per token)
 */
@ConfigurationProperties("docquery.retrieval")
public record RetrievalProperties(String embeddingModel, @DefaultValue("") String queryPrefix,
		@DefaultValue("20") int vectorCandidates, @DefaultValue("20") int keywordCandidates,
		@DefaultValue("5") int maxSources, @DefaultValue("0.6") double minSimilarity, @DefaultValue("60") int rrfK,
		@DefaultValue("4") int focusExtraChunks, @DefaultValue("0.6") double focusMinShare,
		@DefaultValue("8000") int maxContextCharacters) {

	public RetrievalProperties {
		if (embeddingModel == null || embeddingModel.isBlank()) {
			throw new IllegalArgumentException("docquery.retrieval.embedding-model must be configured");
		}
		if (vectorCandidates < 1 || keywordCandidates < 1 || maxSources < 1 || rrfK < 1) {
			throw new IllegalArgumentException("Retrieval candidate counts, max-sources and rrf-k must be positive");
		}
		if (focusExtraChunks < 0 || focusMinShare <= 0 || focusMinShare > 1 || maxContextCharacters < 1) {
			throw new IllegalArgumentException("Invalid focus or context settings for docquery.retrieval");
		}
		queryPrefix = (queryPrefix != null) ? queryPrefix : "";
	}

}
