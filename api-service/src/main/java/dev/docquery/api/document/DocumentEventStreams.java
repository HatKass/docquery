package dev.docquery.api.document;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.docquery.api.document.DocumentDtos.DocumentEventPayload;
import dev.docquery.api.workspace.WorkspaceEvents;

/**
 * Live document updates per workspace over Server-Sent Events.
 * <p>
 * A subscriber is registered only after {@code WorkspaceAccess} admitted them, and receives only
 * events of that one workspace. Removing a member or deleting the workspace closes the affected
 * streams at once; every stream also ends after {@code eventStreamTimeout}, and the browser
 * reconnects with a fresh token, which re-runs the access check.
 * <p>
 * Subscribers are held in memory, so with several api-service replicas each replica only sees the
 * changes it made itself. Phase 4 feeds pipeline status changes from Kafka into every replica.
 */
@Component
class DocumentEventStreams {

	private static final Logger log = LoggerFactory.getLogger(DocumentEventStreams.class);

	static final String READY_EVENT = "ready";

	private record Subscription(UUID workspaceId, UUID userId, SseEmitter emitter) {
	}

	private final Map<UUID, Set<Subscription>> byWorkspace = new ConcurrentHashMap<>();

	private final DocumentProperties properties;

	private final ScheduledExecutorService heartbeat = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("document-sse-heartbeat").daemon().factory());

	DocumentEventStreams(DocumentProperties properties) {
		this.properties = properties;
		long period = properties.eventStreamHeartbeat().toMillis();
		this.heartbeat.scheduleWithFixedDelay(this::sendHeartbeats, period, period, TimeUnit.MILLISECONDS);
	}

	/** Opens a stream for an already-authorized member; the first event, {@code ready}, confirms it. */
	SseEmitter open(UUID workspaceId, UUID userId) {
		SseEmitter emitter = new SseEmitter(this.properties.eventStreamTimeout().toMillis());
		Subscription subscription = new Subscription(workspaceId, userId, emitter);
		Runnable unregister = () -> unregister(subscription);
		emitter.onCompletion(unregister);
		emitter.onTimeout(emitter::complete);
		emitter.onError(error -> unregister.run());
		this.byWorkspace.computeIfAbsent(workspaceId, id -> ConcurrentHashMap.newKeySet()).add(subscription);
		send(subscription, SseEmitter.event().name(READY_EVENT).data("{}", MediaType.APPLICATION_JSON));
		return emitter;
	}

	int subscriberCount(UUID workspaceId) {
		return this.byWorkspace.getOrDefault(workspaceId, Set.of()).size();
	}

	@TransactionalEventListener(fallbackExecution = true)
	void onDocumentChanged(DocumentChangedEvent event) {
		DocumentEventPayload payload = new DocumentEventPayload(event.documentId(), event.document());
		for (Subscription subscription : subscribers(event.workspaceId())) {
			send(subscription,
					SseEmitter.event().name(event.kind().eventName()).data(payload, MediaType.APPLICATION_JSON));
		}
	}

	@TransactionalEventListener(fallbackExecution = true)
	void onMemberRemoved(WorkspaceEvents.MemberRemoved event) {
		close(event.workspaceId(), s -> s.userId().equals(event.userId()));
	}

	@TransactionalEventListener(fallbackExecution = true)
	void onWorkspaceDeleted(WorkspaceEvents.WorkspaceDeleted event) {
		close(event.workspaceId(), s -> true);
	}

	/** Ends all streams before the web server's graceful shutdown would wait for them. */
	@EventListener(ContextClosedEvent.class)
	void closeAll() {
		this.byWorkspace.values().stream().flatMap(Set::stream).toList().forEach(s -> s.emitter().complete());
	}

	@PreDestroy
	void stopHeartbeat() {
		this.heartbeat.shutdownNow();
	}

	private void sendHeartbeats() {
		this.byWorkspace.values()
			.stream()
			.flatMap(Set::stream)
			.toList()
			.forEach(s -> send(s, SseEmitter.event().comment("keep-alive")));
	}

	private void close(UUID workspaceId, Predicate<Subscription> which) {
		subscribers(workspaceId).stream().filter(which).forEach(s -> {
			unregister(s);
			s.emitter().complete();
		});
	}

	private List<Subscription> subscribers(UUID workspaceId) {
		return List.copyOf(this.byWorkspace.getOrDefault(workspaceId, Set.of()));
	}

	private void send(Subscription subscription, SseEmitter.SseEventBuilder event) {
		try {
			subscription.emitter().send(event);
		}
		catch (IOException | IllegalStateException ex) {
			// Client went away (or the emitter already completed): drop the subscription quietly.
			log.debug("Dropping document event stream for workspace {}: {}", subscription.workspaceId(),
					ex.getMessage());
			unregister(subscription);
			subscription.emitter().completeWithError(ex);
		}
	}

	private void unregister(Subscription subscription) {
		this.byWorkspace.computeIfPresent(subscription.workspaceId(), (id, set) -> {
			set.remove(subscription);
			return set.isEmpty() ? null : set;
		});
	}

}
