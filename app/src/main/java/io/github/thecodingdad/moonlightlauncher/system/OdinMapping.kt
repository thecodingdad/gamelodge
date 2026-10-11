package io.github.thecodingdad.moonlightlauncher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    companion object {
        private const val PACKAGE = "com.odin.mapping"
        private const val ACTION = "com.ro.mapping.action.MAPPING_SERVICE"
        private const val DESCRIPTOR = "com.ro.mapping.sdk.IServerApi"
        private const val CODE_GET_BLACKLIST = 35
        private const val CODE_SET_BLACKLISTED = 36
    }
}
