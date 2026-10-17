package io.github.thecodingdad.gamelodge.desktop.games

import io.github.thecodingdad.gamelodge.desktop.isWindows
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Xbox app / Microsoft Store games: installed Store packages (PowerShell Get-AppxPackage) that are
 * games – GDK titles carry a MicrosoftGame.config. Started through the package's AppUserModelId
 * (shell:AppsFolder\PFN!App).
 */
class XboxScanner : GameScanner {
    override val source = "xbox"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        if (!isWindows) return@withContext emptyList()
        listPackages().mapNotNull { (name, family, location) ->
            runCatching { fromPackage(name, family, location) }.onFailure { Log.w("Xbox: package $name unreadable", it) }.getOrNull()
        }
    }

    private fun listPackages(): List<Triple<String, String, String>> = runCatching {
        val script = "Get-AppxPackage -PackageTypeFilter Main | Where-Object { -not \$_.IsFramework -and \$_.SignatureKind -eq 'Store' } | " +
            "Select-Object Name, PackageFamilyName, InstallLocation | ConvertTo-Json -Compress"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        if (output.isBlank()) return@runCatching emptyList()
        val root = Json.parseToJsonElement(output)
        val items: List<JsonElement> = if (root is JsonArray) root else listOf(root)
        items.mapNotNull { i ->
            val o = i as? JsonObject ?: return@mapNotNull null
            fun t(n: String) = (o[n] as? JsonPrimitive)?.content.orEmpty()
            Triple(t("Name"), t("PackageFamilyName"), t("InstallLocation")).takeIf { it.third.isNotEmpty() }
        }
    }.onFailure { Log.w("Xbox: listing Store packages failed", it) }.getOrDefault(emptyList())

    companion object {
        /** A game entry when the package is a game (MicrosoftGame.config present), else null. */
        fun fromPackage(name: String, family: String, location: String): GameEntry? {
            val config = File(location, "MicrosoftGame.config")
            val manifest = File(location, "AppxManifest.xml")
            if (!config.exists() || !manifest.exists()) return null
            val game = parseGameConfig(config.readText())
            val appId = parseManifestAppId(manifest.readText()) ?: return null
            return GameEntry(
                id = "xbox:$family", name = game.displayName ?: name, source = "xbox", storeId = game.storeId ?: family,
                installed = true, installDir = location, launch = "shell:AppsFolder\\$family!$appId", processNames = game.executables,
            )
        }

        data class GameConfig(val displayName: String?, val storeId: String?, val executables: List<String>)

        private fun xml(text: String) = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(text.byteInputStream())

        private fun org.w3c.dom.Document.byLocalName(name: String): List<Element> =
            getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }

        fun parseGameConfig(text: String): GameConfig {
            val doc = xml(text)
            val display = doc.byLocalName("ShellVisuals").firstOrNull()?.getAttribute("DefaultDisplayName")
                ?.takeIf { it.isNotEmpty() && !it.startsWith("ms-resource:", ignoreCase = true) }
            val storeId = doc.byLocalName("StoreId").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val exes = doc.byLocalName("Executable").mapNotNull { it.getAttribute("Name").takeIf { n -> n.isNotEmpty() } }
                .map(WinPath::fileName).distinctBy { it.lowercase() }
            return GameConfig(display, storeId, exes)
        }

        fun parseManifestAppId(text: String): String? =
            xml(text).byLocalName("Application").firstOrNull()?.getAttribute("Id")?.takeIf { it.isNotEmpty() }
    }
}
