package net.mimochodek.fileforge.autoconfigure

import net.mimochodek.fileforge.web.FileForgeProblemDetailsHandler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.ProblemDetail

/**
 * Publishes the RFC 7807 exception handler, when asked to.
 *
 * Opt-in (`fileforge.web.problem-details.enabled: true`) and conditional on
 * Spring MVC being present, so the library never takes over error handling by
 * surprise and stays usable outside a web application.
 */
@AutoConfiguration
@ConditionalOnClass(ProblemDetail::class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "fileforge.web.problem-details", name = ["enabled"], havingValue = "true")
public class FileForgeWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public fun fileForgeProblemDetailsHandler(): FileForgeProblemDetailsHandler = FileForgeProblemDetailsHandler()
}
