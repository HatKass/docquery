package dev.docquery.api.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.docquery.api.admin.AdminDtos.AdminUser;
import dev.docquery.api.admin.AdminDtos.FailedDocument;

/**
 * Cross-tenant queries for platform admins. Deliberately not workspace-scoped; only reachable through
 * {@code /api/admin/**}, which requires the admin role.
 */
@Repository
class AdminRepository {

	private static final String SELECT_USERS = """
			SELECT u.id, u.username, u.display_name, u.email, u.last_seen_at, u.workspace_limit_override,
			       (SELECT count(*) FROM workspace w WHERE w.created_by = u.id) AS created,
			       coalesce(c.questions, 0) AS questions, coalesce(c.uploads, 0) AS uploads
			  FROM app_user u
			  LEFT JOIN daily_usage_counter c ON c.user_id = u.id AND c.usage_date = :today
			""";

	private final JdbcClient jdbc;

	AdminRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Map<String, Long> documentsByStatus() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String status : List.of("UPLOADED", "EXTRACTING", "CHUNKING", "EMBEDDING", "READY", "FAILED")) {
			counts.put(status, 0L);
		}
		this.jdbc.sql("SELECT status, count(*) AS n FROM document GROUP BY status")
			.query((rs, i) -> counts.put(rs.getString("status"), rs.getLong("n")))
			.list();
		return counts;
	}

	long count(String table) {
		// table is one of a few constants, never user input.
		return this.jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	List<FailedDocument> failedDocuments(int limit) {
		return this.jdbc.sql("""
				SELECT d.id, d.workspace_id, w.name AS workspace_name, d.filename, d.content_type, d.failure_reason,
				       d.status_changed_at, u.display_name AS uploaded_by
				  FROM document d
				  JOIN workspace w ON w.id = d.workspace_id
				  LEFT JOIN app_user u ON u.id = d.uploaded_by
				 WHERE d.status = 'FAILED'
				 ORDER BY d.status_changed_at DESC, d.id
				 LIMIT :limit
				""")
			.param("limit", limit)
			.query((rs, i) -> new FailedDocument(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
					rs.getString("workspace_name"), rs.getString("filename"), rs.getString("content_type"),
					rs.getString("failure_reason"), rs.getObject("status_changed_at", OffsetDateTime.class).toInstant(),
					rs.getString("uploaded_by")))
			.list();
	}

	/** Matches username, e-mail or display name (case-insensitive substring); most recently seen first. */
	List<AdminUser> users(String query, LocalDate today, int defaultLimit, int limit) {
		String pattern = (query == null || query.isBlank()) ? null : "%" + escapeLike(query.strip().toLowerCase()) + "%";
		return this.jdbc.sql(SELECT_USERS + """
				 WHERE CAST(:pattern AS text) IS NULL
				    OR lower(u.username) LIKE :pattern OR lower(u.email) LIKE :pattern OR lower(u.display_name) LIKE :pattern
				 ORDER BY u.last_seen_at DESC, u.id
				 LIMIT :limit
				""")
			.param("today", today)
			.param("pattern", pattern, java.sql.Types.VARCHAR)
			.param("limit", limit)
			.query((rs, i) -> map(rs, defaultLimit))
			.list();
	}

	Optional<AdminUser> user(UUID userId, LocalDate today, int defaultLimit) {
		return this.jdbc.sql(SELECT_USERS + " WHERE u.id = :id")
			.param("today", today)
			.param("id", userId)
			.query((rs, i) -> map(rs, defaultLimit))
			.optional();
	}

	private static AdminUser map(java.sql.ResultSet rs, int defaultLimit) throws java.sql.SQLException {
		Integer override = rs.getObject("workspace_limit_override", Integer.class);
		int effective = (override != null) ? override : defaultLimit;
		return new AdminUser(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
				rs.getString("email"), rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(), rs.getInt("created"),
				override, (effective == 0) ? null : effective, rs.getInt("questions"), rs.getInt("uploads"));
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
