package dev.docquery.api.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorSummaryTests {

	static final String LISTENER = "org.springframework.kafka.listener.ListenerExecutionFailedException";

	/** Exactly what the worker's DeadLetterPublishingRecoverer stores today. */
	@Test
	void removesTheSpringKafkaListenerWrapperButKeepsSemicolonsOfTheCause() {
		String message = "Listener method 'void dev.docquery.worker.ingestion.DocumentUploadedListener.onDocumentUploaded("
				+ "org.apache.kafka.clients.consumer.ConsumerRecord<java.lang.String, java.lang.String>)' threw exception; "
				+ "No text was found in this document. Scanned documents (images of text) are not supported yet; "
				+ "upload a file with selectable text.";

		assertThat(ErrorSummary.of(LISTENER, message)).isEqualTo("No text was found in this document. Scanned documents "
				+ "(images of text) are not supported yet; upload a file with selectable text.");
	}

	@Test
	void removesNestedExceptionClassPrefixes() {
		assertThat(ErrorSummary.of(LISTENER,
				"Listener method 'void x.y(z)' threw exception; nested exception is "
						+ "dev.docquery.worker.ingestion.EmbeddingUnavailableException: Connection refused"))
			.isEqualTo("Connection refused");
		assertThat(ErrorSummary.of("java.lang.IllegalStateException", "java.lang.IllegalStateException: Boom"))
			.isEqualTo("Boom");
	}

	@Test
	void leavesPlainMessagesAlone() {
		assertThat(ErrorSummary.of("x.Y", "Connection refused")).isEqualTo("Connection refused");
		assertThat(ErrorSummary.of("x.Y", "Value: 3; expected: 4")).isEqualTo("Value: 3; expected: 4");
	}

	@Test
	void fallsBackToTheClassNameWhenThereIsNoMessage() {
		assertThat(ErrorSummary.of(LISTENER, "Listener method 'void x.y(z)' threw exception"))
			.isEqualTo("ListenerExecutionFailedException (no message)");
		assertThat(ErrorSummary.of("a.b.TimeoutException", null)).isEqualTo("TimeoutException (no message)");
		assertThat(ErrorSummary.of(null, "  ")).isEqualTo("Unknown error");
	}

}
