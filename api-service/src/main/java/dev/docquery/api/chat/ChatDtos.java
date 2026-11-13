package dev.docquery.api.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the chat API. Database rows never leave the service layer. */
public final class ChatDtos {

	private ChatDtos() {
	}

	/** @param title optional; the first question becomes the title otherwise */
	public record CreateConversationRequest(@Size(max = 200) String title) {
	}

	/** The length limit is configurable (docquery.chat.max-question-length) and checked by the service. */
	public record AskRequest(@NotBlank(message = "must not be blank") String question) {
	}

	public record ConversationSummary(UUID id, UUID workspaceId, String title, Instant createdAt, Instant updatedAt) {
	}

	public record ConversationDetail(UUID id, UUID workspaceId, String title, Instant createdAt, Instant updatedAt,
			List<MessageResponse> messages) {
	}

	public enum Role {

		USER, ASSISTANT

	}

	public enum MessageStatus {

		COMPLETE, FAILED

	}

	/**
	 * @param citations the sources an answer cites, ordered by number; empty for questions
	 */
	public record MessageResponse(UUID id, Role role, String content, MessageStatus status, Instant createdAt,
			List<CitationResponse> citations) {
	}

	/**
	 * One cited source. Name, page and snippet are a snapshot taken when the answer was written.
	 *
	 * @param sourceNumber the {@code [n]} marker in the answer text
	 * @param documentId {@code null} once the document has been deleted
	 * @param pageNumber 1-based page for PDFs; {@code null} for unpaginated formats
	 * @param snippet the exact text the answer was based on
	 */
	public record CitationResponse(int sourceNumber, UUID documentId, String documentName, String contentType,
			Integer pageNumber, String snippet) {
	}

	/** First stream event: the stored question and the id the answer will have. */
	public record StreamStart(MessageResponse question, UUID answerId) {
	}

	/** A piece of the answer text, in order. */
	public record StreamDelta(String text) {
	}

	/**
	 * Last stream event: the stored answer. Its content replaces the streamed text (invalid citation
	 * markers are removed), and {@code citations} resolves the markers.
	 */
	public record StreamDone(MessageResponse answer) {
	}

	/** Last stream event when answering failed; the failed answer is stored too. */
	public record StreamError(MessageResponse answer, String detail) {
	}

}
