plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    id("org.jetbrains.dokka") version "2.0.0"
    id("com.gradleup.nmcp.aggregation") version "1.6.1"
    `java-library`
    `maven-publish`
    signing
}

group = "net.mimochodek"
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // `implementation`, not `api`: the AWS SDK is an implementation detail behind
    // the StorageBackend SPI. Exposing it on every consumer's compile classpath
    // invited them to depend on it, and structurally undermined the abstraction.
    implementation(platform("software.amazon.awssdk:bom:2.30.1"))
    implementation("software.amazon.awssdk:s3")

    implementation("org.slf4j:slf4j-api:2.0.16")

    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    // Optional Spring MVC integration (MultipartFile extension, ProblemDetail
    // handler): compile-only, so nothing is forced onto the runtime classpath
    // and the library stays usable outside a web application.
    compileOnly("org.springframework:spring-web:6.2.11")
    // No configuration processor. Kotlin never ran the 1.x `annotationProcessor`
    // declaration, and under kapt the processor emits group entries but no
    // properties for constructor-bound Kotlin classes - it cannot supply what it
    // claims to. So META-INF/spring-configuration-metadata.json is maintained by
    // hand, and ConfigurationMetadataTest asserts by reflection that it matches
    // the properties classes exactly, which is what stops it drifting.

    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.6")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    testImplementation("org.springframework:spring-web:6.2.11")
    // Needed to exercise the optional web integration: @ConditionalOnWebApplication
    // cannot be evaluated without the servlet API on the classpath.
    testImplementation("org.springframework:spring-webmvc:6.2.11")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
}

kotlin {
    // For a published library this is high value: it forces explicit visibility and
    // explicit return types on everything that is part of the public surface, so
    // nothing leaks into it by accident.
    explicitApi()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

// `withJavadocJar()` alone packages the output of the `javadoc` task, which has no
// Java sources here - so 1.0.0 published an empty javadoc jar despite applying
// Dokka. Package Dokka's output instead.
tasks.named<Jar>("javadocJar") {
    from(tasks.named("dokkaJavadoc"))
}

tasks.test {
    useJUnitPlatform()

    // docker-java negotiates Docker API v1.32 by default, which Docker Engine 29+
    // rejects (its minimum is v1.40). Pin a version supported by both old and new
    // engines so the Testcontainers integration tests are not silently skipped.
    systemProperty(
        "api.version",
        providers.gradleProperty("dockerApiVersion").getOrElse("1.43"),
    )
}


publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "fileforge"

            pom {
                name.set("FileForge")
                description.set("Spring Boot library for abstracting file storage with pluggable storage providers.")
                url.set("https://github.com/Repk1ns/fileforge")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://spdx.org/licenses/MIT.html")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("Repk1ns")
                        name.set("Repkins")
                    }
                }

                scm {
                    url.set("https://github.com/Repk1ns/fileforge")
                    connection.set("scm:git:https://github.com/Repk1ns/fileforge.git")
                    developerConnection.set("scm:git:https://github.com/Repk1ns/fileforge.git")
                }
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey").orNull
    val signingPassword = providers.gradleProperty("signingPassword").orNull

    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }

    sign(publishing.publications)

    isRequired = !signingKey.isNullOrBlank()
}

nmcpAggregation {
    centralPortal {
        username = providers.gradleProperty("mavenCentralUsername")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password = providers.gradleProperty("mavenCentralPassword")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
        publishingType = "AUTOMATIC"
    }

    publishAllProjectsProbablyBreakingProjectIsolation()
}

