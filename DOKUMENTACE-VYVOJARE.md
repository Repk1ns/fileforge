# FileForge — vývojářská dokumentace

Dokumentace **pro vývojáře a údržbáře samotné knihovny**, ne pro její konzumenty.

- Jste-li konzument (chcete knihovnu použít ve své aplikaci) → [README.md](README.md).
- Migrujete-li z `1.0.x` → [MIGRATION-2.0.md](MIGRATION-2.0.md).
- Zajímá-li vás, *proč* má knihovna tuto podobu → `ANALYSIS.md` (architektonický
  přehled `1.0.0` a návrh cílového stavu, podle kterého byla `2.0.0` postavena).

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

## Kam se podívat dál

| Otázka | Soubor |
|---|---|
| Jak to použít? | `README.md` |
| Co se změnilo proti `1.x` a proč? | `MIGRATION-2.0.md` |
| Proč je návrh takový, jaký je? | `ANALYSIS.md` |
| Co bylo původní zadání? | `assignment/library_prompt.md` |
| Jak se to vyvíjelo? | `git log` — commity jsou po fázích z `ANALYSIS.md` a každý vysvětluje své rozhodnutí |
