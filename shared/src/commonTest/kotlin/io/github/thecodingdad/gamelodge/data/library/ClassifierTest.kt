package io.github.thecodingdad.gamelodge.data.library

import io.github.thecodingdad.gamelodge.data.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifierTest {
    private fun remote(title: String, uuid: String? = null, companion: Boolean? = null) =
        Classifier.classify(ClassificationInput(title, isRemote = true, remoteAppUuid = uuid, companionGame = companion))

    private fun local(title: String, gameFlag: Boolean = false) =
        Classifier.classify(ClassificationInput(title, isRemote = false, androidGameFlag = gameFlag))

    @Test
    fun `host tools are apps`() {
        listOf("Desktop", "Steam Big Picture", "Playnite", "Terminate", "Resume", "Remote Monitor", "Remote Input")
            .forEach { assertEquals(it, ItemKind.APP, remote(it)) }
    }

    @Test
    fun `remote input uuid is an app regardless of title`() {
        assertEquals(ItemKind.APP, remote("Whatever", uuid = Classifier.REMOTE_INPUT_UUID.lowercase()))
    }

    @Test
    fun `unknown remote entries are games`() {
        assertEquals(ItemKind.GAME, remote("Elden Ring"))
    }

    @Test
    fun `companion kind always wins`() {
        assertEquals(ItemKind.GAME, remote("Steam", companion = true))
        assertEquals(ItemKind.APP, remote("Elden Ring", companion = false))
    }

    @Test
    fun `local apps follow the android game flag`() {
        assertEquals(ItemKind.GAME, local("Genshin Impact", gameFlag = true))
        assertEquals(ItemKind.APP, local("YouTube"))
    }

    @Test
    fun `confident metadata match makes a local app a game`() {
        assertEquals(
            ItemKind.GAME,
            Classifier.classify(ClassificationInput("Stardew Valley", isRemote = false, confidentGameMatch = true)),
        )
    }

    @Test
    fun `emulators are apps although they flag themselves as games`() {
        val eden = ClassificationInput("Eden", isRemote = false, androidGameFlag = true, packageName = "dev.eden.eden_emulator", isEmulator = true)
        assertEquals(ItemKind.APP, Classifier.classify(eden))
        assertEquals(ItemKind.GAME, Classifier.classify(eden.copy(isEmulator = false)))
    }
}
