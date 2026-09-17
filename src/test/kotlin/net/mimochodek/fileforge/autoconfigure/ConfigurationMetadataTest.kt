package net.mimochodek.fileforge.autoconfigure

import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Keeps `META-INF/spring-configuration-metadata.json` honest.
 *
 * The metadata is hand-written, because Kotlin never ran the `annotationProcessor`
 * declaration 1.x carried and kapt does not emit properties for constructor-bound
 * Kotlin classes. Hand-written metadata is free to drift from the classes it
 * describes - silently, and only visibly to consumers as missing or wrong IDE
 * completion. This test removes that freedom: it derives the expected keys from
 * the properties classes by reflection and requires an exact match, so adding,
 * renaming or removing a property fails the build until the metadata follows.
 */
class ConfigurationMetadataTest {

    private val metadata: Metadata = loadMetadata()

    @Test
    fun `should document every property, and no property that does not exist`() {
        val expected = expectedProperties()

        assertEquals(
            expected,
            metadata.propertyNames,
            "spring-configuration-metadata.json has drifted from the properties classes",
        )
    }

    @Test
    fun `should declare a group for every nested properties class`() {
        val expected = expectedGroups()

        assertEquals(expected, metadata.groupNames)
    }

    @Test
    fun `should give every property a description and a source type`() {
        metadata.properties.forEach { property ->
            val name = property["name"] as String
            assertTrue(
                (property["description"] as? String).isNullOrBlank().not(),
                "$name has no description",
            )
            assertTrue(
                (property["sourceType"] as? String).isNullOrBlank().not(),
                "$name has no sourceType",
            )
        }
    }

    @Test
    fun `should point every source type at a class that exists`() {
        (metadata.properties + metadata.groups).forEach { entry ->
            val sourceType = entry["sourceType"] as String
            assertNotNull(
                runCatching { Class.forName(sourceType) }.getOrNull(),
                "${entry["name"]} points at a missing class: $sourceType",
            )
        }
    }

    @Test
    fun `should hint only at properties it documents`() {
        metadata.hints.forEach { hint ->
            val name = hint["name"] as String
            assertTrue(name in metadata.propertyNames, "hint for an undocumented property: $name")
        }
    }

    /**
     * Walks the properties classes the way Spring binds them: a constructor
     * parameter whose type is itself a properties class is a nested group,
     * anything else is a leaf property.
     */
    private fun expectedProperties(): Set<String> = buildSet {
        addAll(walk(FileForgeProperties::class, "fileforge"))
        addAll(walk(S3Properties::class, "fileforge.s3"))
    }

    private fun expectedGroups(): Set<String> = buildSet {
        add("fileforge")
        add("fileforge.s3")
        addAll(walkGroups(FileForgeProperties::class, "fileforge"))
    }

    private fun walk(type: KClass<*>, prefix: String): Set<String> = buildSet {
        type.primaryConstructor!!.parameters.forEach { parameter ->
            val key = "$prefix.${kebab(parameter.name!!)}"
            val parameterType = parameter.type.classifier as KClass<*>
            if (parameterType.isPropertiesClass()) {
                addAll(walk(parameterType, key))
            } else {
                add(key)
            }
        }
    }

    private fun walkGroups(type: KClass<*>, prefix: String): Set<String> = buildSet {
        type.primaryConstructor!!.parameters.forEach { parameter ->
            val parameterType = parameter.type.classifier as KClass<*>
            if (parameterType.isPropertiesClass()) {
                val key = "$prefix.${kebab(parameter.name!!)}"
                add(key)
                addAll(walkGroups(parameterType, key))
            }
        }
    }

    /** Our own nested holder types, as opposed to leaf values like DataSize or ZoneId. */
    private fun KClass<*>.isPropertiesClass(): Boolean =
        qualifiedName?.startsWith("net.mimochodek.fileforge.autoconfigure.") == true

    private fun kebab(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    private class Metadata(
        val groups: List<Map<String, Any?>>,
        val properties: List<Map<String, Any?>>,
        val hints: List<Map<String, Any?>>,
    ) {
        val groupNames: Set<String> get() = groups.mapTo(LinkedHashSet()) { it["name"] as String }
        val propertyNames: Set<String> get() = properties.mapTo(LinkedHashSet()) { it["name"] as String }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadMetadata(): Metadata {
        val json = javaClass.getResourceAsStream("/META-INF/spring-configuration-metadata.json")
            ?.use { it.readBytes().decodeToString() }
        assertNotNull(json, "META-INF/spring-configuration-metadata.json is missing from the jar")

        val parsed = MiniJson.parse(json) as Map<String, Any?>
        return Metadata(
            groups = (parsed["groups"] as List<Map<String, Any?>>),
            properties = (parsed["properties"] as List<Map<String, Any?>>),
            hints = (parsed["hints"] as? List<Map<String, Any?>>).orEmpty(),
        )
    }

    /**
     * A minimal JSON reader, so this test needs no JSON dependency of its own -
     * the library itself has none, and adding one only to read its own metadata
     * would be a strange thing for a consumer to inherit on the test classpath.
     */
    private object MiniJson {

        fun parse(text: String): Any? = Reader(text).let { reader ->
            val value = reader.value()
            reader.skipWhitespace()
            require(reader.atEnd()) { "trailing content at ${reader.position}" }
            value
        }

        private class Reader(private val text: String) {
            var position: Int = 0

            fun atEnd(): Boolean = position >= text.length

            fun skipWhitespace() {
                while (!atEnd() && text[position].isWhitespace()) position++
            }

            fun value(): Any? {
                skipWhitespace()
                return when (val c = text[position]) {
                    '{' -> obj()
                    '[' -> array()
                    '"' -> string()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $position")
                }
            }

            private fun obj(): Map<String, Any?> {
                expect('{')
                val result = LinkedHashMap<String, Any?>()
                skipWhitespace()
                if (text[position] == '}') { position++; return result }
                while (true) {
                    skipWhitespace()
                    val key = string()
                    skipWhitespace()
                    expect(':')
                    result[key] = value()
                    skipWhitespace()
                    when (text[position]) {
                        ',' -> position++
                        '}' -> { position++; return result }
                        else -> error("expected ',' or '}' at $position")
                    }
                }
            }

            private fun array(): List<Any?> {
                expect('[')
                val result = mutableListOf<Any?>()
                skipWhitespace()
                if (text[position] == ']') { position++; return result }
                while (true) {
                    result += value()
                    skipWhitespace()
                    when (text[position]) {
                        ',' -> position++
                        ']' -> { position++; return result }
                        else -> error("expected ',' or ']' at $position")
                    }
                }
            }

            private fun string(): String {
                expect('"')
                val builder = StringBuilder()
                while (true) {
                    when (val c = text[position++]) {
                        '"' -> return builder.toString()
                        '\\' -> builder.append(escape(text[position++]))
                        else -> builder.append(c)
                    }
                }
            }

            private fun escape(c: Char): Char = when (c) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'u' -> text.substring(position, position + 4).toInt(16).toChar().also { position += 4 }
                else -> c
            }

            private fun number(): Any {
                val start = position
                while (!atEnd() && (text[position].isDigit() || text[position] in "-+.eE")) position++
                val raw = text.substring(start, position)
                return raw.toLongOrNull() ?: raw.toDouble()
            }

            private fun <T> literal(token: String, result: T): T {
                require(text.startsWith(token, position)) { "expected $token at $position" }
                position += token.length
                return result
            }

            private fun expect(c: Char) {
                skipWhitespace()
                require(text[position] == c) { "expected '$c' at $position but found '${text[position]}'" }
                position++
            }
        }
    }
}
