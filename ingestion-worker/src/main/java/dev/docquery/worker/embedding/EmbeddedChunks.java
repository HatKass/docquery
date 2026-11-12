package dev.docquery.worker.embedding;

import java.util.List;

/**
 * Vectors for a document's chunks (same order as the chunks), plus what it cost.
 *
 * @param model embedding model name, stored with every chunk so a model change can be detected
 * @param dimensions vector length, identical for all vectors
 * @param inputTokens tokens sent to the provider
 * @param tokensEstimated {@code true} if the provider did not report usage and tokens were estimated
 * @param requestCount number of (batched) provider calls
 */
public record EmbeddedChunks(List<float[]> vectors, String model, int dimensions, long inputTokens,
		boolean tokensEstimated, int requestCount) {

	public EmbeddedChunks {
		vectors = List.copyOf(vectors);
	}

}
