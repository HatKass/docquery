package dev.docquery.api.workspace;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the workspace API. Entities never leave the service layer. */
public final class WorkspaceDtos {

	private WorkspaceDtos() {
	}

	/** A workspace as seen by the caller, including the caller's own role in it. */
	public record WorkspaceResponse(UUID id, String name, WorkspaceRole role, int memberCount, Instant createdAt) {
	}

	public record MemberResponse(UUID userId, String username, String displayName, String email, WorkspaceRole role,
			Instant joinedAt) {
	}

	public record CreateWorkspaceRequest(@NotBlank @Size(max = 100) String name) {
	}

	public record RenameWorkspaceRequest(@NotBlank @Size(max = 100) String name) {
	}

	public record AddMemberRequest(@NotBlank @Size(max = 320) String usernameOrEmail, @NotNull WorkspaceRole role) {
	}

	public record ChangeRoleRequest(@NotNull WorkspaceRole role) {
	}

}
