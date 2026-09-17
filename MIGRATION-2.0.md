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
| `download(key): InputStream` | `read(key): FileContent`, `read(key) { }`, `readBytes(key)` |
| `delete(key)` | `delete(key): Boolean` (true if it existed) |
| `exists(key)` | unchanged |
| `list(prefix): List<String>` | `list(prefix, limit, pageToken, delimiter): FilePage` |
| `archive(from, to)` | `move(from, to)` |
| `presignGetUrl(key, ttl): URL` | `downloadUrl(key, ttl, downloadAs): URI` |
| — | `metadata(key)`, `copy()`, `deleteAll(keys)`, `uploadUrl(key, type, ttl)` |
| `net.mimochodek.fileforge.FileStorageException` | `net.mimochodek.fileforge.exception.FileStorageException` |

The exception hierarchy moved to the `exception` subpackage; the class names are
unchanged, so a find-and-replace on the import is enough.

### `upload` returned the key; `store` returns everything

1.x returned a bare `String`, and every consumer re-derived the rest by hand.
`StoredFile` gives you `key`, `fileName`, `contentType`, `size`, `checksum` and
`storedAt`. Persist `key`; you can usually drop your own columns for the file
name, size and content type, because `metadata(key)` reads them back.

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

`read(key)` returns a `FileContent` that holds a pooled HTTP connection and must
be closed. Prefer `read(key) { ... }`, which closes it for you, or `readBytes`.

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

## 4. Keys keep their old layout only if you ask

The default is now `date-uuid` (`documents/2026/09/17/<uuid>.pdf`). 1.x produced
`documents/2026_<uuid>.pdf`. **Existing keys keep working** — they are stored in
your database and read back verbatim — but new uploads land in the new layout.

To keep generating the 1.x layout:

```yaml
fileforge:
  naming:
    strategy: year-uuid
```

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

- [ ] `FileStorageService` -> `FileStorage`, `upload` -> `store`, `archive` -> `move`
- [ ] Remove `FileValidationService` injections and manual `validate()` calls
- [ ] Fix `net.mimochodek.fileforge.*Exception` imports to `...exception.*`
- [ ] Move `max-file-size-bytes` / `allowed-mime-types` under `fileforge.validation`
- [ ] Confirm the MIME whitelist covers everything you store (validation is now unconditional)
- [ ] Wrap `list()` in a pagination loop if you relied on getting every key
- [ ] Use `read(key) { }` or `readBytes(key)` instead of an unclosed `download()`
- [ ] Set `naming.strategy: year-uuid` if you want the old key layout
- [ ] Declare the AWS SDK yourself if your code imports it
- [ ] Drop `S3Client` / `S3Presigner` overrides, or replace with a `StorageBackend` bean
- [ ] Optional: move per-category folders and limits into `fileforge.profiles`
