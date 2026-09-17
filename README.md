# FileForge

A reusable Spring Boot auto-configuration library that centralises file storage
for an application. S3 is the backend that ships; others are selectable by
configuration without changing a line of consumer code.

It is a **plain library JAR** — no `main()`, no `@SpringBootApplication` —
consumed as a normal Maven dependency.

Adding it to a project is three steps:

1. Add the dependency.
2. Configure `fileforge` in `application.yaml`.
3. Inject `FileStorage`.

```kotlin
@Service
class DocumentService(private val storage: FileStorage) {

    fun upload(file: MultipartFile): StoredFile = storage.store(file, folder = "documents")

    fun link(key: String): URI = storage.downloadUrl(key)
}
```

That is the whole integration. Sanitization, size limits, MIME whitelisting,
magic-byte verification and key generation all happen inside `store()`, so there
is no path that writes unvalidated content and nothing to remember at the call
site.

- Kotlin `2.1.10`, JVM 21, AWS SDK v2.
- Works against AWS S3, versitygw, MinIO, Cloudflare R2 or LocalStack.
- The AWS SDK is an implementation detail: it is not on your compile classpath.
- Usable outside a web application; the Spring MVC integration is optional.

> Upgrading from `1.0.x`? See [MIGRATION-2.0.md](MIGRATION-2.0.md).

## Adding the dependency

Published to **Maven Central** — no repository declaration, no credentials.

```kotlin
dependencies {
    implementation("net.mimochodek:fileforge:2.0.0")
}
```

## Configuration

```yaml
fileforge:
  provider: s3                     # default: s3. Any other value deactivates FileForge.

  s3:
    bucket: my-app-prod            # required
    region: us-east-1              # default: us-east-1
    endpoint: http://nas:7070      # OPTIONAL - omit for real AWS S3
    access-key: ${S3_ACCESS_KEY}   # OPTIONAL - omit both keys to use the AWS
    secret-key: ${S3_SECRET_KEY}   #            default credential chain
    path-style-access: true        # default: true (self-hosted). false for AWS.

  naming:
    strategy: date-uuid            # date-uuid | uuid | year-uuid
    keep-extension: true

  validation:                      # storage-agnostic - applies to every backend
    max-file-size: 50MB
    allowed-mime-types: [image/jpeg, image/png, image/webp, application/pdf]
    verify-magic-bytes: true
    unknown-type-policy: allow     # allow | reject

  web:
    problem-details:
      enabled: false               # opt-in RFC 7807 error responses

  profiles:                        # optional named upload categories
    avatars:
      folder: avatars
      validation: { max-file-size: 2MB, allowed-mime-types: [image/jpeg, image/png] }
    documents:
      folder: documents
      validation: { max-file-size: 25MB, allowed-mime-types: [application/pdf] }
```

Only `fileforge.s3.bucket` is required. A missing or contradictory setting fails
at startup with a message that names the key.

### Against real AWS

Omit `endpoint`, omit both keys, and turn off path-style access:

```yaml
fileforge:
  s3:
    bucket: my-app-prod
    region: eu-central-1
    path-style-access: false
```

Credentials then come from the AWS default chain, so IAM roles, instance
profiles, SSO and environment credentials all work — nothing extra to configure
on ECS, EKS or EC2.

### Keys

| `naming.strategy` | Example key |
| --- | --- |
| `date-uuid` (default) | `documents/2026/09/17/1f7a….pdf` |
| `uuid` | `documents/1f7a….pdf` |
| `year-uuid` | `documents/2026_1f7a….pdf` (the 1.x layout) |

Keys are generated, never taken from the upload, so a hostile file name cannot
influence where the object lands. The original name is stored as object metadata
and returned by `metadata(key)`, so you do not have to keep it in your database.

### Reference

| Key | Required | Default | Description |
| --- | --- | --- | --- |
| `fileforge.provider` | no | `s3` | Backend to activate; any other value deactivates the library |
| `fileforge.s3.bucket` | **yes** | — | Bucket name |
| `fileforge.s3.region` | no | `us-east-1` | S3 region |
| `fileforge.s3.endpoint` | no | — | S3-compatible endpoint; omit for real AWS |
| `fileforge.s3.access-key` | no | — | Omit with `secret-key` to use the AWS default credential chain |
| `fileforge.s3.secret-key` | no | — | Must be set together with `access-key` |
| `fileforge.s3.path-style-access` | no | `true` | `false` for real AWS S3 |
| `fileforge.naming.strategy` | no | `date-uuid` | Key layout |
| `fileforge.naming.keep-extension` | no | `true` | Keep the original extension in the key |
| `fileforge.naming.zone` | no | `Z` | Zone the date path is derived in |
| `fileforge.validation.max-file-size` | no | `50MB` | Enforced against measured content |
| `fileforge.validation.allowed-mime-types` | no | jpeg/png/webp/pdf | MIME whitelist; must not be empty |
| `fileforge.validation.verify-magic-bytes` | no | `true` | Content must match its declared type |
| `fileforge.validation.unknown-type-policy` | no | `allow` | `reject` stores only verifiable content |
| `fileforge.buffering.in-memory-threshold` | no | `1MB` | Larger uploads spill to a temp file |
| `fileforge.buffering.temp-dir` | no | JVM temp dir | Where to spill |
| `fileforge.web.problem-details.enabled` | no | `false` | Publish the RFC 7807 handler |
| `fileforge.profiles.<name>.folder` | no | — | Folder for that profile |
| `fileforge.profiles.<name>.validation.*` | no | inherits `fileforge.validation` | Per-profile overrides |

## Usage

### Storing

```kotlin
// From a Spring MVC upload - one call
val stored: StoredFile = storage.store(file, folder = "documents")

// From bytes
storage.store("report.pdf", bytes, folder = "documents")

// Full control
storage.store(
    FileUpload(
        fileName = "report.pdf",
        content = ContentSource.Stream(inputStream),  // unknown length is fine
        contentType = "application/pdf",              // or null to detect it
        folder = "documents",
        metadata = mapOf("owner" to userId),
    ),
)
```

`StoredFile` carries everything worth persisting — usually you keep `key` and
nothing else:

```kotlin
data class StoredFile(
    val key: String,          // persist this
    val fileName: String,     // sanitized original name, also stored as metadata
    val contentType: String,  // declared or detected
    val size: Long,           // bytes actually written
    val checksum: String?,    // ETag
    val storedAt: Instant,
)
```

The size limit applies to the bytes that arrive, not to any value you pass —
`ContentSource.Stream(stream, length)` treats `length` as a hint only.

### Profiles

Declaring a category once removes the folder concatenation and limit checks that
otherwise get rewritten per call site:

```kotlin
storage.store(file, profile = "documents")   // folder and policy come from config
storage.store(file, profile = "avatars")
```

A profile's `validation` block overrides `fileforge.validation` field by field,
so setting only `max-file-size` narrows the size while keeping the global MIME
whitelist. A profile that sets only a `folder` keeps the global policy entirely —
including a custom `FileValidator` bean, if you published one. Naming a profile
that is not configured throws `UnknownStorageProfileException`, listing the ones
that are.

### Reading

```kotlin
val bytes = storage.readBytes(key)

// Scoped read - the backend connection cannot leak
val text = storage.read(key) { it.stream.reader().readText() }

// Headers without transferring the body
val metadata = storage.metadata(key)   // fileName, contentType, size, checksum
```

### Links

```kotlin
storage.downloadUrl(key)                                          // 15 minutes
storage.downloadUrl(key, Duration.ofHours(1))
storage.downloadUrl(key, downloadAs = "Quarterly Report.pdf")     // saves as this
storage.uploadUrl("uploads/raw.bin", "application/octet-stream")  // browser to storage
```

### Managing

```kotlin
storage.exists(key)
storage.delete(key)             // idempotent; true if it existed
storage.deleteAll(keys)         // batched, 1000 per request on S3
storage.copy(from, to)
storage.move(from, to)

var page = storage.list("documents/", limit = 500)
while (true) {
    page.keys.forEach(::process)
    page = storage.list("documents/", limit = 500, pageToken = page.nextPageToken ?: break)
}
```

### Error handling

Every failure is a `FileStorageException`, and the subtypes expose what went
wrong as properties rather than in the message:

| Exception | Carries | Suggested status |
| --- | --- | --- |
| `FileNotFoundStorageException` | `key` | 404 |
| `FileSizeExceededException` | `actualSize`, `maxSize` | 413 |
| `UnsupportedFileTypeException` | `contentType`, `allowedTypes` | 415 |
| `ContentMismatchException` | `declaredContentType`, `detectedContentType` | 415 |
| `InvalidFileNameException` | `fileName` | 400 |
| `StorageUnavailableException` | — (retryable) | 503 |

Set `fileforge.web.problem-details.enabled: true` and that whole table is mapped
to RFC 7807 responses for you, with the fields attached as problem extensions:

```json
{
  "title": "Payload Too Large",
  "status": 413,
  "detail": "File size 9437184 bytes exceeds the maximum allowed size of 5242880 bytes",
  "maxSize": 5242880,
  "actualSize": 9437184
}
```

## Extending

The library is two interfaces, and you can replace either.

`FileStorage` is what you inject. `StorageBackend` is what a backend implements
— exact keys only, no validation, naming or policy, because all of that lives
above it and is shared by every backend:

```kotlin
@Bean
fun storageBackend(): StorageBackend = MyFilesystemBackend(root)
```

Publish that and the whole pipeline — sanitization, validation, key generation,
the `MultipartFile` extension, the error mapping — works unchanged, and no S3
configuration is required. `src/test/.../testsupport/InMemoryStorageBackend.kt`
is a complete example in about 90 lines.

`ObjectKeyGenerator` and `FileValidator` are replaceable the same way. The
`S3Client` and `S3Presigner` are deliberately **not** beans: a library should not
contribute generically-typed infrastructure it owns, because it silently crosses
wires with a consumer's own client. They are private to the backend and closed
with it.

## Commands

```bash
./gradlew build                  # compile + test
./gradlew test                   # integration tests need Docker; skipped otherwise
./gradlew publishToMavenLocal    # install into ~/.m2 for local consumption

# If Testcontainers cannot reach your Docker daemon, override the API version:
./gradlew test -PdockerApiVersion=1.47
```

## Releasing

`.github/workflows/publish.yml` publishes to Maven Central when a **GitHub
Release is published** — pushing a tag alone does not trigger it:

1. Bump `version` in `build.gradle.kts` and merge it.
2. Publish a GitHub Release for the matching `vMAJOR.MINOR.PATCH` tag.

The artifact version comes from `build.gradle.kts`, not from the tag, so keep
the two in sync. Each version can be published only once.
