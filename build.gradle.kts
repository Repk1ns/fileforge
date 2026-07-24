plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    `java-library`
    `maven-publish`
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
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "file-storage-spring-boot-starter"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/mimochodek/file-storage-spring-boot-starter")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?
            }
        }
    }
}
