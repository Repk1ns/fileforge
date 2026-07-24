# file-storage-spring-boot-starter

A reusable Spring Boot auto-configuration library (starter) that abstracts file
storage over any S3-compatible backend (versitygw, AWS S3, Cloudflare R2, MinIO,
LocalStack, ...). It is a **plain library JAR** — no `main()`, no
`@SpringBootApplication` — consumed as a normal Maven dependency.

- Kotlin `2.1.10`, JVM 21, AWS SDK v2.
- Self-hosted friendly: the S3 client always uses `forcePathStyle(true)`.
- Domain-agnostic API: object keys, streams and content types only.
- Every bean is `@ConditionalOnMissingBean`, so consumers can override any part.

## Adding the dependency

The artifact is published to **GitHub Packages**, which requires authentication
even for public packages.

### 1. Configure GitHub Packages credentials

Add to `~/.gradle/gradle.properties` (or use env vars):

```properties
gpr.user=<your-github-username>
gpr.token=<a personal access token with read:packages>
```

### 2. Add the repository and dependency

`build.gradle.kts` of the consuming application:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/mimochodek/file-storage-spring-boot-starter")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
            password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?
        }
    }
}

dependencies {
    implementation("net.mimochodek:file-storage-spring-boot-starter:0.1.0")
}
```

## Configuration

```yaml
storage:
  s3:
    endpoint: http://<nas-ip>:7070      # required — enables the auto-configuration
    region: us-east-1                   # default: us-east-1
    bucket: my-app-prod                 # required
    access-key: ${S3_ACCESS_KEY}        # required
    secret-key: ${S3_SECRET_KEY}        # required
    # optional, with sensible defaults:
    max-file-size-bytes: 52428800       # default: 52428800 (50 MiB)
    allowed-mime-types:                 # default: image/jpeg, image/png, image/webp, application/pdf
      - image/jpeg
      - image/png
      - image/webp
      - application/pdf
```

| Key | Required | Default | Description |
| --- | --- | --- | --- |
| `storage.s3.endpoint` | yes | — | S3-compatible endpoint URL; the auto-configuration only activates when set |
| `storage.s3.region` | no | `us-east-1` | S3 region |
| `storage.s3.bucket` | yes | — | Bucket name |
| `storage.s3.access-key` | yes | — | Access key |
| `storage.s3.secret-key` | yes | — | Secret key |
| `storage.s3.max-file-size-bytes` | no | `52428800` | Max upload size enforced by `FileValidationService` |
| `storage.s3.allowed-mime-types` | no | jpeg/png/webp/pdf | MIME whitelist enforced by `FileValidationService` |

## Usage

```kotlin
import net.mimochodek.fileforge.FileStorageService
import net.mimochodek.fileforge.FileValidationService
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Duration

@Service
class DocumentService(
    private val fileStorage: FileStorageService,
    private val fileValidation: FileValidationService,
) {
    fun store(file: MultipartFile): String {
        val safeName = fileValidation.sanitizeFileName(file.originalFilename ?: "upload")
        val head = file.inputStream.use { it.readNBytes(16) }
        fileValidation.validate(file.size, file.contentType ?: "application/octet-stream", head)

        val objectKey = "documents/$safeName"
        file.inputStream.use {
            fileStorage.upload(objectKey, it, file.size, file.contentType ?: "application/octet-stream")
        }
        return objectKey
    }

    fun temporaryDownloadLink(objectKey: String) =
        fileStorage.presignGetUrl(objectKey, Duration.ofMinutes(15))

    fun archive(objectKey: String) =
        fileStorage.archive(objectKey, "archive/$objectKey")
}
```

### Overriding beans

Any bean (`S3Client`, `S3Presigner`, `FileValidationService`,
`FileStorageService`) can be replaced simply by declaring your own bean of the
same type — the library backs off thanks to `@ConditionalOnMissingBean`.

## Commands

```bash
# Build
./gradlew build

# Run tests (integration test requires a running Docker daemon; skipped otherwise)
./gradlew test

# Publish to the local Maven repository (~/.m2) for local consumption
./gradlew publishToMavenLocal

# Publish to GitHub Packages (needs GITHUB_ACTOR / GITHUB_TOKEN or gpr.user / gpr.token)
./gradlew publish
```

## Releasing

Publishing to GitHub Packages is done automatically by GitHub Actions whenever a
**GitHub Release** is published with a tag in the `v.MAJOR.MINOR.PATCH` format
(e.g. `v.0.1.0`). The Maven artifact version is derived from the tag by
stripping the `v.` prefix (`v.0.1.0` → `0.1.0`).

Either create the release from the GitHub UI (*Releases → Draft a new release*),
or via the GitHub CLI:

```bash
gh release create v.0.1.0 --title "v.0.1.0" --generate-notes
```

Local builds that don't set the `RELEASE_VERSION` environment variable use the
`0.1.0-SNAPSHOT` fallback version.
