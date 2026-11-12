package dev.docquery.worker.ingestion;

/** The embedding provider failed or could not be reached. Transient: the event is retried. */
class EmbeddingUnavailableException extends RuntimeException {

	EmbeddingUnavailableException(Throwable cause) {
		super("The embedding provider failed: " + cause.getMessage(), cause);
	}

}
