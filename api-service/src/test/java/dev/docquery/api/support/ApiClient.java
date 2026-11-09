package dev.docquery.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small helpers over {@link MockMvcTester} for the workspace API. */
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

	public JsonNode body(MvcTestResult result) {
		return this.json.readTree(result.getResponse().getContentAsByteArray());
	}

}
