package dev.docquery.worker.embedding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.util.StringUtils;

import dev.docquery.worker.chunking.Chunk;

/**
 * Embeds chunks in batches through Spring AI's provider-neutral {@link EmbeddingModel} (Ollama
 * locally, OpenAI or another provider in production; chosen by configuration only).
 * <p>
 * Token usage is taken from the provider's response; when a provider reports none, it is estimated
 * at 4 characters per token and flagged as estimated.
 */
public class ChunkEmbedder {

	private final EmbeddingModel embeddingModel;

	private final String modelName;

	private final int batchSize;

	private final String documentPrefix;

	/**
	 * @param modelName the configured model, recorded with each chunk
	 * @param documentPrefix prepended to every chunk before embedding (task prefix some models
	 * expect, e.g. {@code "search_document: "} for nomic-embed-text); empty for none. Stored chunk
	 * text never contains it.
	 */
	public ChunkEmbedder(EmbeddingModel embeddingModel, String modelName, int batchSize, String documentPrefix) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("Batch size must be positive");
		}
		if (!StringUtils.hasText(modelName)) {
			throw new IllegalArgumentException("The embedding model name must be configured");
		}
		this.embeddingModel = embeddingModel;
		this.modelName = modelName;
		this.batchSize = batchSize;
		this.documentPrefix = (documentPrefix != null) ? documentPrefix : "";
	}

	public String modelName() {
		return this.modelName;
	}

	public EmbeddedChunks embed(List<Chunk> chunks) {
		List<float[]> vectors = new ArrayList<>(chunks.size());
		long tokens = 0;
		boolean estimated = false;
		int requests = 0;
		for (int from = 0; from < chunks.size(); from += this.batchSize) {
			List<String> inputs = chunks.subList(from, Math.min(from + this.batchSize, chunks.size()))
				.stream()
				.map(chunk -> this.documentPrefix + chunk.content())
				.toList();
			// No request options (not embedForResponse): the provider then applies all of its configured
			// options. Spring AI's generic options would drop Ollama-specific ones such as keep_alive.
			EmbeddingResponse response = this.embeddingModel.call(new EmbeddingRequest(inputs, null));
			requests++;
			List<Embedding> results = new ArrayList<>(response.getResults());
			if (results.size() != inputs.size()) {
				throw new IllegalStateException("The embedding provider returned " + results.size()
						+ " vectors for " + inputs.size() + " inputs");
			}
			results.sort(Comparator.comparing(Embedding::getIndex, Comparator.nullsLast(Comparator.naturalOrder())));
			results.forEach(result -> vectors.add(result.getOutput()));
			Integer reported = reportedTokens(response);
			if (reported != null) {
				tokens += reported;
			}
			else {
				estimated = true;
				tokens += inputs.stream().mapToLong(ChunkEmbedder::estimateTokens).sum();
			}
		}
		int dimensions = vectors.isEmpty() ? 0 : vectors.getFirst().length;
		for (float[] vector : vectors) {
			if (vector == null || vector.length == 0 || vector.length != dimensions) {
				throw new IllegalStateException("The embedding provider returned vectors of inconsistent dimensions");
			}
		}
		return new EmbeddedChunks(vectors, this.modelName, dimensions, tokens, estimated, requests);
	}

	private static Integer reportedTokens(EmbeddingResponse response) {
		if (response.getMetadata() == null) {
			return null;
		}
		Usage usage = response.getMetadata().getUsage();
		if (usage == null) {
			return null;
		}
		Integer prompt = usage.getPromptTokens();
		return (prompt != null && prompt > 0) ? prompt : null;
	}

	static long estimateTokens(String text) {
		return Math.max(1, (text.length() + 3) / 4);
	}

}
