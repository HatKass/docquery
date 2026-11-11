package dev.docquery.api.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/** {@link ObjectStorage} over the AWS SDK v2 S3 client. */
class S3ObjectStorage implements ObjectStorage {

	private final S3Client s3;

	private final String bucket;

	S3ObjectStorage(S3Client s3, String bucket) {
		this.s3 = s3;
		this.bucket = bucket;
	}

	@Override
	public void put(String key, Path file, String contentType) {
		this.s3.putObject(request -> request.bucket(this.bucket).key(key).contentType(contentType),
				RequestBody.fromFile(file));
	}

	@Override
	public InputStream get(String key) {
		return this.s3.getObject(request -> request.bucket(this.bucket).key(key));
	}

	@Override
	public void delete(String key) {
		this.s3.deleteObject(request -> request.bucket(this.bucket).key(key));
	}

	@Override
	public int deleteAll(String prefix) {
		if (prefix.isBlank()) {
			throw new IllegalArgumentException("Refusing to delete the whole bucket");
		}
		int deleted = 0;
		String continuationToken = null;
		do {
			ListObjectsV2Request.Builder list = ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix);
			if (continuationToken != null) {
				list.continuationToken(continuationToken);
			}
			ListObjectsV2Response page = this.s3.listObjectsV2(list.build());
			List<ObjectIdentifier> keys = page.contents()
				.stream()
				.map(S3Object::key)
				.map(key -> ObjectIdentifier.builder().key(key).build())
				.toList();
			if (!keys.isEmpty()) {
				DeleteObjectsResponse response = this.s3.deleteObjects(
						request -> request.bucket(this.bucket).delete(Delete.builder().objects(keys).quiet(true).build()));
				if (response.hasErrors() && !response.errors().isEmpty()) {
					throw new IllegalStateException("Could not delete " + response.errors().size() + " object(s) under '"
							+ prefix + "', first error: " + response.errors().getFirst().message());
				}
				deleted += keys.size();
			}
			continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
		}
		while (continuationToken != null);
		return deleted;
	}

	@Override
	public boolean exists(String key) {
		try {
			this.s3.headObject(request -> request.bucket(this.bucket).key(key));
			return true;
		}
		catch (NoSuchKeyException ex) {
			return false;
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				return false;
			}
			throw ex;
		}
	}

}
