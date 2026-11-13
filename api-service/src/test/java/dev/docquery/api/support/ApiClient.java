package dev.docquery.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small helpers over {@link MockMvcTester} for the workspace and document APIs. */
public final class ApiClient {

	private final MockMvcTester mvc;

	private final JsonMapper json;

	public ApiClient(MockMvcTester mvc, JsonMapper json) {
		this.mvc = mvc;
		this.json = json;
	}

	public MvcTestResult get(TestUser user, String uri) {
		return this.mvc.get().uri(uri).with(user.auth()).exchange();
	}

	public MvcTestResult post(TestUser user, String uri, String body) {
		return this.mvc.post().uri(uri).with(user.auth()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	public MvcTestResult put(TestUser user, String uri, String body) {
		return this.mvc.put().uri(uri).with(user.auth()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	public MvcTestResult patch(TestUser user, String uri, String body) {
		return this.mvc.patch().uri(uri).with(user.auth()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	/** Multipart upload exactly like the browser sends it ({@code file} part, generic content type). */
	public MvcTestResult upload(TestUser user, UUID workspaceId, String filename, byte[] content) {
		return this.mvc.post()
			.uri("/api/workspaces/" + workspaceId + "/documents")
			.multipart()
			.file(new MockMultipartFile("file", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
			.with(user.auth())
			.exchange();
	}

	/** Uploads a file that must be accepted and returns the new document's id. */
	public UUID uploadDocument(TestUser user, UUID workspaceId, String filename, byte[] content) {
		MvcTestResult result = upload(user, workspaceId, filename, content);
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return UUID.fromString(body(result).get("id").asString());
	}

	public MvcTestResult delete(TestUser user, String uri) {
		return this.mvc.delete().uri(uri).with(user.auth()).exchange();
	}

	/** Signs the user in (provisioning their local row) and returns their local id. */
	public UUID signIn(TestUser user) {
		MvcTestResult result = get(user, "/api/me");
		assertThat(result).hasStatusOk();
		return UUID.fromString(body(result).get("id").asString());
	}

	public UUID createWorkspace(TestUser owner, String name) {
		MvcTestResult result = post(owner, "/api/workspaces", "{\"name\":\"" + name + "\"}");
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return UUID.fromString(body(result).get("id").asString());
	}

	public void addMember(TestUser owner, UUID workspaceId, TestUser member, String role) {
		signIn(member);
		assertThat(post(owner, "/api/workspaces/" + workspaceId + "/members",
				"{\"usernameOrEmail\":\"" + member.username() + "\",\"role\":\"" + role + "\"}"))
			.hasStatus(HttpStatus.CREATED);
	}

	public UUID createConversation(TestUser user, UUID workspaceId) {
		MvcTestResult result = post(user, "/api/workspaces/" + workspaceId + "/conversations", "{}");
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return UUID.fromString(body(result).get("id").asString());
	}

	/** Asks a question the way the browser does; the result holds the whole event stream once it ended. */
	public MvcTestResult ask(TestUser user, UUID workspaceId, UUID conversationId, String question) {
		String body = this.json.writeValueAsString(java.util.Map.of("question", question));
		return this.mvc.post()
			.uri("/api/workspaces/" + workspaceId + "/conversations/" + conversationId + "/messages")
			.with(user.auth())
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_PROBLEM_JSON)
			.content(body)
			.exchange();
	}

	/** Asks a question that must be accepted and returns the stream's events. */
	public java.util.List<SseEvents.Event> askAndRead(TestUser user, UUID workspaceId, UUID conversationId,
			String question) {
		MvcTestResult result = ask(user, workspaceId, conversationId, question);
		assertThat(result).hasStatusOk();
		String stream = new String(result.getResponse().getContentAsByteArray(),
				java.nio.charset.StandardCharsets.UTF_8);
		return SseEvents.parse(stream, this.json);
	}

	public JsonNode body(MvcTestResult result) {
		return this.json.readTree(result.getResponse().getContentAsByteArray());
	}

}
