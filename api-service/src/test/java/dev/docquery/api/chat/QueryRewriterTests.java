package dev.docquery.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;

import dev.docquery.api.chat.GroundedPrompt.HistoryMessage;
import dev.docquery.api.chat.QueryRewriter.Rewrite;
import dev.docquery.api.support.StubChatModel;

class QueryRewriterTests {

	private final StubChatModel model = new StubChatModel();

	private final QueryRewriter rewriter = new QueryRewriter(this.model);

	private static final List<HistoryMessage> HISTORY = List.of(new HistoryMessage(true, "What are Hatim's skills?"),
			new HistoryMessage(false, "Hatim knows Java and Spring Boot [1]."));

	@Test
	void theFirstQuestionIsSearchedAsAskedWithoutAModelCall() {
		Rewrite rewrite = this.rewriter.rewrite("How long is leave?", List.of());

		assertThat(rewrite).isEqualTo(Rewrite.unchanged("How long is leave?"));
		assertThat(this.model.rewritePrompts()).isEmpty();
	}

	@Test
	void followUpsAreRewrittenFromTheConversation() {
		this.model.rewriteTo("Hatim Kassim CV assessment for a junior developer position");

		Rewrite rewrite = this.rewriter.rewrite("What do you think about this CV for a junior post?", HISTORY);

		assertThat(rewrite.query()).isEqualTo("Hatim Kassim CV assessment for a junior developer position");
		assertThat(rewrite.rewritten()).isTrue();
		assertThat(rewrite.promptTokens()).isEqualTo(100);
		Prompt prompt = this.model.rewritePrompts().getFirst();
		assertThat(prompt.getInstructions().getFirst().getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(prompt.getUserMessage().getText()).contains("User: What are Hatim's skills?")
			// Old citation markers are noise for the rewrite.
			.contains("Assistant: Hatim knows Java and Spring Boot.")
			.contains("Last question: What do you think about this CV for a junior post?");
		assertThat(prompt.getOptions().getTemperature()).isEqualTo(0.0);
		assertThat(prompt.getOptions().getMaxTokens()).isEqualTo(80);
		// Regression: generic options without a model made Ollama reject every rewrite.
		assertThat(prompt.getOptions().getModel()).isEqualTo(StubChatModel.MODEL);
	}

	@Test
	void fallsBackToTheQuestionWhenTheModelFailsOrAnswersNothing() {
		this.model.failRewrites(new IllegalStateException("down"));
		assertThat(this.rewriter.rewrite("Follow-up?", HISTORY)).isEqualTo(Rewrite.unchanged("Follow-up?"));

		this.model.reset();
		this.model.rewriteTo("   ");
		assertThat(this.rewriter.rewrite("Follow-up?", HISTORY)).isEqualTo(Rewrite.unchanged("Follow-up?"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			Hatim Kassim CV                                      | Hatim Kassim CV
			"Hatim Kassim CV"                                    | Hatim Kassim CV
			Standalone search query: paid leave in year 3        | paid leave in year 3
			Query: paid leave                                    | paid leave
			« congés payés année 3 »                             | congés payés année 3
			""")
	void cleansTheModelOutput(String output, String expected) {
		assertThat(QueryRewriter.clean(output)).isEqualTo(expected);
	}

	@Test
	void keepsOnlyTheFirstLineAndBoundsTheLength() {
		assertThat(QueryRewriter.clean("\n\npaid leave year 3\nThis query searches for...")).isEqualTo("paid leave year 3");
		assertThat(QueryRewriter.clean("x".repeat(1000))).hasSize(QueryRewriter.MAX_QUERY_LENGTH);
	}

}
