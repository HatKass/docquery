package dev.docquery.api.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two halves of hybrid retrieval. Both queries filter by {@code workspace_id} first: a chunk of
 * another workspace can never be a candidate, however similar it is.
 */
@Repository
class RetrievalRepository {

	private static final String CHUNK_COLUMNS = """
			c.id, c.document_id, d.filename, d.content_type, c.page_number, c.chunk_index, c.content""";

	private final JdbcClient jdbc;

	private final TransactionTemplate readOnly;

	RetrievalRepository(JdbcClient jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.readOnly = new TransactionTemplate(transactions.getTransactionManager());
		this.readOnly.setReadOnly(true);
	}

	/**
	 * Nearest chunks by cosine distance, among chunks embedded with {@code model} (vectors of different
	 * models are not comparable).
	 * <p>
	 * The expression {@code embedding::vector(n)} and the dimension predicate match the partial HNSW
	 * index of migration V4 (n = 768), so Postgres can use it. With the workspace filter, an HNSW scan
	 * could stop before finding enough rows of this workspace; pgvector's iterative scan keeps scanning
	 * until {@code limit} matching rows are found.
	 */
	List<RetrievedChunk> nearest(UUID workspaceId, float[] query, String model, int limit) {
		int dimensions = query.length;
		// The dimension is an int from the embedding model, never user input: safe to inline (the
		// index expression needs a literal type modifier).
		String vectorType = "vector(" + dimensions + ")";
		String sql = """
				SELECT %s, 1 - n.distance AS similarity
				  FROM (SELECT id, embedding::%s <=> CAST(:query AS %s) AS distance
				          FROM document_chunk
				         WHERE workspace_id = :workspaceId AND embedding_model = :model
				           AND embedding_dimensions = :dimensions
				         ORDER BY embedding::%s <=> CAST(:query AS %s)
				         LIMIT :limit) n
				  JOIN document_chunk c ON c.id = n.id
				  JOIN document d ON d.workspace_id = c.workspace_id AND d.id = c.document_id
				 ORDER BY n.distance, c.id
				""".formatted(CHUNK_COLUMNS, vectorType, vectorType, vectorType, vectorType);
		return this.readOnly.execute(status -> {
			this.jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
			return this.jdbc.sql(sql)
				.param("query", vectorLiteral(query))
				.param("workspaceId", workspaceId)
				.param("model", model)
				.param("dimensions", dimensions)
				.param("limit", limit)
				.query((rs, i) -> map(rs, rs.getDouble("similarity")))
				.list();
		});
	}

	/**
	 * Chunks of one document, most similar to {@code query} first (chunks of another embedding model,
	 * or all chunks when {@code query} is {@code null}, follow in document order). Used to give the
	 * model more of the document a question is about.
	 */
	List<RetrievedChunk> documentChunks(UUID workspaceId, UUID documentId, float[] query, String model, int limit) {
		String order;
		if (query != null) {
			String vectorType = "vector(" + query.length + ")";
			// The CASE guards the cast: it only runs for chunks with the query's dimension.
			order = """
					CASE WHEN c.embedding_model = :model AND c.embedding_dimensions = %d
					     THEN c.embedding::%s <=> CAST(:query AS %s) END NULLS LAST, c.chunk_index"""
				.formatted(query.length, vectorType, vectorType);
		}
		else {
			order = "c.chunk_index";
		}
		var statement = this.jdbc.sql("""
				SELECT %s
				  FROM document_chunk c
				  JOIN document d ON d.workspace_id = c.workspace_id AND d.id = c.document_id
				 WHERE c.workspace_id = :workspaceId AND c.document_id = :documentId
				 ORDER BY %s
				 LIMIT :limit
				""".formatted(CHUNK_COLUMNS, order))
			.param("workspaceId", workspaceId)
			.param("documentId", documentId)
			.param("limit", limit);
		if (query != null) {
			statement = statement.param("model", model).param("query", vectorLiteral(query));
		}
		return statement.query((rs, i) -> map(rs, null)).list();
	}

	/**
	 * Documents of the workspace whose file name shares a word with the query ("CV" in
	 * {@code Hatim_Kassim_CV.pdf}). The extension is ignored and English stop words never match.
	 */
	List<java.util.UUID> documentsNamedIn(UUID workspaceId, String query) {
		return this.jdbc.sql("""
				WITH q AS (
				    SELECT replace(plainto_tsquery('english', :query)::text, ' & ', ' | ')::tsquery AS query
				)
				SELECT d.id
				  FROM q, document d
				 WHERE d.workspace_id = :workspaceId
				   AND to_tsvector('english', regexp_replace(regexp_replace(d.filename, '\\.[^.]*$', ''),
				                                             '[^[:alnum:]]+', ' ', 'g')) @@ q.query
				""")
			.param("query", query)
			.param("workspaceId", workspaceId)
			.query(UUID.class)
			.list();
	}

	/**
	 * Best chunks by full-text search. The question's words are OR-ed (any shared word is a match) and
	 * ranked with {@code ts_rank_cd}, which rewards chunks containing more of them, close together.
	 * {@code plainto_tsquery} would AND them, which almost never matches a natural-language question.
	 */
	List<RetrievedChunk> keywordMatches(UUID workspaceId, String question, int limit) {
		return this.jdbc.sql("""
				WITH q AS (
				    SELECT replace(plainto_tsquery('english', :question)::text, ' & ', ' | ')::tsquery AS query
				)
				SELECT %s, ts_rank_cd(c.content_tsv, q.query) AS rank
				  FROM q, document_chunk c
				  JOIN document d ON d.workspace_id = c.workspace_id AND d.id = c.document_id
				 WHERE c.workspace_id = :workspaceId AND c.content_tsv @@ q.query
				 ORDER BY rank DESC, c.id
				 LIMIT :limit
				""".formatted(CHUNK_COLUMNS))
			.param("question", question)
			.param("workspaceId", workspaceId)
			.param("limit", limit)
			.query((rs, i) -> map(rs, null))
			.list();
	}

	private static RetrievedChunk map(ResultSet rs, Double similarity) throws SQLException {
		return new RetrievedChunk(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
				rs.getString("filename"), rs.getString("content_type"), rs.getObject("page_number", Integer.class),
				rs.getInt("chunk_index"), rs.getString("content"), similarity, null, null, 0);
	}

	/** pgvector's text form, e.g. {@code [0.1,-0.2,0.3]}. */
	static String vectorLiteral(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		return sb.append(']').toString();
	}

}
