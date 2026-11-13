package dev.docquery.api.chat;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import dev.docquery.api.chat.GroundedPrompt.HistoryMessage;

/**
 * Turns a follow-up question ("what do you think about this CV?") into a standalone search query
 * ("assessment of Hatim Kassim's CV for a junior developer position") using the recent conversation,
 * so retrieval can find the passages a reference points to. The answer itself still uses the original
 * question.
 * <p>
 * A short, non-streamed model call, skipped for the first question of a conversation. Its output is
 * only ever used as a search query (never shown, never put in the answer prompt), and it is cleaned:
 * one line, bounded length. If the call fails, retrieval simply uses the original question.
 */
@Component
class QueryRewriter {

	private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

	static final String INSTRUCTIONS = """
			You turn the last question of a conversation into a standalone search query for a document \
			search engine.
			- Use the conversation only to resolve references such as "this CV", "it", "he", "that \
			document" or "the same for year 3".
			- If the question is already self-contained, return it unchanged. Do not add people, \
			documents or topics it does not refer to.
			- Keep names, document titles and key terms. Keep the language of the question.
			- Do not answer the question. Reply with the search query only, on one line, without quotes \
			or explanation.

			Example:
			Conversation:
			User: What are Anna Lee's skills according to her CV?
			Assistant: Anna Lee knows Python and Django.
			Last question: Is this CV good for a senior role?
			Standalone search query: Anna Lee CV skills experience for a senior role""";

	static final int MAX_QUERY_LENGTH = 300;

	/** Earlier messages are shortened to this many characters: references need little context. */
	static final int HISTORY_EXCERPT = 400;

	private static final Pattern LABEL = Pattern.compile(
			"^(standalone\\s+)?(search\\s+)?query\\s*:\\s*|^rewritten( question)?\\s*:\\s*", Pattern.CASE_INSENSITIVE);

	/**
	 * @param query what to search for (the original question if there was nothing to rewrite)
	 * @param rewritten whether {@code query} came from the model
	 * @param promptTokens tokens of the rewrite call as reported by the provider, else {@code null}
	 */
	record Rewrite(String query, boolean rewritten, Integer promptTokens, Integer completionTokens) {

		static Rewrite unchanged(String question) {
			return new Rewrite(question, false, null, null);
		}

	}

	private final ChatModel chatModel;

	QueryRewriter(ChatModel chatModel) {
		this.chatModel = chatModel;
	}

	Rewrite rewrite(String question, List<HistoryMessage> history) {
		if (history.isEmpty()) {
			return Rewrite.unchanged(question);
		}
		try {
			ChatResponse response = this.chatModel.call(prompt(question, history));
			String text = (response != null && response.getResult() != null)
					? response.getResult().getOutput().getText() : null;
			String query = clean(text);
			if (query.isEmpty()) {
				return Rewrite.unchanged(question);
			}
			Usage usage = (response.getMetadata() != null) ? response.getMetadata().getUsage() : null;
			return new Rewrite(query, true, (usage != null) ? usage.getPromptTokens() : null,
					(usage != null) ? usage.getCompletionTokens() : null);
		}
		catch (RuntimeException ex) {
			log.warn("Query rewriting failed, searching with the original question: {}", ex.toString());
			return Rewrite.unchanged(question);
		}
	}

	Prompt prompt(String question, List<HistoryMessage> history) {
		ChatOptions options = options();
		StringBuilder sb = new StringBuilder("Conversation:\n");
		for (HistoryMessage message : history) {
			String text = GroundedPrompt.stripCitationMarkers(message.content()).strip().replaceAll("\\s+", " ");
			if (text.length() > HISTORY_EXCERPT) {
				text = text.substring(0, HISTORY_EXCERPT) + "…";
			}
			sb.append(message.fromUser() ? "User: " : "Assistant: ").append(text).append('\n');
		}
		sb.append("\nLast question: ").append(question.strip()).append("\n\nStandalone search query:");
		return new Prompt(List.of(new SystemMessage(INSTRUCTIONS), new UserMessage(sb.toString())), options);
	}

	/**
	 * The provider's own default options (so its configured model and settings are kept), made
	 * deterministic and short. Generic options without a model would override the configured model
	 * with nothing, which Ollama rejects.
	 */
	private ChatOptions options() {
		ChatOptions defaults = this.chatModel.getDefaultOptions();
		ChatOptions.Builder<?> builder = (defaults != null) ? defaults.mutate() : ChatOptions.builder();
		return builder.temperature(0.0).maxTokens(80).build();
	}

	/** First non-empty line, without labels or quotes, at most {@link #MAX_QUERY_LENGTH} characters. */
	static String clean(String output) {
		if (output == null) {
			return "";
		}
		String line = output.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
		line = LABEL.matcher(line).replaceFirst("").strip();
		line = line.replaceAll("^[\"'“”«»`]+|[\"'“”«»`]+$", "").strip();
		return (line.length() <= MAX_QUERY_LENGTH) ? line : line.substring(0, MAX_QUERY_LENGTH).strip();
	}

}
