package dev.docquery.api.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.docquery.api.workspace.WorkspaceDtos.MemberResponse;

@Repository
class WorkspaceMemberRepository {

	private static final String SELECT_MEMBERS = """
			SELECT u.id, u.username, u.display_name, u.email, m.role, m.joined_at
			  FROM workspace_member m
			  JOIN app_user u ON u.id = m.user_id
			""";

	private static final RowMapper<MemberResponse> MAPPER = (rs, i) -> new MemberResponse(rs.getObject("id", UUID.class),
			rs.getString("username"), rs.getString("display_name"), rs.getString("email"),
			WorkspaceRole.valueOf(rs.getString("role")), rs.getObject("joined_at", OffsetDateTime.class).toInstant());

	private final JdbcClient jdbc;

	WorkspaceMemberRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<WorkspaceRole> findRole(UUID workspaceId, UUID userId) {
		return this.jdbc.sql("SELECT role FROM workspace_member WHERE workspace_id = :workspaceId AND user_id = :userId")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.query(String.class)
			.optional()
			.map(WorkspaceRole::valueOf);
	}

	List<MemberResponse> findAll(UUID workspaceId) {
		return this.jdbc.sql(SELECT_MEMBERS + """
				 WHERE m.workspace_id = :workspaceId
				 ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'MEMBER' THEN 1 ELSE 2 END, lower(u.display_name)
				""").param("workspaceId", workspaceId).query(MAPPER).list();
	}

	Optional<MemberResponse> find(UUID workspaceId, UUID userId) {
		return this.jdbc.sql(SELECT_MEMBERS + " WHERE m.workspace_id = :workspaceId AND m.user_id = :userId")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.query(MAPPER)
			.optional();
	}

	/** @return {@code false} if the user already was a member */
	boolean insert(UUID workspaceId, UUID userId, WorkspaceRole role) {
		return this.jdbc.sql("""
				INSERT INTO workspace_member (workspace_id, user_id, role)
				VALUES (:workspaceId, :userId, :role)
				ON CONFLICT (workspace_id, user_id) DO NOTHING
				""")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.param("role", role.name())
			.update() == 1;
	}

	void updateRole(UUID workspaceId, UUID userId, WorkspaceRole role) {
		this.jdbc.sql("UPDATE workspace_member SET role = :role WHERE workspace_id = :workspaceId AND user_id = :userId")
			.param("role", role.name())
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.update();
	}

	void delete(UUID workspaceId, UUID userId) {
		this.jdbc.sql("DELETE FROM workspace_member WHERE workspace_id = :workspaceId AND user_id = :userId")
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.update();
	}

	int countOwners(UUID workspaceId) {
		return this.jdbc.sql("SELECT count(*) FROM workspace_member WHERE workspace_id = :workspaceId AND role = 'OWNER'")
			.param("workspaceId", workspaceId)
			.query(Integer.class)
			.single();
	}

}
