# TASK: Build an S3-compatible file-storage Spring Boot starter library

You are working inside a **brand-new, empty Kotlin + Gradle (Kotlin DSL) project**
created by IntelliJ IDEA. There is no application code yet — only the Gradle
skeleton (`build.gradle.kts`, `settings.gradle.kts`, Gradle wrapper,
`src/main/kotlin`, `src/test/kotlin`).

Your job is to turn this project into a **reusable Spring Boot auto-configuration
library (a "starter")** that abstracts file storage over an S3-compatible backend.
The library will be consumed by several **independent** Kotlin + Spring Boot 3
backends (each with its own database, its own bucket, its own credentials).

## Non-negotiable nature of the artifact
- This is a **LIBRARY, not an application**.
  - NO `main()` function, NO `@SpringBootApplication`.
  - Do **NOT** apply the `org.springframework.boot` Gradle plugin (no `bootJar`).
  - Produce a plain JAR consumed as a normal Maven dependency.
- The only "Spring Boot" aspect is **auto-configuration** discovered via
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Target runtime / storage backend
- Physical storage is a self-hosted **S3-compatible server (`versitygw`)** running on
  a Synology NAS, reachable over LAN via a custom endpoint (e.g. `http://<nas-ip>:7070`).
- Because it is self-hosted S3, the S3 client **MUST** use `forcePathStyle(true)`.
- The library must stay portable: switching to AWS S3 / R2 / any S3 provider later
  must require **only configuration changes**, no code changes.

## Consumer experience (the whole point of the library)
A consuming backend must only need to:
1. Add the dependency.
2. Add a few lines of YAML config.
3. Constructor-inject a service interface and immediately use it.

Consumer config shape:
```yaml
storage:
  s3:
    endpoint: http://<nas-ip>:7070
    region: us-east-1
    bucket: my-app-prod
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    # optional, with sensible defaults baked into the library:
    max-file-size-bytes: 52428800
    allowed-mime-types:
      - image/jpeg
      - image/png
      - image/webp
      - application/pdf
```

## Technology / versions (align with the existing backends)
- Kotlin `2.1.10`, JVM toolchain `21`.
- Idiomatic Kotlin: `val` by default, data classes, constructor injection,
  nullable types instead of `Optional`, safe calls / Elvis instead of explicit
  null checks. No Java-style code.
- AWS SDK **v2** (`software.amazon.awssdk:s3` + `s3` presigner), managed via the
  AWS SDK BOM.
- Spring only as `compileOnly` (`spring-boot-autoconfigure`) so the library does
  NOT force a Spring version onto consumers.
- Keep the dependency footprint minimal: NO `spring-boot-starter-web`, NO JPA,
  NO domain concepts.

## Gradle build requirements (`build.gradle.kts`)
- Plugins: `kotlin("jvm") 2.1.10`, `kotlin("plugin.spring") 2.1.10`,
  `java-library`, `maven-publish`. (Explicitly NOT the Spring Boot plugin.)
- `group = "com.nemoonli"`, `version = "0.1.0"`, `artifactId =
  "file-storage-spring-boot-starter"`.
- `java { toolchain { languageVersion = JavaLanguageVersion.of(21) }; withSourcesJar() }`.
- Dependencies:
  - `api(platform("software.amazon.awssdk:bom:2.30.1"))`
  - `api("software.amazon.awssdk:s3")`
  - `compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.6")`
  - `annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.5.6")`
  - Test: `spring-boot-starter-test:3.5.6`, `io.mockk:mockk:1.13.17`,
    and Testcontainers for an integration test.
- `maven-publish` publishing block targeting **GitHub Packages**
  (`https://maven.pkg.github.com/<owner>/file-storage-spring-boot-starter`),
  reading credentials from `GITHUB_ACTOR` / `GITHUB_TOKEN` env vars (with a
  fallback to `gpr.user` / `gpr.token` Gradle properties for local dev).

## Package layout
Use base package `com.nemoonli.filestorage`:
```
com/nemoonli/filestorage/
├── FileStorageService.kt            // public interface (domain-agnostic)
├── S3FileStorageService.kt          // AWS SDK v2 implementation
├── FileStorageProperties.kt         // @ConfigurationProperties("storage.s3")
├── FileValidationService.kt         // MIME whitelist + magic bytes + size + name sanitization
├── FileStorageException.kt          // library-specific exception(s)
└── FileStorageAutoConfiguration.kt  // @AutoConfiguration wiring the beans
resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## API design — KEEP IT MINIMAL AND DOMAIN-AGNOSTIC
The library must know **nothing** about domain entities (no `Document`,
`Property`, `entity_type`, database, users, authorization). It works purely with
`objectKey: String`, streams/bytes, and content type. Metadata persistence and
authorization stay in each consuming application.

`FileStorageService` interface (final method set — do not add more):
```kotlin
interface FileStorageService {
    fun upload(objectKey: String, content: InputStream, contentLength: Long, contentType: String)
    fun download(objectKey: String): InputStream
    fun delete(objectKey: String)
    fun exists(objectKey: String): Boolean
    fun list(prefix: String): List<String>
    /** Archive = generic move: copy to targetKey then delete sourceKey. */
    fun archive(sourceKey: String, targetKey: String)
    fun presignGetUrl(objectKey: String, ttl: Duration = Duration.ofMinutes(15)): URL
}
```
Rationale to respect: "Archive" is implemented generically as move/copy+delete on
object keys; what "archived" means at the domain level (a DB flag, who may
archive) is NOT the library's concern.

## Auto-configuration requirements
```kotlin
@AutoConfiguration
@ConditionalOnClass(S3Client::class)
@ConditionalOnProperty(prefix = "storage.s3", name = ["endpoint"])
@EnableConfigurationProperties(FileStorageProperties::class)
class FileStorageAutoConfiguration {
    @Bean @ConditionalOnMissingBean fun s3Client(props: FileStorageProperties): S3Client = ...
    @Bean @ConditionalOnMissingBean fun s3Presigner(props: FileStorageProperties): S3Presigner = ...
    @Bean @ConditionalOnMissingBean fun fileValidationService(props: FileStorageProperties): FileValidationService = ...
    @Bean @ConditionalOnMissingBean fun fileStorageService(client: S3Client, presigner: S3Presigner, props: FileStorageProperties): FileStorageService = ...
}
```
Every bean uses `@ConditionalOnMissingBean` so a consumer can override any part.

## Validation service behavior
- Enforce configured max file size.
- Enforce MIME whitelist; verify actual content by **magic bytes**, not just the
  declared content type / extension.
- Sanitize/normalize file names to prevent path traversal and collisions.
- On violation, throw a clear `FileStorageException` subtype.

## Tests (must be green)
- Unit tests with **MockK** for `S3FileStorageService` (verify correct S3 requests
  are built, error mapping) and `FileValidationService` (size limit, MIME
  whitelist, magic-byte mismatch, name sanitization).
- One **Testcontainers** integration test running the `versity/versitygw` image
  (or LocalStack as fallback) exercising the full upload → exists → download →
  list → archive → delete → presign flow against a real S3 endpoint with
  `forcePathStyle(true)`.

## Deliverables / definition of done
1. `build.gradle.kts` + `settings.gradle.kts` configured as above; project builds.
2. All library classes implemented as specified, idiomatic Kotlin.
3. `AutoConfiguration.imports` present and referencing `FileStorageAutoConfiguration`.
4. `spring-boot-configuration-processor` wired so `storage.s3.*` gets IDE metadata.
5. Unit + integration tests present and passing (`./gradlew test`).
6. A short `README.md` documenting: how to add the dependency (incl. GitHub
   Packages auth note), the YAML config keys, and a copy-paste usage example.
7. A GitHub Actions workflow `.github/workflows/publish.yml` that runs
   `./gradlew publish` on tag push (`v*`) using `GITHUB_TOKEN`.

## Constraints / do NOT do
- Do not introduce any domain model, JPA entity, controller, or REST endpoint.
- Do not hardcode bucket names, endpoints, or credentials — everything comes from
  `FileStorageProperties`.
- Do not apply the Spring Boot Gradle plugin or create a `main()`.
- Keep the public API exactly as defined above.

When finished, print a summary of the created files and the exact commands to
build, test, publish locally (`publishToMavenLocal`), and consume the library.
