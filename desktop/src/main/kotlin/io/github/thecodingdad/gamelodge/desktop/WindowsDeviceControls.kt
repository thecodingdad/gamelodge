package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Wbemcli
import com.sun.jna.platform.win32.COM.WbemcliUtil
import com.sun.jna.platform.win32.Dxva2
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.PhysicalMonitorEnumerationAPI.PHYSICAL_MONITOR
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Variant
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Volume, audio output and screen brightness of the PC for the quick menu. All calls run on one
 * COM thread and return null / do nothing where Windows doesn't offer the control.
 */
object WindowsDeviceControls {
    data class AudioOutput(val id: String, val name: String)

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "device-controls").apply { isDaemon = true } }
    private var comReady = false

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun <T> onComThread(block: () -> T): T? = if (!windows) null else runCatching {
        thread.submit(Callable {
            if (!comReady) {
                Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
                // WMI wants impersonation; fails harmlessly if the process already set its security.
                Ole32.INSTANCE.CoInitializeSecurity(null, -1, null, null, RPC_C_AUTHN_LEVEL_DEFAULT, RPC_C_IMP_LEVEL_IMPERSONATE, null, EOAC_NONE, null)
                comReady = true
            }
            block()
        }).get()
    }.getOrNull()

    // --- Volume (Core Audio, default playback device) ---

    /** Master volume of the default playback device, 0..1. */
    fun volume(): Float? = onComThread {
        withEndpointVolume { vol ->
            val level = FloatByReference()
            if (comCall(vol, GET_MASTER_VOLUME_SCALAR, level) == 0) level.value else null
        }
    }

    fun setVolume(level: Float) {
        onComThread { withEndpointVolume { vol -> comCall(vol, SET_MASTER_VOLUME_SCALAR, level.coerceIn(0f, 1f), null) } }
    }

    // --- Audio output ---

    /** Active playback devices with their names, as in the Windows sound settings. */
    fun outputs(): List<AudioOutput> = onComThread {
        withEnumerator { en ->
            val collection = PointerByReference()
            if (comCall(en, ENUM_AUDIO_ENDPOINTS, E_RENDER, DEVICE_STATE_ACTIVE, collection) != 0) return@withEnumerator emptyList()
            try {
                val count = IntByReference()
                comCall(collection.value, COLLECTION_GET_COUNT, count)
                (0 until count.value).mapNotNull { i ->
                    val device = PointerByReference()
                    if (comCall(collection.value, COLLECTION_ITEM, i, device) != 0) return@mapNotNull null
                    try {
                        val id = deviceId(device.value) ?: return@mapNotNull null
                        AudioOutput(id, friendlyName(device.value) ?: id)
                    } finally {
                        comRelease(device.value)
                    }
                }
            } finally {
                comRelease(collection.value)
            }
        }
    }.orEmpty()

    /** Id of the current default playback device. */
    fun defaultOutput(): String? = onComThread {
        withEnumerator { en ->
            val device = PointerByReference()
            if (comCall(en, GET_DEFAULT_AUDIO_ENDPOINT, E_RENDER, E_MULTIMEDIA, device) != 0) return@withEnumerator null
            try { deviceId(device.value) } finally { comRelease(device.value) }
        }
    }

    /**
     * Makes [id] the default playback device for every role, like the sound settings do
     * (IPolicyConfig: not documented, but stable since Windows 7 and what audio switchers use).
     */
    fun setDefaultOutput(id: String) {
        onComThread {
            val ref = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                Guid.GUID.fromString(CLSID_POLICY_CONFIG), null, CLSCTX_ALL, Guid.GUID.fromString(IID_POLICY_CONFIG), ref,
            )
            if (hr.toInt() != 0) return@onComThread
            try {
                for (role in 0..2) comCall(ref.value, SET_DEFAULT_ENDPOINT, WString(id), role)
            } finally {
                comRelease(ref.value)
            }
        }
    }

    private fun <T> withEnumerator(block: (Pointer) -> T): T? {
        val ref = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            Guid.GUID.fromString(CLSID_DEVICE_ENUMERATOR), null, CLSCTX_ALL, Guid.GUID.fromString(IID_DEVICE_ENUMERATOR), ref,
        )
        if (hr.toInt() != 0) return null
        return try { block(ref.value) } finally { comRelease(ref.value) }
    }

    private fun <T> withEndpointVolume(block: (Pointer) -> T): T? = withEnumerator { en ->
        val device = PointerByReference()
        if (comCall(en, GET_DEFAULT_AUDIO_ENDPOINT, E_RENDER, E_MULTIMEDIA, device) != 0) return@withEnumerator null
        try {
            val vol = PointerByReference()
            if (comCall(device.value, DEVICE_ACTIVATE, Guid.GUID.fromString(IID_ENDPOINT_VOLUME), CLSCTX_ALL, null, vol) != 0) return@withEnumerator null
            try { block(vol.value) } finally { comRelease(vol.value) }
        } finally {
            comRelease(device.value)
        }
    }

    private fun deviceId(device: Pointer): String? {
        val ref = PointerByReference()
        if (comCall(device, DEVICE_GET_ID, ref) != 0) return null
        return ref.value.getWideString(0).also { Ole32.INSTANCE.CoTaskMemFree(ref.value) }
    }

    private fun friendlyName(device: Pointer): String? {
        val store = PointerByReference()
        if (comCall(device, DEVICE_OPEN_PROPERTY_STORE, STGM_READ, store) != 0) return null
        try {
            // PROPERTYKEY PKEY_Device_FriendlyName: GUID + property id.
            val key = Memory(20).apply {
                write(0, Guid.GUID.fromString(PKEY_DEVICE_FRIENDLY_NAME).toByteArray(), 0, 16)
                setInt(16, 14)
            }
            val value = Memory(24).apply { clear() }
            if (comCall(store.value, PROPERTY_STORE_GET_VALUE, key, value) != 0) return null
            return try {
                if (value.getShort(0).toInt() == VT_LPWSTR) value.getPointer(8)?.getWideString(0) else null
            } finally {
                NativeLibrary.getInstance("ole32").getFunction("PropVariantClear").invokeInt(arrayOf(value))
            }
        } finally {
            comRelease(store.value)
        }
    }

    // --- Brightness: internal panel through WMI, external monitors through DDC/CI ---

    private enum class BrightnessProperty { CurrentBrightness }
    private enum class PathProperty { __PATH }

    /** Screen brightness 0..100, null where no display can be dimmed. */
    fun brightness(): Int? = onComThread {
        panelBrightness() ?: monitors { handles ->
            handles.firstNotNullOfOrNull { handle ->
                val min = WinDef.DWORDByReference()
                val cur = WinDef.DWORDByReference()
                val max = WinDef.DWORDByReference()
                if (Dxva2.INSTANCE.GetMonitorBrightness(handle, min, cur, max).booleanValue()) {
                    val range = (max.value.toInt() - min.value.toInt()).coerceAtLeast(1)
                    (cur.value.toInt() - min.value.toInt()) * 100 / range
                } else {
                    null
                }
            }
        }
    }

    fun setBrightness(percent: Int) {
        val value = percent.coerceIn(0, 100)
        onComThread {
            if (setPanelBrightness(value)) return@onComThread
            monitors { handles ->
                handles.forEach { handle ->
                    val min = WinDef.DWORDByReference()
                    val cur = WinDef.DWORDByReference()
                    val max = WinDef.DWORDByReference()
                    if (Dxva2.INSTANCE.GetMonitorBrightness(handle, min, cur, max).booleanValue()) {
                        val lo = min.value.toInt()
                        Dxva2.INSTANCE.SetMonitorBrightness(handle, lo + (max.value.toInt() - lo) * value / 100)
                    }
                }
            }
        }
    }

    private fun panelBrightness(): Int? = runCatching {
        val result = WbemcliUtil.WmiQuery(WMI_NAMESPACE, "WmiMonitorBrightness WHERE Active=TRUE", BrightnessProperty::class.java).execute()
        if (result.resultCount == 0) null else (result.getValue(BrightnessProperty.CurrentBrightness, 0) as? Number)?.toInt()?.and(0xff)
    }.getOrNull()

    private fun setPanelBrightness(percent: Int): Boolean = runCatching {
        val paths = WbemcliUtil.WmiQuery(WMI_NAMESPACE, "WmiMonitorBrightnessMethods WHERE Active=TRUE", PathProperty::class.java).execute()
        if (paths.resultCount == 0) return@runCatching false
        val services = WbemcliUtil.connectServer(WMI_NAMESPACE)
        try {
            val cls = services.GetObject("WmiMonitorBrightnessMethods", 0, null)
            val definition = cls.GetMethod("WmiSetBrightness")
            for (i in 0 until paths.resultCount) {
                val path = paths.getValue(PathProperty.__PATH, i) as? String ?: continue
                val args = definition.SpawnInstance()
                args.Put("Timeout", 0, Variant.VARIANT(1), 0)
                args.Put("Brightness", 0, Variant.VARIANT(WinDef.BYTE(percent.toLong())), 0)
                services.ExecMethod(path, "WmiSetBrightness", 0, null, args)?.Release()
                args.Release()
            }
            definition.Release()
            cls.Release()
            true
        } finally {
            services.Release()
        }
    }.getOrDefault(false)

    /** Physical monitors of every display (closed again after [block]). */
    private fun <T> monitors(block: (List<com.sun.jna.platform.win32.WinNT.HANDLE>) -> T): T {
        val physical = mutableListOf<Array<PHYSICAL_MONITOR>>()
        User32.INSTANCE.EnumDisplayMonitors(null, null, WinUser.MONITORENUMPROC { hMonitor, _, _, _ ->
            val count = WinDef.DWORDByReference()
            if (Dxva2.INSTANCE.GetNumberOfPhysicalMonitorsFromHMONITOR(hMonitor, count).booleanValue() && count.value.toInt() > 0) {
                @Suppress("UNCHECKED_CAST")
                val array = PHYSICAL_MONITOR().toArray(count.value.toInt()) as Array<PHYSICAL_MONITOR>
                if (Dxva2.INSTANCE.GetPhysicalMonitorsFromHMONITOR(hMonitor, array.size, array).booleanValue()) physical += array
            }
            1
        }, null)
        return try {
            block(physical.flatMap { it.map { m -> m.hPhysicalMonitor } })
        } finally {
            physical.forEach { Dxva2.INSTANCE.DestroyPhysicalMonitors(it.size, it) }
        }
    }

    private const val WMI_NAMESPACE = "ROOT\\WMI"
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_ALL = 0x17
    private const val RPC_C_AUTHN_LEVEL_DEFAULT = 0
    private const val RPC_C_IMP_LEVEL_IMPERSONATE = 3
    private const val EOAC_NONE = 0
    private const val E_RENDER = 0
    private const val E_MULTIMEDIA = 1
    private const val DEVICE_STATE_ACTIVE = 0x1
    private const val STGM_READ = 0
    private const val VT_LPWSTR = 31

    private const val CLSID_DEVICE_ENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}"
    private const val IID_DEVICE_ENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}"
    private const val IID_ENDPOINT_VOLUME = "{5CDF2C82-841E-4546-9722-0CF74078229A}"
    private const val CLSID_POLICY_CONFIG = "{870AF99C-171D-4F9E-AF0D-E63DF40C2BC9}"
    private const val IID_POLICY_CONFIG = "{F8679F50-850A-41CF-9C72-430F290290C8}"
    private const val PKEY_DEVICE_FRIENDLY_NAME = "{A45C254E-DF1C-4EFD-8020-67D146A850E0}"

    // IMMDeviceEnumerator / IMMDeviceCollection / IMMDevice / IPropertyStore vtables (after IUnknown 0–2).
    private const val ENUM_AUDIO_ENDPOINTS = 3
    private const val GET_DEFAULT_AUDIO_ENDPOINT = 4
    private const val COLLECTION_GET_COUNT = 3
    private const val COLLECTION_ITEM = 4
    private const val DEVICE_ACTIVATE = 3
    private const val DEVICE_OPEN_PROPERTY_STORE = 4
    private const val DEVICE_GET_ID = 5
    private const val PROPERTY_STORE_GET_VALUE = 5

    // IAudioEndpointVolume.
    private const val SET_MASTER_VOLUME_SCALAR = 7
    private const val GET_MASTER_VOLUME_SCALAR = 9

    // IPolicyConfig.
    private const val SET_DEFAULT_ENDPOINT = 13
}
