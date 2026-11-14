package dev.docquery.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.ChunkFixtures;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.SseEvents;
import dev.docquery.api.support.SseEvents.Event;
import dev.docquery.api.support.StubChatModel;
import dev.docquery.api.support.TestUser;

/** RAG chat end to end: conversations, streaming, citations, grounding and history. */
class ChatApiTests extends ApiIntegrationTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	StubChatModel model;

	TestUser owner;

	UUID workspaceId;

	UUID handbookId;

	UUID conversationId;

	@BeforeEach
	void workspaceWithHandbook() {
		this.model.reset();
		this.owner = TestUser.random("chat-owner");
		this.api.signIn(this.owner);
		this.workspaceId = this.api.createWorkspace(this.owner, "Chat");
		this.handbookId = this.api.uploadDocument(this.owner, this.workspaceId, "Handbook.pdf", SampleFiles.pdf());
		new ChunkFixtures(this.jdbc).index(this.workspaceId, this.handbookId, 7,
				"Employees receive 24 days of paid vacation each year.");
		this.conversationId = this.api.createConversation(this.owner, this.workspaceId);
	}

	@Test
	void streamsAGroundedAnswerWithCitations() {
		this.model.reply("Employees get ", "24 days ", "of paid vacation [1].");

		List<Event> events = this.api.askAndRead(this.owner, this.workspaceId, this.conversationId,
				"How many vacation days do employees receive?");

		assertThat(SseEvents.names(events)).containsExactly("start", "delta", "delta", "delta", "done");
		assertThat(events.getFirst().data().at("/question/content").asString())
			.isEqualTo("How many vacation days do employees receive?");
		assertThat(events.subList(1, 4)).extracting(e -> e.data().get("text").asString())
			.containsExactly("Employees get ", "24 days ", "of paid vacation [1].");
		JsonNode answer = events.getLast().data().get("answer");
		assertThat(answer.get("id").asString()).isEqualTo(events.getFirst().data().get("answerId").asString());
		assertThat(answer.get("status").asString()).isEqualTo("COMPLETE");
		assertThat(answer.get("content").asString()).isEqualTo("Employees get 24 days of paid vacation [1].");
		JsonNode citation = answer.get("citations").get(0);
		assertThat(citation.get("sourceNumber").asInt()).isEqualTo(1);
		assertThat(citation.get("documentId").asString()).isEqualTo(this.handbookId.toString());
		assertThat(citation.get("documentName").asString()).isEqualTo("Handbook.pdf");
		assertThat(citation.get("contentType").asString()).isEqualTo("application/pdf");
		assertThat(citation.get("pageNumber").asInt()).isEqualTo(7);
		assertThat(citation.get("snippet").asString()).isEqualTo("Employees receive 24 days of paid vacation each year.");

		// Stored: the conversation shows both messages, is titled after the question, and the answer's
		// model and token usage are recorded.
		assertThat(this.api.get(this.owner, conversationUri())).hasStatusOk().bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.title").isEqualTo("How many vacation days do employees receive?");
			json.assertThat().extractingPath("$.messages[*].role").asArray().containsExactly("USER", "ASSISTANT");
			json.assertThat().extractingPath("$.messages[1].citations[0].pageNumber").isEqualTo(7);
		});
		assertThat(this.jdbc.sql("""
				SELECT model || ':' || prompt_tokens || ':' || completion_tokens || ':' || tokens_estimated
				  FROM chat_message WHERE id = :id
				""").param("id", UUID.fromString(answer.get("id").asString())).query(String.class).single())
			.isEqualTo("stub-chat:100:10:false");
	}

	@Test
	void answersNotFoundWithoutCallingTheModelWhenNothingIsRelevant() {
		List<Event> events = this.api.askAndRead(this.owner, this.workspaceId, this.conversationId,
				"What is the recipe for pizza dough?");

		assertThat(SseEvents.names(events)).containsExactly("start", "delta", "done");
		JsonNode answer = events.getLast().data().get("answer");
		assertThat(answer.get("content").asString()).isEqualTo("I couldn't find this in your documents.");
		assertThat(answer.get("citations").size()).isZero();
		assertThat(this.model.prompts()).isEmpty();
	}

	@Test
	void modelSayingNotFoundGetsNoCitations() {
		this.model.reply("I couldn't find this in your documents [1].");

		List<Event> events = this.api.askAndRead(this.owner, this.workspaceId, this.conversationId,
				"How many vacation days for contractors?");

		JsonNode answer = events.getLast().data().get("answer");
		assertThat(answer.get("content").asString()).isEqualTo("I couldn't find this in your documents.");
		assertThat(answer.get("citations").size()).isZero();
	}

	@Test
	void modelOutageEndsTheStreamWithAnErrorAndStoresAFailedAnswer() {
		this.model.fail(new IllegalStateException("connection refused"));

		List<Event> events = this.api.askAndRead(this.owner, this.workspaceId, this.conversationId,
				"How many vacation days?");

		assertThat(SseEvents.names(events)).containsExactly("start", "error");
		assertThat(events.getLast().data().get("detail").asString()).contains("not available");
		assertThat(events.getLast().data().at("/answer/status").asString()).isEqualTo("FAILED");

		// The failed turn is not sent back to the model as history.
		this.model.reset();
		this.api.askAndRead(this.owner, this.workspaceId, this.conversationId, "How many vacation days?");
		assertThat(this.model.lastPrompt().getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.SYSTEM, MessageType.USER);
	}

	@Test
	void anEmptyModelAnswerEndsTheStreamWithAFriendlyError() {
		this.model.reply("");

		List<Event> events = this.api.askAndRead(this.owner, this.workspaceId, this.conversationId,
				"How many vacation days?");

		assertThat(SseEvents.names(events)).containsExactly("start", "error");
		assertThat(events.getLast().data().get("detail").asString())
			.isEqualTo("The model returned an empty answer. Please try again.");
		assertThat(events.getLast().data().at("/answer/status").asString()).isEqualTo("FAILED");
	}

	@Test
	void tokensAreEstimatedAndFlaggedWhenTheProviderReportsNoUsage() {
		this.model.reportNoUsage();
		this.model.reply("Employees get 24 days of paid vacation [1].");

		List<Event> events = this.api.askAndRead(this.owner, this.workspaceId, this.conversationId,
				"How many vacation days do employees receive?");

		UUID answerId = UUID.fromString(events.getLast().data().at("/answer/id").asString());
		// About 4 characters per token: the prompt (rules, source, question) is well over 100 tokens, and
		// the 43-character answer is 11.
		assertThat(this.jdbc.sql("""
				SELECT prompt_tokens > 100 AND completion_tokens = 11 AND estimated
				  FROM chat_usage WHERE message_id = :id
				""").param("id", answerId).query(Boolean.class).single()).isTrue();
		assertThat(this.jdbc.sql("SELECT tokens_estimated FROM chat_message WHERE id = :id")
			.param("id", answerId)
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void followUpQuestionsCarryTheConversationHistory() {
		this.model.reply("24 days [1].");
		this.api.askAndRead(this.owner, this.workspaceId, this.conversationId, "How many vacation days?");

		this.api.askAndRead(this.owner, this.workspaceId, this.conversationId, "Are those vacation days paid?");

		List<Message> messages = this.model.lastPrompt().getInstructions();
		assertThat(messages).extracting(Message::getMessageType)
			.containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
		assertThat(messages.get(1).getText()).isEqualTo("How many vacation days?");
		assertThat(messages.get(2).getText()).isEqualTo("24 days.");
	}

	@Test
	void citationsSurviveDeletingTheDocument() {
		this.api.askAndRead(this.owner, this.workspaceId, this.conversationId, "How many vacation days?");

		assertThat(this.api.delete(this.owner, "/api/workspaces/" + this.workspaceId + "/documents/" + this.handbookId))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(this.api.get(this.owner, conversationUri())).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.messages[1].citations[0].documentName").isEqualTo("Handbook.pdf");
			json.assertThat().extractingPath("$.messages[1].citations[0].documentId").isNull();
		});
	}

	@Test
	void conversationsArePrivateToTheirOwnerWithinTheWorkspace() {
		TestUser colleague = TestUser.random("chat-colleague");
		this.api.addMember(this.owner, this.workspaceId, colleague, "MEMBER");

		assertThat(this.api.get(colleague, conversationUri())).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:conversation-not-found");
		assertThat(this.api.ask(colleague, this.workspaceId, this.conversationId, "hi"))
			.hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.api.delete(colleague, conversationUri())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.api.get(colleague, "/api/workspaces/" + this.workspaceId + "/conversations")).bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(0);
		assertThat(this.api.get(this.owner, conversationUri())).hasStatusOk();
	}

	@Test
	void viewersCanAsk() {
		TestUser viewer = TestUser.random("chat-viewer");
		this.api.addMember(this.owner, this.workspaceId, viewer, "VIEWER");
		UUID own = this.api.createConversation(viewer, this.workspaceId);

		List<Event> events = this.api.askAndRead(viewer, this.workspaceId, own, "How many vacation days?");

		assertThat(events.getLast().name()).isEqualTo("done");
	}

	@Test
	void listsAndDeletesConversations() {
		UUID second = this.api.createConversation(this.owner, this.workspaceId);
		this.api.askAndRead(this.owner, this.workspaceId, this.conversationId, "How many vacation days?");

		// Most recently active first.
		assertThat(this.api.get(this.owner, "/api/workspaces/" + this.workspaceId + "/conversations")).bodyJson()
			.extractingPath("$[*].id")
			.asArray()
			.containsExactly(this.conversationId.toString(), second.toString());

		assertThat(this.api.delete(this.owner, conversationUri())).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(this.api.get(this.owner, conversationUri())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.jdbc.sql("SELECT count(*) FROM chat_message WHERE conversation_id = :id")
			.param("id", this.conversationId)
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void validatesQuestions() {
		assertThat(this.api.ask(this.owner, this.workspaceId, this.conversationId, "   "))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(this.api.ask(this.owner, this.workspaceId, this.conversationId, "x".repeat(2001)))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:question-too-long");
		assertThat(this.api.ask(this.owner, this.workspaceId, UUID.randomUUID(), "hi"))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	private String conversationUri() {
		return "/api/workspaces/" + this.workspaceId + "/conversations/" + this.conversationId;
	}

}
