package dev.docquery.api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Connection settings for the S3-compatible object store.
 *
 * @param endpoint custom endpoint (SeaweedFS, MinIO, R2...); blank means AWS S3 itself
 * @param region signing region (any value works for most S3-compatible stores)
 * @param accessKey static access key; blank means the AWS default credentials chain (IAM role, env...)
 * @param secretKey static secret key, used together with {@code accessKey}
 * @param bucket bucket holding all uploaded documents
 * @param pathStyleAccess {@code http://host/bucket/key} instead of {@code http://bucket.host/key};
 * required by most self-hosted stores
 * @param createBucket create the bucket at startup if it is missing (local development and tests)
 */
@ConfigurationProperties("docquery.storage.s3")
public record S3StorageProperties(String endpoint, String region, String accessKey, String secretKey, String bucket,
		boolean pathStyleAccess, boolean createBucket) {

	public S3StorageProperties {
		if (!StringUtils.hasText(bucket)) {
			throw new IllegalArgumentException("docquery.storage.s3.bucket must be set");
		}
		region = StringUtils.hasText(region) ? region : "us-east-1";
	}

	boolean hasStaticCredentials() {
		return StringUtils.hasText(this.accessKey) && StringUtils.hasText(this.secretKey);
	}

}
