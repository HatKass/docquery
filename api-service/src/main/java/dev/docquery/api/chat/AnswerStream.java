package dev.docquery.api.chat;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

import dev.docquery.api.chat.ChatDtos.CitationResponse;
import dev.docquery.api.chat.ChatDtos.MessageResponse;
import dev.docquery.api.chat.ChatDtos.MessageStatus;
import dev.docquery.api.chat.ChatDtos.Role;
import dev.docquery.api.chat.ChatDtos.StreamDelta;
import dev.docquery.api.chat.ChatDtos.StreamDone;
import dev.docquery.api.chat.ChatDtos.StreamError;
import dev.docquery.api.chat.ChatDtos.StreamStart;
import dev.docquery.api.chat.ConversationRepository.NewMessage;
import dev.docquery.api.retrieval.ChunkRetriever;
import dev.docquery.api.retrieval.RetrievedChunk;

/**
 * One answer, streamed as Server-Sent Events: {@code start}, then {@code delta}s as the model
 * produces text, then exactly one of {@code done} or {@code error}.
 * <p>
 * {@link #run()} executes on a (virtual) worker thread and consumes the model's stream blocking, so
 * all writes to the client happen on that thread and never on the HTTP client's event loop. If the
 * browser goes away or the stream times out, the model call is cancelled (no tokens are wasted on an
 * answer nobody reads) and the partial answer is stored as FAILED. Whatever happens, the answer is
 * stored exactly once.
 */
final class AnswerStream {

	private static final Logger log = LoggerFactory.getLogger(AnswerStream.class);

	static final String START = "start";

	static final String DELTA = "delta";

	static final String DONE = "done";

	static final String ERROR = "error";

	private final SseEmitter emitter;

	private final UUID workspaceId;

	private final UUID conversationId;

	private final MessageResponse question;

	private final UUID answerId = UUID.randomUUID();

	private final ConversationRepository conversations;

	private final ChunkRetriever retriever;

	private final QueryRewriter rewriter;

	private final ChatModel chatModel;

	private final ChatProperties properties;

	private final TransactionTemplate transactions;

	/** Completes the model's stream early: client gone or timed out. */
	private final Sinks.Empty<Void> cancel = Sinks.empty();

	private final StringBuilder answer = new StringBuilder();

	private volatile boolean cancelled;

	/** Guarded by {@code this}. */
	private boolean finished;

	private String reportedModel;

	private Integer promptTokens;

	private Integer completionTokens;

	/** What retrieval searched for (the rewritten query for follow-ups), stored with the answer. */
	private String searchQuery;

	/** Usage of the query-rewriting call, added to the answer's totals. */
	private QueryRewriter.Rewrite rewrite;

	/** Characters sent to the model, for a token estimate when the provider reports no usage. */
	private int promptCharacters;

	AnswerStream(SseEmitter emitter, UUID workspaceId, UUID conversationId, MessageResponse question,
			ConversationRepository conversations, ChunkRetriever retriever, QueryRewriter rewriter, ChatModel chatModel,
			ChatProperties properties, TransactionTemplate transactions) {
		this.rewriter = rewriter;
		this.emitter = emitter;
		this.workspaceId = workspaceId;
		this.conversationId = conversationId;
		this.question = question;
		this.conversations = conversations;
		this.retriever = retriever;
		this.chatModel = chatModel;
		this.properties = properties;
		this.transactions = transactions;
	}

	/** Registers the lifecycle callbacks and queues the {@code start} event (sent once the response opens). */
	void start() {
		this.emitter.onTimeout(() -> {
			cancelModel();
			fail("The answer took too long and was stopped. Try a shorter or more specific question.", true);
		});
		this.emitter.onError(error -> {
			cancelModel();
			fail("The answer was interrupted.", false);
		});
		this.emitter.onCompletion(this::cancelModel);
		send(START, new StreamStart(this.question, this.answerId));
	}

	void run() {
		try {
			List<GroundedPrompt.HistoryMessage> history = this.conversations.history(this.workspaceId,
					this.conversationId, this.question.id(), this.properties.historyMessages());
			// Follow-ups ("what about this CV?") are rewritten into a standalone query; both the original
			// question and the rewrite are searched, and the answer still uses the original question.
			this.rewrite = this.rewriter.rewrite(this.question.content(), history);
			this.searchQuery = this.rewrite.query();
			if (this.rewrite.rewritten()) {
				log.info("Conversation {}: searching for \"{}\" (rewritten from \"{}\")", this.conversationId,
						this.searchQuery, this.question.content());
			}
			// Assessments and summaries get more of the document they are about; factual questions don't.
			List<RetrievedChunk> sources = this.retriever.retrieveForAnswer(this.workspaceId,
					List.of(this.searchQuery, this.question.content()),
					QuestionKind.isEvaluative(this.question.content()));
			if (sources.isEmpty()) {
				// Nothing relevant in the workspace: answer without spending a model call.
				appendAndSend(GroundedPrompt.NOT_FOUND_ANSWER);
				complete(sources, false);
				return;
			}
			Prompt prompt = GroundedPrompt.build(this.question.content(), sources, history);
			this.promptCharacters = prompt.getInstructions().stream().mapToInt(m -> m.getText().length()).sum();
			for (ChatResponse response : this.chatModel.stream(prompt).takeUntilOther(this.cancel.asMono()).toIterable()) {
				if (this.cancelled) {
					break;
				}
				record(response);
			}
			if (this.cancelled) {
				fail("The answer was interrupted.", false);
			}
			else if (this.answer.toString().isBlank()) {
				fail("The model returned an empty answer. Please try again.", true);
			}
			else {
				complete(sources, true);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Answering in conversation {} failed: {}", this.conversationId, ex.toString());
			fail("The language model is not available right now. Please try again in a moment.", true);
		}
	}

	private void record(ChatResponse response) {
		ChatResponseMetadata metadata = response.getMetadata();
		if (metadata != null) {
			if (StringUtils.hasText(metadata.getModel())) {
				this.reportedModel = metadata.getModel();
			}
			Usage usage = metadata.getUsage();
			if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
				this.promptTokens = usage.getPromptTokens();
				this.completionTokens = usage.getCompletionTokens();
			}
		}
		Generation generation = response.getResult();
		AssistantMessage output = (generation != null) ? generation.getOutput() : null;
		String text = (output != null) ? output.getText() : null;
		if (text != null && !text.isEmpty()) {
			appendAndSend(text);
		}
	}

	private synchronized void appendAndSend(String text) {
		if (this.finished) {
			return;
		}
		this.answer.append(text);
		send(DELTA, new StreamDelta(text));
	}

	private synchronized void complete(List<RetrievedChunk> sources, boolean modelCalled) {
		if (this.finished) {
			return;
		}
		this.finished = true;
		CitationExtractor.Extracted extracted = CitationExtractor.extract(this.answer.toString(), sources.size());
		List<CitationResponse> citations = extracted.sourceNumbers().stream().map(n -> {
			RetrievedChunk source = sources.get(n - 1);
			return new CitationResponse(n, source.documentId(), source.documentName(), source.contentType(),
					source.pageNumber(), source.content());
		}).toList();
		NewMessage message = answerMessage(extracted.content(), MessageStatus.COMPLETE, modelCalled);
		try {
			MessageResponse stored = store(message, citations);
			send(DONE, new StreamDone(stored));
			this.emitter.complete();
		}
		catch (RuntimeException ex) {
			// E.g. the conversation was deleted while the answer streamed.
			log.warn("Could not store the answer in conversation {}: {}", this.conversationId, ex.toString());
			sendQuietly(ERROR, new StreamError(null, "The answer could not be saved."));
			this.emitter.complete();
		}
	}

	private synchronized void fail(String reason, boolean notifyClient) {
		if (this.finished) {
			return;
		}
		this.finished = true;
		String partial = this.answer.toString().strip();
		String content = partial.isEmpty() ? reason : partial + "\n\n(" + reason + ")";
		MessageResponse stored = null;
		try {
			stored = store(answerMessage(content, MessageStatus.FAILED, !partial.isEmpty()), List.of());
		}
		catch (RuntimeException ex) {
			log.warn("Could not store the failed answer in conversation {}: {}", this.conversationId, ex.toString());
		}
		if (notifyClient) {
			sendQuietly(ERROR, new StreamError(stored, reason));
			this.emitter.complete();
		}
	}

	private MessageResponse store(NewMessage message, List<CitationResponse> citations) {
		return this.transactions.execute(status -> {
			MessageResponse stored = this.conversations.insertMessage(message, citations);
			this.conversations.touch(this.workspaceId, this.conversationId, this.question.content());
			return stored;
		});
	}

	private NewMessage answerMessage(String content, MessageStatus status, boolean modelCalled) {
		if (!modelCalled) {
			return new NewMessage(this.answerId, this.workspaceId, this.conversationId, Role.ASSISTANT, content, status,
					null, null, null, null, this.searchQuery);
		}
		String model = StringUtils.hasText(this.reportedModel) ? this.reportedModel : this.properties.model();
		boolean estimated = this.promptTokens == null;
		// Estimate ≈ 4 characters per token when the provider reports no usage (flagged as estimated).
		int prompt = estimated ? Math.max(1, (this.promptCharacters + 3) / 4) : this.promptTokens;
		int completion = (estimated || this.completionTokens == null) ? estimateTokens(this.answer.toString())
				: this.completionTokens;
		if (this.rewrite != null && this.rewrite.rewritten()) {
			// The rewrite call is part of this answer's cost.
			prompt += (this.rewrite.promptTokens() != null) ? this.rewrite.promptTokens() : 0;
			completion += (this.rewrite.completionTokens() != null) ? this.rewrite.completionTokens() : 0;
		}
		return new NewMessage(this.answerId, this.workspaceId, this.conversationId, Role.ASSISTANT, content, status,
				model, prompt, completion, estimated, this.searchQuery);
	}

	private void cancelModel() {
		this.cancelled = true;
		this.cancel.tryEmitEmpty();
	}

	/** Sends an event; if the client is gone, cancels the model call (the answer is then stored as FAILED). */
	private void send(String name, Object data) {
		try {
			this.emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			log.debug("Answer stream of conversation {} closed by the client: {}", this.conversationId,
					ex.getMessage());
			cancelModel();
		}
	}

	private void sendQuietly(String name, Object data) {
		try {
			this.emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			log.debug("Could not send '{}' to conversation {}: {}", name, this.conversationId, ex.getMessage());
		}
	}

	static int estimateTokens(String text) {
		return Math.max(1, (text.length() + 3) / 4);
	}

}
