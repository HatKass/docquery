package dev.docquery.api.workspace;

import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.user.CurrentUser;

/**
 * The single place that decides whether a user may act in a workspace. Every workspace-scoped feature
 * (documents, chat, usage...) must call {@link #require} before touching tenant data, and then filter
 * its own queries by the same workspace id.
 * <p>
 * Non-members get <b>404</b>, not 403: from the outside, a workspace you do not belong to is
 * indistinguishable from one that does not exist, so ids cannot be probed. Members with too low a
 * role get 403, because they already know the workspace exists.
 */
@Component
public class WorkspaceAccess {

	private final WorkspaceMemberRepository members;

	WorkspaceAccess(WorkspaceMemberRepository members) {
		this.members = members;
	}

	/** Returns the caller's role, or throws if they are not a member or their role is too low. */
	public WorkspaceRole require(UUID workspaceId, CurrentUser user, WorkspaceRole required) {
		WorkspaceRole role = this.members.findRole(workspaceId, user.id()).orElseThrow(() -> notFound(workspaceId));
		if (!role.includes(required)) {
			throw ApiException.forbidden("insufficient-role",
					"This action requires the " + required + " role in this workspace; you are " + role + ".");
		}
		return role;
	}

	static ApiException notFound(UUID workspaceId) {
		return ApiException.notFound("workspace-not-found", "Workspace " + workspaceId + " was not found.");
	}

}
