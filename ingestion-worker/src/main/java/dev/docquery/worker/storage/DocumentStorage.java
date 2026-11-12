package dev.docquery.worker.storage;

import java.io.InputStream;

/** Read access to stored document files. */
public interface DocumentStorage {

	/**
	 * Opens the stored file; the caller must close the stream.
	 * @throws StoredFileMissingException if no object exists under {@code key}
	 */
	InputStream open(String key);

	/** The object a document points to does not exist (it was deleted, or never stored). */
	class StoredFileMissingException extends RuntimeException {

		public StoredFileMissingException(String key, Throwable cause) {
			super("No stored file under '" + key + "'", cause);
		}

	}

}
