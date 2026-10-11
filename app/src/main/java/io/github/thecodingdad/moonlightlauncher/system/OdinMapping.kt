package io.github.thecodingdad.moonlightlauncher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * AYN Odin's touch-mapping service takes over the first external controller and replaces it
 * with a virtual copy without battery and rumble. Its (otherwise unused) blacklist of device
 * names keeps listed controllers untouched; the list is matched by name only, and only applies
 * when a controller connects.
 */
class OdinMapping(private val context: Context) {
    val isAvailable: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Device names Odin Mapping leaves alone, or null when the service can't be reached. */
    suspend fun blacklist(): List<String>? = call { binder ->
        transact(binder, CODE_GET_BLACKLIST) { reply -> reply.createStringArrayList().orEmpty() }
    }

    suspend fun setBlacklisted(name: String, blacklisted: Boolean): Boolean = call { binder ->
        transact(binder, CODE_SET_BLACKLISTED, { writeInt(if (blacklisted) 1 else 0); writeString(name) }) { true }
    } ?: false

    private fun <T> transact(binder: IBinder, code: Int, args: Parcel.() -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.args()
            binder.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private suspend fun <T> call(block: (IBinder) -> T): T? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    val result = runCatching { block(service) }.getOrNull()
                    runCatching { context.unbindService(this) }
                    if (cont.isActive) cont.resume(result)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            val bound = runCatching {
                context.bindService(Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) cont.resume(null)
            cont.invokeOnCancellation { runCatching { context.unbindService(connection) } }
        }
    }

    /**
     * Adds every real external controller to the blacklist as it connects (and those already
     * connected) while [enabled] says so. Odin is faster on a controller's very first connection;
     * from the next one on it stays untouched.
     */
    fun watch(scope: kotlinx.coroutines.CoroutineScope, enabled: kotlinx.coroutines.flow.Flow<Boolean>) {
        if (!isAvailable) return
        val im = context.getSystemService(android.hardware.input.InputManager::class.java)
        val added = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 64)
        im.registerInputDeviceListener(object : android.hardware.input.InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { added.tryEmit(id) }
            override fun onInputDeviceRemoved(id: Int) = Unit
            override fun onInputDeviceChanged(id: Int) = Unit
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        scope.launch {
            enabled.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                // Name captured right away: Odin removes the original device ~0.1 s later.
                val names = kotlinx.coroutines.flow.merge(
                    android.view.InputDevice.getDeviceIds().asList().asFlow(),
                    added,
                ).mapNotNull { id -> android.view.InputDevice.getDevice(id)?.takeIf(::isExternalPad)?.name }
                names.collect { name ->
                    val list = blacklist() ?: return@collect
                    if (name !in list) setBlacklisted(name, true)
                }
            }
        }
    }

    companion object {
        private const val AYN_VENDOR = 0x2020
        private const val COPY_PRODUCT = 0x0111

        /** AYN's built-in controls: AYN vendor id, reported as "external" by Android. */
        fun isBuiltIn(d: android.view.InputDevice): Boolean = d.vendorId == AYN_VENDOR && d.productId != COPY_PRODUCT

        /** Odin Mapping's virtual stand-in for an external controller it took over. */
        fun isOdinCopy(d: android.view.InputDevice): Boolean = d.vendorId == AYN_VENDOR && d.productId == COPY_PRODUCT

        /** Real external gamepads; Odin's own devices (built-in controls, virtual copies) use vendor 0x2020. */
        fun isExternalPad(d: android.view.InputDevice): Boolean =
            !d.isVirtual && d.isExternal && d.vendorId != AYN_VENDOR &&
                (d.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD


        private const val PACKAGE = "com.odin.mapping"
        private const val ACTION = "com.ro.mapping.action.MAPPING_SERVICE"
        private const val DESCRIPTOR = "com.ro.mapping.sdk.IServerApi"
        private const val CODE_GET_BLACKLIST = 35
        private const val CODE_SET_BLACKLISTED = 36
    }
}
