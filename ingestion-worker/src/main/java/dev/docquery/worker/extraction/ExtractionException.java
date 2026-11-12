package dev.docquery.worker.extraction;

/**
 * The file's content cannot be turned into text, and trying again will not help (damaged file,
 * password protection, too much text...). The message is shown to users as is.
 */
public class ExtractionException extends RuntimeException {

	public ExtractionException(String userMessage) {
		super(userMessage);
	}

	public ExtractionException(String userMessage, Throwable cause) {
		super(userMessage, cause);
	}

}
