package io.github.thecodingdad.moonlightlauncher.data.library

import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifierTest {
    private fun remote(title: String, uuid: String? = null, playnite: Boolean = false) =
        Classifier.classify(ClassificationInput(title, isRemote = true, remoteAppUuid = uuid, hasPlayniteId = playnite))

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
    fun `playnite id always wins`() {
        assertEquals(ItemKind.GAME, remote("Steam", playnite = true))
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
}
