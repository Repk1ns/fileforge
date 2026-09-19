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

- Kotlin `2.1.10`, JVM 21, AWS SDK v2, Spring Boot `3.5.x`.
- Works against AWS S3, Versity Gateway, MinIO, Cloudflare R2 or LocalStack.
- The AWS SDK is an implementation detail: it is not on your compile classpath.
- Usable outside a web application; the Spring MVC integration is optional and
  adds nothing to your runtime classpath.
- One dependency line, nothing else to add. 124 kB jar; 10.7 MB resolved,
  almost all of it the AWS SDK.

> **Integrating this into a project?** Jump to
> [Ready-made setup: documents and photos](#ready-made-setup-documents-and-photos)
> for a complete copy-paste configuration and a working service, then read
> [Common mistakes](#common-mistakes) — it lists the four things that go wrong.
>
> Upgrading from `1.0.x`? See [MIGRATION-2.0.md](MIGRATION-2.0.md).

---

## Contents

- [Adding the dependency](#adding-the-dependency)
- [Ready-made setup: documents and photos](#ready-made-setup-documents-and-photos)
- [Common mistakes](#common-mistakes)
- [Configuration](#configuration)
- [Usage](#usage)
- [Serving files: presigned URLs or streaming?](#serving-files-presigned-urls-or-streaming)
- [Error handling](#error-handling)
- [Supported file types](#supported-file-types)
- [Extending](#extending)
- [Commands](#commands)
- [Releasing](#releasing)

---

## Adding the dependency

Published to **Maven Central** — no repository declaration, no credentials.

```kotlin
dependencies {
    implementation("net.mimochodek:fileforge:2.0.0")
}
```

Maven:

```xml
<dependency>
    <groupId>net.mimochodek</groupId>
    <artifactId>fileforge</artifactId>
    <version>2.0.0</version>
</dependency>
```

Nothing else. No `@EnableFileForge`, no `@Import`, no bean declarations — the
auto-configuration activates on its own.

### What that actually pulls in

| | |
| --- | --- |
| The jar itself | 124 kB, 67 classes |
| Declared dependencies | `kotlin-stdlib`, `awssdk:s3` (runtime), `slf4j-api` (runtime) |
| Resolved runtime classpath | 37 artifacts, 10.7 MB — 5.6 MB of it the AWS SDK itself |
| Spring dependencies added | **none** |

`spring-boot-autoconfigure` and `spring-web` are `compileOnly`: they are needed
to build the library, never to run it. Your Spring Boot application already has
them, so the auto-configuration and the `MultipartFile` extension just work; an
application that has neither still starts, and the optional web pieces quietly
back off. There is a test that runs the whole library with the entire
`spring-web` artifact hidden from the classpath, so this cannot silently stop
being true.

The AWS SDK's async Netty client is excluded — this library only ever builds a
synchronous `S3Client`, and shipping Netty cost every consumer 4 MB for code
that never ran. If you need the async client for your own purposes, depend on
`software.amazon.awssdk:netty-nio-client` directly.

---

## Ready-made setup: documents and photos

The common case: a web application storing user-uploaded photos (JPEG/PNG) and
attachments (PDF, Word, Excel), a few MB each. Copy the whole block.

### `application.yaml`

```yaml
fileforge:
  s3:
    bucket: my-app
    # Self-hosted S3 gateway (Versity Gateway, MinIO, …). Omit `endpoint` for real AWS.
    endpoint: http://nas.local:7070
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    path-style-access: true          # default; self-hosted gateways need it

  validation:
    max-file-size: 20MB
    allowed-mime-types:
      - image/jpeg
      - image/png
      - image/webp
      - application/pdf
      - application/vnd.openxmlformats-officedocument.wordprocessingml.document   # .docx
      - application/vnd.openxmlformats-officedocument.spreadsheetml.sheet         # .xlsx

  profiles:
    photos:
      folder: photos
      validation:
        max-file-size: 10MB
        allowed-mime-types: [image/jpeg, image/png, image/webp]
    attachments:
      folder: attachments
      validation:
        max-file-size: 20MB          # inherits the MIME whitelist above

# REQUIRED: Spring Boot's own upload limit defaults to 1MB and applies FIRST.
# Without this, anything over 1MB is rejected before FileForge ever sees it.
spring:
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 25MB
```

A profile that sets only `max-file-size` inherits the rest of the global policy,
so `attachments` accepts all six types while `photos` accepts images only.

### Entity — persist the key, nothing else

```kotlin
@Entity
class Attachment(
    @Id @GeneratedValue
    val id: Long? = null,

    /** The only thing you have to store. Everything else is readable from storage. */
    val storageKey: String,

    val documentId: Long,
)
```

You do **not** need columns for the file name, content type or size —
`storage.metadata(key)` returns all three, because the original name is kept as
object metadata at upload time.

### Service

```kotlin
import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.web.store          // <-- the MultipartFile extension
import org.springframework.web.multipart.MultipartFile

@Service
class AttachmentService(
    private val storage: FileStorage,
    private val repository: AttachmentRepository,
) {

    /** Validates, generates a key and writes — all inside store(). */
    fun upload(documentId: Long, file: MultipartFile): Attachment {
        val stored = storage.store(file, profile = "attachments")
        return repository.save(Attachment(storageKey = stored.key, documentId = documentId))
    }

    fun uploadPhoto(documentId: Long, file: MultipartFile): Attachment {
        val stored = storage.store(file, profile = "photos")
        return repository.save(Attachment(storageKey = stored.key, documentId = documentId))
    }

    fun describe(attachment: Attachment): FileMetadata =
        storage.metadata(attachment.storageKey)

    fun delete(attachment: Attachment) {
        storage.delete(attachment.storageKey)
        repository.delete(attachment)
    }
}
```

### Controller

```kotlin
@RestController
@RequestMapping("/api/documents/{documentId}/attachments")
class AttachmentController(private val service: AttachmentService) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @PathVariable documentId: Long,
        @RequestPart("file") file: MultipartFile,
    ): AttachmentResponse = service.upload(documentId, file).toResponse()

    /** Streams the file through the application — always works. */
    @GetMapping("/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<StreamingResponseBody> =
        service.serve(id)
}
```

### Serving the bytes

Pick one of the two. See
[Serving files](#serving-files-presigned-urls-or-streaming) for which.

```kotlin
// A) Stream through the application. Works regardless of where storage lives.
fun serve(attachment: Attachment): ResponseEntity<StreamingResponseBody> {
    val meta = storage.metadata(attachment.storageKey)
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(meta.contentType))
        .contentLength(meta.size)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(meta.fileName ?: "download")
                .build()
                .toString(),
        )
        .body(
            StreamingResponseBody { out ->
                storage.read(attachment.storageKey) { it.stream.copyTo(out) }
            },
        )
}

// B) Hand the browser a temporary link and let it fetch from storage directly.
//    Requires storage to be reachable from the user's browser.
fun downloadLink(attachment: Attachment): URI {
    val meta = storage.metadata(attachment.storageKey)
    return storage.downloadUrl(
        key = attachment.storageKey,
        ttl = Duration.ofMinutes(15),
        downloadAs = meta.fileName,          // saves under the original name
    )
}
```

### Optional: automatic error responses

```yaml
fileforge:
  web:
    problem-details:
      enabled: true
```

Turns rejected uploads into RFC 7807 responses (413 too large, 415 wrong type,
404 missing, …) without writing a `@RestControllerAdvice`. Leave it off if you
already have your own. See [Error handling](#error-handling).

---

## Common mistakes

Four things that go wrong. The first two are by far the most common.

### 1. Forgetting Spring Boot's own upload limit

`spring.servlet.multipart.max-file-size` defaults to **1MB** and
`max-request-size` to **10MB**. Spring rejects the request *before* FileForge
sees it, so raising `fileforge.validation.max-file-size` alone changes nothing
and you get Spring's `MaxUploadSizeExceededException`, not FileForge's
`FileSizeExceededException`.

Set both, with Spring's limit at least as high as FileForge's.

### 2. Forgetting to import the `MultipartFile` extension

`storage.store(file, …)` is a Kotlin extension function, not a member of the
interface. Without the import it will not compile:

```kotlin
import net.mimochodek.fileforge.web.store
```

(It lives in a separate package because `spring-web` is a `compileOnly`
dependency, which is what keeps the library usable outside web applications.)

### 3. Expecting `list()` to return everything

It returns **one page** — 1000 keys by default. This is deliberate; an
unbounded listing pulls an entire bucket into memory. Loop over
`nextPageToken`, as shown in [Listing](#listing).

### 4. Trying to inject `S3Client`

It is not a bean, on purpose — see [Extending](#extending). Configure the
endpoint, credentials, region and path-style access instead; to replace the
storage implementation entirely, publish a `StorageBackend` bean.

### Integration checklist

- [ ] `implementation("net.mimochodek:fileforge:2.0.0")`
- [ ] `fileforge.s3.bucket` set — it is the only required property
- [ ] `spring.servlet.multipart.max-file-size` ≥ `fileforge.validation.max-file-size`
- [ ] Every content type you intend to store is in `allowed-mime-types`
      (the default list is images + PDF only, **no Office formats**)
- [ ] `import net.mimochodek.fileforge.web.store` where you upload
- [ ] Persist `StoredFile.key`; drop your own file-name/size/type columns
- [ ] Reading with `read(key) { … }` or `readBytes(key)`
- [ ] Bucket exists on the storage side — the library does not create it

---

## Configuration

```yaml
fileforge:
  provider: s3                     # default: s3. Any other value deactivates FileForge.

  s3:
    bucket: my-app-prod            # required
    region: us-east-1              # default: us-east-1
    endpoint: http://nas:7070      # OPTIONAL — omit for real AWS S3
    access-key: ${S3_ACCESS_KEY}   # OPTIONAL — omit both keys to use the AWS
    secret-key: ${S3_SECRET_KEY}   #            default credential chain
    path-style-access: true        # default: true (self-hosted). false for AWS.

  naming:
    strategy: date-uuid            # date-uuid | uuid | year-uuid
    keep-extension: true

  validation:                      # storage-agnostic — applies to every backend
    max-file-size: 50MB
    allowed-mime-types: [image/jpeg, image/png, image/webp, application/pdf]
    verify-magic-bytes: true
    unknown-type-policy: allow     # allow | reject

  buffering:
    in-memory-threshold: 1MB       # larger uploads spill to a temp file

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

### Self-hosted gateway (Versity Gateway, MinIO, …)

```yaml
fileforge:
  s3:
    bucket: my-app
    endpoint: http://nas.local:7070
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    path-style-access: true     # default — gateways generally require it
```

This is the configuration the integration test suite runs against: 17 tests
against a real `versity/versitygw` container covering upload, download,
metadata round-trip, presigned GET and PUT, pagination, delimiter listing, copy,
move, batch delete and multipart upload.

### Real AWS S3

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
on ECS, EKS or EC2. Moving from a self-hosted gateway to AWS is these three
lines and no code change.

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

---

## Usage

### Storing

There is exactly one way in — `store(FileUpload)` — plus a thin extension for
Spring MVC uploads that builds the `FileUpload` for you.

```kotlin
// From a Spring MVC upload — the extension, which streams
val stored: StoredFile = storage.store(file, folder = "documents")

// …or with the folder and policy declared once in configuration
val stored = storage.store(file, profile = "documents")

// Anything else
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

**Prefer `ContentSource.Stream`.** It is buffered only up to
`fileforge.buffering.in-memory-threshold` (1MB) and spilled to a temporary file
beyond that, so a 20MB upload never sits whole in the heap.
`ContentSource.Bytes` loads everything into memory and is meant for content that
is already there — a generated report, a thumbnail. There is deliberately no
`store(fileName, bytes, …)` shorthand, because it makes the memory-hungry choice
the convenient one.

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

### Reading

```kotlin
// Scoped read — the backend connection cannot leak
val text = storage.read(key) { it.stream.reader().readText() }

// Whole object into memory, for small files
val bytes = storage.readBytes(key)

// Headers without transferring the body
val metadata = storage.metadata(key)   // fileName, contentType, size, checksum
```

Reading is scoped on purpose: `read` hands the open object to your lambda and
closes it afterwards, including when the lambda throws. There is no unscoped
variant that returns an open `FileContent`, because that is a connection leak
waiting for the first person who forgets.

### Links

```kotlin
storage.downloadUrl(key)                                       // 15 minutes
storage.downloadUrl(key, Duration.ofHours(1))
storage.downloadUrl(key, downloadAs = "Quarterly Report.pdf")  // saves as this
```

### Listing

```kotlin
var page = storage.list("documents/", limit = 500)
while (true) {
    page.keys.forEach(::process)
    page = storage.list("documents/", limit = 500, pageToken = page.nextPageToken ?: break)
}

// Directory-style listing of a single level
val level = storage.list("documents/", delimiter = "/")
level.keys            // keys directly under documents/
level.commonPrefixes  // documents/2026/, documents/2025/, ...
```

### Managing

```kotlin
storage.exists(key)
storage.delete(key)             // idempotent; true if it existed
storage.deleteAll(keys)         // batched, 1000 per request on S3
```

Uploads larger than S3's 5 GiB single-request limit are sent as a multipart
upload automatically; nothing to configure.

There is no `copy` or `move`. Relocating an object on S3 is a full server-side
copy followed by a delete, it is not atomic, and it changes the key you have
stored in your database. **To archive a file, mark it inactive in your own
database and leave the object where it is** — no copying, atomic, instantly
reversible, and the key never moves.

---

## Serving files: presigned URLs or streaming?

A **presigned URL** is a time-limited signed link that lets its holder read (or
write) an object without any storage credentials. The browser talks to the
storage directly and the bytes never pass through your application.

Which to use depends on one question: **can the user's browser reach your
storage?**

| | Stream through the app | Presigned GET |
| --- | --- | --- |
| Storage must be publicly reachable | no | **yes** |
| Bytes pass through your app | yes | no |
| Access control | your code, per request | link-based; anyone with the link |
| Works for a LAN-only NAS | **yes** | no |
| Bandwidth cost on the app | full | none |

**Self-hosted gateway on a LAN (NAS, internal Docker host).** A presigned URL
points at the gateway's own host and port — for example
`http://nas.local:7070/…`. If the browser cannot resolve or reach that address,
the link is dead. **Stream through the application** (variant A above), or
expose the gateway through a reverse proxy on a public hostname and set
`fileforge.s3.endpoint` to that hostname.

**Real AWS S3, or a gateway behind a public reverse proxy.** Presigned GET is
the better choice: your application does not carry the traffic, and
`downloadAs` makes the file save under its original name.

Either way, a presigned URL carries **no authorization of its own** — check that
this user may see this file before you generate the link, and keep the TTL short.

### Why there is no presigned PUT

A presigned upload URL would let a browser write **straight into storage**,
skipping the application — and therefore skipping every check this library
exists to perform. Size, MIME type and magic bytes would all go unverified,
because the library would never see the bytes, and the key would be chosen by
the caller instead of generated.

It is not offered. Upload through `store()` and keep the validation. If you ever
genuinely need direct browser-to-storage writes for multi-GB files, implement
your own `StorageBackend` extension for it and own that trade-off explicitly.

---

## Error handling

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
| `UnknownStorageProfileException` | `profile`, `knownProfiles` | 500 (configuration bug) |

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

The 503 and 500 branches deliberately report generically and log the cause —
their messages contain bucket and host names that should not reach clients.

---

## Supported file types

`verify-magic-bytes` checks that content really is what it claims to be. Types
with a known signature are verified; whitelisted types without one are accepted
unless `unknown-type-policy: reject`.

| Type | MIME | Verified |
| --- | --- | --- |
| JPEG | `image/jpeg` | yes |
| PNG | `image/png` | yes |
| WebP | `image/webp` | yes |
| GIF | `image/gif` | yes |
| TIFF | `image/tiff` | yes |
| BMP | `image/bmp` | yes |
| PDF | `application/pdf` | yes |
| ZIP | `application/zip` | yes |
| GZIP | `application/gzip` | yes |
| Word | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | as a ZIP container |
| Excel | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | as a ZIP container |
| PowerPoint | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | as a ZIP container |
| Word (legacy) | `application/msword` | as an OLE2 container |
| Excel (legacy) | `application/vnd.ms-excel` | as an OLE2 container |
| PowerPoint (legacy) | `application/vnd.ms-powerpoint` | as an OLE2 container |

**Only JPEG, PNG, WebP and PDF are whitelisted by default** — list explicitly
whatever else you intend to store.

### A note on Office formats

`.docx` / `.xlsx` / `.pptx` are ZIP archives and `.doc` / `.xls` are OLE2
compound files, so magic bytes can only confirm the *container*. FileForge
verifies that much — an executable declared as `.docx` is rejected — and uses
the file extension to resolve the container to the specific Office type. That is
a narrowly scoped exception to "magic bytes win over the extension", valid only
because both answers are true at once: a PDF renamed to `.docx` is still
detected as a PDF.

To store Office files you therefore need nothing beyond adding the MIME types to
`allowed-mime-types`; detection works whether or not the client declares a
content type.

---

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

---

## Commands

```bash
./gradlew build                  # compile + test
./gradlew test                   # integration tests need Docker; skipped otherwise
./gradlew publishToMavenLocal    # install into ~/.m2 for local consumption

# If Testcontainers cannot reach your Docker daemon, override the API version:
./gradlew test -PdockerApiVersion=1.47
```

Maintaining the library itself? See
[DOKUMENTACE-VYVOJARE.md](DOKUMENTACE-VYVOJARE.md) (Czech) — a per-file
walkthrough, the invariants that must keep holding, and a glossary.

---

## Releasing

`.github/workflows/publish.yml` publishes to Maven Central when a **GitHub
Release is published** — pushing a tag alone does not trigger it:

1. Bump `version` in `build.gradle.kts` and merge it.
2. Publish a GitHub Release for the matching `vMAJOR.MINOR.PATCH` tag.

The artifact version comes from `build.gradle.kts`, not from the tag, so keep
the two in sync. Each version can be published only once.
