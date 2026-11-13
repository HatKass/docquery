package dev.docquery.api.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic, offline embedding model: hashes words into a 768-dimension bag-of-words vector and
 * normalizes it, so texts sharing words are similar. Can be told to fail, to simulate an outage.
 * Same algorithm as the ingestion worker's stub, so query and chunk vectors are comparable.
 */
public class StubEmbeddingModel implements EmbeddingModel {

	public static final String MODEL = "stub-embedding";

	public static final int DIMENSIONS = 768;

	private final AtomicInteger calls = new AtomicInteger();

	private final AtomicInteger failuresLeft = new AtomicInteger();

	private final List<String> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** The next {@code count} calls throw, as if the provider were unreachable. */
	public void failNext(int count) {
		this.failuresLeft.set(count);
	}

	public void reset() {
		this.calls.set(0);
		this.failuresLeft.set(0);
		this.inputs.clear();
	}

	public int calls() {
		return this.calls.get();
	}

	public List<String> inputs() {
		return List.copyOf(this.inputs);
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		this.calls.incrementAndGet();
		if (this.failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			throw new IllegalStateException("Simulated embedding provider outage");
		}
		List<Embedding> results = new ArrayList<>();
		List<String> texts = request.getInstructions();
		for (int i = 0; i < texts.size(); i++) {
			this.inputs.add(texts.get(i));
			results.add(new Embedding(vector(texts.get(i)), i));
		}
		return new EmbeddingResponse(results);
	}

	@Override
	public float[] embed(Document document) {
		return vector(document.getText());
	}

	@Override
	public int dimensions() {
		return DIMENSIONS;
	}

	public static float[] vector(String text) {
		float[] vector = new float[DIMENSIONS];
		for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
			if (!word.isEmpty()) {
				CRC32 crc = new CRC32();
				crc.update(word.getBytes(StandardCharsets.UTF_8));
				vector[(int) (crc.getValue() % DIMENSIONS)] += 1f;
			}
		}
		double norm = 0;
		for (float v : vector) {
			norm += v * v;
		}
		if (norm == 0) {
			vector[0] = 1f;
			return vector;
		}
		float scale = (float) (1 / Math.sqrt(norm));
		for (int i = 0; i < vector.length; i++) {
			vector[i] *= scale;
		}
		return vector;
	}

}
