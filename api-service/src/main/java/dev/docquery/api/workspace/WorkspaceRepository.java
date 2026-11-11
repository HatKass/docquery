package dev.docquery.api.workspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.docquery.api.workspace.WorkspaceDtos.WorkspaceResponse;

/**
 * Workspace queries. Every read is <em>joined through the caller's membership</em>, so a query can
 * only ever return workspaces the given user belongs to.
 */
@Repository
class WorkspaceRepository {

	private static final String SELECT_FOR_MEMBER = """
			SELECT w.id, w.name, w.created_at, m.role,
			       (SELECT count(*) FROM workspace_member c WHERE c.workspace_id = w.id) AS member_count
			  FROM workspace w
			  JOIN workspace_member m ON m.workspace_id = w.id AND m.user_id = :userId
			""";

	private final JdbcClient jdbc;

	WorkspaceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<WorkspaceResponse> findAllForMember(UUID userId) {
		return this.jdbc.sql(SELECT_FOR_MEMBER + " ORDER BY lower(w.name), w.id")
			.param("userId", userId)
			.query(WorkspaceRepository::map)
			.list();
	}

	Optional<WorkspaceResponse> findForMember(UUID workspaceId, UUID userId) {
		return this.jdbc.sql(SELECT_FOR_MEMBER + " WHERE w.id = :workspaceId")
			.param("userId", userId)
			.param("workspaceId", workspaceId)
			.query(WorkspaceRepository::map)
			.optional();
	}

	UUID insert(String name, UUID createdBy) {
		return this.jdbc.sql("INSERT INTO workspace (name, created_by) VALUES (:name, :createdBy) RETURNING id")
			.param("name", name)
			.param("createdBy", createdBy)
			.query(UUID.class)
			.single();
	}

	void rename(UUID workspaceId, String name) {
		this.jdbc.sql("UPDATE workspace SET name = :name, updated_at = now() WHERE id = :id")
			.param("name", name)
			.param("id", workspaceId)
			.update();
	}

	void delete(UUID workspaceId) {
		this.jdbc.sql("DELETE FROM workspace WHERE id = :id").param("id", workspaceId).update();
	}

	/**
	 * Row-locks the workspace for the rest of the transaction. Membership changes take this lock first,
	 * so two concurrent requests cannot both remove "the other" owner and leave the workspace ownerless.
	 */
	void lock(UUID workspaceId) {
		this.jdbc.sql("SELECT id FROM workspace WHERE id = :id FOR UPDATE").param("id", workspaceId).query().rowSet();
	}

	private static WorkspaceResponse map(ResultSet rs, int rowNum) throws SQLException {
		return new WorkspaceResponse(rs.getObject("id", UUID.class), rs.getString("name"),
				WorkspaceRole.valueOf(rs.getString("role")), rs.getInt("member_count"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

}
