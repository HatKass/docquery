package dev.docquery.api.chat;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.docquery.api.chat.ChatDtos.AskRequest;
import dev.docquery.api.chat.ChatDtos.ConversationDetail;
import dev.docquery.api.chat.ChatDtos.ConversationSummary;
import dev.docquery.api.chat.ChatDtos.CreateConversationRequest;
import dev.docquery.api.user.CurrentUser;

@RestController
class ConversationController {

	private static final String BASE = "/api/workspaces/{workspaceId}/conversations";

	private final ChatService chat;

	ConversationController(ChatService chat) {
		this.chat = chat;
	}

	/** The caller's own conversations in this workspace, most recent first. */
	@GetMapping(BASE)
	List<ConversationSummary> list(@PathVariable UUID workspaceId, CurrentUser user) {
		return this.chat.list(workspaceId, user);
	}

	@PostMapping(BASE)
	ResponseEntity<ConversationSummary> create(@PathVariable UUID workspaceId,
			@Valid @RequestBody(required = false) CreateConversationRequest request, CurrentUser user) {
		ConversationSummary created = this.chat.create(workspaceId, (request != null) ? request.title() : null, user);
		return ResponseEntity.created(URI.create("/api/workspaces/" + workspaceId + "/conversations/" + created.id()))
			.body(created);
	}

	@GetMapping(BASE + "/{conversationId}")
	ConversationDetail get(@PathVariable UUID workspaceId, @PathVariable UUID conversationId, CurrentUser user) {
		return this.chat.get(workspaceId, conversationId, user);
	}

	@DeleteMapping(BASE + "/{conversationId}")
	ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID conversationId, CurrentUser user) {
		this.chat.delete(workspaceId, conversationId, user);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Asks a question. The answer streams back as Server-Sent Events: {@code start}, {@code delta}...,
	 * then {@code done} (or {@code error}). See {@link AnswerStream}.
	 */
	@PostMapping(path = BASE + "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter ask(@PathVariable UUID workspaceId, @PathVariable UUID conversationId,
			@Valid @RequestBody AskRequest request, CurrentUser user) {
		return this.chat.ask(workspaceId, conversationId, request.question(), user);
	}

}
