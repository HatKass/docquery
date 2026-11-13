package dev.docquery.api.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.docquery.api.chat.ChatDtos.CitationResponse;
import dev.docquery.api.chat.ChatDtos.ConversationSummary;
import dev.docquery.api.chat.ChatDtos.MessageResponse;
import dev.docquery.api.chat.ChatDtos.MessageStatus;
import dev.docquery.api.chat.ChatDtos.Role;
import dev.docquery.api.chat.GroundedPrompt.HistoryMessage;

/**
 * Conversations, messages and citations. Conversations are private: every query that a request can
 * trigger is filtered by workspace <em>and</em> owner, so another member's conversation id (or one
 * from another workspace) matches nothing.
 */
@Repository
class ConversationRepository {

	static final String DEFAULT_TITLE = "New conversation";

	/** An answer (or question) to store. Token fields are only set for answers. */
	record NewMessage(UUID id, UUID workspaceId, UUID conversationId, Role role, String content, MessageStatus status,
			String model, Integer promptTokens, Integer completionTokens, Boolean tokensEstimated, String searchQuery) {

		static NewMessage question(UUID workspaceId, UUID conversationId, String content) {
			return new NewMessage(UUID.randomUUID(), workspaceId, conversationId, Role.USER, content,
					MessageStatus.COMPLETE, null, null, null, null, null);
		}

	}

	private static final String SELECT_CONVERSATION = """
			SELECT id, workspace_id, title, created_at, updated_at FROM conversation
			 WHERE workspace_id = :workspaceId AND user_id = :userId""";

	private final JdbcClient jdbc;

	ConversationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	ConversationSummary insert(UUID workspaceId, UUID userId, String title) {
		return this.jdbc.sql("""
				INSERT INTO conversation (workspace_id, user_id, title) VALUES (:workspaceId, :userId, :title)
				RETURNING id, workspace_id, title, created_at, updated_at
				""")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.param("title", title)
			.query(ConversationRepository::mapConversation)
			.single();
	}

	List<ConversationSummary> findAll(UUID workspaceId, UUID userId) {
		return this.jdbc.sql(SELECT_CONVERSATION + " ORDER BY updated_at DESC, id")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.query(ConversationRepository::mapConversation)
			.list();
	}

	Optional<ConversationSummary> find(UUID workspaceId, UUID userId, UUID conversationId) {
		return this.jdbc.sql(SELECT_CONVERSATION + " AND id = :id")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.param("id", conversationId)
			.query(ConversationRepository::mapConversation)
			.optional();
	}

	/** Like {@link #find}, but row-locks the conversation: questions in one conversation are serialized. */
	Optional<ConversationSummary> lock(UUID workspaceId, UUID userId, UUID conversationId) {
		return this.jdbc.sql(SELECT_CONVERSATION + " AND id = :id FOR UPDATE")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.param("id", conversationId)
			.query(ConversationRepository::mapConversation)
			.optional();
	}

	boolean delete(UUID workspaceId, UUID userId, UUID conversationId) {
		return this.jdbc
			.sql("DELETE FROM conversation WHERE workspace_id = :workspaceId AND user_id = :userId AND id = :id")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.param("id", conversationId)
			.update() == 1;
	}

	/** Marks activity; an untitled conversation without messages is named after its first question. */
	void touch(UUID workspaceId, UUID conversationId, String firstQuestion) {
		this.jdbc.sql("""
				UPDATE conversation c
				   SET updated_at = now(),
				       title = CASE WHEN c.title = :defaultTitle
				                     AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.conversation_id = c.id)
				                    THEN :title ELSE c.title END
				 WHERE c.workspace_id = :workspaceId AND c.id = :id
				""")
			.param("defaultTitle", DEFAULT_TITLE)
			.param("title", titleFrom(firstQuestion))
			.param("workspaceId", workspaceId)
			.param("id", conversationId)
			.update();
	}

	MessageResponse insertMessage(NewMessage message, List<CitationResponse> citations) {
		Instant createdAt = this.jdbc.sql("""
				INSERT INTO chat_message (id, workspace_id, conversation_id, role, content, status, model,
				                          prompt_tokens, completion_tokens, tokens_estimated, search_query)
				VALUES (:id, :workspaceId, :conversationId, :role, :content, :status, :model,
				        :promptTokens, :completionTokens, :tokensEstimated, :searchQuery)
				RETURNING created_at
				""")
			.param("id", message.id())
			.param("workspaceId", message.workspaceId())
			.param("conversationId", message.conversationId())
			.param("role", message.role().name())
			.param("content", message.content())
			.param("status", message.status().name())
			.param("model", message.model(), Types.VARCHAR)
			.param("promptTokens", message.promptTokens(), Types.INTEGER)
			.param("completionTokens", message.completionTokens(), Types.INTEGER)
			.param("tokensEstimated", message.tokensEstimated(), Types.BOOLEAN)
			.param("searchQuery", message.searchQuery(), Types.VARCHAR)
			.query(OffsetDateTime.class)
			.single()
			.toInstant();
		for (CitationResponse citation : citations) {
			this.jdbc.sql("""
					INSERT INTO chat_citation (message_id, source_number, workspace_id, document_id, document_name,
					                           content_type, page_number, snippet)
					VALUES (:messageId, :sourceNumber, :workspaceId, :documentId, :documentName, :contentType,
					        :pageNumber, :snippet)
					""")
				.param("messageId", message.id())
				.param("sourceNumber", citation.sourceNumber())
				.param("workspaceId", message.workspaceId())
				.param("documentId", citation.documentId(), Types.OTHER)
				.param("documentName", citation.documentName())
				.param("contentType", citation.contentType())
				.param("pageNumber", citation.pageNumber(), Types.INTEGER)
				.param("snippet", citation.snippet())
				.update();
		}
		return new MessageResponse(message.id(), message.role(), message.content(), message.status(), createdAt,
				List.copyOf(citations));
	}

	/** All messages of a conversation, oldest first, each with its citations. */
	List<MessageResponse> findMessages(UUID workspaceId, UUID conversationId) {
		Map<UUID, List<CitationResponse>> citations = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT ci.message_id, ci.source_number, ci.document_id, ci.document_name, ci.content_type,
				       ci.page_number, ci.snippet
				  FROM chat_citation ci
				  JOIN chat_message m ON m.id = ci.message_id
				 WHERE m.workspace_id = :workspaceId AND m.conversation_id = :conversationId
				   AND ci.workspace_id = :workspaceId
				 ORDER BY ci.message_id, ci.source_number
				""")
			.param("workspaceId", workspaceId)
			.param("conversationId", conversationId)
			.query((ResultSet rs) -> {
				citations.computeIfAbsent(rs.getObject("message_id", UUID.class), id -> new ArrayList<>())
					.add(new CitationResponse(rs.getInt("source_number"), rs.getObject("document_id", UUID.class),
							rs.getString("document_name"), rs.getString("content_type"),
							rs.getObject("page_number", Integer.class), rs.getString("snippet")));
			});
		return this.jdbc.sql("""
				SELECT id, role, content, status, created_at FROM chat_message
				 WHERE workspace_id = :workspaceId AND conversation_id = :conversationId
				 ORDER BY created_at, id
				""")
			.param("workspaceId", workspaceId)
			.param("conversationId", conversationId)
			.query((rs, i) -> {
				UUID id = rs.getObject("id", UUID.class);
				return new MessageResponse(id, Role.valueOf(rs.getString("role")), rs.getString("content"),
						MessageStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
						List.copyOf(citations.getOrDefault(id, List.of())));
			})
			.list();
	}

	/**
	 * The last {@code limit} complete messages before {@code beforeMessageId}, oldest first. A question
	 * whose answer failed is left out with it, so the history always alternates question / answer.
	 */
	List<HistoryMessage> history(UUID workspaceId, UUID conversationId, UUID beforeMessageId, int limit) {
		if (limit == 0) {
			return List.of();
		}
		List<HistoryMessage> newestFirst = this.jdbc.sql("""
				SELECT q.content AS question, a.content AS answer
				  FROM chat_message a
				  JOIN LATERAL (SELECT content, created_at, id FROM chat_message q
				                 WHERE q.conversation_id = a.conversation_id AND q.role = 'USER'
				                   AND (q.created_at, q.id) < (a.created_at, a.id)
				                 ORDER BY q.created_at DESC, q.id DESC LIMIT 1) q ON true
				 WHERE a.workspace_id = :workspaceId AND a.conversation_id = :conversationId
				   AND a.role = 'ASSISTANT' AND a.status = 'COMPLETE'
				   AND (a.created_at, a.id) < (SELECT created_at, id FROM chat_message WHERE id = :before)
				 ORDER BY a.created_at DESC, a.id DESC
				 LIMIT :pairs
				""")
			.param("workspaceId", workspaceId)
			.param("conversationId", conversationId)
			.param("before", beforeMessageId)
			.param("pairs", Math.max(1, limit / 2))
			.query((rs, i) -> List.of(new HistoryMessage(false, rs.getString("answer")),
					new HistoryMessage(true, rs.getString("question"))))
			.list()
			.stream()
			.flatMap(List::stream)
			.toList();
		return newestFirst.reversed();
	}

	static String titleFrom(String question) {
		String oneLine = question.strip().replaceAll("\\s+", " ");
		return (oneLine.length() <= 80) ? oneLine : oneLine.substring(0, 79).strip() + "…";
	}

	private static ConversationSummary mapConversation(ResultSet rs, int rowNum) throws SQLException {
		return new ConversationSummary(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
				rs.getString("title"), instant(rs, "created_at"), instant(rs, "updated_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

}
