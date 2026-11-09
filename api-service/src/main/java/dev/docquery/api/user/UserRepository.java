package dev.docquery.api.user;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

	private static final RowMapper<CurrentUser> MAPPER = (rs, i) -> new CurrentUser(rs.getObject("id", UUID.class),
			rs.getString("subject"), rs.getString("username"), rs.getString("email"), rs.getString("display_name"));

	private final JdbcClient jdbc;

	UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the user on first sight and refreshes profile fields from the token. To avoid a write on
	 * every request, an existing row is only updated when its profile changed or {@code last_seen_at}
	 * is older than {@code touchInterval}; {@code RETURNING} then yields no row and we read it instead.
	 */
	CurrentUser upsert(String subject, String username, String email, String displayName, Duration touchInterval) {
		return this.jdbc.sql("""
				INSERT INTO app_user (subject, username, email, display_name)
				VALUES (:subject, :username, :email, :displayName)
				ON CONFLICT (subject) DO UPDATE
				   SET username = EXCLUDED.username,
				       email = EXCLUDED.email,
				       display_name = EXCLUDED.display_name,
				       last_seen_at = now()
				 WHERE app_user.username IS DISTINCT FROM EXCLUDED.username
				    OR app_user.email IS DISTINCT FROM EXCLUDED.email
				    OR app_user.display_name IS DISTINCT FROM EXCLUDED.display_name
				    OR app_user.last_seen_at < now() - make_interval(secs => :touchSeconds)
				RETURNING id, subject, username, email, display_name
				""")
			.param("subject", subject)
			.param("username", username)
			.param("email", email)
			.param("displayName", displayName)
			.param("touchSeconds", (double) touchInterval.toSeconds())
			.query(MAPPER)
			.optional()
			.orElseGet(() -> findBySubject(subject).orElseThrow());
	}

	Optional<CurrentUser> findBySubject(String subject) {
		return this.jdbc.sql("SELECT id, subject, username, email, display_name FROM app_user WHERE subject = :subject")
			.param("subject", subject)
			.query(MAPPER)
			.optional();
	}

	/** Case-insensitive match on username or e-mail; the most recently active user wins a tie. */
	Optional<CurrentUser> findByUsernameOrEmail(String usernameOrEmail) {
		return this.jdbc.sql("""
				SELECT id, subject, username, email, display_name
				  FROM app_user
				 WHERE lower(username) = lower(:value) OR lower(email) = lower(:value)
				 ORDER BY last_seen_at DESC
				 LIMIT 1
				""").param("value", usernameOrEmail).query(MAPPER).optional();
	}

}
