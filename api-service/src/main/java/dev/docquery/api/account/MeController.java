package dev.docquery.api.account;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceDtos.WorkspaceResponse;
import dev.docquery.api.workspace.WorkspaceService;

/** The signed-in user's profile and workspaces: what the SPA loads right after login. */
@RestController
@RequestMapping("/api/me")
class MeController {

	record MeResponse(UUID id, String username, String email, String displayName, List<WorkspaceResponse> workspaces) {
	}

	private final WorkspaceService workspaces;

	MeController(WorkspaceService workspaces) {
		this.workspaces = workspaces;
	}

	@GetMapping
	MeResponse me(CurrentUser user) {
		return new MeResponse(user.id(), user.username(), user.email(), user.displayName(),
				this.workspaces.listMine(user));
	}

}
