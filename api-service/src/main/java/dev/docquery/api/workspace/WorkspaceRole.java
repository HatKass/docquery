package dev.docquery.api.workspace;

/**
 * Per-workspace role, ordered from most to least privileged. Each role includes the permissions of
 * the roles below it.
 * <ul>
 * <li>{@code OWNER}: manage the workspace and its members, plus everything a MEMBER can do.</li>
 * <li>{@code MEMBER}: upload and delete documents, ask questions (later phases).</li>
 * <li>{@code VIEWER}: read documents and ask questions; no changes.</li>
 * </ul>
 */
public enum WorkspaceRole {

	OWNER(3), MEMBER(2), VIEWER(1);

	private final int level;

	WorkspaceRole(int level) {
		this.level = level;
	}

	/** Whether a holder of this role may perform an action that requires {@code required}. */
	public boolean includes(WorkspaceRole required) {
		return this.level >= required.level;
	}

}
