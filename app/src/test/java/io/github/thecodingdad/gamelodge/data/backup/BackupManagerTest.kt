package io.github.thecodingdad.gamelodge.data.backup

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.thecodingdad.gamelodge.data.db.HaTileEntity
import io.github.thecodingdad.gamelodge.data.db.HaTileType
import io.github.thecodingdad.gamelodge.data.db.HostConfigEntity
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.KindSource
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.WakeMethod
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.library.LibrarySync
import io.github.thecodingdad.gamelodge.data.library.SourceEntry
import io.github.thecodingdad.gamelodge.data.settings.StartTab
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackupManagerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var source: LauncherDatabase
    private lateinit var target: LauncherDatabase

    private fun db() = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java).allowMainThreadQueries().build()

    private suspend fun seedLibrary(db: LauncherDatabase) {
        LibrarySync(db) { 1L }.syncHost(
            "host",
            listOf("Elden Ring", "Hades", "Desktop").mapIndexed { i, name ->
                SourceEntry(SourceEntry.remoteId("host", i), TargetType.REMOTE, name, hostUuid = "host", hostName = "PC", appId = i)
            },
        )
    }

    @Before
    fun setUp() {
        source = db()
        target = db()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun `user changes survive export and import into a fresh install`() = runTest {
        val settings = io.github.thecodingdad.gamelodge.platform.AndroidStores.settings(context)
        val manager = BackupManager(source, settings, io.github.thecodingdad.gamelodge.platform.AndroidStores.secrets(context), io.github.thecodingdad.gamelodge.data.backup.ArtemisStreamBackup(context))
        seedLibrary(source)
        val dao = source.libraryDao()
        val elden = dao.getAllItems().first { it.title == "Elden Ring" }
        dao.setFavorite(elden.id, true)
        dao.addPlaytime(elden.id, 3600)
        val desktop = dao.getAllItems().first { it.title == "Desktop" }
        dao.setKind(desktop.id, ItemKind.GAME, KindSource.MANUAL)
        source.haTileDao().upsert(listOf(HaTileEntity("light.desk", HaTileType.LIGHT, null, 0, showOnHome = true)))
        source.hostConfigDao().upsert(HostConfigEntity("host", WakeMethod.HOME_ASSISTANT, haWakeEntity = "switch.vm"))
        settings.update { it.copy(startTab = StartTab.APPS) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("list_fps", "120").putInt("seekbar_bitrate_kbps", 40000).apply()

        val text = manager.encode(manager.create(password = null))

        // Fresh install: library comes from the host again, settings are reset.
        settings.update { it.copy(startTab = StartTab.HOME) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()
        seedLibrary(target)
        BackupManager(target, settings, io.github.thecodingdad.gamelodge.platform.AndroidStores.secrets(context), io.github.thecodingdad.gamelodge.data.backup.ArtemisStreamBackup(context)).restore(manager.decode(text), null)

        val restored = target.libraryDao().getAllItems().associateBy { it.title }
        assertTrue(restored.getValue("Elden Ring").favorite)
        assertEquals(3600L, restored.getValue("Elden Ring").playtimeSeconds)
        assertEquals(ItemKind.GAME, restored.getValue("Desktop").kind)
        assertEquals(KindSource.MANUAL, restored.getValue("Desktop").kindSource)
        assertEquals("light.desk", target.haTileDao().getAll().single().entityId)
        assertEquals("switch.vm", target.hostConfigDao().get("host")?.haWakeEntity)
        assertEquals(StartTab.APPS, settings.settings.first().startTab)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("120", prefs.getString("list_fps", null))
        assertEquals(40000, prefs.getInt("seekbar_bitrate_kbps", 0))
    }

    @Test(expected = WrongPasswordException::class)
    fun `credentials need the right password`() = runTest {
        val manager = BackupManager(source, io.github.thecodingdad.gamelodge.platform.AndroidStores.settings(context), io.github.thecodingdad.gamelodge.platform.AndroidStores.secrets(context), io.github.thecodingdad.gamelodge.data.backup.ArtemisStreamBackup(context))
        val backup = manager.create(password = "correct horse")
        manager.restore(manager.decode(manager.encode(backup)), "wrong")
    }
}
