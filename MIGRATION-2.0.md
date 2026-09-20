# Migrating from FileForge 1.0.x to 2.0.0

2.0.0 replaces the API. The change is deliberate: 1.x pushed validation,
naming and metadata bookkeeping back onto every consuming application, which is
the opposite of what the library is for. The upgrade is mostly deleting code.

There is no compatibility shim — `FileStorageService`, `FileValidationService`
and `FileStorageProperties` are gone. Everything below is a mechanical rename
except where marked **behaviour change**.

## 1. Inject `FileStorage`, delete the validation dance

Before:

```kotlin
@Service
class DocumentService(
    private val fileStorage: FileStorageService,
    private val fileValidation: FileValidationService,
) {
    fun store(file: MultipartFile): String {
        val safeName = fileValidation.sanitizeFileName(file.originalFilename ?: "upload")
        val head = file.inputStream.use { it.readNBytes(16) }
        fileValidation.validate(file.size, file.contentType ?: "application/octet-stream", head)
        return file.inputStream.use {
            fileStorage.upload("documents/$safeName", it, file.size, file.contentType ?: "application/octet-stream")
        }
    }
}
```

After:

```kotlin
@Service
class DocumentService(private val storage: FileStorage) {
    fun store(file: MultipartFile): StoredFile = storage.store(file, folder = "documents")
}
```

Sanitization and validation are now inside `store()` and cannot be skipped, so
delete the `FileValidationService` injection and every call to it. If you need
the sanitizer alone it is `FileNames.sanitize(name)`; the validator is
`FileValidator`, published as a bean and replaceable.

**Behaviour change.** Uploads that previously bypassed `validate()` — anywhere
you forgot the call — are now validated and may be rejected. Check that
`fileforge.validation.allowed-mime-types` covers everything you actually store
before deploying.

## 2. Method and type renames

| 1.0.x | 2.0.0 |
| --- | --- |
| `FileStorageService` | `FileStorage` |
| `upload(key, stream, length, type): String` | `store(FileUpload): StoredFile` |
| `download(key): InputStream` | `read(key) { … }` or `readBytes(key)` |
| `delete(key)` | `delete(key): Boolean` (true if it existed) |
| `exists(key)` | unchanged |
| `list(prefix): List<String>` | `list(prefix, limit, pageToken, delimiter): FilePage` |
| `archive(from, to)` | **removed** — mark the file inactive in your own database instead |
| `presignGetUrl(key, ttl): URL` | `downloadUrl(key, ttl, downloadAs): URI` |
| — | `metadata(key)`, `deleteAll(keys)` |
| `net.mimochodek.fileforge.FileStorageException` | `net.mimochodek.fileforge.exception.FileStorageException` |
| `sanitizeFileName(name)` | **removed** — the name is not stored, keep it in your own row |
| `InvalidFileNameException` | **removed** — nothing rejects a name any more |

The exception hierarchy moved to the `exception` subpackage; the class names are
unchanged, so a find-and-replace on the import is enough.

### `upload` returned the key; `store` returns everything

1.x returned a bare `String`, and every consumer re-derived the rest by hand.
`StoredFile` gives you `key`, `contentType`, `size`, `checksum` and `storedAt`.

Persist `key` **and the uploaded file name**: the library generates the key and
takes nothing from the name but its extension, so the name is yours to keep.
Content type and size are readable with `metadata(key)` if you would rather not
store them.

### `list` is paginated

1.x `list()` had no cap and materialized every key in the bucket. If you relied
on getting everything, loop:

```kotlin
var page = storage.list(prefix, limit = 1000)
while (true) {
    page.keys.forEach(::process)
    page = storage.list(prefix, limit = 1000, pageToken = page.nextPageToken ?: break)
}
```

### `download` no longer leaks

Reading is scoped: `read(key) { … }` hands you the open object and closes it
afterwards, including when your lambda throws. There is no unscoped variant, so
the pooled HTTP connection cannot be leaked. For small files, `readBytes(key)`.

## 3. Configuration keys moved

Validation is storage-agnostic policy, so it is no longer nested under a
provider prefix. There are no deprecated aliases; the old keys are simply not
read.

| 1.0.x | 2.0.0 |
| --- | --- |
| `fileforge.s3.max-file-size-bytes: 52428800` | `fileforge.validation.max-file-size: 50MB` |
| `fileforge.s3.allowed-mime-types` | `fileforge.validation.allowed-mime-types` |
| `fileforge.s3.endpoint` (required) | `fileforge.s3.endpoint` (**optional**) |
| `fileforge.s3.access-key` / `secret-key` (required) | optional — omit both for the AWS credential chain |
| — | `fileforge.provider` (drives activation) |
| — | `fileforge.s3.path-style-access` (was hardcoded `true`) |
| — | `fileforge.naming.*`, `fileforge.buffering.*`, `fileforge.web.*` |
| — | `fileforge.profiles.*` (named upload categories) |

Sizes are now Spring `DataSize`, so `50MB`, `2048KB` and a raw byte count all
work.

**Activation changed.** 1.x activated on the presence of `fileforge.s3.endpoint`.
2.0.0 activates on `fileforge.provider` (default `s3`), so the library comes up
as soon as the dependency is present and will fail at startup if
`fileforge.s3.bucket` is missing. To keep the dependency without activating it,
set `fileforge.provider` to anything else — for example `none`.

## 4. Keys keep the 1.x layout

`<folder>/<year>_<uuid>.<ext>` — the same shape 1.x produced, and now the only
one. Existing keys keep working unchanged; they live in your database and are
read back verbatim.

What did change: the uploaded file name no longer influences the key at all
(1.x kept the prefix of the `objectKey` argument you passed), and the extension
is checked to be a plausible one before it is used.

## 5. The AWS SDK left your compile classpath

1.x exposed `software.amazon.awssdk:s3` as an `api` dependency. It is now
`implementation`, so if your own code imports AWS SDK classes, declare the SDK
yourself:

```kotlin
dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.30.1"))
    implementation("software.amazon.awssdk:s3")
}
```

## 6. `S3Client` and `S3Presigner` are no longer beans

They were published in 1.x and conditional on missing beans, which cut both
ways: a consumer with their own `S3Client` silently had FileForge write to the
wrong place, and a consumer adding an unrelated S3 feature silently inherited
FileForge's client. They are now private to the backend.

If you were overriding them to change the wiring, you no longer need to — the
endpoint, credentials, region and path-style access are all configuration. If
you need to replace the storage implementation outright, publish a
`StorageBackend` bean instead; the rest of the pipeline is unaffected and no S3
configuration is required.

If you were *consuming* FileForge's `S3Client` for your own purposes, declare
your own bean — it will no longer conflict.

## 7. Optional: replace per-category folder logic with profiles

If you had `"documents/$name"` in one service and a hand-rolled 2 MB avatar
check in another, declare both once:

```yaml
fileforge:
  profiles:
    avatars:
      folder: avatars
      validation: { max-file-size: 2MB, allowed-mime-types: [image/jpeg, image/png] }
    documents:
      folder: documents
      validation: { max-file-size: 25MB, allowed-mime-types: [application/pdf] }
```

```kotlin
storage.store(file, profile = "documents")
```

Purely additive — if you pass `folder` instead, nothing changes.

## 8. Optional: delete your error-handling advice

If you wrote a `@RestControllerAdvice` mapping FileForge exceptions to HTTP
statuses, replace it with:

```yaml
fileforge:
  web:
    problem-details:
      enabled: true
```

It maps the whole hierarchy to RFC 7807 responses and attaches each exception's
fields as problem extensions. It is opt-in, so leaving it off keeps your own
handling in charge.

## Checklist

- [ ] `FileStorageService` -> `FileStorage`, `upload` -> `store`
- [ ] `archive(from, to)` -> flag the row in your database; there is no move
- [ ] Remove `FileValidationService` injections and manual `validate()` calls
- [ ] Keep a column for the uploaded file name — 2.0 does not store it
- [ ] Fix `net.mimochodek.fileforge.*Exception` imports to `...exception.*`
- [ ] Move `max-file-size-bytes` / `allowed-mime-types` under `fileforge.validation`
- [ ] Confirm the MIME whitelist covers everything you store (validation is now unconditional)
- [ ] Wrap `list()` in a pagination loop if you relied on getting every key
- [ ] Use `read(key) { }` or `readBytes(key)` instead of `download()`
- [ ] Declare the AWS SDK yourself if your code imports it
- [ ] Drop `S3Client` / `S3Presigner` overrides, or replace with a `StorageBackend` bean
- [ ] Optional: move per-category folders and limits into `fileforge.profiles`
