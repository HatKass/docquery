package dev.docquery.api.workspace;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceDtos.AddMemberRequest;
import dev.docquery.api.workspace.WorkspaceDtos.ChangeRoleRequest;
import dev.docquery.api.workspace.WorkspaceDtos.CreateWorkspaceRequest;
import dev.docquery.api.workspace.WorkspaceDtos.MemberResponse;
import dev.docquery.api.workspace.WorkspaceDtos.RenameWorkspaceRequest;
import dev.docquery.api.workspace.WorkspaceDtos.WorkspaceResponse;

@RestController
@RequestMapping("/api/workspaces")
class WorkspaceController {

	private final WorkspaceService service;

	WorkspaceController(WorkspaceService service) {
		this.service = service;
	}

	@GetMapping
	List<WorkspaceResponse> list(CurrentUser user) {
		return this.service.listMine(user);
	}

	@PostMapping
	ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest request, CurrentUser user) {
		WorkspaceResponse created = this.service.create(request.name(), user);
		return ResponseEntity.created(URI.create("/api/workspaces/" + created.id())).body(created);
	}

	@GetMapping("/{workspaceId}")
	WorkspaceResponse get(@PathVariable UUID workspaceId, CurrentUser user) {
		return this.service.get(workspaceId, user);
	}

	@PatchMapping("/{workspaceId}")
	WorkspaceResponse rename(@PathVariable UUID workspaceId, @Valid @RequestBody RenameWorkspaceRequest request,
			CurrentUser user) {
		return this.service.rename(workspaceId, request.name(), user);
	}

	@DeleteMapping("/{workspaceId}")
	ResponseEntity<Void> delete(@PathVariable UUID workspaceId, CurrentUser user) {
		this.service.delete(workspaceId, user);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{workspaceId}/members")
	List<MemberResponse> members(@PathVariable UUID workspaceId, CurrentUser user) {
		return this.service.listMembers(workspaceId, user);
	}

	@PostMapping("/{workspaceId}/members")
	ResponseEntity<MemberResponse> addMember(@PathVariable UUID workspaceId,
			@Valid @RequestBody AddMemberRequest request, CurrentUser user) {
		MemberResponse member = this.service.addMember(workspaceId, request.usernameOrEmail(), request.role(), user);
		return ResponseEntity.created(URI.create("/api/workspaces/" + workspaceId + "/members/" + member.userId()))
			.body(member);
	}

	@PutMapping("/{workspaceId}/members/{userId}")
	MemberResponse changeRole(@PathVariable UUID workspaceId, @PathVariable UUID userId,
			@Valid @RequestBody ChangeRoleRequest request, CurrentUser user) {
		return this.service.changeRole(workspaceId, userId, request.role(), user);
	}

	@DeleteMapping("/{workspaceId}/members/{userId}")
	ResponseEntity<Void> removeMember(@PathVariable UUID workspaceId, @PathVariable UUID userId, CurrentUser user) {
		this.service.removeMember(workspaceId, userId, user);
		return ResponseEntity.noContent().build();
	}

}
