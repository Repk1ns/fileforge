package net.mimochodek.fileforge.autoconfigure

import net.mimochodek.fileforge.web.FileForgeProblemDetailsHandler
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileForgeWebAutoConfigurationTest {

    private val webRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileForgeWebAutoConfiguration::class.java))

    @Test
    fun `should be off by default so the library never takes over error handling by surprise`() {
        webRunner.run { context ->
            assertTrue(context.getBeanNamesForType(FileForgeProblemDetailsHandler::class.java).isEmpty())
        }
    }

    @Test
    fun `should publish the handler when enabled`() {
        webRunner.withPropertyValues("fileforge.web.problem-details.enabled=true").run { context ->
            context.startupFailure?.let { throw it }

            assertEquals(1, context.getBeanNamesForType(FileForgeProblemDetailsHandler::class.java).size)
        }
    }

    @Test
    fun `should stay inactive outside a web application even when enabled`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileForgeWebAutoConfiguration::class.java))
            .withPropertyValues("fileforge.web.problem-details.enabled=true")
            .run { context ->
                assertTrue(context.getBeanNamesForType(FileForgeProblemDetailsHandler::class.java).isEmpty())
            }
    }

    @Test
    fun `should bind the web properties`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileForgeAutoConfiguration::class.java))
            .withPropertyValues("fileforge.web.problem-details.enabled=true")
            .run { context ->
                val properties = context.getBean(FileForgeProperties::class.java)

                assertTrue(properties.web.problemDetails.enabled)
            }
    }

    @Test
    fun `should default the web properties to disabled`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileForgeAutoConfiguration::class.java))
            .run { context ->
                assertEquals(false, context.getBean(FileForgeProperties::class.java).web.problemDetails.enabled)
            }
    }
}
