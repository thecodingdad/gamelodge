package io.github.thecodingdad.gamelodge.resources

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** The strings read at run time are exactly what Android made of the same XML (escapes, entities, placeholders). */
class StringResourcesTest {
    /** Common strings plus the desktop's own (the test runs on the desktop). */
    private fun xml(folder: String): Map<String, String> = listOf("commonMain", "desktopMain").flatMap { set ->
        val file = File("src/$set/composeResources/$folder/strings.xml")
        if (!file.exists()) return@flatMap emptyList()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("name") to androidText(it.textContent) }
    }.toMap()

    /** What aapt does with a string's text: backslash escapes resolved. */
    private fun androidText(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun check(locale: Locale, folder: String) {
        val default = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val expected = xml("values") + xml(folder)
            val resources = Res.allStringResources
            assertEquals(expected.keys, resources.keys)
            runBlocking {
                resources.forEach { (name, res) -> assertEquals("$name ($locale)", expected.getValue(name), getString(res)) }
            }
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun englishMatchesAndroid() = check(Locale.ENGLISH, "values")

    @Test
    fun germanMatchesAndroid() = check(Locale.GERMAN, "values-de")

    @Test
    fun spanishMatchesAndroid() = check(Locale("es"), "values-es")

    @Test
    fun frenchMatchesAndroid() = check(Locale.FRENCH, "values-fr")

    @Test
    fun italianMatchesAndroid() = check(Locale.ITALIAN, "values-it")

    @Test
    fun brazilianPortugueseMatchesAndroid() = check(Locale("pt", "BR"), "values-pt-rBR")

    @Test
    fun everyLanguageHasTheSameStringsAndPlaceholders() {
        val en = xml("values")
        val placeholder = Regex("""%\d+\$[sd]""")
        for (folder in listOf("values-de", "values-es", "values-fr", "values-it", "values-pt-rBR")) {
            val loc = xml(folder)
            assertEquals(folder, en.keys - "launcher_name", loc.keys - "launcher_name")
            loc.forEach { (name, text) ->
                assertEquals(
                    "$folder $name",
                    placeholder.findAll(en.getValue(name)).map { it.value }.toSortedSet(),
                    placeholder.findAll(text).map { it.value }.toSortedSet(),
                )
                assertTrue("$folder $name", !Regex("""%[sd]""").containsMatchIn(text))
            }
        }
    }
}
