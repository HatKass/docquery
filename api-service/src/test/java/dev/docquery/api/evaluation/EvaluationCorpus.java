package dev.docquery.api.evaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.support.ApiClient;
import dev.docquery.api.support.TestUser;

/**
 * Loads the evaluation documents into a workspace the way an upload would: the files are uploaded
 * through the API, and their chunks (from {@code /evaluation/fixtures/sample-data-chunks.json}, made by
 * the ingestion worker's real extractor and chunker) are embedded with the configured embedding model
 * and stored as the worker stores them.
 */
final class EvaluationCorpus {

	static final Path CHUNK_FIXTURE = EvaluationSet.REPO.resolve("evaluation")
		.resolve("fixtures")
		.resolve("sample-data-chunks.json");

	private static final int BATCH_SIZE = 32;

	private final ApiClient api;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final EmbeddingModel embeddingModel;

	private final String embeddingModelName;

	private final String documentPrefix;

	/**
	 * @param embeddingModelName recorded with each chunk; must be the model retrieval compares with
	 * @param documentPrefix task prefix the worker adds before embedding a chunk (e.g. nomic-embed-text's
	 * {@code "search_document: "})
	 */
	EvaluationCorpus(ApiClient api, JdbcClient jdbc, JsonMapper json, EmbeddingModel embeddingModel,
			String embeddingModelName, String documentPrefix) {
		this.api = api;
		this.jdbc = jdbc;
		this.json = json;
		this.embeddingModel = embeddingModel;
		this.embeddingModelName = embeddingModelName;
		this.documentPrefix = documentPrefix;
	}

	/** Uploads and indexes every evaluation document; returns document ids by file name. */
	Map<String, UUID> load(TestUser owner, UUID workspaceId) {
		JsonNode fixture = this.json.readTree(read(CHUNK_FIXTURE));
		Map<String, UUID> documents = new LinkedHashMap<>();
		for (JsonNode document : fixture.get("documents")) {
			String file = document.get("file").asString();
			byte[] content = readBytes(EvaluationSet.REPO.resolve(document.get("path").asString()));
			UUID id = this.api.uploadDocument(owner, workspaceId, file, content);
			JsonNode pageCount = document.get("pageCount");
			index(workspaceId, id, document.get("chunks"),
					(pageCount == null || pageCount.isNull()) ? null : pageCount.asInt());
			documents.put(file, id);
		}
		return documents;
	}

	private void index(UUID workspaceId, UUID documentId, JsonNode chunks, Integer pageCount) {
		List<JsonNode> all = new ArrayList<>();
		chunks.forEach(all::add);
		List<float[]> vectors = embed(all.stream().map(c -> this.documentPrefix + c.get("content").asString()).toList());
		for (int i = 0; i < all.size(); i++) {
			JsonNode chunk = all.get(i);
			JsonNode page = chunk.get("page");
			this.jdbc.sql("""
					INSERT INTO document_chunk (workspace_id, document_id, chunk_index, page_number, start_offset,
					                            end_offset, content, embedding, embedding_model, embedding_dimensions)
					VALUES (:ws, :doc, :index, :page, :start, :end, :content, CAST(:embedding AS vector), :model, :dims)
					""")
				.param("ws", workspaceId)
				.param("doc", documentId)
				.param("index", chunk.get("index").asInt())
				.param("page", (page == null || page.isNull()) ? null : page.asInt(), java.sql.Types.INTEGER)
				.param("start", chunk.get("start").asInt())
				.param("end", chunk.get("end").asInt())
				.param("content", chunk.get("content").asString())
				.param("embedding", literal(vectors.get(i)))
				.param("model", this.embeddingModelName)
				.param("dims", vectors.get(i).length)
				.update();
		}
		this.jdbc.sql("""
				UPDATE document SET status = 'READY', failure_reason = NULL, chunk_count = :count,
				       page_count = :pages, status_changed_at = now()
				 WHERE workspace_id = :ws AND id = :doc
				""")
			.param("count", all.size())
			.param("pages", pageCount, java.sql.Types.INTEGER)
			.param("ws", workspaceId)
			.param("doc", documentId)
			.update();
	}

	/** In batches, without request options (like the worker), so provider settings such as keep_alive apply. */
	private List<float[]> embed(List<String> texts) {
		List<float[]> vectors = new ArrayList<>();
		for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
			List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
			EmbeddingResponse response = this.embeddingModel.call(new EmbeddingRequest(batch, null));
			List<Embedding> results = new ArrayList<>(response.getResults());
			results.sort(java.util.Comparator.comparing(Embedding::getIndex));
			results.forEach(r -> vectors.add(r.getOutput()));
		}
		if (vectors.size() != texts.size()) {
			throw new IllegalStateException("Expected " + texts.size() + " vectors, got " + vectors.size());
		}
		return vectors;
	}

	private static String literal(float[] vector) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < vector.length; i++) {
			sb.append(i == 0 ? "" : ",").append(vector[i]);
		}
		return sb.append(']').toString();
	}

	private static String read(Path file) {
		return new String(readBytes(file), java.nio.charset.StandardCharsets.UTF_8);
	}

	private static byte[] readBytes(Path file) {
		try {
			return Files.readAllBytes(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
