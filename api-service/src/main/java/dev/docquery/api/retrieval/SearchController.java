package dev.docquery.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceAccess;
import dev.docquery.api.workspace.WorkspaceRole;

/**
 * Search without generation: the chunks the chat would see for a query, with how each was found.
 * Useful on its own and to inspect retrieval quality (RAG evaluation, Phase 8).
 */
@RestController
class SearchController {

	/**
	 * @param page 1-based page, {@code null} for unpaginated documents
	 * @param similarity cosine similarity if found by vector search
	 * @param vectorRank rank among vector results, {@code null} if not found that way
	 * @param keywordRank rank among full-text results, {@code null} if not found that way
	 */
	record SearchHit(UUID documentId, String documentName, String contentType, Integer page, String snippet,
			double score, Double similarity, Integer vectorRank, Integer keywordRank) {
	}

	private final ChunkRetriever retriever;

	private final WorkspaceAccess access;

	SearchController(ChunkRetriever retriever, WorkspaceAccess access) {
		this.retriever = retriever;
		this.access = access;
	}

	@GetMapping("/api/workspaces/{workspaceId}/search")
	List<SearchHit> search(@PathVariable UUID workspaceId, @RequestParam("q") String query,
			@RequestParam(defaultValue = "10") int limit, CurrentUser user) {
		// Access first: a non-member gets 404 whatever the parameters.
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		if (query.isBlank() || query.length() > 2000) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-query", "Invalid request",
					"The search query must have 1 to 2000 characters.");
		}
		if (limit < 1 || limit > 50) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-limit", "Invalid request",
					"The limit must be between 1 and 50.");
		}
		return this.retriever.retrieve(workspaceId, query.strip(), limit)
			.stream()
			.map(c -> new SearchHit(c.documentId(), c.documentName(), c.contentType(), c.pageNumber(), c.content(),
					c.score(), c.similarity(), c.vectorRank(), c.keywordRank()))
			.toList();
	}

}
