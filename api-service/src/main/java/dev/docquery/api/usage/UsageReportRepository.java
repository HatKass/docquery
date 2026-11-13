package dev.docquery.api.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.docquery.api.usage.UsageDtos.UsageKind;

@Repository
class UsageReportRepository {

	/**
	 * Usage of one (day, workspace, user, model, kind) combination. Names are {@code null} when the
	 * user or workspace no longer exists.
	 */
	record UsageRow(LocalDate day, UUID workspaceId, String workspaceName, UUID userId, String username,
			String displayName, String model, UsageKind kind, long inputTokens, long outputTokens, long events,
			boolean estimated) {
	}

	private final JdbcClient jdbc;

	UsageReportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * All chat and embedding usage since {@code from} (inclusive, UTC), grouped. With a workspace id,
	 * only that workspace's usage (tenant-scoped report); with {@code null}, the whole platform (admin).
	 */
	List<UsageRow> find(LocalDate from, UUID workspaceId) {
		Instant since = from.atStartOfDay(ZoneOffset.UTC).toInstant();
		return this.jdbc.sql("""
				WITH usage AS (
				    SELECT created_at, workspace_id, user_id, model, 'CHAT' AS kind,
				           prompt_tokens::bigint AS input_tokens, completion_tokens::bigint AS output_tokens, estimated
				      FROM chat_usage
				     WHERE created_at >= :since AND (CAST(:workspaceId AS uuid) IS NULL OR workspace_id = :workspaceId)
				    UNION ALL
				    SELECT created_at, workspace_id, user_id, model, 'EMBEDDING', input_tokens, 0, estimated
				      FROM embedding_usage
				     WHERE created_at >= :since AND (CAST(:workspaceId AS uuid) IS NULL OR workspace_id = :workspaceId)
				), grouped AS (
				    SELECT (created_at AT TIME ZONE 'UTC')::date AS day, workspace_id, user_id, model, kind,
				           sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,
				           count(*) AS events, bool_or(estimated) AS estimated
				      FROM usage
				     GROUP BY 1, 2, 3, 4, 5
				)
				SELECT g.*, w.name AS workspace_name, u.username, u.display_name
				  FROM grouped g
				  LEFT JOIN workspace w ON w.id = g.workspace_id
				  LEFT JOIN app_user u ON u.id = g.user_id
				 ORDER BY g.day
				""")
			.param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
			.param("workspaceId", workspaceId, java.sql.Types.OTHER)
			.query((rs, i) -> new UsageRow(rs.getObject("day", LocalDate.class), rs.getObject("workspace_id", UUID.class),
					rs.getString("workspace_name"), rs.getObject("user_id", UUID.class), rs.getString("username"),
					rs.getString("display_name"), rs.getString("model"), UsageKind.valueOf(rs.getString("kind")),
					rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("events"),
					rs.getBoolean("estimated")))
			.list();
	}

}
