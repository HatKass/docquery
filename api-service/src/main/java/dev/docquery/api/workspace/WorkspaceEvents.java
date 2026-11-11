package dev.docquery.api.workspace;

import java.util.UUID;

/**
 * Domain events other features react to (e.g. documents remove stored files and close live streams).
 * They are published inside the workspace transaction; listeners that touch external systems should
 * use {@code @TransactionalEventListener} so they only run after a successful commit.
 */
public final class WorkspaceEvents {

	private WorkspaceEvents() {
	}

	/** The workspace and, by cascade, all of its rows are gone. */
	public record WorkspaceDeleted(UUID workspaceId) {
	}

	/** The user removed themselves or was removed; they must lose access immediately. */
	public record MemberRemoved(UUID workspaceId, UUID userId) {
	}

}
