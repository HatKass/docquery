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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.TestUser;

/**
 * Tenant isolation: a user must never see or change anything in a workspace they do not belong to.
 * <p>
 * Every workspace-scoped endpoint is called by an outsider and must answer 404 (indistinguishable
 * from a non-existent workspace) without side effects. {@link #everyWorkspaceScopedEndpointIsCovered}
 * fails as soon as someone adds a new {@code /api/workspaces/{workspaceId}/...} endpoint without
 * adding it to {@link #workspaceScopedEndpoints()}, so later phases (documents, chunks, chats,
 * citations) cannot forget isolation.
 */
class TenantIsolationTests extends ApiIntegrationTest {

	record Endpoint(String method, String pathPattern, String body) {

		String uri(UUID workspaceId, UUID memberId) {
			return this.pathPattern.replace("{workspaceId}", workspaceId.toString())
				.replace("{userId}", memberId.toString());
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
				new Endpoint("DELETE", "/api/workspaces/{workspaceId}/members/{userId}", null));
	}

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	TestUser owner;

	TestUser outsider;

	UUID ownerId;

	UUID workspaceId;

	@BeforeEach
	void twoTenants() {
		this.owner = TestUser.random("tenant-a-owner");
		this.outsider = TestUser.random("tenant-b-owner");
		this.ownerId = this.api.signIn(this.owner);
		this.api.signIn(this.outsider);
		this.workspaceId = this.api.createWorkspace(this.owner, "Tenant A");
		this.api.createWorkspace(this.outsider, "Tenant B");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("workspaceScopedEndpoints")
	void outsiderGets404AndChangesNothing(Endpoint endpoint) {
		MvcTestResult result = call(endpoint, this.outsider);

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:workspace-not-found");
		assertTenantAUnchanged();
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

	private MvcTestResult call(Endpoint endpoint, TestUser caller) {
		String uri = endpoint.uri(this.workspaceId, this.ownerId);
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
	}

}
