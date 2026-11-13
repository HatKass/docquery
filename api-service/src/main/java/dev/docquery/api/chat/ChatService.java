package dev.docquery.api.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.docquery.api.chat.ChatDtos.ConversationDetail;
import dev.docquery.api.chat.ChatDtos.ConversationSummary;
import dev.docquery.api.chat.ChatDtos.MessageResponse;
import dev.docquery.api.chat.ConversationRepository.NewMessage;
import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.retrieval.ChunkRetriever;
import dev.docquery.api.usage.QuotaService;
import dev.docquery.api.usage.UsageLedger;
import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceAccess;
import dev.docquery.api.workspace.WorkspaceRole;

/**
 * RAG chat use cases. Every one starts with a {@link WorkspaceAccess} check (any member, VIEWER and
 * up, may ask), and conversations are private to the user who started them.
 */
@Service
public class ChatService {

	private final ConversationRepository conversations;

	private final WorkspaceAccess access;

	private final ChunkRetriever retriever;

	private final ChatModel chatModel;

	private final ChatProperties properties;

	private final TransactionTemplate transactions;

	private final AsyncTaskExecutor executor;

	private final QueryRewriter rewriter;

	private final QuotaService quotas;

	private final UsageLedger ledger;

	private final ChatMetrics metrics;

	ChatService(ConversationRepository conversations, WorkspaceAccess access, ChunkRetriever retriever,
			ChatModel chatModel, ChatProperties properties, TransactionTemplate transactions,
			@Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor, QueryRewriter rewriter,
			QuotaService quotas, UsageLedger ledger, ChatMetrics metrics) {
		this.metrics = metrics;
		this.quotas = quotas;
		this.ledger = ledger;
		this.rewriter = rewriter;
		this.conversations = conversations;
		this.access = access;
		this.retriever = retriever;
		this.chatModel = chatModel;
		this.properties = properties;
		this.transactions = transactions;
		this.executor = executor;
	}

	public List<ConversationSummary> list(UUID workspaceId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		return this.conversations.findAll(workspaceId, user.id());
	}

	public ConversationSummary create(UUID workspaceId, String title, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		String name = (title == null || title.isBlank()) ? ConversationRepository.DEFAULT_TITLE
				: ConversationRepository.titleFrom(title);
		return this.conversations.insert(workspaceId, user.id(), name);
	}

	public ConversationDetail get(UUID workspaceId, UUID conversationId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		ConversationSummary conversation = this.conversations.find(workspaceId, user.id(), conversationId)
			.orElseThrow(() -> notFound(conversationId));
		return new ConversationDetail(conversation.id(), conversation.workspaceId(), conversation.title(),
				conversation.createdAt(), conversation.updatedAt(),
				this.conversations.findMessages(workspaceId, conversationId));
	}

	public void delete(UUID workspaceId, UUID conversationId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		if (!this.conversations.delete(workspaceId, user.id(), conversationId)) {
			throw notFound(conversationId);
		}
	}

	/**
	 * Stores the question, then streams the answer as Server-Sent Events (see {@link AnswerStream}).
	 * Access, the conversation and the question are checked before the stream opens, so those errors
	 * are ordinary ProblemDetail responses. The question counts against the asker's daily quota in the
	 * same transaction that stores it (429 once the quota is used up).
	 */
	public SseEmitter ask(UUID workspaceId, UUID conversationId, String question, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		String text = question.strip();
		if (text.length() > this.properties.maxQuestionLength()) {
			throw ApiException.unprocessable("question-too-long",
					"Questions can be at most " + this.properties.maxQuestionLength() + " characters long.");
		}
		MessageResponse stored = this.transactions.execute(status -> {
			this.conversations.lock(workspaceId, user.id(), conversationId).orElseThrow(() -> notFound(conversationId));
			this.quotas.countQuestion(user.id());
			this.conversations.touch(workspaceId, conversationId, text);
			return this.conversations.insertMessage(NewMessage.question(workspaceId, conversationId, text), List.of());
		});
		SseEmitter emitter = new SseEmitter(this.properties.streamTimeout().toMillis());
		AnswerStream stream = new AnswerStream(emitter, workspaceId, conversationId, user.id(), stored,
				this.conversations, this.retriever, this.rewriter, this.chatModel, this.properties, this.transactions,
				this.ledger, this.metrics);
		stream.start();
		this.executor.execute(stream::run);
		return emitter;
	}

	private static ApiException notFound(UUID conversationId) {
		return ApiException.notFound("conversation-not-found", "Conversation " + conversationId + " was not found.");
	}

}
