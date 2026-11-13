package dev.docquery.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.ChunkFixtures;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.SseEvents.Event;
import dev.docquery.api.support.StubChatModel;
import dev.docquery.api.support.TestUser;

/**
 * Regression tests for the chat-quality issues found in manual testing of Phase 5: entity mix-ups
 * across documents, follow-up questions that need rewriting before retrieval, evaluative questions
 * about one document, and the answer language. (Formatting is covered by the frontend tests.)
 * <p>
 * A stub model cannot show that a real model behaves; these tests pin down what DocQuery controls:
 * which passages and instructions the model gets, and what is searched and stored. The behaviour of
 * llama3.2:3b on the same scenarios is checked by hand (see ADR 0012).
 */
class ChatQualityTests extends ApiIntegrationTest {

	static final String CV_TEXT = "Hatim Kassim. Skills: Java, Spring Boot, Angular, PostgreSQL. Experience: two "
			+ "internships building REST services.";

	static final String HANDBOOK_TEXT = "Employees receive 23 days of paid leave in year 3.";

	@Autowired
	JdbcClient jdbc;

	@Autowired
	StubChatModel model;

	ChunkFixtures chunks;

	TestUser owner;

	UUID workspaceId;

	UUID cvId;

	UUID handbookId;

	UUID conversationId;

	@BeforeEach
	void cvAndHandbook() {
		this.model.reset();
		this.chunks = new ChunkFixtures(this.jdbc);
		this.owner = TestUser.random("quality-owner");
		this.api.signIn(this.owner);
		this.workspaceId = this.api.createWorkspace(this.owner, "Quality");
		this.cvId = this.api.uploadDocument(this.owner, this.workspaceId, "Hatim_Kassim_Resume.pdf", SampleFiles.pdf());
		this.chunks.index(this.workspaceId, this.cvId, 1, CV_TEXT);
		this.handbookId = this.api.uploadDocument(this.owner, this.workspaceId, "Handbook.md", SampleFiles.text("h"));
		this.chunks.index(this.workspaceId, this.handbookId, null, HANDBOOK_TEXT);
		this.conversationId = this.api.createConversation(this.owner, this.workspaceId);
	}

	/** Issue 1: "How many days of paid leave in year 3?" after talking about a CV. */
	@Test
	void aQuestionAboutAnotherDocumentGetsThatDocumentsPassagesAndTheAttributionRule() {
		this.model.reply("Hatim Kassim knows Java and Spring Boot [1].");
		ask("What are Hatim Kassim's skills?");
		this.model.reply("Employees receive 23 days of paid leave in year 3 [1].");

		List<Event> events = ask("How many days of paid leave in year 3?");

		Prompt prompt = this.model.lastPrompt();
		String userTurn = prompt.getInstructions().getLast().getText();
		// The sources are the handbook's, labelled with the handbook's name; the CV is not among them.
		assertThat(userTurn).contains("document=\"Handbook.md\"").contains(HANDBOOK_TEXT)
			.doesNotContain("Hatim_Kassim_Resume.pdf")
			.doesNotContain(CV_TEXT)
			.contains("Question: How many days of paid leave in year 3?");
		// The earlier CV exchange is history, and the rules say facts stay with their own passage.
		assertThat(prompt.getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
		assertThat(prompt.getInstructions().getFirst().getText())
			.contains("never attribute it to a person, company or other entity unless the cited passage names them");
		// The citation resolves to the handbook.
		JsonNode citation = events.getLast().data().at("/answer/citations/0");
		assertThat(citation.get("documentName").asString()).isEqualTo("Handbook.md");
		// The self-contained question was searched as is.
		assertThat(storedSearchQuery(events)).isEqualTo("How many days of paid leave in year 3?");
	}

	/** Issue 2: "What do you think about this CV ...?" only works once it is rewritten. */
	@Test
	void followUpsAreRewrittenBeforeRetrievalAndTheRewriteIsStored() {
		ask("What are Hatim Kassim's skills?");
		assertThat(this.model.rewritePrompts()).as("no rewrite for the first question").isEmpty();
		this.model.rewriteTo("Hatim Kassim skills experience");
		this.model.reply("**Facts:** Java and Spring Boot [1].");

		List<Event> events = ask("What do you think about this CV for a confirmed junior post?");

		assertThat(this.model.rewritePrompts()).hasSize(1);
		assertThat(this.model.rewritePrompts().getFirst().getUserMessage().getText())
			.contains("User: What are Hatim Kassim's skills?")
			.contains("Last question: What do you think about this CV for a confirmed junior post?");
		// The rewrite found the CV, and the model still got the original question.
		String userTurn = this.model.lastPrompt().getInstructions().getLast().getText();
		assertThat(userTurn).contains(CV_TEXT)
			.contains("Question: What do you think about this CV for a confirmed junior post?")
			.doesNotContain("Hatim Kassim skills experience");
		assertThat(storedSearchQuery(events)).isEqualTo("Hatim Kassim skills experience");
	}

	@Test
	void withoutARewriteTheSameFollowUpFindsNothing() {
		ask("What are Hatim Kassim's skills?");
		this.model.failRewrites(new IllegalStateException("model down"));
		int answersBefore = this.model.prompts().size();

		List<Event> events = ask("What do you think about this CV for a confirmed junior post?");

		// Rewriting failed, so the raw follow-up was searched: it shares no words with the CV.
		assertThat(events.getLast().data().at("/answer/content").asString())
			.isEqualTo("I couldn't find this in your documents.");
		assertThat(this.model.prompts()).hasSize(answersBefore);
		assertThat(storedSearchQuery(events)).isEqualTo("What do you think about this CV for a confirmed junior post?");
	}

	/** Issue 2/3: a question naming a document ("this CV") gets it, whatever its wording. */
	@Test
	void aDocumentNamedInTheQuestionIsRetrievedEvenWithoutMatchingWords() {
		UUID annaCv = this.api.uploadDocument(this.owner, this.workspaceId, "Anna_Lee_CV.pdf", SampleFiles.pdf());
		this.chunks.index(this.workspaceId, annaCv, 1, "Anna Lee. Python, Django, three years at Acme.");

		ask("Is this CV good for a confirmed junior post?");

		String userTurn = this.model.lastPrompt().getInstructions().getLast().getText();
		assertThat(userTurn).contains("document=\"Anna_Lee_CV.pdf\"").contains("Anna Lee. Python, Django");
	}

	@Test
	void anAmbiguousNameTargetsNoDocument() {
		UUID first = this.api.uploadDocument(this.owner, this.workspaceId, "Anna_Lee_CV.pdf", SampleFiles.pdf());
		this.chunks.index(this.workspaceId, first, 1, "Anna Lee. Python, Django.");
		UUID second = this.api.uploadDocument(this.owner, this.workspaceId, "Bob_Smith_CV.pdf", SampleFiles.pdf());
		this.chunks.index(this.workspaceId, second, 1, "Bob Smith. Go, Kubernetes.");

		List<Event> events = ask("Is this CV good for a confirmed junior post?");

		// Two CVs: "this CV" is ambiguous, so no document is picked by name (and nothing else matches).
		assertThat(events.getLast().data().at("/answer/content").asString())
			.isEqualTo("I couldn't find this in your documents.");
	}

	/** Issue 3: an evaluative question about one document gets more of that document. */
	@Test
	void questionsAboutOneDocumentGetMoreOfItsPassages() {
		String[] sections = IntStream.range(0, 8)
			.mapToObj(i -> "Hatim Kassim section " + i + ": project number " + i + " with Java.")
			.toArray(String[]::new);
		this.chunks.index(this.workspaceId, this.cvId, 1, sections);

		ask("Assess Hatim Kassim for a junior role");

		String userTurn = this.model.lastPrompt().getInstructions().getLast().getText();
		// 5 fused sources, all from the CV, plus up to 4 more of its passages (focus-extra-chunks).
		assertThat(count(userTurn, "<source ")).isEqualTo(5 + 3);
		assertThat(count(userTurn, "document=\"Hatim_Kassim_Resume.pdf\"")).isEqualTo(8);
		assertThat(this.model.lastPrompt().getInstructions().getFirst().getText()).contains("**Assessment:**");
	}

	@Test
	void onlyEvaluativeQuestionsAreWidenedWhenNoDocumentIsNamed() {
		UUID rules = this.api.uploadDocument(this.owner, this.workspaceId, "Rules.md", SampleFiles.text("r"));
		this.chunks.index(this.workspaceId, rules, null, IntStream.range(0, 8)
			.mapToObj(i -> "Leave rule " + i + ": employees receive " + (20 + i) + " days.")
			.toArray(String[]::new));

		// Factual: the 5 best passages only (extra ones just invite padded answers).
		ask("How many days do employees receive?");
		assertThat(count(this.model.lastPrompt().getInstructions().getLast().getText(), "<source ")).isEqualTo(5);

		// Evaluative: most sources come from Rules.md (not named), so it is widened to all 8 of its passages.
		ask("Summarize what employees receive");
		String userTurn = this.model.lastPrompt().getInstructions().getLast().getText();
		assertThat(count(userTurn, "document=\"Rules.md\"")).isEqualTo(8);
	}

	/** Issue 4: English question, French source: the model is told to answer in English. */
	@Test
	void theAnswerLanguageFollowsTheQuestionNotTheSources() {
		UUID guide = this.api.uploadDocument(this.owner, this.workspaceId, "Guide.md", SampleFiles.text("g"));
		this.chunks.index(this.workspaceId, guide, null,
				"Les employés reçoivent 25 jours de congés payés. Leave policy: congés payés.");

		ask("How much leave policy congés payés do employees get?");

		String userTurn = this.model.lastPrompt().getInstructions().getLast().getText();
		assertThat(userTurn).contains("Les employés reçoivent 25 jours de congés payés")
			.endsWith("Question: How much leave policy congés payés do employees get?\n\n"
					+ GroundedPrompt.ANSWER_REMINDER);
		assertThat(this.model.lastPrompt().getInstructions().getFirst().getText())
			.contains("Always answer in the language of the user's question, even when the sources are written in another language");
	}

	private List<Event> ask(String question) {
		return this.api.askAndRead(this.owner, this.workspaceId, this.conversationId, question);
	}

	private String storedSearchQuery(List<Event> events) {
		UUID answerId = UUID.fromString(events.getLast().data().at("/answer/id").asString());
		return this.jdbc.sql("SELECT search_query FROM chat_message WHERE id = :id")
			.param("id", answerId)
			.query(String.class)
			.single();
	}

	private static int count(String text, String part) {
		int count = 0;
		for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
			count++;
		}
		return count;
	}

}
