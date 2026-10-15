package io.github.thecodingdad.moonlightlauncher.data.emulation

import android.content.Context
import io.github.thecodingdad.moonlightlauncher.data.library.LibraryRepository
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ROM folders and emulators: configuration, scanning into the library, choosing the emulator. */
class EmulationRepository(
    context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    val catalog = EmulatorCatalog(context)
    private val scanner = RomScanner(context, catalog)
    private val mutex = Mutex()
    private var lastScan = 0L

    val config: StateFlow<EmulationConfig> = settings.settings.map { EmulationConfig.decode(it.emulation) }
        .stateIn(scope, SharingStarted.Eagerly, EmulationConfig())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Games found by the last scan (null: not scanned yet). */
    private val _found = MutableStateFlow<Int?>(null)
    val found: StateFlow<Int?> = _found.asStateFlow()

    /** Folders the last scan couldn't read (access lost). */
    private val _unreadable = MutableStateFlow<List<RomFolder>>(emptyList())
    val unreadable: StateFlow<List<RomFolder>> = _unreadable.asStateFlow()

    fun start() {
        scope.launch { rescan() }
        // Folders added, removed or reassigned: scan again.
        scope.launch {
            settings.settings.map { EmulationConfig.decode(it.emulation).folders }.distinctUntilChanged().drop(1).collect { rescan() }
        }
    }

    suspend fun update(transform: (EmulationConfig) -> EmulationConfig) =
        settings.update { it.copy(emulation = transform(EmulationConfig.decode(it.emulation)).encode()) }

    suspend fun rescan() = mutex.withLock {
        _scanning.value = true
        try {
            val result = withContext(Dispatchers.IO) { scanner.scan(config.value) }
            library.syncRoms(result.roms.map { io.github.thecodingdad.moonlightlauncher.data.library.RomGame(it.name, it.platform.id, it.platform.displayName, it.uri, it.path, it.image) }, keepUnder = result.unreadable.map { it.uri })
            _found.value = result.roms.size
            _unreadable.value = result.unreadable
            lastScan = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e("ROM scan failed", e)
        } finally {
            _scanning.value = false
        }
    }

    /** Back in the launcher: rescan now and then (new ROMs copied in meanwhile). */
    fun rescanIfStale() {
        if (config.value.folders.isEmpty() || System.currentTimeMillis() - lastScan < 10 * 60_000) return
        scope.launch { rescan() }
    }

    fun choicesOf(platformId: String): List<EmulatorChoice> = catalog.choices(platformId, config.value)

    fun resolve(platformId: String, gameKey: String?): EmulatorChoice? = catalog.resolve(platformId, config.value, gameKey)

    /** The same for the shared UI. */
    val options: Emulators = object : Emulators {
        override fun choices(platformId: String) = choicesOf(platformId).map { EmulatorOption(it.key, it.label, it.experimental) }
        override fun resolve(platformId: String, gameKey: String?) =
            this@EmulationRepository.resolve(platformId, gameKey)?.let { EmulatorOption(it.key, it.label, it.experimental) }
        override fun platformName(platformId: String) = catalog.platform(platformId)?.displayName
    }
}
