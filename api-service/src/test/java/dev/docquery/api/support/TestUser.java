package dev.docquery.api.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A test identity. {@link #auth()} attaches an already-validated JWT to a MockMvc request, the same
 * way the resource server would after checking a real Keycloak token (that path is covered by
 * {@code KeycloakTokenIntegrationTests}).
 */
public record TestUser(String subject, String username) {

	/** A fresh user with a random subject, so tests never interfere with each other. */
	public static TestUser random(String usernamePrefix) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return new TestUser(UUID.randomUUID().toString(), usernamePrefix + "-" + suffix);
	}

	public RequestPostProcessor auth() {
		return jwt().jwt(token -> token.subject(this.subject)
			.claim("preferred_username", this.username)
			.claim("email", this.username + "@example.test")
			.claim("name", "Test " + this.username));
	}

}
