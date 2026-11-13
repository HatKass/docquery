package dev.docquery.api.usage;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class QuotaRepository {

	/** Today's counters of one user (zero when nothing was counted yet). */
	record DailyCounts(int questions, int uploads) {
	}

	/** Workspaces created by a user that still exist, and the user's admin override (if any). */
	record WorkspaceAllowance(int created, Integer override) {
	}

	private final JdbcClient jdbc;

	QuotaRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Adds one question to today's counter unless that would exceed {@code limit} (0 = no limit). One
	 * statement, so concurrent requests can never overshoot the quota.
	 * @return the new count, or empty if the quota is used up
	 */
	Optional<Integer> incrementQuestions(UUID userId, LocalDate day, int limit) {
		return increment("questions", userId, day, limit);
	}

	Optional<Integer> incrementUploads(UUID userId, LocalDate day, int limit) {
		return increment("uploads", userId, day, limit);
	}

	private Optional<Integer> increment(String column, UUID userId, LocalDate day, int limit) {
		// column is one of two constants, never user input.
		return this.jdbc.sql("""
				INSERT INTO daily_usage_counter AS c (user_id, usage_date, %1$s)
				VALUES (:userId, :day, 1)
				ON CONFLICT (user_id, usage_date) DO UPDATE SET %1$s = c.%1$s + 1
				 WHERE :limit = 0 OR c.%1$s < :limit
				RETURNING c.%1$s
				""".formatted(column))
			.param("userId", userId)
			.param("day", day)
			.param("limit", limit)
			.query(Integer.class)
			.optional();
	}

	DailyCounts dailyCounts(UUID userId, LocalDate day) {
		return this.jdbc.sql("SELECT questions, uploads FROM daily_usage_counter WHERE user_id = :userId AND usage_date = :day")
			.param("userId", userId)
			.param("day", day)
			.query((rs, i) -> new DailyCounts(rs.getInt("questions"), rs.getInt("uploads")))
			.optional()
			.orElse(new DailyCounts(0, 0));
	}

	/**
	 * Row-locks the user for the rest of the transaction and returns their workspace allowance, so two
	 * concurrent "create workspace" requests are checked one after the other.
	 */
	WorkspaceAllowance lockWorkspaceAllowance(UUID userId) {
		return this.jdbc.sql("""
				SELECT u.workspace_limit_override,
				       (SELECT count(*) FROM workspace w WHERE w.created_by = u.id) AS created
				  FROM app_user u
				 WHERE u.id = :userId
				   FOR UPDATE OF u
				""")
			.param("userId", userId)
			.query((rs, i) -> new WorkspaceAllowance(rs.getInt("created"),
					rs.getObject("workspace_limit_override", Integer.class)))
			.single();
	}

	WorkspaceAllowance workspaceAllowance(UUID userId) {
		return this.jdbc.sql("""
				SELECT u.workspace_limit_override,
				       (SELECT count(*) FROM workspace w WHERE w.created_by = u.id) AS created
				  FROM app_user u
				 WHERE u.id = :userId
				""")
			.param("userId", userId)
			.query((rs, i) -> new WorkspaceAllowance(rs.getInt("created"),
					rs.getObject("workspace_limit_override", Integer.class)))
			.single();
	}

	/** Row-locks the workspace (serializes concurrent uploads) and counts its documents. */
	int lockAndCountDocuments(UUID workspaceId) {
		this.jdbc.sql("SELECT id FROM workspace WHERE id = :id FOR UPDATE").param("id", workspaceId).query().rowSet();
		return this.jdbc.sql("SELECT count(*) FROM document WHERE workspace_id = :id")
			.param("id", workspaceId)
			.query(Integer.class)
			.single();
	}

	/** @return whether the user exists */
	boolean setWorkspaceLimitOverride(UUID userId, Integer limit) {
		return this.jdbc.sql("UPDATE app_user SET workspace_limit_override = :limit WHERE id = :userId")
			.param("limit", limit, java.sql.Types.INTEGER)
			.param("userId", userId)
			.update() == 1;
	}

}
