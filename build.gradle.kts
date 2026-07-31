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
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api(platform("software.amazon.awssdk:bom:2.30.1"))
    api("software.amazon.awssdk:s3")

    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.5.6")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.6")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.6"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
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
    useInMemoryPgpKeys(
        providers.gradleProperty("signingKey").orNull,
        providers.gradleProperty("signingPassword").orNull
    )

    sign(publishing.publications)

    isRequired = gradle.taskGraph.hasTask("publish")
}

nmcpAggregation {
    centralPortal {
        username = System.getenv("MAVEN_CENTRAL_USERNAME")
        password = System.getenv("MAVEN_CENTRAL_PASSWORD")
        publishingType = "AUTOMATIC"
    }

    publishAllProjectsProbablyBreakingProjectIsolation()
}

