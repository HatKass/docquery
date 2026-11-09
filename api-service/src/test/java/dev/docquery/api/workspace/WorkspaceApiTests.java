package dev.docquery.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.TestUser;

class WorkspaceApiTests extends ApiIntegrationTest {

	@Nested
	class Authentication {

		@Test
		void apiRequiresABearerTokenAndAnswersWithProblemDetail() {
			MvcTestResult result = mvc.get().uri("/api/workspaces").exchange();

			assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED).hasContentType("application/problem+json");
			// Spring Security 7 also advertises RFC 9728 resource metadata in this header.
			assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
			assertThat(result).bodyJson()
				.extractingPath("$.type")
				.isEqualTo("urn:docquery:problem:unauthenticated");
		}

		@Test
		void healthStaysPublic() {
			assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
		}

	}

	@Nested
	class Me {

		@Test
		void firstRequestProvisionsTheUserFromTokenClaims() {
			TestUser user = TestUser.random("alice");

			MvcTestResult result = api.get(user, "/api/me");

			assertThat(result).hasStatusOk();
			JsonNode me = api.body(result);
			assertThat(me.get("username").asString()).isEqualTo(user.username());
			assertThat(me.get("email").asString()).isEqualTo(user.username() + "@example.test");
			assertThat(me.get("displayName").asString()).isEqualTo("Test " + user.username());
			assertThat(me.get("workspaces")).isEmpty();
		}

		@Test
		void subsequentRequestsMapToTheSameLocalUser() {
			TestUser user = TestUser.random("bob");
			assertThat(api.signIn(user)).isEqualTo(api.signIn(user));
		}

		@Test
		void demoSeedPutsTheDemoOwnerInTheDemoWorkspace() {
			// Matches the fixed Keycloak user id of "owner" in infra/keycloak/realm/docquery-realm.json.
			TestUser demoOwner = new TestUser("6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0001", "owner");

			assertThat(api.get(demoOwner, "/api/me")).bodyJson().satisfies(json -> {
				json.assertThat().extractingPath("$.workspaces[0].name").isEqualTo("Demo workspace");
				json.assertThat().extractingPath("$.workspaces[0].role").isEqualTo("OWNER");
				json.assertThat().extractingPath("$.workspaces[0].memberCount").isEqualTo(3);
			});
		}

	}

	@Nested
	class Workspaces {

		@Test
		void creatorBecomesOwner() {
			TestUser owner = TestUser.random("owner");

			MvcTestResult result = api.post(owner, "/api/workspaces", "{\"name\":\"  Legal team  \"}");

			assertThat(result).hasStatus(HttpStatus.CREATED);
			JsonNode created = api.body(result);
			assertThat(created.get("name").asString()).isEqualTo("Legal team");
			assertThat(created.get("role").asString()).isEqualTo("OWNER");
			assertThat(created.get("memberCount").asInt()).isEqualTo(1);
			assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
				.isEqualTo("/api/workspaces/" + created.get("id").asString());
		}

		@Test
		void listShowsOnlyMyWorkspacesWithMyRole() {
			TestUser owner = TestUser.random("owner");
			TestUser viewer = TestUser.random("viewer");
			UUID shared = api.createWorkspace(owner, "Shared");
			api.createWorkspace(owner, "Private");
			api.addMember(owner, shared, viewer, "VIEWER");

			assertThat(api.get(viewer, "/api/workspaces")).bodyJson().satisfies(json -> {
				json.assertThat().extractingPath("$.length()").isEqualTo(1);
				json.assertThat().extractingPath("$[0].name").isEqualTo("Shared");
				json.assertThat().extractingPath("$[0].role").isEqualTo("VIEWER");
			});
		}

		@Test
		void blankOrTooLongNamesAreRejectedWithFieldErrors() {
			TestUser owner = TestUser.random("owner");

			assertThat(api.post(owner, "/api/workspaces", "{\"name\":\"   \"}")).hasStatus(HttpStatus.BAD_REQUEST)
				.bodyJson()
				.satisfies(json -> {
					json.assertThat().extractingPath("$.type").isEqualTo("urn:docquery:problem:validation");
					json.assertThat().extractingPath("$.errors[0].field").isEqualTo("name");
				});
			assertThat(api.post(owner, "/api/workspaces", "{\"name\":\"" + "x".repeat(101) + "\"}"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		}

		@Test
		void ownerCanRenameAndDelete() {
			TestUser owner = TestUser.random("owner");
			UUID id = api.createWorkspace(owner, "Old name");

			assertThat(api.patch(owner, "/api/workspaces/" + id, "{\"name\":\"New name\"}")).hasStatusOk()
				.bodyJson()
				.extractingPath("$.name")
				.isEqualTo("New name");
			assertThat(api.delete(owner, "/api/workspaces/" + id)).hasStatus(HttpStatus.NO_CONTENT);
			assertThat(api.get(owner, "/api/workspaces/" + id)).hasStatus(HttpStatus.NOT_FOUND);
		}

	}

	@Nested
	class RolesAndMembers {

		@Test
		void membersAndViewersCanReadButNotManage() {
			TestUser owner = TestUser.random("owner");
			TestUser member = TestUser.random("member");
			TestUser viewer = TestUser.random("viewer");
			TestUser newcomer = TestUser.random("newcomer");
			UUID id = api.createWorkspace(owner, "Team");
			api.addMember(owner, id, member, "MEMBER");
			api.addMember(owner, id, viewer, "VIEWER");
			api.signIn(newcomer);
			String base = "/api/workspaces/" + id;

			for (TestUser user : new TestUser[] { member, viewer }) {
				assertThat(api.get(user, base)).hasStatusOk();
				assertThat(api.get(user, base + "/members")).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(3);
				assertThat(api.patch(user, base, "{\"name\":\"Hijacked\"}")).hasStatus(HttpStatus.FORBIDDEN)
					.bodyJson()
					.extractingPath("$.type")
					.isEqualTo("urn:docquery:problem:insufficient-role");
				assertThat(api.delete(user, base)).hasStatus(HttpStatus.FORBIDDEN);
				assertThat(api.post(user, base + "/members",
						"{\"usernameOrEmail\":\"" + newcomer.username() + "\",\"role\":\"VIEWER\"}"))
					.hasStatus(HttpStatus.FORBIDDEN);
			}
		}

		@Test
		void ownerCanAddByEmailChangeRolesAndRemove() {
			TestUser owner = TestUser.random("owner");
			TestUser colleague = TestUser.random("colleague");
			UUID colleagueId = api.signIn(colleague);
			UUID id = api.createWorkspace(owner, "Team");
			String base = "/api/workspaces/" + id + "/members";

			assertThat(api.post(owner, base,
					"{\"usernameOrEmail\":\"" + colleague.username().toUpperCase() + "@EXAMPLE.TEST\",\"role\":\"VIEWER\"}"))
				.hasStatus(HttpStatus.CREATED)
				.bodyJson()
				.extractingPath("$.role")
				.isEqualTo("VIEWER");
			assertThat(api.put(owner, base + "/" + colleagueId, "{\"role\":\"MEMBER\"}")).hasStatusOk()
				.bodyJson()
				.extractingPath("$.role")
				.isEqualTo("MEMBER");
			assertThat(api.delete(owner, base + "/" + colleagueId)).hasStatus(HttpStatus.NO_CONTENT);
			assertThat(api.get(colleague, "/api/workspaces/" + id)).hasStatus(HttpStatus.NOT_FOUND);
		}

		@Test
		void addingUnknownOrExistingMembersFailsClearly() {
			TestUser owner = TestUser.random("owner");
			TestUser colleague = TestUser.random("colleague");
			UUID id = api.createWorkspace(owner, "Team");
			api.addMember(owner, id, colleague, "MEMBER");
			String base = "/api/workspaces/" + id + "/members";

			assertThat(api.post(owner, base, "{\"usernameOrEmail\":\"nobody-" + UUID.randomUUID() + "\",\"role\":\"MEMBER\"}"))
				.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
				.bodyJson()
				.extractingPath("$.type")
				.isEqualTo("urn:docquery:problem:user-not-found");
			assertThat(api.post(owner, base, "{\"usernameOrEmail\":\"" + colleague.username() + "\",\"role\":\"MEMBER\"}"))
				.hasStatus(HttpStatus.CONFLICT)
				.bodyJson()
				.extractingPath("$.type")
				.isEqualTo("urn:docquery:problem:already-member");
			assertThat(api.post(owner, base, "{\"usernameOrEmail\":\"" + colleague.username() + "\",\"role\":\"ADMIN\"}"))
				.hasStatus(HttpStatus.BAD_REQUEST);
		}

		@Test
		void theLastOwnerCannotLeaveOrBeDemoted() {
			TestUser owner = TestUser.random("owner");
			UUID ownerId = api.signIn(owner);
			UUID id = api.createWorkspace(owner, "Team");
			String self = "/api/workspaces/" + id + "/members/" + ownerId;

			assertThat(api.delete(owner, self)).hasStatus(HttpStatus.CONFLICT)
				.bodyJson()
				.extractingPath("$.type")
				.isEqualTo("urn:docquery:problem:last-owner");
			assertThat(api.put(owner, self, "{\"role\":\"MEMBER\"}")).hasStatus(HttpStatus.CONFLICT);
		}

		@Test
		void anOwnerCanStepDownOnceThereIsAnotherOwner() {
			TestUser owner = TestUser.random("owner");
			TestUser coOwner = TestUser.random("coowner");
			UUID ownerId = api.signIn(owner);
			UUID id = api.createWorkspace(owner, "Team");
			api.addMember(owner, id, coOwner, "OWNER");

			assertThat(api.put(owner, "/api/workspaces/" + id + "/members/" + ownerId, "{\"role\":\"VIEWER\"}"))
				.hasStatusOk();
			assertThat(api.patch(owner, "/api/workspaces/" + id, "{\"name\":\"x\"}")).hasStatus(HttpStatus.FORBIDDEN);
		}

		@Test
		void anyMemberCanLeave() {
			TestUser owner = TestUser.random("owner");
			TestUser viewer = TestUser.random("viewer");
			UUID id = api.createWorkspace(owner, "Team");
			api.addMember(owner, id, viewer, "VIEWER");
			UUID viewerId = api.signIn(viewer);

			assertThat(api.delete(viewer, "/api/workspaces/" + id + "/members/" + viewerId))
				.hasStatus(HttpStatus.NO_CONTENT);
			assertThat(api.get(viewer, "/api/workspaces")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		}

	}

}
