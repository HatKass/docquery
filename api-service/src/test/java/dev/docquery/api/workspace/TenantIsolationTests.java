package dev.docquery.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.ChunkFixtures;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.TestUser;

/**
 * Tenant isolation: a user must never see or change anything in a workspace they do not belong to.
 * <p>
 * Every workspace-scoped endpoint is called by an outsider and must answer 404 (indistinguishable
 * from a non-existent workspace) without side effects. {@link #everyWorkspaceScopedEndpointIsCovered}
 * fails as soon as someone adds a new {@code /api/workspaces/{workspaceId}/...} endpoint without
 * adding it to {@link #workspaceScopedEndpoints()}, so later phases (chunks, chats, citations)
 * cannot forget isolation. Document endpoints are also called with the outsider's <em>own</em>
 * workspace id and tenant A's document id, which must not leak either.
 */
class TenantIsolationTests extends ApiIntegrationTest {

	/**
	 * {@code body} is a JSON template; {@link #MULTIPART} marks a file upload. {@code query} is appended
	 * to the URI (it is not part of the mapped path pattern).
	 */
	record Endpoint(String method, String pathPattern, String body, String query) {

		static final String MULTIPART = "<multipart file>";

		Endpoint(String method, String pathPattern) {
			this(method, pathPattern, null, null);
		}

		Endpoint(String method, String pathPattern, String body) {
			this(method, pathPattern, body, null);
		}

		String uri(UUID workspaceId, UUID memberId, UUID documentId, UUID conversationId) {
			String path = this.pathPattern.replace("{workspaceId}", workspaceId.toString())
				.replace("{userId}", memberId.toString())
				.replace("{documentId}", documentId.toString())
				.replace("{conversationId}", conversationId.toString());
			return (this.query != null) ? path + "?" + this.query : path;
		}

		@Override
		public String toString() {
			return this.method + " " + this.pathPattern;
		}

	}

	static Stream<Endpoint> workspaceScopedEndpoints() {
		return Stream.of(new Endpoint("GET", "/api/workspaces/{workspaceId}", null),
				new Endpoint("PATCH", "/api/workspaces/{workspaceId}", "{\"name\":\"pwned\"}"),
				new Endpoint("DELETE", "/api/workspaces/{workspaceId}", null),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/members", null),
				new Endpoint("POST", "/api/workspaces/{workspaceId}/members",
						"{\"usernameOrEmail\":\"%s\",\"role\":\"OWNER\"}"),
				new Endpoint("PUT", "/api/workspaces/{workspaceId}/members/{userId}", "{\"role\":\"VIEWER\"}"),
				new Endpoint("DELETE", "/api/workspaces/{workspaceId}/members/{userId}", null),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/documents"),
				new Endpoint("POST", "/api/workspaces/{workspaceId}/documents", Endpoint.MULTIPART),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/documents/events"),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/documents/{documentId}"),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/documents/{documentId}/content"),
				new Endpoint("POST", "/api/workspaces/{workspaceId}/documents/{documentId}/reindex"),
				new Endpoint("DELETE", "/api/workspaces/{workspaceId}/documents/{documentId}"),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/search", null, "q=secret"),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/conversations"),
				new Endpoint("POST", "/api/workspaces/{workspaceId}/conversations", "{}"),
				new Endpoint("GET", "/api/workspaces/{workspaceId}/conversations/{conversationId}"),
				new Endpoint("DELETE", "/api/workspaces/{workspaceId}/conversations/{conversationId}"),
				new Endpoint("POST", "/api/workspaces/{workspaceId}/conversations/{conversationId}/messages",
						"{\"question\":\"What does the secret document say?\"}"));
	}

	/** Endpoints addressing one document: also tried with the outsider's own workspace id. */
	static Stream<Endpoint> documentEndpoints() {
		return workspaceScopedEndpoints().filter(e -> e.pathPattern().contains("{documentId}"));
	}

	/** Endpoints addressing one conversation: also tried with the outsider's own workspace id. */
	static Stream<Endpoint> conversationEndpoints() {
		return workspaceScopedEndpoints().filter(e -> e.pathPattern().contains("{conversationId}"));
	}

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	TestUser owner;

	TestUser outsider;

	UUID ownerId;

	UUID workspaceId;

	UUID outsiderWorkspaceId;

	UUID documentId;

	UUID conversationId;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void twoTenants() {
		this.owner = TestUser.random("tenant-a-owner");
		this.outsider = TestUser.random("tenant-b-owner");
		this.ownerId = this.api.signIn(this.owner);
		this.api.signIn(this.outsider);
		this.workspaceId = this.api.createWorkspace(this.owner, "Tenant A");
		this.outsiderWorkspaceId = this.api.createWorkspace(this.outsider, "Tenant B");
		this.documentId = this.api.uploadDocument(this.owner, this.workspaceId, "tenant-a-secret.pdf",
				SampleFiles.pdf());
		new ChunkFixtures(this.jdbc).index(this.workspaceId, this.documentId, 1,
				"The secret document says the merger closes in March.");
		this.conversationId = this.api.createConversation(this.owner, this.workspaceId);
		this.api.askAndRead(this.owner, this.workspaceId, this.conversationId, "What does the secret document say?");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("workspaceScopedEndpoints")
	void outsiderGets404AndChangesNothing(Endpoint endpoint) {
		MvcTestResult result = call(endpoint, this.outsider, this.workspaceId);

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:workspace-not-found");
		assertTenantAUnchanged();
	}

	/**
	 * The outsider is a legitimate OWNER of their own workspace and passes the access check there;
	 * a foreign document id must still match nothing, because every query filters by workspace.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("documentEndpoints")
	void foreignDocumentIdThroughOwnWorkspaceGets404(Endpoint endpoint) {
		MvcTestResult result = call(endpoint, this.outsider, this.outsiderWorkspaceId);

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:document-not-found");
		assertTenantAUnchanged();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("conversationEndpoints")
	void foreignConversationIdThroughOwnWorkspaceGets404(Endpoint endpoint) {
		MvcTestResult result = call(endpoint, this.outsider, this.outsiderWorkspaceId);

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:conversation-not-found");
		assertTenantAUnchanged();
	}

	/** Even with a perfectly matching question, search and chat see only the caller's own workspace. */
	@Test
	void outsiderSearchAndChatNeverSeeForeignChunks() {
		assertThat(this.api.get(this.outsider,
				"/api/workspaces/" + this.outsiderWorkspaceId + "/search?q=secret%20document%20merger"))
			.hasStatusOk()
			.bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(0);
		UUID own = this.api.createConversation(this.outsider, this.outsiderWorkspaceId);
		var events = this.api.askAndRead(this.outsider, this.outsiderWorkspaceId, own,
				"What does the secret document say?");
		assertThat(events.getLast().data().at("/answer/content").asString())
			.isEqualTo("I couldn't find this in your documents.");
		assertThat(events.getLast().data().at("/answer/citations").size()).isZero();
		assertThat(this.api.get(this.outsider, "/api/workspaces/" + this.outsiderWorkspaceId + "/conversations"))
			.bodyJson()
			.extractingPath("$[*].id")
			.asArray()
			.containsExactly(own.toString());
	}

	@Test
	void outsiderListsNoForeignDocuments() {
		assertThat(this.api.get(this.outsider, "/api/workspaces/" + this.outsiderWorkspaceId + "/documents"))
			.hasStatusOk()
			.bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(0);
	}

	@Test
	void outsiderListsOnlyTheirOwnWorkspaces() {
		assertThat(this.api.get(this.outsider, "/api/workspaces")).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.length()").isEqualTo(1);
			json.assertThat().extractingPath("$[0].name").isEqualTo("Tenant B");
		});
		assertThat(this.api.get(this.outsider, "/api/me")).bodyJson()
			.extractingPath("$.workspaces[*].name")
			.asArray()
			.containsExactly("Tenant B");
	}

	@Test
	void nonExistentAndForeignWorkspacesLookIdentical() {
		MvcTestResult foreign = this.api.get(this.outsider, "/api/workspaces/" + this.workspaceId);
		MvcTestResult missing = this.api.get(this.outsider, "/api/workspaces/" + UUID.randomUUID());

		assertThat(foreign.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus());
		assertThat(this.api.body(foreign).get("title")).isEqualTo(this.api.body(missing).get("title"));
	}

	@Test
	void removedMemberLosesAccessImmediately() {
		TestUser former = TestUser.random("former");
		this.api.addMember(this.owner, this.workspaceId, former, "MEMBER");
		UUID formerId = this.api.signIn(former);
		assertThat(this.api.get(former, "/api/workspaces/" + this.workspaceId)).hasStatusOk();

		this.api.delete(this.owner, "/api/workspaces/" + this.workspaceId + "/members/" + formerId);

		assertThat(this.api.get(former, "/api/workspaces/" + this.workspaceId)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.api.get(former, "/api/workspaces/" + this.workspaceId + "/members"))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void everyWorkspaceScopedEndpointIsCovered() {
		Set<String> mapped = new TreeSet<>();
		this.handlerMapping.getHandlerMethods().forEach((info, method) -> {
			for (String pattern : info.getPatternValues()) {
				if (pattern.startsWith("/api/workspaces/{workspaceId}")) {
					info.getMethodsCondition().getMethods().forEach(m -> mapped.add(m.name() + " " + pattern));
				}
			}
		});
		List<String> covered = workspaceScopedEndpoints().map(Endpoint::toString).toList();

		assertThat(mapped).as("Add new workspace-scoped endpoints to workspaceScopedEndpoints()")
			.containsExactlyInAnyOrderElementsOf(covered);
	}

	private MvcTestResult call(Endpoint endpoint, TestUser caller, UUID inWorkspace) {
		String uri = endpoint.uri(inWorkspace, this.ownerId, this.documentId, this.conversationId);
		if (Endpoint.MULTIPART.equals(endpoint.body())) {
			return this.mvc.post()
				.uri(uri)
				.multipart()
				.file(new MockMultipartFile("file", "intruder.txt", MediaType.TEXT_PLAIN_VALUE, SampleFiles.text("x")))
				.with(caller.auth())
				.exchange();
		}
		var request = this.mvc.method(org.springframework.http.HttpMethod.valueOf(endpoint.method()))
			.uri(uri)
			.with(caller.auth());
		if (endpoint.body() != null) {
			request = request.contentType(MediaType.APPLICATION_JSON)
				.content(endpoint.body().formatted(caller.username()));
		}
		return request.exchange();
	}

	private void assertTenantAUnchanged() {
		assertThat(this.api.get(this.owner, "/api/workspaces/" + this.workspaceId)).hasStatusOk()
			.bodyJson()
			.satisfies(json -> {
				json.assertThat().extractingPath("$.name").isEqualTo("Tenant A");
				json.assertThat().extractingPath("$.memberCount").isEqualTo(1);
				json.assertThat().extractingPath("$.role").isEqualTo("OWNER");
			});
		assertThat(this.api.get(this.owner, "/api/workspaces/" + this.workspaceId + "/documents")).hasStatusOk()
			.bodyJson()
			.extractingPath("$[*].id")
			.asArray()
			.containsExactly(this.documentId.toString());
		assertThat(this.api.get(this.owner, "/api/workspaces/" + this.workspaceId + "/conversations/"
				+ this.conversationId))
			.hasStatusOk()
			.bodyJson()
			.extractingPath("$.messages.length()")
			.isEqualTo(2);
	}

}
