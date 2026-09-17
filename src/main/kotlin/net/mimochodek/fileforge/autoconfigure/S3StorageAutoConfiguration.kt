package net.mimochodek.fileforge.autoconfigure

import net.mimochodek.fileforge.internal.s3.S3StorageBackend
import net.mimochodek.fileforge.spi.StorageBackend
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * Wires the S3 backend when `fileforge.provider` is `s3` (the default).
 *
 * The `S3Client` and `S3Presigner` are **not** published as beans. A library
 * must not contribute generically-typed infrastructure it owns: in 1.x the
 * combination of public `S3Client` beans and `@ConditionalOnMissingBean` cut
 * both ways - a consumer with their own `S3Client` would silently have FileForge
 * write to the wrong endpoint, and a consumer adding an unrelated S3 feature
 * would silently inherit FileForge's client. Here they are constructed
 * privately, owned by the backend, and closed with it.
 *
 * To replace the wiring entirely, publish your own [StorageBackend] bean.
 */
@AutoConfiguration
@ConditionalOnClass(S3Client::class)
@ConditionalOnProperty(
    prefix = "fileforge",
    name = ["provider"],
    havingValue = FileForgeProperties.PROVIDER_S3,
    matchIfMissing = true,
)
public class S3StorageAutoConfiguration {

    /**
     * Nested so that the `fileforge.s3` properties are bound - and validated -
     * only when this backend is actually going to be built. A consumer who
     * publishes their own [StorageBackend] must not be forced to configure a
     * bucket they will never use.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(StorageBackend::class)
    @EnableConfigurationProperties(S3Properties::class)
    public class S3BackendConfiguration {

        @Bean(destroyMethod = "close")
        public fun s3StorageBackend(properties: S3Properties): StorageBackend {
            val credentials = properties.credentialsProvider()

            val client = S3Client.builder()
                .region(Region.of(properties.region))
                .credentialsProvider(credentials)
                // Path style must be configured in exactly one place - setting it on
                // both the builder and S3Configuration makes the SDK reject the client.
                .forcePathStyle(properties.pathStyleAccess)
                .also { builder ->
                    // Optional: omitting it lets the SDK resolve the real AWS endpoint.
                    properties.endpoint?.let(builder::endpointOverride)
                }
                .build()

            val presigner = S3Presigner.builder()
                .region(Region.of(properties.region))
                .credentialsProvider(credentials)
                .serviceConfiguration(
                    S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess)
                        .build(),
                )
                .also { builder -> properties.endpoint?.let(builder::endpointOverride) }
                .build()

            return S3StorageBackend(client, presigner, properties.bucket)
        }

        /**
         * Static credentials when both keys are configured, otherwise the AWS
         * default chain (environment, system properties, web identity token, SSO,
         * profile file, container and instance metadata) - which is what makes
         * IAM roles and instance profiles work.
         */
        private fun S3Properties.credentialsProvider(): AwsCredentialsProvider =
            if (hasStaticCredentials) {
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
            } else {
                DefaultCredentialsProvider.create()
            }
    }
}
