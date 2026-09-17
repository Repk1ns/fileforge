package net.mimochodek.fileforge.autoconfigure

import net.mimochodek.fileforge.DefaultFileStorage
import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.StorageProfile
import net.mimochodek.fileforge.naming.DefaultObjectKeyGenerator
import net.mimochodek.fileforge.spi.FileValidator
import net.mimochodek.fileforge.spi.ObjectKeyGenerator
import net.mimochodek.fileforge.spi.StorageBackend
import net.mimochodek.fileforge.validation.DefaultFileValidator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * Backend-agnostic wiring: the naming policy, the validator and the storage
 * pipeline itself.
 *
 * Everything here is independent of which [StorageBackend] is active, so a new
 * backend only has to publish a `StorageBackend` bean to get the whole pipeline.
 * Each bean backs off when the consumer declares their own.
 */
@AutoConfiguration(after = [S3StorageAutoConfiguration::class])
@EnableConfigurationProperties(FileForgeProperties::class)
public class FileForgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public fun fileForgeObjectKeyGenerator(properties: FileForgeProperties): ObjectKeyGenerator =
        DefaultObjectKeyGenerator(
            strategy = properties.naming.strategy,
            keepExtension = properties.naming.keepExtension,
            clock = Clock.system(properties.naming.zone),
            zone = properties.naming.zone,
        )

    @Bean
    @ConditionalOnMissingBean
    public fun fileForgeValidator(properties: FileForgeProperties): FileValidator =
        properties.validation.toValidator()

    @Bean
    @ConditionalOnBean(StorageBackend::class)
    @ConditionalOnMissingBean
    public fun fileStorage(
        backend: StorageBackend,
        keyGenerator: ObjectKeyGenerator,
        validator: FileValidator,
        properties: FileForgeProperties,
    ): FileStorage = DefaultFileStorage(
        backend = backend,
        keyGenerator = keyGenerator,
        validator = validator,
        maxFileSize = properties.validation.maxFileSize.toBytes(),
        inMemoryThreshold = properties.buffering.inMemoryThreshold.toBytes(),
        tempDir = properties.buffering.tempDir,
        profiles = properties.toStorageProfiles(),
    )

    /**
     * A profile that overrides nothing keeps [validator] - including a
     * consumer's replacement - and only contributes its folder.
     */
    private fun FileForgeProperties.toStorageProfiles(): Map<String, StorageProfile> =
        profiles.mapValues { (_, profile) ->
            if (profile.validation.isEmpty()) {
                StorageProfile(folder = profile.folder)
            } else {
                val policy = profile.validation.mergedWith(validation)
                StorageProfile(
                    folder = profile.folder,
                    validator = policy.toValidator(),
                    maxFileSize = policy.maxFileSize.toBytes(),
                )
            }
        }

    private fun ValidationProperties.toValidator(): FileValidator = DefaultFileValidator(
        maxFileSize = maxFileSize.toBytes(),
        allowedMimeTypes = allowedMimeTypes,
        verifyMagicBytes = verifyMagicBytes,
        unknownTypePolicy = unknownTypePolicy,
    )
}
