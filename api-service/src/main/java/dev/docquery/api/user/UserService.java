package dev.docquery.api.user;

import java.time.Duration;
import java.util.Optional;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Maps a validated access token to a local user, provisioning it just-in-time on first login.
 * Keycloak stays the source of truth for identity; the local row exists so workspaces, documents
 * and usage can reference users with foreign keys.
 */
@Service
public class UserService {

	static final Duration LAST_SEEN_TOUCH_INTERVAL = Duration.ofMinutes(5);

	private final UserRepository users;

	UserService(UserRepository users) {
		this.users = users;
	}

	public CurrentUser resolve(Jwt jwt) {
		String subject = jwt.getSubject();
		if (!StringUtils.hasText(subject)) {
			throw new IllegalArgumentException("Access token has no 'sub' claim");
		}
		String username = firstNonBlank(jwt.getClaimAsString("preferred_username"), subject);
		String email = blankToNull(jwt.getClaimAsString("email"));
		String displayName = firstNonBlank(jwt.getClaimAsString("name"),
				joinNames(jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name")), username);
		return this.users.upsert(subject, username, email, displayName, LAST_SEEN_TOUCH_INTERVAL);
	}

	/** Looks up a user who has signed in at least once, by username or e-mail. */
	public Optional<CurrentUser> findByUsernameOrEmail(String usernameOrEmail) {
		return this.users.findByUsernameOrEmail(usernameOrEmail.strip());
	}

	private static String joinNames(String given, String family) {
		String joined = ((given == null ? "" : given.strip()) + " " + (family == null ? "" : family.strip())).strip();
		return joined.isEmpty() ? null : joined;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.strip();
			}
		}
		throw new IllegalArgumentException("No non-blank value");
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.strip() : null;
	}

}
