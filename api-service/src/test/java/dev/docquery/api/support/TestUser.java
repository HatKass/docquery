package dev.docquery.api.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import dev.docquery.api.security.AdminAuthorities;

/**
 * A test identity. {@link #auth()} attaches an already-validated JWT to a MockMvc request, the same
 * way the resource server would after checking a real Keycloak token (that path is covered by
 * {@code KeycloakTokenIntegrationTests}). Authorities come from the application's own token
 * converter, so a platform admin is recognised exactly as in production: by the Keycloak realm role.
 *
 * @param admin whether the token carries the {@code docquery-admin} realm role
 */
public record TestUser(String subject, String username, boolean admin) {

	public static final String ADMIN_ROLE = "docquery-admin";

	public TestUser(String subject, String username) {
		this(subject, username, false);
	}

	/** A fresh user with a random subject, so tests never interfere with each other. */
	public static TestUser random(String usernamePrefix) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return new TestUser(UUID.randomUUID().toString(), usernamePrefix + "-" + suffix);
	}

	/** A fresh platform admin. */
	public static TestUser randomAdmin(String usernamePrefix) {
		TestUser user = random(usernamePrefix);
		return new TestUser(user.subject(), user.username(), true);
	}

	public RequestPostProcessor auth() {
		List<String> roles = this.admin ? List.of("default-roles-docquery", ADMIN_ROLE) : List.of("default-roles-docquery");
		return jwt().jwt(token -> token.subject(this.subject)
			.claim("preferred_username", this.username)
			.claim("email", this.username + "@example.test")
			.claim("name", "Test " + this.username)
			.claim("realm_access", Map.of("roles", roles))).authorities(new AdminAuthorities(ADMIN_ROLE));
	}

}
