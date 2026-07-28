package net.mimochodek.fileforge

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * Auto-configuration for the S3-backed [FileStorageService].
 *
 * Activated when the AWS S3 client is on the classpath and `fileforge.s3.endpoint`
 * is configured. Every bean is conditional on a missing bean so consumers can
 * override any part of the wiring.
 */
@AutoConfiguration
@ConditionalOnClass(S3Client::class)
@ConditionalOnProperty(prefix = "fileforge.s3", name = ["endpoint"])
@EnableConfigurationProperties(FileStorageProperties::class)
class FileStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun s3Client(props: FileStorageProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, props.secretKey),
                ),
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build(),
            )
            .forcePathStyle(true)
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun s3Presigner(props: FileStorageProperties): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, props.secretKey),
                ),
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build(),
            )
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun fileValidationService(props: FileStorageProperties): FileValidationService =
        FileValidationService(props)

    @Bean
    @ConditionalOnMissingBean
    fun fileStorageService(
        client: S3Client,
        presigner: S3Presigner,
        props: FileStorageProperties,
    ): FileStorageService = S3FileStorageService(client, presigner, props)
}
