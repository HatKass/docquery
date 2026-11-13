package dev.docquery.api.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Deterministic, offline chat model.
 * <ul>
 * <li>{@link #stream} produces answers: by default "Based on the sources [1]." in a few pieces;
 * tests can script the pieces per prompt or make it fail.</li>
 * <li>{@link #call} is what DocQuery uses for query rewriting: by default it returns the last
 * question unchanged; tests can script the rewrite or make it fail.</li>
 * </ul>
 * Every prompt is recorded, so tests can check what the model would have seen.
 */
public class StubChatModel implements ChatModel {

	public static final String MODEL = "stub-chat";

	public static final Function<Prompt, List<String>> DEFAULT_REPLY = prompt -> List.of("Based on ", "the sources ",
			"[1].");

	/** Echoes the question from the rewrite prompt ("Last question: ..."). */
	public static final Function<Prompt, String> ECHO_QUESTION = prompt -> {
		String text = prompt.getUserMessage().getText();
		int start = text.lastIndexOf("Last question: ") + "Last question: ".length();
		int end = text.indexOf('\n', start);
		return text.substring(start, (end < 0) ? text.length() : end);
	};

	private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

	private final List<Prompt> rewritePrompts = new CopyOnWriteArrayList<>();

	private final AtomicReference<Function<Prompt, List<String>>> reply = new AtomicReference<>(DEFAULT_REPLY);

	private final AtomicReference<Function<Prompt, String>> rewrite = new AtomicReference<>(ECHO_QUESTION);

	private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

	private final AtomicReference<RuntimeException> rewriteFailure = new AtomicReference<>();

	public void reply(Function<Prompt, List<String>> reply) {
		this.reply.set(reply);
	}

	public void reply(String... pieces) {
		reply(prompt -> List.of(pieces));
	}

	/** Every following answer fails with this exception, as if the provider were down. */
	public void fail(RuntimeException failure) {
		this.failure.set(failure);
	}

	public void rewriteTo(String query) {
		this.rewrite.set(prompt -> query);
	}

	public void failRewrites(RuntimeException failure) {
		this.rewriteFailure.set(failure);
	}

	public void reset() {
		this.prompts.clear();
		this.rewritePrompts.clear();
		this.reply.set(DEFAULT_REPLY);
		this.rewrite.set(ECHO_QUESTION);
		this.failure.set(null);
		this.rewriteFailure.set(null);
	}

	/** Answer prompts, in order. */
	public List<Prompt> prompts() {
		return List.copyOf(this.prompts);
	}

	public Prompt lastPrompt() {
		return this.prompts.getLast();
	}

	/** Query-rewriting prompts, in order. */
	public List<Prompt> rewritePrompts() {
		return List.copyOf(this.rewritePrompts);
	}

	/** Like a real provider: the configured model and settings. */
	@Override
	public ChatOptions getDefaultOptions() {
		return ChatOptions.builder().model(MODEL).temperature(0.1).maxTokens(1024).build();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		this.rewritePrompts.add(prompt);
		RuntimeException error = this.rewriteFailure.get();
		if (error != null) {
			throw error;
		}
		return response(this.rewrite.get().apply(prompt), true);
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		this.prompts.add(prompt);
		RuntimeException error = this.failure.get();
		if (error != null) {
			return Flux.error(error);
		}
		List<String> pieces = this.reply.get().apply(prompt);
		return Flux.range(0, pieces.size()).map(i -> response(pieces.get(i), i == pieces.size() - 1));
	}

	private static ChatResponse response(String text, boolean withUsage) {
		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(MODEL);
		if (withUsage) {
			metadata.usage(new DefaultUsage(100, 10));
		}
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata.build());
	}

}
