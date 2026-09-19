package net.mimochodek.fileforge.autoconfigure

import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.spi.StorageBackend
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.web.FileForgeProblemDetailsHandler
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.function.Predicate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs the library with **`spring-web` removed from the classpath entirely**.
 *
 * `spring-web` is a `compileOnly` dependency, so it never reaches a consumer's
 * runtime classpath and the `MultipartFile` extension plus the RFC 7807 handler
 * cost nothing. That is a claim the README makes twice, and it is the kind of
 * claim that silently stops being true - one carelessly placed import in an
 * auto-configuration, one `@ConditionalOnClass` replaced by a direct reference,
 * and every non-web consumer gets a `NoClassDefFoundError` at startup.
 *
 * [FilteredClassLoader] hides the whole `org.springframework.web` package, so
 * this test fails the moment that happens.
 */
class WithoutSpringWebTest {

    /**
     * Everything the `spring-web` artifact ships. `org.springframework.http`
     * lives there too, so hiding only `org.springframework.web` would leave
     * ProblemDetail visible and quietly weaken this whole class - which is
     * exactly what the guard test below caught the first time.
     */
    private val SPRING_WEB_ARTIFACT = Predicate<String> { name ->
        name.startsWith("org.springframework.web.") || name.startsWith("org.springframework.http.")
    }

    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(SPRING_WEB_ARTIFACT))
        .withConfiguration(
            AutoConfigurations.of(
                S3StorageAutoConfiguration::class.java,
                FileForgeAutoConfiguration::class.java,
                FileForgeWebAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(Backend::class.java)

    @Test
    fun `should really have spring-web hidden`() {
        // Guards the other tests in this class: if the filter silently stopped
        // filtering, they would all pass without proving anything.
        runner.run { context ->
            val loader = context.classLoader!!

            listOf(
                "org.springframework.web.multipart.MultipartFile",
                "org.springframework.web.bind.annotation.RestControllerAdvice",
                "org.springframework.http.ProblemDetail",
                "org.springframework.http.HttpStatus",
            ).forEach { name ->
                assertFailsWith<ClassNotFoundException>("$name should be hidden") { loader.loadClass(name) }
            }
        }
    }

    @Test
    fun `should start with no spring-web on the classpath at all`() {
        runner.run { context ->
            context.startupFailure?.let { throw it }

            assertEquals(1, context.getBeanNamesForType(FileStorage::class.java).size)
            assertEquals(1, context.getBeanNamesForType(StorageBackend::class.java).size)
        }
    }

    @Test
    fun `should store and read without spring-web`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            val stored = storage.store(
                FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), folder = "docs"),
            )
            assertEquals(Fixtures.PDF.size, storage.readBytes(stored.key).size)
        }
    }

    @Test
    fun `should back off from the web auto-configuration even when it is switched on`() {
        // @ConditionalOnClass must be evaluated from bytecode without loading
        // ProblemDetail. If anyone replaces it with a direct class reference, the
        // context blows up here instead of in a consumer's non-web application.
        runner.withPropertyValues("fileforge.web.problem-details.enabled=true").run { context ->
            context.startupFailure?.let { throw it }

            assertTrue(context.getBeanNamesForType(FileForgeProblemDetailsHandler::class.java).isEmpty())
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Backend {
        @Bean
        fun storageBackend(): StorageBackend = InMemoryStorageBackend()
    }
}
