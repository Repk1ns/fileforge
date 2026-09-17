# FileForge — vývojářská dokumentace

Dokumentace **pro vývojáře a údržbáře samotné knihovny**, ne pro její konzumenty.

- Jste-li konzument (chcete knihovnu použít ve své aplikaci) → [README.md](README.md).
- Migrujete-li z `1.0.x` → [MIGRATION-2.0.md](MIGRATION-2.0.md).
- Zajímá-li vás, *proč* má knihovna tuto podobu → `ANALYSIS.md` (architektonický
  přehled `1.0.0` a návrh cílového stavu, podle kterého byla `2.0.0` postavena).

**Hledáte něco konkrétního?**

- Neznámý pojem (SPI, bean, presigned URL, magic bytes…) → [slovníček, sekce 20](#20-slovníček-pojmů)
- Co dělá konkrétní funkce → [referenční přehled, sekce 21](#21-referenční-přehled-funkcí--polopaticky)
- Jak archivovat místo mazání → [recept v sekci 18](#archivovat-místo-mazání)

Verze popisovaného stavu: **2.0.0**, Kotlin `2.1.10`, JVM 21, AWS SDK v2,
Spring Boot `3.5.6`. Jeden Gradle modul, 29 produkčních souborů, 173 testů.

---

## Obsah

1. [Architektura v kostce](#1-architektura-v-kostce)
2. [Invarianty — co nesmí přestat platit](#2-invarianty--co-nesmí-přestat-platit)
3. [Tok dat při `store()`](#3-tok-dat-při-store)
4. [Struktura repozitáře](#4-struktura-repozitáře)
5. [Veřejné API — kořenový balíček](#5-veřejné-api--kořenový-balíček)
6. [`model/` — datové typy](#6-model--datové-typy)
7. [`spi/` — rozšiřovací body](#7-spi--rozšiřovací-body)
8. [`validation/` — politika obsahu](#8-validation--politika-obsahu)
9. [`naming/` — generování klíčů](#9-naming--generování-klíčů)
10. [`internal/` — neveřejné vnitřnosti](#10-internal--neveřejné-vnitřnosti)
11. [`internal/s3/` — S3 backend](#11-internals3--s3-backend)
12. [`exception/` — hierarchie chyb](#12-exception--hierarchie-chyb)
13. [`autoconfigure/` — Spring wiring](#13-autoconfigure--spring-wiring)
14. [`web/` — volitelná Spring MVC integrace](#14-web--volitelná-spring-mvc-integrace)
15. [`resources/` — kontrakt starteru](#15-resources--kontrakt-starteru)
16. [Testy — co která třída chrání](#16-testy--co-která-třída-chrání)
17. [Build — `build.gradle.kts` po částech](#17-build--buildgradlekts-po-částech)
18. [Časté úkoly](#18-časté-úkoly)
19. [Pasti a známé nedodělky](#19-pasti-a-známé-nedodělky)
20. [Slovníček pojmů](#20-slovníček-pojmů)
21. [Referenční přehled funkcí — polopaticky](#21-referenční-přehled-funkcí--polopaticky)

---

## 1. Architektura v kostce

Celý návrh stojí na **jednom rozdělení**: to, co konzument volá, a to, co
implementuje backend, jsou **dva různé typy**.

```
          FileStorage                 <- jediný bean, který konzument injektuje
               |
        DefaultFileStorage            <- sanitizace -> načtení -> validace
               |                         -> generování klíče -> delegace
               |-- FileValidator         storage-agnostická politika, VŽDY aplikovaná
               |-- ObjectKeyGenerator    storage-agnostické pojmenování, vyměnitelné
               \-- StorageBackend        úzké SPI: pracuje jen s PŘESNÝMI klíči
                        |
                        \-- S3StorageBackend   (internal)
```

Proč to takhle: v `1.0.0` byl `FileStorageService` zároveň „co injektuji“
i „co implementuje backend“. Z toho plynulo všechno ostatní špatné —
generování klíčů bylo uvězněné v S3 třídě (druhý backend by ho musel
zkopírovat) a validace nemohla být povinná, protože mezi volajícím
a backendem nebyla žádná vrstva.

Dvě rozhraní místo jednoho tady **není over-engineering**. Je to přesně to, co
umožňuje, aby současně platilo:

- backend je volitelný konfigurací, bez zásahu do kódu konzumenta,
- validace je nepodmíněná a nedá se obejít.

`StorageBackend` je úmyslně **hloupý**: `put` / `get` / `head` / `exists` /
`delete` / `deleteAll` / `list` / `copy` / `presignGet` / `presignPut` nad
**přesnými** klíči. Žádná validace, žádné pojmenování, žádná politika. Přidání
filesystem nebo Azure backendu znamená implementovat těchto 10 metod —
referenční ukázkou je `InMemoryStorageBackend` v testech (124 řádků včetně
KDoc).

---

## 2. Invarianty — co nesmí přestat platit

Toto je nejdůležitější sekce dokumentu. Každý z těchto bodů řeší konkrétní
defekt verze `1.0.0` a každý je krytý testem. Pokud refaktorujete, ověřte, že
ani jeden nepadl.

| # | Invariant | Kde se vynucuje | Kdo to hlídá |
|---|---|---|---|
| I1 | **Neexistuje cesta, která zapíše nevalidovaný obsah.** Validace je uvnitř `store()`, ne vedle něj. | `DefaultFileStorage.store()` | `DefaultFileStorageTest.ValidationIsUnconditional` |
| I2 | **Velikost se měří, nevěří se jí.** Délka od volajícího je jen nápověda. | `resolveContent()` | `DefaultFileStorageTest.SizeIsMeasuredNotTrusted` |
| I3 | **Single-pass stream se čte právě jednou** a knihovna ho zavře. | `ContentSource.Stream` + `resolveContent()` | `…should consume a single-pass stream exactly once` |
| I4 | **Klíč se generuje, nikdy nepřebírá z uploadu.** Nepřátelské jméno neovlivní, kam objekt padne. | `ObjectKeyGenerator` + `FileNames.sanitize` | `…should sanitize a traversing file name before it reaches the backend` |
| I5 | **Žádný typ AWS SDK neuteče přes abstrakci.** Chytá se `SdkException`, ne jen `S3Exception`. | `S3StorageBackend.call()` | `S3StorageBackendTest.Putting` |
| I6 | **Knihovna nepublikuje genericky typované beany, které vlastní** (`S3Client`, `S3Presigner`). | `S3StorageAutoConfiguration` | `…should not publish S3Client or S3Presigner beans` |
| I7 | **`list()` je vždy stránkovaný** a nikdy nematerializuje celý bucket. | `StorageBackend.list` bez defaultů | `S3StorageBackendTest.Listing` |
| I8 | **SPI nemá defaultní argumenty** (žádné `DefaultImpls`, které by dědil každý backend). | `spi/StorageBackend.kt` | audit v `ANALYSIS.md` |
| I9 | **Konfigurační metadata nesmí odplout od properties tříd.** | ruční JSON + reflexní test | `ConfigurationMetadataTest` |
| I10 | **Selhání startu je čitelné** a jmenuje konkrétní konfigurační klíč. | `init { require(...) }` v properties | `…should fail fast with a readable message…` |

---

## 3. Tok dat při `store()`

Nejdůležitější cesta v celé knihovně. Pořadí kroků není libovolné.

```
store(FileUpload)
  │
  1. FileNames.sanitize(upload.fileName)
  │      └─ InvalidFileNameException, když nezbyde nic použitelného
  │
  2. rozřešení profilu (upload.profile)
  │      └─ UnknownStorageProfileException, když profil není nakonfigurovaný
  │      └─ určí effectiveMaxSize, effectiveValidator, folder
  │
  3. resolveContent(content, effectiveMaxSize, inMemoryThreshold, tempDir)
  │      ├─ Bytes     -> použije se na místě
  │      ├─ LocalFile -> použije se na místě (Files.size)
  │      └─ Stream    -> buffer v paměti, nad limitem spill na disk
  │      └─ FileSizeExceededException UŽ TADY (čtení se přeruší)
  │      => výsledek má PRAVDIVOU velikost + prvních 64 bajtů (head)
  │
  4. contentType = deklarovaný (normalizovaný) ?: detekce z head + přípony
  │
  5. validator.validate(FileCandidate(fileName, contentType, size, head))
  │      └─ FileSizeExceededException / UnsupportedFileTypeException
  │      └─ ContentMismatchException
  │
  6. key = keyGenerator.generate(ObjectKeyRequest(fileName, folder, contentType))
  │
  7. backend.put(BackendPutRequest(key, stream, size, contentType, fileName, metadata))
  │      └─ nad 5 GiB automaticky multipart upload
  │
  8. StoredFile(key, fileName, contentType, size, checksum, storedAt)
```

**Proč krok 3 předchází kroku 5:** validace potřebuje skutečnou velikost
a skutečné hlavičkové bajty. V `1.0.0` šla deklarovaná `contentLength` přímo do
`RequestBody.fromInputStream`, které přečte právě tolik bajtů — volající mohl
deklarovat `5` a limit velikosti tím kompletně obejít.

**Proč krok 3 hlídá limit sám:** kdyby ho hlídala jen validace v kroku 5,
museli bychom nejprve nabufferovat celý nepřátelský upload a teprve pak ho
odmítnout. `bufferStream()` proto čtení přeruší, jakmile limit přeteče.

**Proč se `fileName` posílá zvlášť do `put()`:** je to explicitní starost
backendu. S3 user metadata cestují v HTTP hlavičkách a neunesou neASCII, takže
backend jméno URL-enkóduje sám — obecná `metadata` mapa konzumenta zůstane
nedotčená.

---

## 4. Struktura repozitáře

```
fileforge/
├── build.gradle.kts                 149 ř. — jediný build skript
├── settings.gradle.kts                4 ř.
├── gradle.properties                  1 ř. — kotlin.code.style=official
├── README.md                        313 ř. — dokumentace pro KONZUMENTY
├── MIGRATION-2.0.md                 213 ř. — přechod z 1.0.x
├── DOKUMENTACE-VYVOJARE.md                — tento dokument
├── ANALYSIS.md                            — architektonický přehled + plán (netrackovaný)
├── assignment/library_prompt.md     169 ř. — původní zadání (historický kontext)
├── .github/workflows/publish.yml     36 ř. — publikace na Maven Central
├── src/main/kotlin/net/mimochodek/fileforge/
│   ├── FileStorage.kt               152 ř. — VEŘEJNÉ API
│   ├── DefaultFileStorage.kt        176 ř. — pipeline
│   ├── StorageProfile.kt             29 ř. — rozřešený profil
│   ├── model/                             — datové typy (6 souborů)
│   ├── spi/                               — rozšiřovací body (3 soubory)
│   ├── validation/                        — politika obsahu (5 souborů)
│   ├── naming/                            — generování klíčů (1 soubor)
│   ├── internal/                          — vnitřnosti (2 soubory)
│   │   └── s3/                            — S3 backend (2 soubory)
│   ├── exception/                         — hierarchie chyb (1 soubor)
│   ├── autoconfigure/                     — Spring wiring (5 souborů)
│   └── web/                               — volitelná MVC integrace (2 soubory)
├── src/main/resources/META-INF/
│   ├── spring-configuration-metadata.json 202 ř.
│   └── spring/…AutoConfiguration.imports    3 ř.
└── src/test/kotlin/net/mimochodek/fileforge/   — 16 souborů, 173 testů
```

### Konvence balíčků

| Balíček | Význam pro vývojáře |
|---|---|
| kořen | To, co konzument vidí jako první: `FileStorage`, jeho implementace, `StorageProfile`. |
| `model/` | Čistá data. Bez závislosti na Springu i na AWS. |
| `spi/` | **Stabilní kontrakt pro implementátory backendů.** Změna tady je breaking change pro každý cizí backend. Žádné defaultní argumenty. |
| `validation/`, `naming/` | Veřejné, vyměnitelné implementace politiky. Storage-agnostické. |
| `internal/` | Kotlinovské `internal`. Konzument to nevidí; v testech ano (Gradle asociuje test s main). |
| `autoconfigure/` | Jediné místo se závislostí na Spring Boot autoconfigure. |
| `web/` | Jediné místo se závislostí na `spring-web` — a ta je `compileOnly`. |

---

## 5. Veřejné API — kořenový balíček

### `FileStorage.kt` (152 ř.)

**Rozhraní, které konzument injektuje.** Jediný typ, který je opravdu nutné
znát, aby se knihovna dala použít.

14 metod ve čtyřech skupinách:

| Skupina | Metody |
|---|---|
| zápis | `store(FileUpload)`, `store(fileName, bytes, contentType, folder, profile)` |
| čtení | `read(key)`, `read(key) { }`, `readBytes(key)`, `metadata(key)` |
| správa | `exists`, `delete`, `deleteAll`, `copy`, `move`, `list` |
| odkazy | `downloadUrl(key, ttl, downloadAs)`, `uploadUrl(key, contentType, ttl)` |

Companion object drží `DEFAULT_TTL` (15 minut) a `DEFAULT_PAGE_SIZE` (1000).

**Pozor na rozdíl proti SPI:** tady **defaultní argumenty chceme** — jsou to
právě ony, co dělá API ergonomickým (`storage.downloadUrl(key)`).
`ANALYSIS.md` kritizuje defaulty na rozhraní kvůli generovanému
`DefaultImpls`, ale to platí pro **rozšiřovací bod**. `FileStorage` není
rozšiřovací bod — tím je `StorageBackend`, a ten defaulty nemá.

**Při editaci:** každá změna signatury tady je breaking change pro konzumenty.
Přidáváte-li metodu, přidejte ji i do `DefaultFileStorage` (implementuje
rozhraní, kompilátor si to vynutí) a zvažte, jestli potřebuje nový řádek v SPI.

### `DefaultFileStorage.kt` (176 ř.)

**Pipeline `sanitizace → načtení → validace → klíč → delegace`.** Jediná
implementace `FileStorage`, kterou knihovna dodává.

Konstruktor (8 parametrů, 4 s defaulty):

```kotlin
DefaultFileStorage(
    backend: StorageBackend,            // kam se zapisuje
    keyGenerator: ObjectKeyGenerator,   // jak se jmenují klíče
    validator: FileValidator,           // globální politika
    maxFileSize: Long,                  // globální limit v bajtech
    inMemoryThreshold: Long = 1 MiB,    // nad tím spill na disk
    tempDir: Path? = null,              // kam spillovat
    profiles: Map<String, StorageProfile> = emptyMap(),
    clock: Clock = Clock.systemUTC(),   // injektovaný kvůli testovatelnosti
)
```

Vše nad `backend` je společné pro **všechny** backendy — proto je nový backend
jen hrst triviálních metod a proto se validace nedá vynechat.

Zvláštní pozornost si zaslouží `move()`:

```kotlin
override fun move(from: String, to: String) {
    backend.copy(from, to)
    try { backend.delete(from) } catch (e: RuntimeException) {
        log.warn("Moved {} to {} but could not delete the source; both keys now exist", …)
        throw e
    }
    …
}
```

Operace **není atomická** a být nemůže — copy a delete jsou dva volání
backendu. Zdroj se maže až po úspěšném kopírování, takže objekt se nikdy
neztratí; ale když selže mazání, existují oba klíče, zatímco volající věří, že
existuje jen cíl. Proto `WARN` — v `1.0.0` tohle selhání proběhlo v úplném
tichu.

### `StorageProfile.kt` (29 ř.)

**Rozřešený profil** — runtime podoba jedné položky `fileforge.profiles.<jméno>`.

```kotlin
public class StorageProfile(
    public val folder: String = "",
    public val validator: FileValidator? = null,   // null => globální validátor
    public val maxFileSize: Long? = null,          // null => globální limit
)
```

`maxFileSize` je tu **zvlášť** od `validator` záměrně: nejen se jím validuje,
ale i **omezuje, kolik se ze streamu přečte** předtím, než se upload vzdá.
Kdyby byl schovaný jen ve validátoru, globální limit by pustil nabufferování
celé zásilky a teprve pak by ji menší limit profilu odmítl.

`validator == null` znamená „profil politiku nepřepisuje“ — a tím se zachová
i konzumentův vlastní `FileValidator` bean pro uploady, které profil jmenují.

---

## 6. `model/` — datové typy

Čistá data. **Žádná závislost na Springu ani na AWS SDK** — proto je model
použitelný i v backendu, který o S3 nic neví.

### `ContentSource.kt` (40 ř.)

`sealed interface` se třemi implementacemi — bajty, které chce volající uložit,
v té podobě, v jaké je zrovna má:

| Varianta | Kdy | Jak se zpracuje |
|---|---|---|
| `Bytes(value)` | data v paměti | použijí se na místě, nekopírují se |
| `Stream(stream, length?)` | socket, servlet body, HTTP | nabufferuje se; `length` je **jen nápověda** |
| `LocalFile(path)` | soubor na disku | použije se na místě, velikost z `Files.size` |

`companion object` má přetížené `of(...)` pro pohodlí z Javy (`@JvmOverloads`
na variantě se streamem).

**Kontrakt:** stream se konzumuje **jednou** a knihovna ho **zavře**.
`length` se nikdy nepoužije k vynucení limitu.

### `FileUpload.kt` (25 ř.)

`data class` — vše, co je potřeba k uložení jednoho souboru: `fileName`,
`content`, `contentType?`, `folder`, `metadata`, `profile?`.

`contentType = null` znamená „detekuj“. Prázdný `folder` znamená kořen bucketu
nebo folder profilu, je-li profil jmenovaný.

> `data class` s `ByteArray`/`InputStream` uvnitř → `equals` je fakticky
> identita. Pro `FileUpload` je rovnost bezvýznamná, `copy()` a pojmenované
> argumenty se ale vyplatí.

### `StoredFile.kt` (23 ř.)

**Výsledek zápisu** — `key`, `fileName`, `contentType`, `size`, `checksum?`,
`storedAt`.

Toto je odpověď na jeden z nejdražších defektů `1.0.0`: `upload()` vracel
prostý `String` a každý konzument si zbytek dopočítával a přeplumboval ručně,
v každém projektu. `size` je **skutečně zapsaný počet bajtů**, ne tvrzení
volajícího.

### `FileMetadata.kt` (18 ř.)

Metadata bez přenosu obsahu — to, co potřebuje HTTP odpověď na hlavičky.
Obsahuje `fileName?` (původní jméno, bylo-li při uploadu zaznamenáno), a mapu
`metadata` **už bez** rezervovaných položek knihovny.

### `FileContent.kt` (22 ř.)

`Closeable`: `metadata` + `stream`. Drží prostředek backendu (u S3 poolovanou
HTTP konexi) a **musí se zavřít**. Proto existuje `read(key) { }` — varianta,
která zavřít zapomenout nedovolí.

### `FilePage.kt` (22 ř.)

`keys`, `nextPageToken?`, `commonPrefixes`, + vypočítané `hasMore`.

`commonPrefixes` se plní jen při `list(..., delimiter = "/")` — jsou to
„podsložky“ dotazovaného prefixu.

---

## 7. `spi/` — rozšiřovací body

> **Nejcitlivější balíček v repozitáři.** Změna signatury tady rozbije každý
> cizí backend. Nepřidávejte sem defaultní argumenty (viz I8).

### `StorageBackend.kt` (104 ř.)

SPI, které implementuje backend. Kromě 10 metod obsahuje i dva pomocné typy:

```kotlin
public class BackendPutRequest(
    key, content, contentLength, contentType, fileName?, metadata,
)
public class BackendPutResult(size, checksum?)
```

`contentLength` je v tomto bodě **pravdivá** — vrstva nad tím ji už zjistila.

Povinnosti implementace, které kompilátor nevynutí:

1. Přeložit chyby backendu do hierarchie `exception/`. **Žádný vlastní typ
   nesmí uniknout přes rozhraní.**
2. Perzistovat `fileName` tak, aby ho `FileMetadata.fileName` přečetl
   nezměněné — včetně enkódování, pokud ho přenosová vrstva vyžaduje.
3. `delete` musí být idempotentní a vracet `true`, jen když objekt existoval.
4. `list` musí respektovat `limit` a vracet `nextPageToken`.

### `ObjectKeyGenerator.kt` (25 ř.)

`fun interface` + `ObjectKeyRequest(fileName, folder, contentType?)`.

Pojmenování je **storage-agnostická politika**, proto sídlí tady a ne v
backendu — každý backend dostane stejné rozvržení a konzument může politiku
vyměnit vlastním beanem. V `1.0.0` byla tato logika privátní metodou uvnitř
`S3FileStorageService`.

### `FileValidator.kt` (30 ř.)

`fun interface` + `FileCandidate(fileName, contentType, size, head)`.

`size` je skutečný počet bajtů, `head` jsou úvodní bajty pro kontrolu magic
bytes. Aplikuje se **nepodmíněně** při každém `store()`.

---

## 8. `validation/` — politika obsahu

### `FileNames.kt` (35 ř.)

`object` se dvěma funkcemi:

- `sanitize(fileName)` — odřízne cesty (`/` i `\`), whitespace na `_`, vyhodí
  vše kromě `[A-Za-z0-9._-]`, odstraní vedoucí tečky. Když nezbyde nic
  použitelného → `InvalidFileNameException`.
- `extensionOf(fileName)` — přípona malými písmeny bez tečky, nebo `null`.

### `MagicBytes.kt` (78 ř.)

Tabulka podpisů pro 9 typů: `image/jpeg`, `png`, `webp`, `gif`, `tiff`, `bmp`,
`application/pdf`, `zip`, `gzip`.

Klíčový detail modelu: `Signature` drží **seznam částí (`Part`), které musí
platit VŠECHNY** na svých offsetech.

```kotlin
"image/webp" to listOf(
    // "RIFF" <4 bajty délky> "WEBP" — obě části jsou povinné
    Signature(part("RIFF"), part(8, "WEBP")),
),
```

Přesně tohle byla bezpečnostní chyba `1.0.0`: kontroloval se jen `RIFF`, takže
**WAV soubor prošel jako `image/webp`**. Model s jednou částí na podpis takový
test neumožňoval vyjádřit — proto ta struktura.

API: `isKnown(contentType)`, `matches(contentType, head)`, `detect(head)`.

### `ContentTypeDetector.kt` (44 ř.)

- `normalize(contentType)` — zahodí parametry (`; charset=…`), otrimuje,
  převede na malá písmena. Používá se **všude**, kde se typy porovnávají.
- `detect(head, fileName)` — **magic bytes vyhrávají nad příponou**, protože
  přípona je pod kontrolou útočníka. Fallback: mapa 20 přípon, pak
  `application/octet-stream`.

### `DefaultFileValidator.kt` (70 ř.)

Vestavěná politika: limit velikosti, MIME whitelist, kontrola magic bytes.

Whitelist se normalizuje **jednou v konstruktoru** do `HashSet`. V `1.0.0` se
`properties.allowedMimeTypes.map { it.lowercase() }` alokoval nový seznam při
**každé** validaci.

Metody `validateSize` / `validateMimeType` / `validateMagicBytes` jsou veřejné
zvlášť, aby se daly testovat i použít jednotlivě.

### `UnknownTypePolicy.kt` (17 ř.)

`ALLOW` (default) / `REJECT` — co dělat s whitelistovaným typem, pro který
nemáme podpis, a nelze ho tedy ověřit proti obsahu. `1.0.0` takové typy tiše
přeskakoval; teď je to dokumentované a konfigurovatelné rozhodnutí.

---

## 9. `naming/` — generování klíčů

### `ObjectKeyGenerators.kt` (58 ř.)

Obsahuje enum `NamingStrategy` a `DefaultObjectKeyGenerator`.

| Strategie | Výsledek |
|---|---|
| `DATE_UUID` (default) | `documents/2026/09/17/<uuid>.pdf` |
| `UUID` | `documents/<uuid>.pdf` |
| `YEAR_UUID` | `documents/2026_<uuid>.pdf` — rozvržení `1.x`, kvůli kompatibilitě |

`Clock` **i** `ZoneId` jsou parametry konstruktoru. `1.0.0` používal
`Year.now()`, tedy systémovou zónu — nepřipnuté a netestovatelné. Test
`…should derive the date in the configured zone, not the system default`
ověřuje, že 23:30 UTC sedmnáctého je v Tokiu už osmnáctého.

`folder` se normalizuje `trim('/')`, takže `/documents/` i `documents` dají
stejný prefix.

---

## 10. `internal/` — neveřejné vnitřnosti

### `ResolvedContent.kt` (169 ř.)

**Srdce invariantu I2.** Převádí `ContentSource` na obsah, u kterého známe
pravdivou velikost a umíme ho otevřít znovu.

```kotlin
internal interface ResolvedContent : Closeable {
    val size: Long           // skutečný počet bajtů
    val head: ByteArray      // prvních 64 bajtů (HEAD_BYTES)
    fun openStream(): InputStream
}
```

Tři privátní implementace a jedna vstupní funkce:

```kotlin
internal fun resolveContent(
    source: ContentSource, maxSize: Long, inMemoryThreshold: Long, tempDir: Path?,
): ResolvedContent
```

| Vstup | Chování |
|---|---|
| `Bytes` | `ByteArrayContent` — bez kopírování, kontrola limitu |
| `LocalFile` | `FileBackedContent(ownsFile = false)` — bez kopírování |
| `Stream` | `bufferStream()` — paměť po `inMemoryThreshold`, pak spill |

`bufferStream()` má jeden nenápadný trik:

```kotlin
// O jeden bajt za hranicí: přečtení TOHO bajtu je přesně to, co říká,
// že se obsah do paměti nevejde a musí se spillovat.
val cap = (min(inMemoryThreshold, maxSize) + 1)…
```

Dále: při spillu se v cyklu kontroluje `written <= maxSize`, takže se
nepřátelský upload nedočte do konce; při **jakémkoli** selhání se temp soubor
smaže; `FileBackedContent` s `ownsFile = true` maže svůj spill v `close()`.

> **Návratnost prostředků:** `resolveContent(...)` se **vždy** volá v `use { }`.
> Když to změníte, spill soubory zůstanou na disku.

**Vedlejší efekt, který stojí za zmínku:** tato vrstva zároveň zdarma vyřešila
streamy neznámé délky, které `1.0.0` neumělo vůbec.

---

## 11. `internal/s3/` — S3 backend

### `S3StorageBackend.kt` (451 ř.)

Největší soubor v repozitáři. `internal`, takže konzument ho nevidí — vynucení
abstrakce je Kotlinovské, ne jen konvenční.

```kotlin
internal class S3StorageBackend(
    s3: S3Client,
    presigner: S3Presigner,
    bucket: String,
    multipartThreshold: Long = MAX_SINGLE_PART_PUT,   // 5 GiB
    uploadPartSize: Long = UPLOAD_PART_SIZE,          // 512 MiB
) : StorageBackend, AutoCloseable
```

Poslední dva parametry jsou injektovatelné **právě proto, aby byla multipart
cesta testovatelná bez 5 GiB fixture** — testy ji projedou se 4bajtovými
částmi.

#### Konstanty a proč mají ty hodnoty

| Konstanta | Hodnota | Důvod |
|---|---|---|
| `FILE_NAME_METADATA` | `fileforge-filename` | S3 user metadata jde v HTTP hlavičkách → jméno se URL-enkóduje |
| `MAX_KEYS_PER_DELETE` | 1000 | tvrdý limit `DeleteObjects` |
| `MAX_SINGLE_PART_PUT` | 5 GiB | S3 odmítne jednorázový `PutObject` nad tím |
| `MAX_SINGLE_PART_COPY` | 5 GiB | totéž pro `CopyObject` |
| `UPLOAD_PART_SIZE` | 512 MiB | bezpečně nad 5 MiB minimem; 512 MiB × 10 000 částí pokryje cokoli |
| `COPY_PART_SIZE` | 1 GiB | totéž pro multipart copy |

#### Centrální překlad chyb

```kotlin
private inline fun <T> call(action: String, notFoundKey: String? = null, block: () -> T): T
```

Každé volání SDK jde přes tuto funkci. Chytá `NoSuchKeyException`, pak
**`SdkException`** (ne `S3Exception`) — takže „connection refused“, selhání DNS
a timeouty socketu se přeloží taky. `1.0.0` je propouštěl jako surové AWS typy
přímo skrz abstrakci.

`mapped()` dělá ještě jedno rozlišení: `SdkClientException` (chyba bez HTTP
odpovědi) → `StorageUnavailableException`, protože to je „backend je
nedosažitelný“, je to retryovatelné a mapuje se na jiný HTTP status.

#### Netriviální operace

| Metoda | Co je na ní zvláštního |
|---|---|
| `put` | nad `multipartThreshold` přepne na `multipartPut` |
| `multipartPut` | `BoundedInputStream` na každou část; při selhání **abort**, jinak by osiřelé části dál stály za skladování |
| `delete` | S3 neřekne, zda objekt existoval → nejdřív `exists()`, pak `delete`. Dvě volání, dokumentovaná cena za `Boolean` |
| `deleteAll` | chunkuje po 1000; kontroluje `response.errors()` a vyhodí s prvním selháním |
| `list` | **jeden** `listObjectsV2`, ne paginátor: `.contents()` na paginátoru je eager |
| `copy` | nad 5 GiB → `multipartCopy` s `UploadPartCopy` a rozsahy `bytes=…` |
| `presignGet` | `downloadAs` → `responseContentDisposition` |
| `close` | zavírá `s3` i `presigner` — backend je vlastní |

`contentDisposition()` skládá RFC 6266/5987 hlavičku: ASCII `filename=` pro
starší klienty **a** `filename*=UTF-8''…` s přesným jménem.

### `BoundedInputStream.kt` (38 ř.)

Pohled na následujících `limit` bajtů delegáta. `close()` **záměrně nedělá
nic** — podkladový stream je ještě potřeba pro další části multipart uploadu.
Kdyby zavíral, druhá část by selhala.

---

## 12. `exception/` — hierarchie chyb

### `FileForgeExceptions.kt` (111 ř.)

Základ `FileStorageException(message, cause)` a 7 podtypů.

Design pravidlo: **podtypy vystavují fakta jako properties**, ne v textu
zprávy. V `1.0.0` bylo `actualSize`/`maxSize` zahrabané ve stringu, takže si
volající nemohl postavit rozumnou chybovou odpověď bez parsování textu.

| Typ | Properties |
|---|---|
| `FileNotFoundStorageException` | `key` |
| `FileSizeExceededException` | `actualSize: Long?`, `maxSize: Long` |
| `UnsupportedFileTypeException` | `contentType`, `allowedTypes: Set<String>` |
| `ContentMismatchException` | `declaredContentType`, `detectedContentType?` |
| `InvalidFileNameException` | `fileName` |
| `StorageUnavailableException` | — (signalizuje retryovatelnost) |
| `UnknownStorageProfileException` | `profile`, `knownProfiles` |

`actualSize` je **nullable** úmyslně: když se upload přeruší v okamžiku
překročení limitu, skutečnou velikost neznáme a předstírat ji by bylo lhaní.
Zpráva se podle toho mění.

---

## 13. `autoconfigure/` — Spring wiring

Jediný balíček se závislostí na Spring Boot autoconfigure (a ta je
`compileOnly`).

### Tři auto-konfigurace a jejich pořadí

`META-INF/spring/…AutoConfiguration.imports` má **tři** položky v tomto pořadí:

```
net.mimochodek.fileforge.autoconfigure.S3StorageAutoConfiguration
net.mimochodek.fileforge.autoconfigure.FileForgeAutoConfiguration
net.mimochodek.fileforge.autoconfigure.FileForgeWebAutoConfiguration
```

### `S3StorageAutoConfiguration.kt` (98 ř.)

```kotlin
@AutoConfiguration
@ConditionalOnClass(S3Client::class)
@ConditionalOnProperty(prefix = "fileforge", name = ["provider"],
                       havingValue = "s3", matchIfMissing = true)
public class S3StorageAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(StorageBackend::class)
    @EnableConfigurationProperties(S3Properties::class)
    public class S3BackendConfiguration { … }
}
```

**Vnořená konfigurace není kosmetika.** `@EnableConfigurationProperties` váže
a validuje properties *eagerně*. Kdyby byla na vnější třídě, konzument, který
publikuje vlastní `StorageBackend`, by byl přesto nucen nakonfigurovat
`fileforge.s3.bucket`, který nikdy nepoužije. Tohle odhalil až
`ApplicationContextRunner` test.

`S3Client` a `S3Presigner` se **nepublikují jako beany** (I6). Vznikají uvnitř,
vlastní je backend a zavírají se s ním (`@Bean(destroyMethod = "close")`).

Detail, na který se dá naletět podruhé: path-style access se nastavuje
**přesně na jednom místě**. V `1.0.0` byl nastavený zároveň na builderu
(`forcePathStyle`) i na `S3Configuration` a AWS SDK takový klient odmítne
zkonstruovat — bean `s3Client` tak nešel vytvořit **nikdy** a od `1.0.0` si to
nikdo nevšiml, protože neexistoval autoconfig test.

### `FileForgeAutoConfiguration.kt` (87 ř.)

```kotlin
@AutoConfiguration(after = [S3StorageAutoConfiguration::class])
@EnableConfigurationProperties(FileForgeProperties::class)
```

Backend-agnostické wiring. Tři beany, každý s `@ConditionalOnMissingBean`:

| Bean | Typ | Poznámka |
|---|---|---|
| `fileForgeObjectKeyGenerator` | `ObjectKeyGenerator` | `Clock.system(zone)` z konfigurace |
| `fileForgeValidator` | `FileValidator` | z `fileforge.validation` |
| `fileStorage` | `FileStorage` | navíc `@ConditionalOnBean(StorageBackend::class)` |

`after = [...]` je nutné, aby `@ConditionalOnBean(StorageBackend)` backend
vůbec viděl.

Privátní `toStorageProfiles()` převádí properties na `Map<String, StorageProfile>`
a obsahuje pravidlo: **profil, který nepřepisuje nic, si nechá injektovaný
validátor** (tedy i konzumentův vlastní) a přispěje jen folderem.

### `FileForgeProperties.kt` (164 ř.)

Osm tříd v jednom souboru, prefix `fileforge`:

```
FileForgeProperties         provider, naming, validation, buffering, web, profiles
├── NamingProperties        strategy, keepExtension, zone
├── ValidationProperties    maxFileSize, allowedMimeTypes, verifyMagicBytes, unknownTypePolicy
├── BufferingProperties     inMemoryThreshold, tempDir
├── WebProperties           problemDetails
│   └── ProblemDetailsProperties   enabled
└── ProfileProperties       folder, validation
    └── ProfileValidationProperties   (vše nullable) + isEmpty() + mergedWith()
```

Validace a pojmenování **nejsou** pod prefixem providera. Je to
storage-agnostická politika — budoucí filesystem backend nesmí číst
`fileforge.s3.max-file-size-bytes`.

`ProfileValidationProperties` má **všechna pole nullable** a to je podstatné.
Nevnořená, nenullable `ValidationProperties` by způsobila past: nastavit
v profilu jen `max-file-size` by tiše resetovalo ostatní pole na **defaulty
knihovny**, ne na nakonfigurované globální hodnoty. `mergedWith(global)` skládá
přepisy pole po poli.

Velikosti jsou Spring `DataSize` (`50MB`, `512KB`, i prostý počet bajtů).

### `S3Properties.kt` (45 ř.)

Prefix `fileforge.s3`. Jen skutečně S3-specifické věci. Povinný je **pouze**
`bucket`.

| Property | Změna proti `1.0.0` |
|---|---|
| `endpoint: URI?` | **volitelný** — býval aktivační spouštěč, takže pro AWS se musel vymýšlet falešný endpoint |
| `accessKey` / `secretKey` | **volitelné** — bez nich se použije AWS default credential chain (IAM role, instance profily, SSO) |
| `pathStyleAccess` | **konfigurovatelný** — býval zadrátovaný na `true` |

`init` blok vynucuje tři pravidla s čitelnými zprávami (I10):
neprázdný bucket, neprázdný region, a klíče nastavené **oba, nebo ani jeden**.

> **Proč `init { require(...) }` a ne `@Validated` / JSR-380:** JSR-380
> potřebuje hibernate-validator na runtime classpath konzumenta, a **bez něj se
> `@Validated` tiše ignoruje** — chybějící bucket by pak stejně skončil surovým
> `BindException`. `init` funguje vždy a nevnucuje konzumentovi závislost.

### `FileForgeWebAutoConfiguration.kt` (28 ř.)

```kotlin
@ConditionalOnClass(ProblemDetail::class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "fileforge.web.problem-details",
                       name = ["enabled"], havingValue = "true")
```

Tři podmínky, defaultně **vypnuto**. Knihovna nesmí konzumentovi převzít
zpracování chyb bez vyžádání.

---

## 14. `web/` — volitelná Spring MVC integrace

`spring-web` je `compileOnly`. Důsledky, které je potřeba udržet:

- na runtime classpath konzumenta se nic nepřidá,
- knihovna zůstane použitelná i mimo webovou aplikaci,
- v testech je `spring-web`, `spring-webmvc` a `jakarta.servlet-api` jen
  `testImplementation`.

### `MultipartFileExtensions.kt` (44 ř.)

Jedna extension funkce:

```kotlin
@JvmOverloads
public fun FileStorage.store(
    file: MultipartFile, folder: String = "",
    profile: String? = null, metadata: Map<String, String> = emptyMap(),
): StoredFile
```

Tohle je adaptér, který si psala každá konzumující aplikace sama. Pět řádků
ceremoniálu z README `1.0.0` je teď jeden řádek.

Dva detaily uvnitř: `file.contentType` se předá jako **deklarovaný** typ (tedy
se ověří proti magic bytes, ne uvěří), a `file.size` jde do
`ContentSource.Stream` jen jako nápověda.

### `FileForgeProblemDetailsHandler.kt` (99 ř.)

`@RestControllerAdvice` mapující hierarchii na RFC 7807. Fakta z exceptions se
připojují jako problem extensions (`maxSize`, `allowedTypes`, `key`, …).

| Exception | Status |
|---|---|
| `FileNotFoundStorageException` | 404 |
| `FileSizeExceededException` | 413 |
| `UnsupportedFileTypeException` | 415 |
| `ContentMismatchException` | 415 |
| `InvalidFileNameException` | 400 |
| `StorageUnavailableException` | 503 |
| `FileStorageException` (ostatní) | 500 |

Poslední dvě větve **logují příčinu a reportují genericky** — jejich zprávy
obsahují jména bucketů a interních hostů, která nemají co dělat u klienta.
Test `…without leaking internals` to hlídá.

---

## 15. `resources/` — kontrakt starteru

### `spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (3 ř.)

Seznam auto-konfigurací. **Nejkřehčí soubor v repozitáři** — přejmenování
třídy bez úpravy tohoto souboru knihovnu tiše vypne u všech konzumentů.
Proto `…should register every auto-configuration in the imports file` čte tento
resource z classpath a porovnává s `::class.java.name`.

### `spring-configuration-metadata.json` (202 ř.)

Metadata pro IDE completion: 7 groups, 18 properties, 2 hints.

**Udržuje se ručně** — a to je rozhodnutí podložené důkazy, ne lenost:

1. `1.0.0` deklarovalo `annotationProcessor(spring-boot-configuration-processor)`,
   což Kotlin **nikdy nespustí**.
2. kapt vyzkoušen: procesor se spustí, ale pro konstruktorově vázané Kotlin
   třídy vyprodukuje **group entries a nula properties**.
3. Podporovaný `additional-spring-configuration-metadata.json` se **nesloučí**,
   protože kapt nedává resources na classpath procesoru.

Ponechat plugin, který nedodá to, co slibuje, by zopakovalo chybu `1.0.0`.
Místo toho ruční JSON + `ConfigurationMetadataTest`, který očekávané klíče
**derivuje reflexí z properties tříd** a vyžaduje přesnou shodu.

> **Přidáváte property? Musíte upravit i tento JSON**, jinak build padne. To je
> účel.

---

## 16. Testy — co která třída chrání

173 testů, 0 skipped. Integrační testy vyžadují Docker.

| Soubor | Ř. | Co chrání |
|---|---|---|
| `DefaultFileStorageTest` | 404 | Pipeline. Nested klasy `Storing`, `ValidationIsUnconditional`, `SizeIsMeasuredNotTrusted`, `Reading`, `Managing`, `PresignedUrls` — mapují se přímo na invarianty I1–I4. |
| `internal/s3/S3StorageBackendTest` | 551 | Tvar requestů, překlad chyb, batching, stránkování, multipart upload i abort, Content-Disposition (včetně neASCII). MockK. |
| `FileForgeIntegrationTest` | 357 | 16 testů proti **skutečnému** S3 endpointu (versitygw). Round-trip, presigned GET i PUT, delimiter, **reálný multipart s 5 MiB částmi**. |
| `autoconfigure/FileForgeAutoConfigurationTest` | 276 | Kontrakt starteru: aktivace, binding, defaulty, fail-fast, back-off, imports soubor. **Nejcennější test v repozitáři** — jeho absence v `1.0.0` skryla nekonstruovatelný bean. |
| `autoconfigure/ConfigurationMetadataTest` | 259 | Drift metadat (I9). Obsahuje vlastní mini JSON parser, aby knihovna nemusela mít JSON závislost ani v testech. |
| `StorageProfileTest` | 206 | Profily na úrovni pipeline: folder, přepis validátoru, limit při čtení, neznámý profil. |
| `autoconfigure/ProfilePropertiesTest` | 150 | Profily end-to-end: z konfigurace až po vynucení, včetně dědění nenastavených polí. |
| `validation/DefaultFileValidatorTest` | 150 | Limit, whitelist, magic bytes — včetně regresního testu „WAV nesmí projít jako webp“. |
| `testsupport/InMemoryStorageBackend` | 124 | **Není to mock, je to druhý úplný backend.** Existuje, aby držel SPI u slibu: kdyby kdy potřeboval vlastní validaci nebo pojmenování, je šev na špatném místě. |
| `web/MultipartFileExtensionsTest` | 95 | Že extension je bezpečná konstrukcí (deklarovaný typ se ověřuje, `size` se neuvěří). |
| `web/FileForgeProblemDetailsHandlerTest` | 92 | Mapování statusů, problem extensions, neunikání interních detailů. |
| `naming/DefaultObjectKeyGeneratorTest` | 88 | Tři strategie, unikátnost, a že se datum bere z **konfigurované** zóny. |
| `autoconfigure/FileForgeWebAutoConfigurationTest` | 62 | Že je advice defaultně vypnutý a neaktivuje se mimo web. |
| `validation/FileNamesTest` | 55 | Path traversal, Windows cesty, degenerovaná jména. |
| `validation/ContentTypeDetectorTest` | 36 | Že magic bytes vyhrávají nad lživou příponou. |
| `testsupport/Fixtures` | 12 | Bajtové fixtures se **skutečně správnými** magic bytes, plus `WAV` jako negativní případ. |

### Testovací infrastruktura — dvě věci, na které pozor

**Docker API.** `build.gradle.kts` pinuje `systemProperty("api.version", "1.43")`.
docker-java si defaultně vyjednává v1.32, což Docker Engine 29+ odmítá
(minimum v1.40) — Testcontainers pak reportuje „no Docker“ a **celá integrační
sada se tiše přeskočí**. Přebít lze `-PdockerApiVersion=1.47`.

**Image je pinnutý** na `versity/versitygw:v1.8.0` a readiness se testuje
**HTTP sondou**, ne otevřeným portem — otevřený port neznamená, že S3 API už
obsluhuje.

**Každý integrační test je samostatný** ve vlastním folderu s UUID. Žádné
`@Order`, žádný sdílený `lateinit` stav — selhání jednoho testu nekaskáduje do
zbytku a každý se dá spustit izolovaně.

---

## 17. Build — `build.gradle.kts` po částech

| Sekce | Co je důležité |
|---|---|
| `plugins` | `kotlin.jvm`, `kotlin.plugin.spring`, `dokka 2.0.0`, `nmcp.aggregation`, `java-library`, `maven-publish`, `signing`. **Žádný kapt** — viz sekce 15. |
| `dependencies` | AWS SDK je **`implementation`**, ne `api` → v POM skončí jako `runtime` a nestojí konzumentovi na compile classpath. `slf4j-api` deklarovaná. `spring-boot-autoconfigure` a `spring-web` `compileOnly`. |
| `kotlin { explicitApi() }` | Vynucuje explicitní viditelnost a návratové typy na celé veřejné ploše. Ověřeno: odebrání `public` build shodí. |
| `java` | JVM toolchain 21, `withSourcesJar()`, `withJavadocJar()`. |
| `tasks.named<Jar>("javadocJar")` | `from(tasks.named("dokkaJavadoc"))`. Bez tohoto `withJavadocJar()` zabalí výstup tasku `javadoc`, který tady nemá Java sources → `1.0.0` publikovalo **prázdný javadoc jar**, přestože Dokku aplikovalo. |
| `tasks.test` | JUnit Platform + pin Docker API verze. |
| `publishing` | `artifactId = "fileforge"` + POM metadata (licence, developers, scm). |
| `signing` | In-memory PGP klíče z Gradle properties; podpis je povinný, jen když je klíč k dispozici (lokální build tak nevyžaduje GPG). |
| `nmcpAggregation` | Maven Central Portal, `publishingType = "AUTOMATIC"`. |

### Publikace

`.github/workflows/publish.yml` se spouští na **`release: published`**, ne na
push tagu. Verze artefaktu pochází z `build.gradle.kts`, ne z tagu — musí se
držet v synchronizaci. Každá verze se dá publikovat jen jednou.

---

## 18. Časté úkoly

### Archivovat místo mazání

Častý požadavek: fotky se nemají mazat, ale „odložit do archivu“. Jsou na to dvě
cesty a liší se podstatně.

#### A) Logický archiv — doporučená výchozí volba

Objekt zůstane, kde je, a archivaci si poznamenáte ve své databázi:

```kotlin
@Entity
class Photo(
    val storageKey: String,
    var archivedAt: Instant? = null,   // null = aktivní
)

fun archive(photo: Photo) { photo.archivedAt = Instant.now() }   // to je celé
fun restore(photo: Photo) { photo.archivedAt = null }
```

Proč začít tímhle:

- **Nic se nekopíruje.** Přesun na S3 znamená fyzické zkopírování celého objektu
  a jeho následné smazání — u 10 000 fotek to je 10 000 kopií a 10 000 mazání.
- **Je to atomické.** Jedna změna v databázové transakci. Nemůže se stát, že
  polovina operace projde.
- **Klíč se nemění**, takže nemůže vzniknout rozpor mezi databází a bucketem.
- **Vrácení z archivu je okamžité.**

#### B) Fyzický přesun — když na prefixu něco závisí

Skutečně přesouvat má smysl jen tehdy, když na prefixu `archive/` něco jedná:

- **S3 lifecycle pravidlo** na prefix (např. „po 30 dnech přesuň do Glacieru“) —
  to je nejčastější legitimní důvod,
- odlišná přístupová práva na prefix,
- jiný proces, který si obsah `archive/` čte nebo zálohuje.

```kotlin
@Service
class PhotoService(private val storage: FileStorage, private val repo: PhotoRepository) {

    fun archive(photo: Photo) {
        val archivedKey = "archive/${photo.storageKey}"

        storage.move(photo.storageKey, archivedKey)

        // Nový klíč MUSÍ skončit v databázi, jinak fotku už nikdy nenajdete.
        photo.storageKey = archivedKey
        repo.save(photo)
    }

    fun restore(photo: Photo) {
        val activeKey = photo.storageKey.removePrefix("archive/")
        storage.move(photo.storageKey, activeKey)
        photo.storageKey = activeKey
        repo.save(photo)
    }
}
```

Na co si dát pozor:

**Klíč už prefix obsahuje.** `store(file, folder = "photos")` vyrobí klíč
`photos/2026/09/17/<uuid>.jpg`. Z `"archive/$key"` tedy vyjde
`archive/photos/2026/09/17/<uuid>.jpg`. To je v pořádku a je to žádoucí —
`removePrefix("archive/")` vrátí přesně původní klíč. Nesnažte se prefix
v klíči nahrazovat, jen ho předřazujte.

**`move()` není atomický.** Je to `copy` a pak `delete` — dvě volání S3. Zdroj
se maže až po úspěšném zkopírování, takže se fotka nikdy neztratí, ale když
selže mazání, existují oba klíče. Knihovna to zaloguje jako `WARN`
(*„Moved … but could not delete the source; both keys now exist“*) a výjimku
propustí dál. Uklidit původní klíč pak musíte vy.

**Pořadí operací vůči databázi.** Nejbezpečnější je `move()` → až potom uložit
nový klíč. Když spadne `move()`, databáze ukazuje na starý klíč, který stále
existuje — jste v konzistentním stavu. Kdybyste uložili klíč první a `move()`
pak spadl, databáze ukazuje na neexistující objekt.

**Metadata přesun přežijí.** Původní název souboru i content type se při
`copy()`/`move()` přenesou — S3 `CopyObject` kopíruje user metadata
automaticky. Je to ověřeno integračními testy `should carry metadata across a
copy` a `should move an object`, takže na tom můžete stavět: po archivaci
`storage.metadata(archivedKey).fileName` dál vrací `Quarterly_Report.pdf`.

**Jen v rámci jednoho bucketu.** `copy()` i `move()` mají zdrojový i cílový
bucket nastavený na `fileforge.s3.bucket`. Archivace do *jiného* bucketu
podporovaná není; potřebovali byste rozšířit `StorageBackend` o cílový bucket.

**Hromadný přesun neexistuje.** Není `moveAll()`. Pro dávku je nejúspornější
zkopírovat po jednom a mazat dávkově, protože mazání umí S3 po 1000:

```kotlin
val pairs = photos.map { it.storageKey to "archive/${it.storageKey}" }
pairs.forEach { (from, to) -> storage.copy(from, to) }
storage.deleteAll(pairs.map { it.first })      // jedno volání na 1000 klíčů
```

**Profily tu nepomohou.** `fileforge.profiles` se uplatní jen při `store()` —
určují, kam se soubor ukládá poprvé a jaká politika na něj platí. Na přesun
existujícího objektu nemají vliv.

#### Prohlížení archivu

```kotlin
var page = storage.list("archive/photos/", limit = 500)
while (true) {
    page.keys.forEach(::process)
    page = storage.list("archive/photos/", limit = 500, pageToken = page.nextPageToken ?: break)
}
```

> Poznámka k historii: `1.0.0` mělo metodu `archive(sourceKey, targetKey)`, jejíž
> vlastní KDoc přiznával „Archive = generic move“. Byla to obecná operace
> přesunu se zavádějícím jménem, proto se v `2.0.0` jmenuje `move()`.
> Archivace je *použití* té operace, ne její název.

### Přidat nový storage backend

1. Implementujte `StorageBackend` (10 metod). Vzor: `InMemoryStorageBackend`.
2. Přeložte chyby do `exception/`. Nic vlastního nesmí uniknout.
3. Perzistujte `BackendPutRequest.fileName` tak, aby ho `FileMetadata.fileName`
   přečetl nezměněné.
4. Novou auto-konfiguraci aktivujte přes `@ConditionalOnProperty(… havingValue = "<jméno>")`
   a properties svažte ve **vnořené** `@Configuration` s
   `@ConditionalOnMissingBean(StorageBackend::class)`.
5. Zapište třídu do `AutoConfiguration.imports` **před** `FileForgeAutoConfiguration`.
6. Rozšiřte `ConfigurationMetadataTest`-krytá metadata o nové properties.

Kód konzumenta se nemění ani o znak. To je celý smysl toho švu.

### Přidat podporovaný MIME typ

1. Podpis do `MagicBytes.SIGNATURES`. **Potřebuje-li typ víc než jednu
   kontrolu, uveďte všechny části** — přesně tady vznikla webp/WAV chyba.
2. Příponu do `ContentTypeDetector.BY_EXTENSION`.
3. Test do `DefaultFileValidatorTest` — pozitivní **i negativní** případ
   (podobný formát, který projít nesmí).
4. Fixture do `Fixtures`, má-li smysl.

### Přidat konfigurační property

1. Parametr do příslušné `*Properties` třídy (s defaultem).
2. **Záznam do `spring-configuration-metadata.json`** s `description`
   a `sourceType` — bez toho `ConfigurationMetadataTest` padne.
3. Binding test do `FileForgeAutoConfigurationTest`.
4. Řádek do referenční tabulky v `README.md`.

### Přidat metodu do veřejného API

1. `FileStorage` (s defaulty, chcete-li ergonomii).
2. `DefaultFileStorage` (kompilátor vynutí).
3. Potřebuje-li novou operaci backendu → `StorageBackend` (**bez defaultů**),
   `S3StorageBackend` **a** `InMemoryStorageBackend`.
4. Testy na obou úrovních + integrační, jde-li o chování S3.
5. `README.md`; při breaking změně i `MIGRATION-*.md`.

### Vydat verzi

1. `version` v `build.gradle.kts`.
2. Merge do `master`.
3. Publikovat GitHub Release na odpovídajícím `vX.Y.Z` tagu.

---

## 19. Pasti a známé nedodělky

### Pasti

**Path-style access nastavujte na jednom místě.** Builder `forcePathStyle`
**nebo** `S3Configuration.pathStyleAccessEnabled`, ne obojí — AWS SDK takový
klient odmítne zkonstruovat. Tohle už jednou nefungovalo celou verzi.

**`resolveContent()` vždy v `use { }`.** Jinak zůstávají spill soubory na disku.

**`BoundedInputStream.close()` musí zůstat no-op.** Multipart upload potřebuje
podkladový stream pro další části.

**Přejmenování auto-konfigurace znamená úpravu `AutoConfiguration.imports`.**
Bez toho se knihovna u konzumentů tiše vypne.

**Defaulty v SPI nepřidávat** (I8). V `FileStorage` naopak ano.

**`fileforge.provider` má default `s3`.** Přidání závislosti bez konfigurace
tedy **shodí start** s `fileforge.s3.bucket must be configured`. Je to
zamýšleno (fail-fast), je to dokumentované v README i migračním průvodci,
a vypíná se nastavením `fileforge.provider` na cokoli jiného.

**Profilová validace se dědí pole po poli.** Nepřevádějte
`ProfileValidationProperties` na nenullable typ — tiše by to resetovalo
nepřepsaná pole na defaulty knihovny místo na globální konfiguraci.

### Známé nedodělky

- **`MagicBytes.REQUIRED_HEAD_BYTES = 16` je deklarovaná, ale nikde
  nepoužitá.** Skutečný počet bufferovaných hlavičkových bajtů je
  `HEAD_BYTES = 64` v `ResolvedContent.kt`. Je to mrtvá veřejná konstanta
  (a pod `explicitApi()` tedy součást publikované plochy) a navíc zavádějící.
  Buď ji odstranit, nebo z ní udělat jediný zdroj pravdy pro `HEAD_BYTES`.
- **`delete()` u S3 stojí dvě volání** (`exists` + `delete`), protože S3
  neřekne, zda objekt existoval. Dokumentovaná cena za návratový `Boolean`.
  Kdyby to někdy tlačilo, nabízí se varianta `deleteQuietly()` bez návratové
  hodnoty.
- **`move()` není atomický** a být nemůže. Pokrytí je `WARN` + dokumentace.
- **Dokka je stále na V1 pluginu** (build hlásí deprecation warning). Migrace
  na V2 je neurgentní úklid.
- **Multipart *copy* nemá integrační test** — na rozdíl od multipart *uploadu*
  by vyžadoval >5 GiB objekt, protože `COPY_PART_SIZE` nelze injektovat.
  Pokrytý je jen jednotkově.

---

## 20. Slovníček pojmů

Abecedně. Pojmy, které se v kódu a v této dokumentaci opakují.

**API (Application Programming Interface)** — rozhraní, které **voláte**.
Knihovna ho implementuje, vy jste klient. V FileForge je to `FileStorage`.

**Atomická operace** — buď se provede celá, nebo vůbec. `move()` atomický
**není**: skládá se z kopie a mazání, takže mezi nimi existuje okamžik, kdy
platí jen polovina.

**Auto-konfigurace** — třída, kterou Spring Boot najde a spustí sám, bez toho
aby ji kdokoli importoval. Registruje se souborem
`META-INF/spring/…AutoConfiguration.imports`. Díky ní stačí přidat závislost
a knihovna se „sama zapojí“.

**Backend** — konkrétní úložiště, kam soubory fyzicky padají. FileForge dodává
S3 backend; přidat filesystem nebo Azure znamená implementovat `StorageBackend`.

**Back-off** (v kontextu Springu) — „ustoupení“. Když konzument nadeklaruje
vlastní bean stejného typu, knihovna svůj nevyrobí. Zajišťuje to anotace
`@ConditionalOnMissingBean`.

**Bean** — objekt, který spravuje Spring: vytvoří ho, uchová a rozdá tam, kde
je potřeba. „Publikovat bean“ = říct Springu „tenhle objekt dávej k dispozici“.

**Bucket** — pojmenovaný kontejner na S3, do kterého se ukládají objekty.
Obdoba disku nebo „kořenové složky“.

**Buffer / bufferování** — dočasné uložení dat, abychom s nimi mohli pracovat
víckrát nebo zjistit jejich velikost. FileForge bufferuje uploady, aby znal
jejich skutečnou délku ještě před zápisem.

**Content-Disposition** — HTTP hlavička, která prohlížeči říká „tenhle obsah
ulož jako soubor s tímto jménem“ místo „zobraz ho“. Bez ní by se stáhl soubor
pojmenovaný podle nesrozumitelného klíče.

**Content type / MIME typ** — označení druhu obsahu, např. `image/jpeg`,
`application/pdf`. Prohlížeč ho posílá při uploadu, ale je pod kontrolou
útočníka, takže se **ověřuje**, nevěří se mu.

**Continuation token / page token** — „záložka“ ve výpisu. Server řekne „tady
je 1000 klíčů a tohle je záložka“; s ní si příště vyžádáte pokračování.

**Credential chain** (AWS default credential chain) — řetěz míst, kde AWS SDK
hledá přihlašovací údaje, pokud je nezadáte: proměnné prostředí, system
properties, web identity token, SSO, profilový soubor, metadata kontejneru,
metadata instance. Právě díky němu fungují IAM role na ECS/EKS/EC2 bez toho,
aby kdokoli někam zapsal tajný klíč.

**Delimiter** — oddělovač ve výpisu, typicky `"/"`. Řekne S3 „nevypisuj vše do
hloubky, ale slož mi to, co je pod oddělovačem, do skupin“. Tak vznikne výpis
podobný obsahu složky.

**DI / Dependency Injection / injektování** — Spring vytvoří potřebné objekty
a předá je do konstruktoru, místo aby si je třída vyráběla sama. Proto stačí
napsat `class DocumentService(private val storage: FileStorage)` a `storage`
už je připravený.

**DataSize** — springový typ pro velikost. Umí `50MB`, `512KB` i prostý počet
bajtů, takže se v konfiguraci nemusí psát `52428800`.

**ETag** — kontrolní součet objektu, který vrací S3 po zápisu. FileForge ho
předává jako `StoredFile.checksum`.

**Fail-fast** — „selži hned“. Chybná konfigurace shodí aplikaci při startu
s jasnou zprávou, místo aby se projevila až za měsíc při prvním uploadu.

**Idempotentní operace** — druhé a další zavolání už nic nezmění. `delete()` je
idempotentní: smazat neexistující objekt není chyba (jen dostanete `false`).

**Invariant** — tvrzení, které musí platit pořád. Sekce 2 vypisuje deset
invariantů této knihovny; jsou to vlastnosti, které nesmí zmizet ani při
refaktoringu.

**Klíč (key)** — plná cesta objektu v bucketu, např.
`documents/2026/09/17/1f7a….pdf`. Na S3 neexistují skutečné složky; „složka“ je
jen část klíče před lomítkem.

**Magic bytes** — první bajty souboru, které prozrazují jeho skutečný formát.
PDF začíná `%PDF-`, PNG osmibajtovou signaturou. Kontrolou magic bytes se
pozná soubor, který se jen *tváří* jako PDF.

**Mock** — falešná implementace použitá v testu, která předstírá chování
skutečné. Testy používají knihovnu MockK pro `S3Client`.

**Multipart upload** — nahrávání po částech. S3 odmítne jedním požadavkem
zapsat víc než 5 GiB, takže se velký objekt musí poslat po částech a nakonec
„složit“.

**Objekt** — jedna uložená položka na S3 (obsah + metadata). Obdoba souboru.

**Path traversal** — útok, kdy se jméno souboru použije k útěku z určené
složky, např. `../../etc/passwd`. `FileNames.sanitize()` mu brání.

**Path-style access** — způsob, jakým se v URL adresuje bucket:
`http://host/bucket/klíč` (path-style) proti `http://bucket.host/klíč`
(virtual-hosted). Samohostované gatewaye obvykle potřebují path-style, AWS
preferuje virtual-hosted.

**Prefix** — začátek klíče, podle kterého se filtruje výpis. `list("photos/")`
vrátí vše, co začíná na `photos/`.

**Presigned URL** — odkaz s dočasným podpisem, který na omezenou dobu dovolí
stáhnout nebo nahrát objekt i tomu, kdo přihlašovací údaje k úložišti nemá.
Prohlížeč tak může načíst obrázek přímo z úložiště, aniž by data tekla přes
vaši aplikaci.

**RFC 7807 / ProblemDetail** — standardizovaný formát chybové JSON odpovědi
(`type`, `title`, `status`, `detail` a volitelná další pole). `ProblemDetail`
je jeho springová implementace.

**Sanitizace** — očištění vstupu od nebezpečných částí. U jména souboru
znamená odříznutí cest a vyhození znaků, které by mohly škodit.

**Single-pass stream** — proud dat, který lze přečíst jen jednou; po přečtení je
prázdný. Takové jsou sockety a tělo HTTP požadavku. `1.0.0` otevíralo stream
dvakrát, což u nich nefunguje.

**Spill** — „přetečení na disk“. Když je upload větší než povolený kus paměti,
zbytek se dopíše do dočasného souboru.

**SPI (Service Provider Interface)** — rozhraní, které **implementujete**. Vy
jste dodavatel, knihovna je klient a vaši implementaci volá. Přesný opak API.

> **Analogie:** API je zásuvka ve zdi — zapojíte se do ní a berete proud.
> SPI je předepsaný tvar vidlice — splníte ho, když chcete vyrobit nový
> spotřebič, aby ho zásuvka přijala.
>
> V FileForge: `FileStorage` je **API** (voláte `store()`), `StorageBackend` je
> **SPI** (implementujete `put()`, aby ho knihovna volala). Právě proto jsou to
> dva různé typy — kdyby to byl jeden, jako v `1.0.0`, nešlo by současně měnit
> úložiště konfigurací a vynucovat validaci. Viz sekce 1.

**Starter** — knihovna, která obsahuje auto-konfiguraci, takže se po přidání
závislosti „zapojí sama“. FileForge je starter.

**Stream** — proud dat čtený postupně, bez nutnosti mít ho celý v paměti.

**Testcontainers** — knihovna, která si pro test nastartuje Docker kontejner.
Integrační testy si takto zvednou skutečný S3 gateway.

**TTL (time to live)** — doba platnosti. U presigned URL doba, po kterou odkaz
funguje (výchozí 15 minut).

---

## 21. Referenční přehled funkcí — polopaticky

Co která funkce dělá, kdy ji použít a na co si dát pozor. Neznámý pojem najdete
v [slovníčku](#20-slovníček-pojmů).

### 21.1 `FileStorage` — to, co budete volat nejčastěji

#### `store(upload: FileUpload): StoredFile`

**Co dělá:** uloží soubor. Po cestě mu očistí jméno, změří skutečnou velikost,
zvaliduje obsah, vygeneruje klíč a zapíše ho do úložiště.

**Kdy použít:** když potřebujete plnou kontrolu — vlastní metadata, profil,
obsah z cesty na disku.

**Vrací:** `StoredFile` s vygenerovaným klíčem, očištěným jménem, content
typem, **skutečnou** velikostí, kontrolním součtem a časem. Do databáze si
uložte `key`; ostatní se dá později přečíst přes `metadata()`.

**Vyhodí:** `InvalidFileNameException` (z jména nezbylo nic použitelného),
`FileSizeExceededException`, `UnsupportedFileTypeException` (typ není na
whitelistu), `ContentMismatchException` (obsah neodpovídá typu),
`UnknownStorageProfileException` (profil není nakonfigurovaný).

**Pozor:** klíč **není** jméno souboru — generuje se. Když v `FileUpload`
nevyplníte `contentType`, knihovna ho detekuje z obsahu.

#### `store(fileName, bytes, contentType?, folder, profile?): StoredFile`

**Co dělá:** totéž, ale pro data, která už máte jako `ByteArray`.

**Kdy použít:** vygenerovaný PDF report, obrázek z převodu, cokoli, co vzniklo
v paměti.

#### `store(file: MultipartFile, folder, profile?, metadata): StoredFile`

**Co dělá:** uloží soubor nahraný přes HTTP formulář.

**Kdy použít:** ve Spring MVC controlleru — tohle je ta nejčastější varianta:

```kotlin
fun upload(file: MultipartFile) = storage.store(file, folder = "documents")
```

**Pozor:** je to *extension funkce* (rozšiřující funkce) v balíčku
`net.mimochodek.fileforge.web`, takže se musí naimportovat:
`import net.mimochodek.fileforge.web.store`. Content type od prohlížeče se
**ověřuje** proti magic bytes, `file.size` je jen nápověda.

#### `read(key: String): FileContent`

**Co dělá:** otevře objekt — dá vám metadata a stream s obsahem.

**Pozor:** **musíte to zavřít.** Drží HTTP konexi do úložiště. Zapomenete-li,
konexe se nevrátí do poolu a aplikace po čase zatuhne. Použijte radši variantu
s lambdou níž. Tuhle sáhněte jen tehdy, když stream potřebujete předat dál
(např. do HTTP odpovědi) a zavřete ho sami.

**Vyhodí:** `FileNotFoundStorageException`.

#### `read(key: String, block: (FileContent) -> T): T`

**Co dělá:** otevře objekt, předá ho vaší lambdě a **vždy ho zavře** — i když
lambda vyhodí výjimku.

**Kdy použít:** skoro vždy, když čtete. Tohle je bezpečná varianta.

```kotlin
val text = storage.read(key) { it.stream.reader().readText() }
```

#### `readBytes(key: String): ByteArray`

**Co dělá:** přečte celý objekt do paměti a zavře stream.

**Pozor:** načte **vše**. U 2 GB souboru vám vyčerpá haldu. Pro velké objekty
použijte `read(key) { }` a čtěte po částech.

#### `metadata(key: String): FileMetadata`

**Co dělá:** zjistí informace o objektu **bez stahování obsahu** — původní
jméno, content type, velikost, kontrolní součet, čas změny.

**Kdy použít:** když skládáte HTTP odpověď a potřebujete hlavičky. Nebo když si
chcete ověřit, co v úložišti je.

**Proč je to důležité:** právě tohle vás zbavuje nutnosti držet si jméno, typ
a velikost souboru ve vlastní databázi.

#### `exists(key: String): Boolean`

**Co dělá:** řekne, jestli objekt existuje.

**Pozor:** nepoužívejte to jako pojistku před `delete()` — `delete()` už
existenci řeší sám a bylo by to volání navíc.

#### `delete(key: String): Boolean`

**Co dělá:** smaže objekt. Vrátí `true`, pokud tam byl, `false`, pokud už tam
nebyl.

**Kdy použít:** při skutečném mazání. Chcete-li spíš „odložit do archivu“,
podívejte se na [recept v sekci 18](#archivovat-místo-mazání).

**Pozor:** je idempotentní — druhé zavolání není chyba. Na S3 stojí **dvě
volání** (nejdřív se ověří existence, pak se maže), protože S3 sám neřekne,
jestli objekt existoval.

#### `deleteAll(keys: Collection<String>)`

**Co dělá:** smaže víc objektů najednou.

**Kdy použít:** když mažete entitu s mnoha přílohami. S3 zvládne 1000 klíčů
jedním požadavkem, takže tohle je řádově rychlejší než `delete()` v cyklu.

**Pozor:** selže-li kterýkoli klíč, vyhodí výjimku se jménem prvního
neúspěšného. Některé z dávky už ale mohly být smazané — počítejte s tím.

#### `copy(from: String, to: String)`

**Co dělá:** zkopíruje objekt na nový klíč. Zdroj nechá být.

**Pozor:** kopíruje se **uvnitř jednoho bucketu**. Metadata včetně původního
jména se přenesou. Nad 5 GiB knihovna automaticky přepne na kopírování po
částech, protože jednorázová kopie by selhala.

#### `move(from: String, to: String)`

**Co dělá:** přesune objekt — zkopíruje a pak smaže zdroj.

**Kdy použít:** archivace, přerovnání do jiného prefixu.

**Pozor:** **není atomický.** Zdroj se maže až po úspěšné kopii, takže se
objekt nikdy neztratí; ale selže-li mazání, existují oba klíče a knihovna
zaloguje `WARN`. Podrobně v [receptu na archivaci](#archivovat-místo-mazání).

#### `list(prefix, limit, pageToken?, delimiter?): FilePage`

**Co dělá:** vypíše klíče začínající na `prefix`, po stránkách.

**Pozor:** **vždy** dostanete jen jednu stránku (výchozí 1000). Chcete-li vše,
musíte cyklit přes `nextPageToken`. Je to úmyslné — `1.0.0` vracelo všechno
a u velkého bucketu tím vyčerpalo paměť.

```kotlin
var page = storage.list("documents/", limit = 500)
while (true) {
    page.keys.forEach(::process)
    page = storage.list("documents/", limit = 500, pageToken = page.nextPageToken ?: break)
}
```

Zadáte-li `delimiter = "/"`, dostanete výpis jedné úrovně: v `keys` budou jen
klíče přímo pod prefixem a v `commonPrefixes` „podsložky“.

#### `downloadUrl(key, ttl, downloadAs?): URI`

**Co dělá:** vyrobí dočasný odkaz na stažení. Kdo ho má, může si objekt
stáhnout bez přihlašovacích údajů k úložišti — po dobu `ttl` (výchozí 15 minut).

**Kdy použít:** když chcete, aby si prohlížeč obrázek nebo PDF vzal **přímo
z úložiště** a data netekla přes vaši aplikaci.

**`downloadAs`:** nastaví hlavičku Content-Disposition, takže se soubor uloží
pod čitelným jménem místo pod vygenerovaným klíčem:

```kotlin
val name = storage.metadata(key).fileName
storage.downloadUrl(key, downloadAs = name)   // stáhne se jako "Quarterly_Report.pdf"
```

**Pozor:** odkaz **neřeší autorizaci**. Ověřit, že *tenhle* uživatel na *tenhle*
soubor smí, musíte vy, ještě než odkaz vygenerujete.

#### `uploadUrl(key, contentType, ttl): URI`

**Co dělá:** vyrobí dočasný odkaz pro **nahrání**, takže prohlížeč pošle soubor
rovnou do úložiště.

**Kdy použít:** u velkých souborů, kde nechcete zatěžovat aplikaci přeposíláním
dat.

**Pozor — důležité:** obsah nahraný tudy **neprojde validací**. Knihovna ho
nikdy nevidí, takže se nezkontroluje velikost, typ ani magic bytes a klíč si
volíte vy. Používejte to jen tam, kde vám nekontrolovaný obsah nevadí, nebo
doplňte ověření po nahrání.

### 21.2 Pomocné funkce, které smíte volat sami

Všechny jsou veřejné a bez stavu, takže se dají použít samostatně.

| Funkce | Co dělá |
|---|---|
| `FileNames.sanitize(name)` | Očistí jméno souboru: odřízne cesty, mezery na `_`, vyhodí nebezpečné znaky. Vyhodí `InvalidFileNameException`, nezbyde-li nic. |
| `FileNames.extensionOf(name)` | Přípona malými písmeny bez tečky, nebo `null`. |
| `ContentTypeDetector.normalize(type)` | Sjednotí zápis typu: zahodí `; charset=…`, otrimuje, malá písmena. Používejte vždy před porovnáváním typů. |
| `ContentTypeDetector.detect(head, name)` | Uhodne content type. Magic bytes mají přednost před příponou (přípona je pod kontrolou útočníka). Nakonec `application/octet-stream`. |
| `MagicBytes.isKnown(type)` | Víme pro tento typ podpis? |
| `MagicBytes.matches(type, head)` | Odpovídají úvodní bajty tomuto typu? |
| `MagicBytes.detect(head)` | Který známý typ tyto bajty představují, nebo `null`. |
| `DefaultFileValidator.validate(candidate)` | Celá kontrola: velikost → typ → magic bytes, v tomto pořadí. |
| `DefaultFileValidator.validateSize/MimeType/MagicBytes` | Jednotlivé kontroly zvlášť, když potřebujete jen jednu. |
| `DefaultObjectKeyGenerator.generate(request)` | Vyrobí klíč podle nastavené strategie. |

### 21.3 `StorageBackend` — jen pro toho, kdo píše nový backend

Tohle je [SPI](#20-slovníček-pojmů) — tyto funkce **implementujete**, knihovna
je volá. Vždy dostanete **přesný klíč**; nic negenerujte a nic nevalidujte, to
už proběhlo nad vámi.

| Funkce | Co má udělat |
|---|---|
| `providerName` | Krátké jméno do logů, např. `s3`. |
| `put(request)` | Zapiš. `request.contentLength` je už **pravdivá** délka. `request.fileName` ulož tak, aby ho `head()` vrátil nezměněný. |
| `get(key)` | Otevři obsah + metadata. Chybí-li, vyhoď `FileNotFoundStorageException`. |
| `head(key)` | Jen metadata, bez přenosu obsahu. |
| `exists(key)` | Existuje? |
| `delete(key)` | Smaž. Musí být idempotentní a vrátit `true`, jen když objekt existoval. |
| `deleteAll(keys)` | Smaž dávku; dávkuj, umí-li to úložiště. |
| `list(prefix, limit, pageToken, delimiter)` | Jedna stránka. Respektuj `limit` a vrať `nextPageToken`. |
| `copy(from, to)` | Zkopíruj, zdroj nech být. |
| `presignGet(key, ttl, downloadAs)` | Dočasný odkaz na čtení. |
| `presignPut(key, contentType, ttl)` | Dočasný odkaz na zápis. |

**Nepsané povinnosti:** přeložit všechny chyby úložiště do balíčku
`exception/` (nic vlastního nesmí uniknout) a nepřidávat do rozhraní defaultní
argumenty (viz invariant I8).

### 21.4 Vnitřní funkce — abyste se v kódu neztratili

Označené `internal`, konzument je nevidí. Sem se podívejte, když hledáte, kde
se něco děje.

| Funkce | Kde | Co dělá |
|---|---|---|
| `resolveContent(source, maxSize, threshold, tempDir)` | `internal/ResolvedContent.kt` | Převede vstup na obsah se **známou** velikostí a možností číst ho znovu. Tady se poprvé vynucuje limit velikosti. |
| `bufferStream(...)` | tamtéž | Nabufferuje stream: do limitu v paměti, nad ním do dočasného souboru. Přeruší čtení, jakmile se přeteče `maxSize`, aby se nepřátelský upload nedočítal do konce. |
| `ResolvedContent.openStream()` | tamtéž | Otevře obsah **znovu** — proto zvládneme single-pass streamy. |
| `call(action, notFoundKey) { }` | `internal/s3/S3StorageBackend.kt` | Obálka kolem každého volání AWS SDK. Překládá chyby, aby žádný AWS typ neunikl skrz abstrakci. |
| `mapped(action, e)` | tamtéž | Rozhoduje, na jakou výjimku chybu přeložit. Chyba bez HTTP odpovědi (nedostupná síť) → `StorageUnavailableException`, jinak `FileStorageException`. |
| `multipartPut(request)` | tamtéž | Nahrávání po částech nad 5 GiB. Při selhání upload **abortuje**, jinak by osiřelé části dál stály peníze za skladování. |
| `multipartCopy(...)` | tamtéž | Totéž pro kopírování nad 5 GiB. |
| `contentDisposition(fileName)` | tamtéž | Sestaví hlavičku pro stažení pod čitelným jménem, ve dvou variantách (ASCII pro staré klienty + UTF-8 pro přesné jméno). |
| `s3Metadata()` / `decodeFileName()` | tamtéž | Zakódují a dekódují původní jméno. S3 metadata jdou v HTTP hlavičkách a neunesou diakritiku, proto se jméno URL-enkóduje. |
| `userMetadata()` | tamtéž | Odfiltruje interní položky knihovny, aby konzument viděl jen svá metadata. |
| `BoundedInputStream` | `internal/s3/BoundedInputStream.kt` | Pohled na dalších N bajtů streamu. Jeho `close()` **záměrně nedělá nic** — podkladový stream je ještě potřeba pro další části uploadu. |

### 21.5 `FileForgeProblemDetailsHandler` — automatické chybové odpovědi

Volitelná součást (`fileforge.web.problem-details.enabled: true`), která
nahradí `@RestControllerAdvice`, jaký by si jinak napsala každá aplikace znovu.

**Jak to funguje:** `@RestControllerAdvice` je springový „odchytávač výjimek“ —
vyletí-li z controlleru výjimka, Spring najde metodu označenou
`@ExceptionHandler` pro její typ a její návratovou hodnotu pošle jako odpověď.
Zde tou hodnotou je `ProblemDetail`, tedy [RFC 7807](#20-slovníček-pojmů) JSON.

Sedm metod `handle()`, lišících se typem parametru:

| Výjimka | Status | Co přidá do JSON navíc |
|---|---|---|
| `FileNotFoundStorageException` | **404** Not Found | `key` |
| `FileSizeExceededException` | **413** Payload Too Large | `maxSize`, a `actualSize`, je-li známá |
| `UnsupportedFileTypeException` | **415** Unsupported Media Type | `contentType`, `allowedTypes` |
| `ContentMismatchException` | **415** Unsupported Media Type | `declaredContentType`, `detectedContentType` |
| `InvalidFileNameException` | **400** Bad Request | `fileName` |
| `StorageUnavailableException` | **503** Service Unavailable | — |
| `FileStorageException` (ostatní) | **500** Internal Server Error | — |

Výsledek pro příliš velký soubor:

```json
{
  "title": "Payload Too Large",
  "status": 413,
  "detail": "File size 9437184 bytes exceeds the maximum allowed size of 5242880 bytes",
  "maxSize": 5242880,
  "actualSize": 9437184
}
```

Klient se tak nemusí dohadovat z textu — `maxSize` si přečte jako číslo.

**Dvě věci na tom stojí za pozornost:**

**Poslední dvě větve úmyslně nic neprozradí.** U 503 a 500 se původní zpráva
**nepošle** — místo ní jdou obecné texty *„File storage is temporarily
unavailable“* a *„The file storage operation failed“*, a skutečná příčina se
zaloguje na `ERROR`. Je to proto, že tyto zprávy obsahují jména bucketů
a interních hostů (*„…bucket internal-prod“*, *„…nas:7070 refused“*), která
nemají u klienta co dělat. U zbylých pěti je zpráva bezpečná i užitečná, tam se
posílá. Hlídají to testy `…without leaking internals`.

**Řazení podle konkrétnosti.** Spring vybírá handler podle **nejbližšího**
typu, takže `FileNotFoundStorageException` chytí svou vlastní metodu, ne tu
obecnou pro `FileStorageException`. Přidáte-li novou výjimku a zapomenete pro
ni metodu, spadne do obecné 500 — technicky správně, ale zákazník nedostane
užitečnou informaci.

**Proč je to vypnuté defaultně:** knihovna nemá právo převzít aplikaci
zpracování chyb bez vyžádání. Máte-li vlastní advice, necháte to vypnuté
a mapování si uděláte po svém — tabulka výše vám řekne, jaké statusy jsou
rozumné.

### 21.6 Konfigurační pomocníci

| Funkce | Co dělá |
|---|---|
| `ProfileValidationProperties.isEmpty()` | Řekne, že profil nepřepisuje žádnou politiku — pak si nechá globální validátor včetně toho, který si dodal konzument. |
| `ProfileValidationProperties.mergedWith(global)` | Složí politiku profilu **pole po poli** nad globální. Nastavíte-li v profilu jen `max-file-size`, whitelist se zdědí. |
| `S3Properties.hasStaticCredentials` | Zadal uživatel oba klíče? Pokud ne, použije se AWS credential chain. |
| `FileForgeProperties.toStorageProfiles()` | Převede konfiguraci profilů na runtime podobu. |
| `ValidationProperties.toValidator()` | Vyrobí `DefaultFileValidator` z konfigurace. |
| `FilePage.hasMore` | Je ještě další stránka? (Totéž jako `nextPageToken != null`.) |
| `FileContent.close()` | Zavře stream a vrátí konexi do poolu. Volejte, nebo použijte `read(key) { }`. |
| `ContentSource.of(...)` | Pohodlné vyrobení vstupu z bajtů, streamu nebo cesty — hlavně pro volání z Javy. |

---

## Kam se podívat dál

| Otázka | Soubor |
|---|---|
| Jak to použít? | `README.md` |
| Co se změnilo proti `1.x` a proč? | `MIGRATION-2.0.md` |
| Proč je návrh takový, jaký je? | `ANALYSIS.md` |
| Co bylo původní zadání? | `assignment/library_prompt.md` |
| Jak se to vyvíjelo? | `git log` — commity jsou po fázích z `ANALYSIS.md` a každý vysvětluje své rozhodnutí |
