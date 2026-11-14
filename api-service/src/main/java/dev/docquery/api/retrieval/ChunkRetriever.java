package dev.docquery.api.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval: pgvector similarity and PostgreSQL full-text search, merged by reciprocal rank
 * fusion (ADR 0011). Vector search finds paraphrases ("time off" ≈ "vacation"); full-text search finds
 * exact terms the embedding blurs (product codes, names, numbers).
 * <p>
 * Callers must have checked workspace access; every query here is scoped by the workspace id.
 * <p>
 * If the embedding provider is unavailable, retrieval degrades to full-text search only instead of
 * failing: a slightly worse answer beats no answer.
 * <p>
 * Every retrieval is a {@code docquery.retrieval} observation (span + timer, tagged search or answer).
 */
@Service
public class ChunkRetriever {

	private static final Logger log = LoggerFactory.getLogger(ChunkRetriever.class);

	private final RetrievalRepository repository;

	private final EmbeddingModel embeddingModel;

	private final RetrievalProperties properties;

	private final ObservationRegistry observations;

	ChunkRetriever(RetrievalRepository repository, EmbeddingModel embeddingModel, RetrievalProperties properties,
			ObservationRegistry observations) {
		this.repository = repository;
		this.embeddingModel = embeddingModel;
		this.properties = properties;
		this.observations = observations;
	}

	/** Plain hybrid search for one query (the search endpoint). */
	public List<RetrievedChunk> retrieve(UUID workspaceId, String query, int limit) {
		return observe("search", () -> {
			Search search = search(workspaceId, List.of(query));
			return RankFusion.fuseAll(search.vectorLists(), search.keywordLists(), this.properties.rrfK(), limit);
		});
	}

	/**
	 * The sources for an answer:
	 * <ol>
	 * <li>hybrid search for every query (the question as asked and, for follow-ups, its standalone
	 * rewrite), all lists fused together, best {@code maxSources} kept;</li>
	 * <li>if the queries name exactly one document by file name ("this CV"), or (when allowed) most
	 * sources come from one document, the question is about that document: up to
	 * {@code focusExtraChunks} more of its chunks are added, most similar first;</li>
	 * <li>sources beyond {@code maxContextCharacters} are dropped, so everything fits the model's context.</li>
	 * </ol>
	 */
	public List<RetrievedChunk> retrieveForAnswer(UUID workspaceId, List<String> queries) {
		return retrieveForAnswer(workspaceId, queries, true);
	}

	/**
	 * @param widenByMajority whether a document that most sources come from may be widened too (for
	 * evaluative questions); a document the queries name by file name is always widened
	 */
	public List<RetrievedChunk> retrieveForAnswer(UUID workspaceId, List<String> queries, boolean widenByMajority) {
		return observe("answer", () -> sourcesForAnswer(workspaceId, queries, widenByMajority));
	}

	private List<RetrievedChunk> observe(String kind, Supplier<List<RetrievedChunk>> retrieval) {
		Observation observation = Observation.createNotStarted("docquery.retrieval", this.observations)
			.contextualName("retrieval " + kind)
			.lowCardinalityKeyValue(KeyValue.of("kind", kind));
		return observation.observe(() -> {
			List<RetrievedChunk> found = retrieval.get();
			observation.highCardinalityKeyValue(KeyValue.of("docquery.retrieval.sources", Integer.toString(found.size())));
			return found;
		});
	}

	private List<RetrievedChunk> sourcesForAnswer(UUID workspaceId, List<String> queries, boolean widenByMajority) {
		List<String> distinct = List.copyOf(new LinkedHashSet<>(queries));
		Search search = search(workspaceId, distinct);
		List<RetrievedChunk> fused = RankFusion.fuseAll(search.vectorLists(), search.keywordLists(),
				this.properties.rrfK(), this.properties.maxSources());
		List<RetrievedChunk> sources = new ArrayList<>(fused);
		// A document the question names ("this CV" → Hatim_Kassim_CV.pdf) is the target even if the
		// passages' wording did not match; otherwise the document most sources come from, if any.
		UUID named = namedDocument(workspaceId, distinct);
		UUID focused = (named != null) ? named : (widenByMajority ? focusedDocument(fused) : null);
		if (focused != null && this.properties.focusExtraChunks() > 0) {
			Set<UUID> present = new HashSet<>();
			fused.forEach(c -> present.add(c.chunkId()));
			this.repository
				.documentChunks(workspaceId, focused, search.firstVector(), this.properties.embeddingModel(),
						this.properties.focusExtraChunks() + present.size())
				.stream()
				.filter(c -> !present.contains(c.chunkId()))
				.limit(this.properties.focusExtraChunks())
				.forEach(sources::add);
		}
		return withinBudget(sources, this.properties.maxContextCharacters());
	}

	/** The one document whose file name the queries mention; {@code null} if none or several do. */
	private UUID namedDocument(UUID workspaceId, List<String> queries) {
		Set<UUID> named = new HashSet<>();
		queries.forEach(query -> named.addAll(this.repository.documentsNamedIn(workspaceId, query)));
		return (named.size() == 1) ? named.iterator().next() : null;
	}

	/** The document holding at least {@code focusMinShare} of the sources, if any. */
	UUID focusedDocument(List<RetrievedChunk> fused) {
		if (fused.isEmpty()) {
			return null;
		}
		Map<UUID, Integer> counts = new LinkedHashMap<>();
		fused.forEach(c -> counts.merge(c.documentId(), 1, Integer::sum));
		return counts.entrySet()
			.stream()
			.filter(e -> e.getValue() >= Math.ceil(this.properties.focusMinShare() * fused.size()))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(null);
	}

	/** Keeps sources in order while their text fits {@code maxCharacters}; always keeps the first. */
	static List<RetrievedChunk> withinBudget(List<RetrievedChunk> sources, int maxCharacters) {
		List<RetrievedChunk> kept = new ArrayList<>();
		int used = 0;
		for (RetrievedChunk source : sources) {
			int length = source.content().length();
			if (!kept.isEmpty() && used + length > maxCharacters) {
				continue; // a shorter later source may still fit
			}
			kept.add(source);
			used += length;
		}
		return List.copyOf(kept);
	}

	private Search search(UUID workspaceId, List<String> queries) {
		List<List<RetrievedChunk>> vectorLists = new ArrayList<>();
		List<List<RetrievedChunk>> keywordLists = new ArrayList<>();
		float[] firstVector = null;
		for (String query : queries) {
			float[] vector = embed(query);
			if (vector != null) {
				firstVector = (firstVector != null) ? firstVector : vector;
				vectorLists.add(this.repository
					.nearest(workspaceId, vector, this.properties.embeddingModel(), this.properties.vectorCandidates())
					.stream()
					.filter(hit -> hit.similarity() >= this.properties.minSimilarity())
					.toList());
			}
			keywordLists
				.add(this.repository.keywordMatches(workspaceId, query, this.properties.keywordCandidates()));
		}
		return new Search(vectorLists, keywordLists, firstVector);
	}

	private float[] embed(String query) {
		try {
			// No request options (not embed(String)): the provider then applies all of its configured
			// options. Spring AI's generic options would drop Ollama-specific ones such as keep_alive.
			EmbeddingResponse response = this.embeddingModel
				.call(new EmbeddingRequest(List.of(this.properties.queryPrefix() + query), null));
			float[] vector = (response.getResult() != null) ? response.getResult().getOutput() : null;
			return (vector == null || vector.length == 0) ? null : vector;
		}
		catch (RuntimeException ex) {
			log.warn("Query embedding failed, falling back to full-text search only: {}", ex.getMessage());
			return null;
		}
	}

	private record Search(List<List<RetrievedChunk>> vectorLists, List<List<RetrievedChunk>> keywordLists,
			float[] firstVector) {
	}

}
