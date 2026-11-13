package dev.docquery.api.workspace;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.usage.QuotaService;
import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.user.UserService;
import dev.docquery.api.workspace.WorkspaceDtos.MemberResponse;
import dev.docquery.api.workspace.WorkspaceDtos.WorkspaceResponse;

/**
 * Workspace and membership use cases. Each method starts with an access check through
 * {@link WorkspaceAccess}; every query is additionally scoped by workspace id.
 */
@Service
public class WorkspaceService {

	private final WorkspaceRepository workspaces;

	private final WorkspaceMemberRepository members;

	private final WorkspaceAccess access;

	private final UserService users;

	private final ApplicationEventPublisher events;

	private final QuotaService quotas;

	WorkspaceService(WorkspaceRepository workspaces, WorkspaceMemberRepository members, WorkspaceAccess access,
			UserService users, ApplicationEventPublisher events, QuotaService quotas) {
		this.quotas = quotas;
		this.workspaces = workspaces;
		this.members = members;
		this.access = access;
		this.users = users;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public List<WorkspaceResponse> listMine(CurrentUser user) {
		return this.workspaces.findAllForMember(user.id());
	}

	@Transactional(readOnly = true)
	public WorkspaceResponse get(UUID workspaceId, CurrentUser user) {
		return this.workspaces.findForMember(workspaceId, user.id())
			.orElseThrow(() -> WorkspaceAccess.notFound(workspaceId));
	}

	/**
	 * Creates a workspace; the creator becomes its first OWNER. Each user may create a limited number
	 * of workspaces (docquery.usage.quotas.max-workspaces-per-user, overridable per user by an admin).
	 */
	@Transactional
	public WorkspaceResponse create(String name, CurrentUser user) {
		this.quotas.requireWorkspaceSlot(user.id());
		UUID id = this.workspaces.insert(name.strip(), user.id());
		this.members.insert(id, user.id(), WorkspaceRole.OWNER);
		return get(id, user);
	}

	@Transactional
	public WorkspaceResponse rename(UUID workspaceId, String name, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.OWNER);
		this.workspaces.rename(workspaceId, name.strip());
		return get(workspaceId, user);
	}

	@Transactional
	public void delete(UUID workspaceId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.OWNER);
		// Document rows cascade; stored files are removed after commit by a WorkspaceDeleted listener.
		this.workspaces.delete(workspaceId);
		this.events.publishEvent(new WorkspaceEvents.WorkspaceDeleted(workspaceId));
	}

	@Transactional(readOnly = true)
	public List<MemberResponse> listMembers(UUID workspaceId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		return this.members.findAll(workspaceId);
	}

	/**
	 * Adds an existing DocQuery user. Users are provisioned on first sign-in, so the person must have
	 * signed in once; inviting by e-mail is out of scope for this phase.
	 */
	@Transactional
	public MemberResponse addMember(UUID workspaceId, String usernameOrEmail, WorkspaceRole role, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.OWNER);
		CurrentUser target = this.users.findByUsernameOrEmail(usernameOrEmail)
			.orElseThrow(() -> ApiException.unprocessable("user-not-found", "No DocQuery user matches '"
					+ usernameOrEmail.strip() + "'. They need to sign in once before they can be added."));
		if (!this.members.insert(workspaceId, target.id(), role)) {
			throw ApiException.conflict("already-member", target.username() + " is already a member of this workspace.");
		}
		return this.members.find(workspaceId, target.id()).orElseThrow();
	}

	@Transactional
	public MemberResponse changeRole(UUID workspaceId, UUID memberId, WorkspaceRole role, CurrentUser user) {
		this.workspaces.lock(workspaceId);
		this.access.require(workspaceId, user, WorkspaceRole.OWNER);
		MemberResponse member = requireMember(workspaceId, memberId);
		if (member.role() == WorkspaceRole.OWNER && role != WorkspaceRole.OWNER) {
			ensureAnotherOwner(workspaceId);
		}
		this.members.updateRole(workspaceId, memberId, role);
		return this.members.find(workspaceId, memberId).orElseThrow();
	}

	/** Owners can remove anyone; every member can remove themselves (leave the workspace). */
	@Transactional
	public void removeMember(UUID workspaceId, UUID memberId, CurrentUser user) {
		boolean leaving = memberId.equals(user.id());
		this.workspaces.lock(workspaceId);
		this.access.require(workspaceId, user, leaving ? WorkspaceRole.VIEWER : WorkspaceRole.OWNER);
		MemberResponse member = requireMember(workspaceId, memberId);
		if (member.role() == WorkspaceRole.OWNER) {
			ensureAnotherOwner(workspaceId);
		}
		this.members.delete(workspaceId, memberId);
		this.events.publishEvent(new WorkspaceEvents.MemberRemoved(workspaceId, memberId));
	}

	private MemberResponse requireMember(UUID workspaceId, UUID memberId) {
		return this.members.find(workspaceId, memberId)
			.orElseThrow(() -> ApiException.notFound("member-not-found", "That user is not a member of this workspace."));
	}

	private void ensureAnotherOwner(UUID workspaceId) {
		if (this.members.countOwners(workspaceId) <= 1) {
			throw ApiException.conflict("last-owner",
					"A workspace needs at least one owner. Make someone else an owner first, or delete the workspace.");
		}
	}

}
