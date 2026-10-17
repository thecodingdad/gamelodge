package io.github.thecodingdad.gamelodge.data.remote

import android.content.Context
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import io.github.thecodingdad.gamelodge.util.Log
import java.io.File

/** Stores the cover image each host provides for its apps (used when no metadata cover exists). */
class BoxArtCache(context: Context) {
    private val dir = File(context.filesDir, "boxart").apply { mkdirs() }

    fun file(hostUuid: String, appId: Int): File = File(dir, "${hostUuid}_$appId.png")

    /** Blocking; returns the cached file or null if the host has no art. */
    fun fetch(http: NvHTTP, hostUuid: String, app: NvApp): File? {
        val target = file(hostUuid, app.appId)
        if (target.length() > 0) return target
        return runCatching {
            val tmp = File(dir, "${target.name}.tmp")
            http.getBoxArt(app).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 0) tmp.renameTo(target) else tmp.delete()
            target.takeIf { it.exists() }
        }.onFailure { Log.w("Box art for ${app.appName} unavailable", it) }.getOrNull()
    }

    fun deleteForHost(hostUuid: String) {
        dir.listFiles { f -> f.name.startsWith("${hostUuid}_") }?.forEach { it.delete() }
    }
}
