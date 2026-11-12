package dev.docquery.worker.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Connection settings for the S3-compatible object store holding uploaded documents. Same variables
 * as api-service; the worker only ever reads.
 *
 * @param endpoint custom endpoint (SeaweedFS, MinIO, R2...); blank means AWS S3 itself
 * @param region signing region
 * @param accessKey static access key; blank means the AWS default credentials chain
 * @param secretKey static secret key, used together with {@code accessKey}
 * @param bucket bucket holding all uploaded documents
 * @param pathStyleAccess {@code http://host/bucket/key} addressing (needed by most self-hosted stores)
 */
@ConfigurationProperties("docquery.storage.s3")
public record S3StorageProperties(String endpoint, String region, String accessKey, String secretKey, String bucket,
		boolean pathStyleAccess) {

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
