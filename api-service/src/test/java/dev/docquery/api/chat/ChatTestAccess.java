package dev.docquery.api.chat;

/** Lets tests in other packages (the RAG evaluation) use package-private chat logic. */
public final class ChatTestAccess {

	private ChatTestAccess() {
	}

	/** Whether the chat treats the question as an assessment or summary (it then widens retrieval). */
	public static boolean isEvaluative(String question) {
		return QuestionKind.isEvaluative(question);
	}

	/** The chat's exact "not in your documents" reply. */
	public static String notFoundAnswer() {
		return GroundedPrompt.NOT_FOUND_ANSWER;
	}

}
