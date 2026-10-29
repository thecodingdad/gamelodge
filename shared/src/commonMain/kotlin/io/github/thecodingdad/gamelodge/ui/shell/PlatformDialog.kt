package io.github.thecodingdad.gamelodge.ui.shell

import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.PlatformSource
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

private val commonPlatforms = listOf(
    "PC", "Android", "Nintendo Switch", "PlayStation 5", "PlayStation 4", "Xbox Series X|S", "Xbox One",
    "Wii U", "Wii", "Nintendo 3DS", "PlayStation 3", "PlayStation 2", "Nintendo GameCube", "SNES", "NES",
)

/** Manual platform choice; "automatic" hands the item back to Playnite/IGDB detection. */
@Composable
fun PlatformDialog(
    entry: LibraryEntry?,
    knownPlatforms: List<String>,
    onSelect: (LibraryEntry, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalKeyboardController.current
    val customTitle = stringResource(Res.string.platform_custom)
    Modal(visible = entry != null, onDismiss = onDismiss, title = stringResource(Res.string.platform_title), maxWidth = 560.dp) {
        val e = entry ?: return@Modal
        val source = when (e.item.platformSource) {
            PlatformSource.MANUAL -> Res.string.platform_source_manual
            PlatformSource.PLAYNITE -> Res.string.platform_source_playnite
            PlatformSource.COMPANION -> Res.string.platform_source_companion
            PlatformSource.IGDB -> Res.string.platform_source_igdb
            PlatformSource.SUGGESTED, null -> Res.string.platform_source_suggested
        }
        Text(
            stringResource(Res.string.platform_current, e.item.platform ?: "–", stringResource(source)),
            style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted,
        )
        Column(
            Modifier.weight(1f, fill = false).popupHeightIn(400.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LauncherButton(
                stringResource(Res.string.platform_auto), { onSelect(e, null); onDismiss() }, Modifier.fillMaxWidth(), Icons.Filled.AutoMode,
                primary = e.item.platformSource == PlatformSource.MANUAL,
            )
            (knownPlatforms + commonPlatforms).distinct().forEach { p ->
                val current = p == e.item.platform
                LauncherButton(
                    p, { onSelect(e, p); onDismiss() }, Modifier.fillMaxWidth(),
                    if (current) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                )
            }
            LauncherButton(
                customTitle, {
                    keyboard.open(customTitle, e.item.platform.orEmpty()) { name ->
                        if (name.isNotBlank()) onSelect(e, name.trim())
                        onDismiss()
                    }
                }, Modifier.fillMaxWidth(), Icons.Filled.Edit,
            )
        }
    }
}
