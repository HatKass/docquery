package dev.docquery.worker.storage;

import java.net.URI;

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
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageProperties.class)
class StorageConfiguration {

	@Bean(destroyMethod = "close")
	S3Client s3Client(S3StorageProperties properties) {
		S3ClientBuilder builder = S3Client.builder()
			.region(Region.of(properties.region()))
			.forcePathStyle(properties.pathStyleAccess())
			// Several S3-compatible stores reject the SDK's default CRC checksums (see api-service).
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
	DocumentStorage documentStorage(S3Client s3, S3StorageProperties properties) {
		return key -> {
			try {
				return s3.getObject(request -> request.bucket(properties.bucket()).key(key));
			}
			catch (NoSuchKeyException ex) {
				throw new DocumentStorage.StoredFileMissingException(key, ex);
			}
		};
	}

}
