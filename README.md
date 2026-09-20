# FileForge

A Spring Boot library that centralises file storage for an application. Add the
dependency, configure a bucket, inject `FileStorage`. S3 is the backend that
ships; others are selectable by configuration without changing consumer code.

It is a **plain library JAR** — no `main()`, no `@SpringBootApplication` —
consumed as a normal Maven dependency.

```kotlin
@Service
class DocumentService(private val storage: FileStorage) {

    fun upload(file: MultipartFile): String = storage.store(file, profile = "documents").key

    fun download(key: String): ByteArray = storage.readBytes(key)
}
```

Sanitization, size limits, MIME whitelisting, magic-byte verification and key
generation all happen inside `store()`. There is no path that writes
unvalidated content and nothing to remember at the call site.

- Kotlin `2.1.10`, JVM 21, AWS SDK v2, Spring Boot `3.5.x`.
- Works against AWS S3, Versity Gateway, MinIO, Cloudflare R2 or LocalStack.
- Adds **no Spring dependency** to your runtime classpath; the AWS SDK is not on
  your compile classpath.
- 124 kB jar, 37 artifacts / 10.7 MB resolved, almost all of it the AWS SDK.

---

> ## Integrating this library? Read this section, then stop.
>
> [**Integration guide**](#integration-guide) is a complete, ordered, copy-paste
> procedure. Everything below it is reference material you only need when you
> want something the guide does not cover.
>
> Four things are easy to get wrong and are called out in the guide:
> Spring Boot's own 1 MB upload limit, the required `import` for the
> `MultipartFile` extension, the fact that Office MIME types are not whitelisted
> by default, and that the library stores no file name.

---

## Contents

- [Integration guide](#integration-guide) — start here
- [What the library does and does not do](#what-the-library-does-and-does-not-do)
- [API reference](#api-reference)
- [Configuration reference](#configuration-reference)
- [Supported file types](#supported-file-types)
- [Serving files: presigned URLs or streaming?](#serving-files-presigned-urls-or-streaming)
- [Troubleshooting](#troubleshooting)
- [Extending](#extending)
- [Commands and releasing](#commands-and-releasing)

---

# Integration guide

Follow these six steps in order. They assume a Spring Boot 3.5 web application
storing user-uploaded documents and photos.

## Step 1 — Add the dependency

Published to Maven Central. No repository declaration, no credentials.

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.mimochodek:fileforge:2.0.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>net.mimochodek</groupId>
    <artifactId>fileforge</artifactId>
    <version>2.0.0</version>
</dependency>
```

Nothing else. **Do not** add `@EnableFileForge`, `@Import`, a `@Configuration`
class, or an `S3Client` bean — none of those exist or are needed. The
auto-configuration activates on its own.

> If the version does not resolve, it has not been released yet. Build it from
> the FileForge checkout with `./gradlew publishToMavenLocal` and add
> `mavenLocal()` to your repositories.

## Step 2 — Configure `application.yaml`

Copy this whole block and change `bucket`, `endpoint` and the credentials.

```yaml
fileforge:
  s3:
    bucket: my-app                 # REQUIRED — the only mandatory property
    endpoint: http://nas.local:7070   # self-hosted gateway; DELETE this line for real AWS
    access-key: ${S3_ACCESS_KEY}      # DELETE both keys for real AWS (uses the IAM role)
    secret-key: ${S3_SECRET_KEY}
    region: us-east-1
    path-style-access: true        # true for self-hosted gateways, false for real AWS

  validation:
    max-file-size: 20MB
    allowed-mime-types:            # NOTHING outside this list can be stored
      - image/jpeg
      - image/png
      - image/webp
      - application/pdf
      - application/vnd.openxmlformats-officedocument.wordprocessingml.document   # .docx
      - application/vnd.openxmlformats-officedocument.spreadsheetml.sheet         # .xlsx

  profiles:                        # named categories; use instead of hardcoding folders
    photos:
      folder: photos
      validation:
        max-file-size: 10MB
        allowed-mime-types: [image/jpeg, image/png, image/webp]
    attachments:
      folder: attachments
      validation:
        max-file-size: 20MB        # inherits the MIME whitelist above

# REQUIRED. Spring Boot's own limit is 1MB and is applied BEFORE FileForge sees
# the request. Without this, uploads over 1MB fail with Spring's
# MaxUploadSizeExceededException and FileForge never runs.
spring:
  servlet:
    multipart:
      max-file-size: 20MB          # must be >= fileforge.validation.max-file-size
      max-request-size: 25MB
```

Notes that change what you write:

- **`bucket` must already exist on the storage side.** The library does not
  create it.
- A profile that sets only `max-file-size` inherits the rest of the global
  policy. Above, `attachments` accepts all six types; `photos` accepts images.
- Startup fails with a message naming the key if configuration is missing or
  contradictory. That is intended — do not add `try/catch` around it.

## Step 3 — Create the entity

The library returns **one string: the key**. Everything a human reads is yours
to store.

```kotlin
@Entity
class Attachment(
    @Id @GeneratedValue
    val id: Long? = null,

    /** What storage.store() returned. All the library needs back. */
    val storageKey: String,

    /** Whatever your application calls this attachment: a title the user typed,
     *  the original file name, a label. The library stores none of these. */
    val name: String,

    val documentId: Long,
)
```

**Do not add columns for content type or size** unless you want to list them
without touching storage — `storage.metadata(key)` returns both.

**Do not expect the uploaded file name anywhere.** The library takes only the
extension from it and neither stores nor returns it. If your UI never shows an
uploaded file name, you need no `name` column at all.

## Step 4 — Write the service

```kotlin
import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.web.store        // REQUIRED for store(MultipartFile, …)
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class AttachmentService(
    private val storage: FileStorage,            // the only bean you inject
    private val repository: AttachmentRepository,
) {

    /** Validates, generates a key and writes — all inside store(). */
    fun upload(documentId: Long, name: String, file: MultipartFile): Attachment {
        val stored = storage.store(file, profile = "attachments")
        return repository.save(
            Attachment(storageKey = stored.key, name = name, documentId = documentId),
        )
    }

    fun uploadPhoto(documentId: Long, name: String, file: MultipartFile): Attachment {
        val stored = storage.store(file, profile = "photos")
        return repository.save(
            Attachment(storageKey = stored.key, name = name, documentId = documentId),
        )
    }

    fun describe(attachment: Attachment): FileMetadata =
        storage.metadata(attachment.storageKey)

    fun delete(attachment: Attachment) {
        storage.delete(attachment.storageKey)
        repository.delete(attachment)
    }

    fun deleteAll(attachments: List<Attachment>) {
        storage.deleteAll(attachments.map { it.storageKey })   // one request per 1000 keys
        repository.deleteAll(attachments)
    }
}
```

`import net.mimochodek.fileforge.web.store` is **not optional**.
`store(file, …)` is an extension function, not a member of the interface; the
code does not compile without it.

## Step 5 — Serve the bytes

Use this unless you have read
[Serving files](#serving-files-presigned-urls-or-streaming) and concluded
otherwise. It works in every deployment.

```kotlin
@RestController
@RequestMapping("/api/documents/{documentId}/attachments")
class AttachmentController(
    private val service: AttachmentService,
    private val storage: FileStorage,
    private val repository: AttachmentRepository,
) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @PathVariable documentId: Long,
        @RequestParam name: String,
        @RequestPart("file") file: MultipartFile,
    ): AttachmentResponse = service.upload(documentId, name, file).toResponse()

    @GetMapping("/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<StreamingResponseBody> {
        val attachment = repository.findById(id).orElseThrow()
        val meta = storage.metadata(attachment.storageKey)

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(meta.contentType))
            .contentLength(meta.size)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(attachment.downloadName).build().toString(),
            )
            .body(
                StreamingResponseBody { out ->
                    storage.read(attachment.storageKey) { it.stream.copyTo(out) }
                },
            )
    }
}

/**
 * A user-typed title has no extension, so a PDF would download as "Smlouva"
 * with no suffix. The key ends in the extension, so take it from there.
 */
private val Attachment.downloadName: String
    get() = storageKey.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() }
        ?.let { "$name.$it" }
        ?: name
```

Read **only** with `storage.read(key) { … }` or `storage.readBytes(key)`. There
is no unscoped `read(key)` returning an open stream, by design.

## Step 6 — Decide on error responses

Rejected uploads throw. Either map them yourself, or let the library do it:

```yaml
fileforge:
  web:
    problem-details:
      enabled: true      # off by default
```

That publishes a `@RestControllerAdvice` turning every FileForge exception into
an RFC 7807 response — 413 too large, 415 wrong type, 404 missing, 503
unreachable. Leave it `false` if you already have your own advice, and map the
table in [Errors](#errors) yourself.

## Integration checklist

- [ ] `implementation("net.mimochodek:fileforge:2.0.0")` and nothing else added
- [ ] `fileforge.s3.bucket` set, and that bucket exists on the storage side
- [ ] `spring.servlet.multipart.max-file-size` ≥ `fileforge.validation.max-file-size`
- [ ] Every content type you will store is in `allowed-mime-types`
      (the default is images + PDF only — **no Office formats**)
- [ ] `import net.mimochodek.fileforge.web.store` in every file that uploads
- [ ] `FileStorage` injected; no `S3Client`, no `@Configuration` for FileForge
- [ ] `StoredFile.key` persisted; any human-readable name kept in your own row
- [ ] Reading via `read(key) { … }` or `readBytes(key)`
- [ ] `list()` calls wrapped in a pagination loop, if you use it at all

---

# What the library does and does not do

## Does

| | |
| --- | --- |
| Generates the object key | `documents/2026_1f7a8b2c-….pdf` — never derived from the upload |
| Measures the real size | the value you pass is a hint; the bytes are counted |
| Enforces a size limit | against the measured size, aborting the read when exceeded |
| Enforces a MIME whitelist | per profile or globally |
| Verifies magic bytes | content must match its declared type |
| Detects the content type | when you pass none, from magic bytes then extension |
| Buffers safely | in memory to 1 MB, spilled to a temp file beyond |
| Handles huge uploads | multipart above S3's 5 GiB single-request limit |
| Translates failures | no AWS SDK type escapes the abstraction |

## Does not

| | Do this instead |
| --- | --- |
| Store the uploaded file name | keep it in your own row, if you need it |
| Create the bucket | create it on the storage side |
| Copy or move objects | to archive, flag the row in your database |
| Offer presigned uploads | upload through `store()` and keep the validation |
| Authorize anything | check permissions before you call |
| Scan for viruses, resize images, or keep an index | out of scope |

## The key

```
documents/2026_1f7a8b2c-9d4e-4f31-b0a7-5c6e2f1a3d90.pdf
└───┬───┘ └┬─┘ └──────────────────┬──────────────────┘└┬─┘
  folder   year          random UUID                extension
```

S3 has no real folders — the slashes are characters in the key. The folder
comes from the profile, or from `FileUpload.folder`.

The uploaded file name never appears. Only the extension carries over, and only
after it has been checked that it could be an extension at all — so a user
uploading `../../etc/passwd` cannot influence where the object lands, and two
uploads of the same file never collide.

---

# API reference

`FileStorage` has nine methods. That is the whole consumer API.

## Storing

```kotlin
// From a Spring MVC upload. Streams; never holds the whole file in memory.
fun FileStorage.store(
    file: MultipartFile,
    folder: String = "",
    profile: String? = null,
    metadata: Map<String, String> = emptyMap(),
): StoredFile

// Everything else.
fun store(upload: FileUpload): StoredFile

data class FileUpload(
    val fileName: String,                        // ONLY its extension is used
    val content: ContentSource,
    val contentType: String? = null,             // null = detect from the bytes
    val folder: String = "",                     // ignored if the profile sets one
    val metadata: Map<String, String> = emptyMap(),
    val profile: String? = null,
)

sealed interface ContentSource {
    class Bytes(val value: ByteArray)                        // whole file in memory
    class Stream(val stream: InputStream, val length: Long? = null)  // preferred
    class LocalFile(val path: Path)
}

data class StoredFile(
    val key: String,          // persist this
    val contentType: String,  // declared or detected
    val size: Long,           // bytes actually written
    val checksum: String?,    // ETag
    val storedAt: Instant,
)
```

**Prefer `ContentSource.Stream`.** It is buffered only to
`fileforge.buffering.in-memory-threshold` and spilled to a temporary file
beyond, so a 20 MB upload never sits whole in the heap. `Bytes` is for content
already in memory — a generated report, a thumbnail. `length` is a hint and is
never trusted for the size limit.

```kotlin
storage.store(
    FileUpload(
        fileName = "report.pdf",
        content = ContentSource.Stream(inputStream),   // unknown length is fine
        contentType = "application/pdf",
        profile = "attachments",
    ),
)
```

## Reading

```kotlin
fun <T> read(key: String, block: (FileContent) -> T): T   // always closes
fun readBytes(key: String): ByteArray                     // whole object into memory
fun metadata(key: String): FileMetadata                   // one HEAD request, no body

data class FileMetadata(
    val key: String,
    val contentType: String,
    val size: Long,
    val checksum: String?,
    val lastModified: Instant?,
    val metadata: Map<String, String>,   // the map you passed to FileUpload
)
```

`read` is scoped on purpose: the object holds a pooled HTTP connection, and an
API that hands that out unscoped leaks connections the first time someone
forgets. `readBytes` loads everything — for large objects use `read(key) { }`
and copy in chunks.

## Managing

```kotlin
fun exists(key: String): Boolean
fun delete(key: String): Boolean              // idempotent; true if it existed
fun deleteAll(keys: Collection<String>)       // batched, 1000 per request on S3
fun list(prefix: String, limit: Int = 1000, pageToken: String? = null,
         delimiter: String? = null): FilePage
```

`list` returns **one page**. To walk everything:

```kotlin
var page = storage.list("attachments/", limit = 500)
while (true) {
    page.keys.forEach(::process)
    page = storage.list("attachments/", limit = 500, pageToken = page.nextPageToken ?: break)
}
```

With `delimiter = "/"`, `page.keys` holds only keys directly under the prefix
and `page.commonPrefixes` holds the "subfolders".

## Links

```kotlin
fun downloadUrl(key: String, ttl: Duration = Duration.ofMinutes(15),
                downloadAs: String? = null): URI
```

A time-limited signed link. See
[Serving files](#serving-files-presigned-urls-or-streaming) before using it —
whether it works depends on who fetches the bytes.

## Errors

Every failure is a `FileStorageException`. Subtypes carry the facts as
properties, so you never parse a message:

| Exception | Properties | Status |
| --- | --- | --- |
| `FileNotFoundStorageException` | `key` | 404 |
| `FileSizeExceededException` | `actualSize`, `maxSize` | 413 |
| `UnsupportedFileTypeException` | `contentType`, `allowedTypes` | 415 |
| `ContentMismatchException` | `declaredContentType`, `detectedContentType` | 415 |
| `StorageUnavailableException` | — (retryable) | 503 |
| `UnknownStorageProfileException` | `profile`, `knownProfiles` | 500 (config bug) |

They live in `net.mimochodek.fileforge.exception`. With
`fileforge.web.problem-details.enabled: true` this table is mapped for you, with
the properties attached as RFC 7807 extension members:

```json
{
  "title": "Payload Too Large",
  "status": 413,
  "detail": "File size 9437184 bytes exceeds the maximum allowed size of 5242880 bytes",
  "maxSize": 5242880,
  "actualSize": 9437184
}
```

---

# Configuration reference

Every property. Only `fileforge.s3.bucket` is required.

| Key | Default | Description |
| --- | --- | --- |
| `fileforge.provider` | `s3` | Backend to activate. Any other value deactivates the library. |
| `fileforge.s3.bucket` | — | **Required.** Must already exist. |
| `fileforge.s3.region` | `us-east-1` | |
| `fileforge.s3.endpoint` | — | S3-compatible endpoint. Omit for real AWS. |
| `fileforge.s3.access-key` | — | Omit with `secret-key` to use the AWS credential chain. |
| `fileforge.s3.secret-key` | — | Must be set together with `access-key`. |
| `fileforge.s3.path-style-access` | `true` | `false` for real AWS S3. |
| `fileforge.naming.keep-extension` | `true` | Keep the original extension in the key. |
| `fileforge.validation.max-file-size` | `50MB` | Enforced against measured content. |
| `fileforge.validation.allowed-mime-types` | jpeg, png, webp, pdf | Must not be empty. |
| `fileforge.validation.verify-magic-bytes` | `true` | Content must match its declared type. |
| `fileforge.validation.unknown-type-policy` | `allow` | `reject` stores only verifiable content. |
| `fileforge.buffering.in-memory-threshold` | `1MB` | Larger uploads spill to a temp file. |
| `fileforge.buffering.temp-dir` | JVM temp dir | Where to spill. |
| `fileforge.web.problem-details.enabled` | `false` | Publish the RFC 7807 handler. |
| `fileforge.profiles.<name>.folder` | — | Folder for that profile. |
| `fileforge.profiles.<name>.validation.*` | inherits `fileforge.validation` | Per-profile overrides, field by field. |

## Real AWS S3

Delete `endpoint`, delete both keys, turn off path-style:

```yaml
fileforge:
  s3:
    bucket: my-app-prod
    region: eu-central-1
    path-style-access: false
```

Credentials then come from the AWS default chain, so IAM roles, instance
profiles, SSO and environment credentials all work. Moving from a self-hosted
gateway to AWS is these three lines and no code change.

## Profiles

A profile names a category of upload: where it goes and what is allowed in it.
Declare it once instead of writing `"documents/$name"` and a size check in every
service.

```kotlin
storage.store(file, profile = "attachments")
```

A profile's `validation` block overrides the global one **field by field** —
setting only `max-file-size` keeps the global whitelist. A profile that sets
only `folder` keeps the global policy entirely, including a custom
`FileValidator` bean if you published one. Naming a profile that is not
configured throws `UnknownStorageProfileException` listing the ones that are.

---

# Supported file types

`verify-magic-bytes` checks that content really is what it claims. Types with a
known signature are verified; whitelisted types without one are accepted unless
`unknown-type-policy: reject`.

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

**Only JPEG, PNG, WebP and PDF are whitelisted by default.** List anything else
you intend to store in `allowed-mime-types`.

`.docx` / `.xlsx` / `.pptx` are ZIP archives and `.doc` / `.xls` are OLE2
compound files, so magic bytes can only confirm the container. FileForge
verifies that much — an executable declared as `.docx` is rejected — and uses
the extension to resolve the container to the specific Office type. Detection
works whether or not the client declares a content type.

---

# Serving files: presigned URLs or streaming?

A presigned URL is a time-limited signed link that lets its holder read an
object without storage credentials. It contains the storage endpoint —
`http://nas.local:7070/…` — so it only works for a fetcher that can reach that
host.

| | Stream through the app | Presigned, fetched server-side | Presigned, fetched by the browser |
| --- | --- | --- | --- |
| Who fetches | your Spring app | another server (BFF, Next.js, worker) | the end user's browser |
| Storage reachable from | your app | that server | the public internet |
| Works with a LAN-only NAS | **yes** | **yes** | no |
| Bytes cross a server of yours | yes | yes, a different one | **no** |
| Needs storage credentials | yes | **no** | no |

**LAN-only gateway, browser outside it.** The browser cannot reach
`nas.local:7070`. Stream through the application — Step 5.

**LAN-only gateway with a server-side consumer on that LAN.** Presigned GET
works, and gives that consumer time-limited read access to one object without
ever holding S3 credentials. But the bytes still cross a server of yours; you
have moved the load, not removed it. It pays off when that server does
something with them — `next/image` fetching, optimizing and caching means your
app never touches image traffic.

> **The trap:** the URL must stay server-side. Render it into HTML —
> `<img src={presignedUrl}>` from a Server Component — and the browser becomes
> the fetcher, pointed at a host it cannot reach.

**Real AWS S3, or a gateway behind a public reverse proxy.** Presigned GET is
the right choice: no server of yours carries the traffic.

Two things either way. A presigned URL carries **no authorization** — check the
user may see this file before generating it. And **TTL trades cacheability
against exposure**: a URL that changes every request defeats HTTP and
`next/image` caching, because both key on the URL.

**There is no presigned PUT.** It would let a browser write straight into
storage, skipping every check this library performs. Upload through `store()`.

---

# Troubleshooting

| Symptom | Cause |
| --- | --- |
| `MaxUploadSizeExceededException` on anything over 1 MB | `spring.servlet.multipart.max-file-size` not raised. It applies before FileForge. |
| `store(file, …)` does not compile | Missing `import net.mimochodek.fileforge.web.store`. |
| `UnsupportedFileTypeException` for a `.docx` | Office MIME types are not in the default whitelist. Add them. |
| Startup fails: `fileforge.s3.bucket must be configured` | Set it, or set `fileforge.provider` to something other than `s3` to deactivate. |
| Startup fails: `access-key and secret-key must be set together` | Set both, or neither (then the AWS credential chain is used). |
| `NoSuchBucket` at runtime | The library does not create buckets. Create it. |
| `StorageUnavailableException` | Endpoint unreachable: wrong host/port, storage down, firewall. |
| Presigned URL times out in the browser | The browser cannot reach the storage host. Stream through the app instead. |
| `list()` returns only 1000 keys | It returns one page. Loop over `nextPageToken`. |
| No bean of type `S3Client` | Correct — it is not published. Configure the endpoint and credentials instead. |
| Downloaded file has no extension | A user-typed name has none; append it from the key (Step 5). |

---

# Extending

Two interfaces, either replaceable.

`FileStorage` is what you inject. `StorageBackend` is what a backend implements
— eight methods over exact keys, with no validation, naming or policy, because
all of that lives above it and is shared:

```kotlin
@Bean
fun storageBackend(): StorageBackend = MyFilesystemBackend(root)
```

Publish that and the whole pipeline — validation, key generation, the
`MultipartFile` extension, the error mapping — works unchanged, and no S3
configuration is required. `src/test/.../testsupport/InMemoryStorageBackend.kt`
is a complete example in about 90 lines.

`ObjectKeyGenerator` and `FileValidator` are replaceable the same way. The
`S3Client` and `S3Presigner` are deliberately **not** beans: a library should
not contribute generically-typed infrastructure it owns, because it silently
crosses wires with a consumer's own client. They are private to the backend and
closed with it.

---

# Commands and releasing

```bash
./gradlew build                  # compile + test
./gradlew test                   # integration tests need Docker; skipped otherwise
./gradlew publishToMavenLocal    # install into ~/.m2 for local consumption

# If Testcontainers cannot reach your Docker daemon:
./gradlew test -PdockerApiVersion=1.47
```

`.github/workflows/publish.yml` publishes to Maven Central when a **GitHub
Release is published** — pushing a tag alone does not trigger it:

1. Bump `version` in `build.gradle.kts` and merge it.
2. Publish a GitHub Release for the matching `vMAJOR.MINOR.PATCH` tag.

The artifact version comes from `build.gradle.kts`, not from the tag, so keep
the two in sync. Each version can be published only once.

Maintaining the library itself? See
[DOKUMENTACE-VYVOJARE.md](DOKUMENTACE-VYVOJARE.md) (Czech) — a per-file
walkthrough, the invariants that must keep holding, and a glossary.
Upgrading from `1.0.x`? See [MIGRATION-2.0.md](MIGRATION-2.0.md).
