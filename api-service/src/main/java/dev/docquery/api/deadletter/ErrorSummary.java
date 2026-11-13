package dev.docquery.api.deadletter;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The human-readable root cause of a dead-lettered event's error message.
 * <p>
 * Spring Kafka wraps the pipeline's exception: the stored message reads
 * {@code Listener method 'void ...onDocumentUploaded(...)' threw exception; No text was found in this
 * document...}. Only these known wrappers (and leading exception class names) are removed; the cause
 * itself is kept intact, even if it contains semicolons. The full message stays available for the
 * details view. Applied when reading, so events stored before this existed benefit too.
 */
final class ErrorSummary {

	private static final List<Pattern> WRAPPERS = List.of(
			// Spring Kafka: ListenerExecutionFailedException
			Pattern.compile("^Listener method '[^']*' threw exception;?\\s*"),
			Pattern.compile("^Listener failed;?\\s*"),
			// Older Spring style and "java.lang.IllegalStateException: ..." prefixes
			Pattern.compile("^nested exception is\\s*"),
			Pattern.compile("^[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+(Exception|Error)(:\\s*|;\\s*|$)"));

	private ErrorSummary() {
	}

	/**
	 * @param exceptionClass fully qualified class of the top-level exception, may be {@code null}
	 * @param message the top-level exception's message, may be {@code null}
	 * @return the root-cause message, or a short fallback when there is none
	 */
	static String of(String exceptionClass, String message) {
		String summary = (message != null) ? message.strip() : "";
		boolean changed = true;
		while (changed && !summary.isEmpty()) {
			changed = false;
			for (Pattern wrapper : WRAPPERS) {
				String stripped = wrapper.matcher(summary).replaceFirst("").strip();
				if (!stripped.equals(summary)) {
					summary = stripped;
					changed = true;
				}
			}
		}
		if (!summary.isEmpty()) {
			return summary;
		}
		if (exceptionClass != null && !exceptionClass.isBlank()) {
			return exceptionClass.substring(exceptionClass.lastIndexOf('.') + 1) + " (no message)";
		}
		return "Unknown error";
	}

}
