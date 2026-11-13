package dev.docquery.api.account;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.docquery.api.security.AdminAuthorities;
import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceDtos.WorkspaceResponse;
import dev.docquery.api.workspace.WorkspaceService;

/** The signed-in user's profile and workspaces: what the SPA loads right after login. */
@RestController
@RequestMapping("/api/me")
class MeController {

	/** @param admin platform admin (Keycloak realm role): the SPA shows the admin page */
	record MeResponse(UUID id, String username, String email, String displayName, boolean admin,
			List<WorkspaceResponse> workspaces) {
	}

	private final WorkspaceService workspaces;

	MeController(WorkspaceService workspaces) {
		this.workspaces = workspaces;
	}

	@GetMapping
	MeResponse me(CurrentUser user, Authentication authentication) {
		return new MeResponse(user.id(), user.username(), user.email(), user.displayName(),
				AdminAuthorities.isAdmin(authentication), this.workspaces.listMine(user));
	}

}
