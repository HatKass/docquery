package dev.docquery.api.usage;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Records chat token usage in the {@code chat_usage} ledger. Called in the same transaction that
 * stores the answer, so an answer and its usage are committed together. (Embedding usage is recorded
 * by the ingestion worker in {@code embedding_usage}.)
 */
@Component
public class UsageLedger {

	private final JdbcClient jdbc;

	UsageLedger(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void recordChat(UUID answerId, UUID workspaceId, UUID userId, String model, int promptTokens,
			int completionTokens, boolean estimated) {
		this.jdbc.sql("""
				INSERT INTO chat_usage (message_id, workspace_id, user_id, model, prompt_tokens, completion_tokens, estimated)
				VALUES (:id, :workspaceId, :userId, :model, :prompt, :completion, :estimated)
				ON CONFLICT (message_id) DO NOTHING
				""")
			.param("id", answerId)
			.param("workspaceId", workspaceId)
			.param("userId", userId)
			.param("model", model)
			.param("prompt", promptTokens)
			.param("completion", completionTokens)
			.param("estimated", estimated)
			.update();
	}

}
