package dev.docquery.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.TestcontainersConfiguration;

/**
 * End-to-end token validation against a real Keycloak running the committed realm export
 * ({@code infra/keycloak/realm/docquery-realm.json}). Proves that real tokens are accepted (signature
 * via JWKS, issuer, audience mapper) and that forged or foreign tokens are rejected.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class KeycloakTokenIntegrationTests {

	/** Same image as compose.yaml. */
	static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.2";

	static final Path REALM_FILE = Path.of("..", "infra", "keycloak", "realm", "docquery-realm.json");

	@Container
	static final GenericContainer<?> keycloak = new GenericContainer<>(KEYCLOAK_IMAGE)
		.withCommand("start-dev", "--import-realm")
		.withEnv("KC_HEALTH_ENABLED", "true")
		.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
		.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
		.withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE), "/opt/keycloak/data/import/docquery-realm.json")
		.withExposedPorts(8080, 9000)
		// start-dev builds Keycloak before starting it: about 2.5 minutes on a busy laptop.
		.waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(5)));

	@DynamicPropertySource
	static void oidc(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realmUrl("docquery"));
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> realmUrl("docquery") + "/protocol/openid-connect/certs");
	}

	static String realmUrl(String realm) {
		return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/" + realm;
	}

	private final HttpClient http = HttpClient.newHttpClient();

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Test
	void realTokenForTheDemoOwnerIsAcceptedAndSeesTheDemoWorkspace() throws Exception {
		String token = passwordGrant("docquery", "docquery-dev-cli", "owner", "demo123");

		assertThat(this.mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.hasStatusOk()
			.bodyJson()
			.satisfies(body -> {
				body.assertThat().extractingPath("$.username").isEqualTo("owner");
				body.assertThat().extractingPath("$.displayName").isEqualTo("Olivia Owner");
				body.assertThat().extractingPath("$.workspaces[0].name").isEqualTo("Demo workspace");
				body.assertThat().extractingPath("$.workspaces[0].role").isEqualTo("OWNER");
			});
	}

	@Test
	void demoViewerCanReadButNotRename() throws Exception {
		String token = passwordGrant("docquery", "docquery-dev-cli", "viewer", "demo123");
		String demo = "/api/workspaces/00000000-0000-4000-8000-000000000001";

		assertThat(this.mvc.get().uri(demo).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.role")
			.isEqualTo("VIEWER");
		assertThat(this.mvc.patch()
			.uri(demo)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Renamed\"}")).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void outsiderDoesNotSeeTheDemoWorkspace() throws Exception {
		String token = passwordGrant("docquery", "docquery-dev-cli", "outsider", "demo123");

		assertThat(this.mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.workspaces")
			.asArray()
			.isEmpty();
		assertThat(this.mvc.get()
			.uri("/api/workspaces/00000000-0000-4000-8000-000000000001")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatus(HttpStatus.NOT_FOUND);
	}

	/** The docquery-admin realm role travels in realm_access.roles and unlocks /api/admin. */
	@Test
	void demoAdminIsRecognisedFromTheRealmRole() throws Exception {
		String admin = passwordGrant("docquery", "docquery-dev-cli", "admin", "demo123");
		String owner = passwordGrant("docquery", "docquery-dev-cli", "owner", "demo123");

		assertThat(this.mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.admin")
			.isEqualTo(true);
		assertThat(this.mvc.get().uri("/api/admin/overview").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
			.hasStatusOk();
		// A workspace OWNER is not a platform admin.
		assertThat(this.mvc.get().uri("/api/admin/overview").header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void tamperedTokenIsRejected() throws Exception {
		String token = passwordGrant("docquery", "docquery-dev-cli", "viewer", "demo123");
		String[] parts = token.split("\\.");
		// Re-encode the payload with a different subject but keep the original signature.
		String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
			.replace("6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0003", "6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0001");
		String forged = parts[0] + "."
				+ java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
				+ "." + parts[2];

		assertThat(this.mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void tokenFromAnotherRealmIsRejected() throws Exception {
		// Valid Keycloak token, but wrong issuer, wrong signing keys and no docquery-api audience.
		String token = passwordGrant("master", "admin-cli", "admin", "admin");

		assertThat(this.mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	private String passwordGrant(String realm, String clientId, String username, String password)
			throws IOException, InterruptedException {
		String form = Map.of("grant_type", "password", "client_id", clientId, "username", username, "password", password,
				"scope", "openid")
			.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
		HttpRequest request = HttpRequest
			.newBuilder(URI.create(realmUrl(realm) + "/protocol/openid-connect/token"))
			.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return this.json.readTree(response.body()).get("access_token").asString();
	}

}
