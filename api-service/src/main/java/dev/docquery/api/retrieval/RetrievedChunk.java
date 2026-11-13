package dev.docquery.api.retrieval;

import java.util.UUID;

/**
 * A chunk found for a query, with everything needed to show it to the model and to cite it.
 *
 * @param pageNumber 1-based page for PDFs; {@code null} for unpaginated formats
 * @param similarity cosine similarity to the query, if it was found by vector search
 * @param vectorRank 1-based rank in the vector results, or {@code null}
 * @param keywordRank 1-based rank in the full-text results, or {@code null}
 * @param score reciprocal rank fusion score (higher is better)
 */
public record RetrievedChunk(UUID chunkId, UUID documentId, String documentName, String contentType,
		Integer pageNumber, int chunkIndex, String content, Double similarity, Integer vectorRank,
		Integer keywordRank, double score) {

	RetrievedChunk withVectorRank(int rank, double similarity) {
		return new RetrievedChunk(this.chunkId, this.documentId, this.documentName, this.contentType, this.pageNumber,
				this.chunkIndex, this.content, similarity, rank, this.keywordRank, this.score);
	}

	RetrievedChunk withKeywordRank(int rank) {
		return new RetrievedChunk(this.chunkId, this.documentId, this.documentName, this.contentType, this.pageNumber,
				this.chunkIndex, this.content, this.similarity, this.vectorRank, rank, this.score);
	}

	RetrievedChunk withScore(double score) {
		return new RetrievedChunk(this.chunkId, this.documentId, this.documentName, this.contentType, this.pageNumber,
				this.chunkIndex, this.content, this.similarity, this.vectorRank, this.keywordRank, score);
	}

}
