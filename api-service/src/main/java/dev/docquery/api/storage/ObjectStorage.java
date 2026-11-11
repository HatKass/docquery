package dev.docquery.api.storage;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Where uploaded files live. The application only needs these few operations, so any S3-compatible
 * store (SeaweedFS locally, AWS S3 / Cloudflare R2 / MinIO elsewhere) can sit behind it.
 */
public interface ObjectStorage {

	/** Stores the file under {@code key}, overwriting any existing object. */
	void put(String key, Path file, String contentType);

	/** Opens the object for reading; the caller must close the stream. */
	InputStream get(String key);

	/** Deletes one object; deleting a missing key is not an error. */
	void delete(String key);

	/**
	 * Deletes every object whose key starts with {@code prefix}.
	 * @return the number of objects deleted
	 */
	int deleteAll(String prefix);

	boolean exists(String key);

}
