package io.github.thecodingdad.gamelodge.data.library

import io.github.thecodingdad.gamelodge.data.remote.HostApp
import io.github.thecodingdad.gamelodge.data.remote.RemoteApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPlaceholderTest {
    private fun app(id: Int, name: String) = RemoteApp("h1", "Host", HostApp(id, name, null, false))

    @Test
    fun `apollo's stand-in entry is no app list`() {
        assertTrue(isPermissionPlaceholder(listOf(app(114514, "Permission denied - enable List applications for this device in the host's Web UI"))))
        assertTrue(isPermissionPlaceholder(listOf(app(1, "Permission denied"))))
    }

    @Test
    fun `real lists are kept`() {
        assertFalse(isPermissionPlaceholder(listOf(app(1, "Desktop"))))
        assertFalse(isPermissionPlaceholder(listOf(app(114514, "Permission denied"), app(2, "Steam Big Picture"))))
        assertFalse(isPermissionPlaceholder(emptyList()))
    }
}
