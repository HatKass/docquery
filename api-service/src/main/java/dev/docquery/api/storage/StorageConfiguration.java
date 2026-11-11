package dev.docquery.api.storage;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageProperties.class)
class StorageConfiguration {

	private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

	@Bean(destroyMethod = "close")
	S3Client s3Client(S3StorageProperties properties) {
		S3ClientBuilder builder = S3Client.builder()
			.region(Region.of(properties.region()))
			.forcePathStyle(properties.pathStyleAccess())
			// Newer SDKs add CRC checksums to every request by default; several S3-compatible stores
			// reject or ignore them. Only send/verify checksums where the S3 API requires them.
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			.credentialsProvider(properties.hasStaticCredentials()
					? StaticCredentialsProvider
						.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()))
					: DefaultCredentialsProvider.builder().build());
		if (StringUtils.hasText(properties.endpoint())) {
			builder.endpointOverride(URI.create(properties.endpoint()));
		}
		return builder.build();
	}

	@Bean
	ObjectStorage objectStorage(S3Client s3Client, S3StorageProperties properties) {
		return new S3ObjectStorage(s3Client, properties.bucket());
	}

	/** Local development and tests only: production buckets are provisioned outside the app. */
	@Bean
	@ConditionalOnBooleanProperty(name = "docquery.storage.s3.create-bucket")
	ApplicationRunner createBucketIfMissing(S3Client s3, S3StorageProperties properties) {
		return args -> {
			try {
				s3.headBucket(request -> request.bucket(properties.bucket()));
			}
			catch (NoSuchBucketException ex) {
				createBucket(s3, properties.bucket());
			}
			catch (S3Exception ex) {
				if (ex.statusCode() != 404) {
					throw ex;
				}
				createBucket(s3, properties.bucket());
			}
		};
	}

	private static void createBucket(S3Client s3, String bucket) {
		s3.createBucket(request -> request.bucket(bucket));
		log.info("Created object storage bucket '{}'", bucket);
	}

}
