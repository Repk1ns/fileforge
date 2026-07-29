plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

rootProject.name = "fileforge"

nmcpSettings {
    centralPortal {
        username = System.getenv("MAVEN_CENTRAL_USERNAME")
        password = System.getenv("MAVEN_CENTRAL_PASSWORD")

        publishingType = "AUTOMATIC"
    }
}