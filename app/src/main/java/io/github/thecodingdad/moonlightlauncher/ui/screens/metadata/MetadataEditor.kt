package io.github.thecodingdad.moonlightlauncher.ui.screens.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
import io.github.thecodingdad.moonlightlauncher.data.db.MetaField
import io.github.thecodingdad.moonlightlauncher.metadata.AgeRatings
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.TextKind
import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the editor rows edit; [field] = protected against automatic updates when edited. */
private enum class EditRow(val field: MetaField?) {
    TITLE(MetaField.TITLE), DESCRIPTION(MetaField.DESCRIPTION), GENRES(MetaField.GENRES), DEVELOPER(MetaField.DEVELOPER),
    PUBLISHER(MetaField.PUBLISHER), SERIES(MetaField.SERIES), FEATURES(MetaField.FEATURES), RELEASE(MetaField.RELEASE),
    RATING(MetaField.RATING), AGE_RATING(MetaField.AGE_RATING), PLAYTIME(null), LAST_PLAYED(null),
}

/**
 * Edit every metadata field of one game by hand. Edited fields keep their value when the
 * metadata is matched again; each can be reset to the IGDB value.
 */
@Composable
fun MetadataEditorDialog(
    entry: LibraryEntry?,
    library: List<LibraryEntry>,
    onPlatform: (LibraryEntry) -> Unit,
    onDismiss: () -> Unit,
    onFix: (LibraryEntry) -> Unit = {},
    onArtwork: (LibraryEntry) -> Unit = {},
) {
    val service = LocalContext.current.container.metadata
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EditRow?>(null) }
    // Everything changed here (fields, match, artwork, platform) is a draft: "Save" keeps it,
    // "Cancel" (also B / back) restores the game as it was when the editor opened.
    val appScope = LocalContext.current.container.scope
    val itemId = entry?.item?.id
    androidx.compose.runtime.LaunchedEffect(itemId) { if (itemId != null) service.beginEdit(itemId) }
    val cancel: () -> Unit = {
        appScope.launch { service.cancelEdit() }
        onDismiss()
    }
    val save: () -> Unit = {
        appScope.launch { service.commitEdit() }
        onDismiss()
    }
    Modal(visible = entry != null, onDismiss = cancel, title = stringResource(R.string.meta_edit_title), maxWidth = 820.dp, modifier = Modifier.fillMaxHeight(), closeButton = false) {
        val e = entry ?: return@Modal
        val item = e.item
        val manual = item.manual
        fun edited(row: EditRow) = row.field != null && row.field in manual
        val editedLabel = stringResource(R.string.meta_edited)
        val none = stringResource(R.string.meta_none)
        // The rows scroll; "Done" stays at the bottom, always in view.
        Column(Modifier.weight(1f).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.meta_edit_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            // New IGDB match and other artwork (formerly buttons on the detail page).
            ButtonRow(spacing = 10.dp) {
                // Only with a source for IGDB / SteamGridDB; artwork stays (own and local images).
                if (service.isConfigured) LauncherButton(stringResource(R.string.metadata_fix_title), onClick = { onFix(e) }, icon = Icons.Filled.Search)
                LauncherButton(stringResource(R.string.artwork_title), onClick = { onArtwork(e) }, icon = Icons.Filled.Image)
            }
            @Composable
            fun Row(row: EditRow, label: Int, value: String?, first: Boolean = false) = NavRow(
                stringResource(label), { editing = row },
                subtitle = value?.takeIf { it.isNotBlank() } ?: none,
                trailing = if (edited(row)) editedLabel else null,
                initialFocus = first,
            )
            SectionHeader(stringResource(R.string.filter_section_game))
            Row(EditRow.TITLE, R.string.meta_name, item.title)
            NavRow(stringResource(R.string.filter_field_platform), { onPlatform(e) }, subtitle = item.platform ?: none)
            Row(EditRow.DESCRIPTION, R.string.meta_description, item.description?.take(120))
            SectionHeader(stringResource(R.string.filter_section_metadata))
            Row(EditRow.GENRES, R.string.filter_field_genre, item.genres)
            Row(EditRow.DEVELOPER, R.string.filter_field_developer, item.developer)
            Row(EditRow.PUBLISHER, R.string.filter_field_publisher, item.publisher)
            Row(EditRow.SERIES, R.string.filter_field_series, item.series)
            Row(EditRow.FEATURES, R.string.filter_field_feature, item.features)
            Row(EditRow.RELEASE, R.string.meta_release, releaseText(item))
            Row(EditRow.RATING, R.string.filter_field_rating, item.rating?.let { "%.0f".format(it) })
            Row(EditRow.AGE_RATING, R.string.filter_field_age_rating, AgeRatings.display(item.ageRatings))
            SectionHeader(stringResource(R.string.filter_section_activity))
            Row(EditRow.PLAYTIME, R.string.filter_field_playtime, stringResource(R.string.meta_hours, "%.1f".format(item.playtimeSeconds / 3600.0)))
            Row(EditRow.LAST_PLAYED, R.string.filter_field_last_played, item.lastPlayedAt?.let { dateFormat().format(Date(it)) })
        }
        ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 16.dp)) {
            LauncherButton(stringResource(R.string.action_save), onClick = save, primary = true)
            LauncherButton(stringResource(R.string.action_cancel), onClick = cancel)
        }
    }

    val e = entry
    FieldDialog(
        row = editing.takeIf { e != null },
        item = e?.item,
        library = library,
        onSave = { row, transform -> e?.let { scope.launch { service.edit(it.item.id, row.field, transform) } } },
        onReset = { row -> e?.let { entry -> row.field?.let { f -> scope.launch { service.resetField(entry.item.id, f) } } } },
        onDismiss = { editing = null },
    )
}

/** Edits one field: text via the keyboard, lists as chips, age rating as a choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldDialog(
    row: EditRow?,
    item: LibraryItemEntity?,
    library: List<LibraryEntry>,
    onSave: (EditRow, (LibraryItemEntity) -> LibraryItemEntity) -> Unit,
    onReset: (EditRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalKeyboardController.current
    Modal(visible = row != null && item != null, onDismiss = onDismiss, title = row?.let { rowTitle(it) }, maxWidth = 760.dp, closeButton = false) {
        val r = row ?: return@Modal
        val it0 = item ?: return@Modal
        val title = rowTitle(r)
        val invalid = stringResource(R.string.meta_invalid)
        var error by remember(r) { mutableStateOf<String?>(null) }
        Column(Modifier.heightIn(max = 600.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (r) {
                EditRow.GENRES, EditRow.FEATURES -> ChipEditor(
                    current = splitList(if (r == EditRow.GENRES) it0.genres else it0.features),
                    suggestions = library.flatMap { splitList(if (r == EditRow.GENRES) it.item.genres else it.item.features) },
                    multi = true, title = title,
                ) { values ->
                    val joined = values.joinToString(", ").ifEmpty { null }
                    onSave(r) { if (r == EditRow.GENRES) it.copy(genres = joined) else it.copy(features = joined) }
                }
                EditRow.DEVELOPER, EditRow.PUBLISHER, EditRow.SERIES -> {
                    fun of(i: LibraryItemEntity) = when (r) { EditRow.DEVELOPER -> i.developer; EditRow.PUBLISHER -> i.publisher; else -> i.series }
                    ChipEditor(listOfNotNull(of(it0)), library.mapNotNull { of(it.item) }, multi = false, title = title) { values ->
                        val v = values.firstOrNull()
                        onSave(r) { i -> when (r) { EditRow.DEVELOPER -> i.copy(developer = v); EditRow.PUBLISHER -> i.copy(publisher = v); else -> i.copy(series = v) } }
                    }
                }
                EditRow.AGE_RATING -> {
                    val system = AgeRatings.primarySystem()
                    val all = AgeRatings.decode(it0.ageRatings)
                    ChoiceRow(
                        system, listOf<String?>(null) + AgeRatings.VALUES.getValue(system), all[system],
                        label = { v -> v?.let { "$system $it" } ?: stringResource(R.string.meta_none) },
                        onSelect = { v ->
                            val updated = if (v == null) all - system else all + (system to v)
                            onSave(r) { it.copy(ageRatings = AgeRatings.encode(updated)) }
                        },
                        initialFocus = true,
                    )
                }
                else -> {
                    val current = when (r) {
                        EditRow.TITLE -> it0.title
                        EditRow.DESCRIPTION -> it0.description.orEmpty()
                        EditRow.RELEASE -> releaseText(it0).orEmpty()
                        EditRow.RATING -> it0.rating?.let { "%.0f".format(it) }.orEmpty()
                        EditRow.PLAYTIME -> "%.1f".format(Locale.ROOT, it0.playtimeSeconds / 3600.0)
                        EditRow.LAST_PLAYED -> it0.lastPlayedAt?.let { dateFormat().format(Date(it)) }.orEmpty()
                        else -> ""
                    }
                    Text(current.ifBlank { stringResource(R.string.meta_none) }, style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                    rowHint(r)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted) }
                    error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    val numeric = r == EditRow.RATING || r == EditRow.PLAYTIME
                    LauncherButton(stringResource(R.string.meta_change), icon = Icons.Filled.Edit, primary = true, onClick = {
                        keyboard.open(title, current, if (numeric) TextKind.NUMBER else TextKind.TEXT) { text ->
                            val t = text.trim()
                            val transform: ((LibraryItemEntity) -> LibraryItemEntity)? = when (r) {
                                EditRow.TITLE -> t.takeIf { it.isNotEmpty() }?.let { v -> { i: LibraryItemEntity -> i.copy(title = v) } }
                                EditRow.DESCRIPTION -> { i -> i.copy(description = t.ifEmpty { null }) }
                                EditRow.RELEASE -> if (t.isEmpty()) { i -> i.copy(releaseDate = null, releaseYear = null) }
                                else parseDate(t)?.let { (millis, year) -> { i: LibraryItemEntity -> i.copy(releaseDate = millis, releaseYear = year) } }
                                EditRow.RATING -> if (t.isEmpty()) { i -> i.copy(rating = null) }
                                else t.replace(',', '.').toFloatOrNull()?.takeIf { it in 0f..100f }?.let { v -> { i: LibraryItemEntity -> i.copy(rating = v) } }
                                EditRow.PLAYTIME -> t.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
                                    ?.let { h -> { i: LibraryItemEntity -> i.copy(playtimeSeconds = (h * 3600).toLong()) } }
                                EditRow.LAST_PLAYED -> if (t.isEmpty()) { i -> i.copy(lastPlayedAt = null) }
                                else parseDate(t)?.let { (millis, _) -> { i: LibraryItemEntity -> i.copy(lastPlayedAt = millis) } }
                                else -> null
                            }
                            if (transform == null) error = invalid else { error = null; onSave(r, transform) }
                        }
                    })
                }
            }
            ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
                LauncherButton(stringResource(R.string.action_done), onClick = onDismiss)
                if (r.field != null && r.field in it0.manual) {
                    LauncherButton(
                        stringResource(if (r == EditRow.TITLE) R.string.meta_reset_title else R.string.meta_reset),
                        icon = Icons.Filled.Restore, onClick = { onReset(r) },
                    )
                }
            }
        }
    }
}

/** Chips of the current values plus suggestions from the library; "new" adds via the keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipEditor(current: List<String>, suggestions: List<String>, multi: Boolean, title: String, onChange: (List<String>) -> Unit) {
    val keyboard = LocalKeyboardController.current
    val options = (current + suggestions.distinct().sortedBy { it.lowercase() }).distinct()
    Text(
        stringResource(if (multi) R.string.meta_chips_multi else R.string.meta_chips_single),
        style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
    )
    LauncherButton(stringResource(R.string.meta_new_value), icon = Icons.Filled.Add, onClick = {
        keyboard.open(title, "") { text ->
            val v = text.trim()
            if (v.isNotEmpty()) onChange(if (multi) (current + v).distinct() else listOf(v))
        }
    })
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { v ->
            val selected = v in current
            Chip(v, selected = selected, onClick = {
                onChange(
                    when {
                        selected -> current - v
                        multi -> current + v
                        else -> listOf(v)
                    },
                )
            })
        }
    }
}

@Composable
private fun rowTitle(row: EditRow): String = stringResource(
    when (row) {
        EditRow.TITLE -> R.string.meta_name
        EditRow.DESCRIPTION -> R.string.meta_description
        EditRow.GENRES -> R.string.filter_field_genre
        EditRow.DEVELOPER -> R.string.filter_field_developer
        EditRow.PUBLISHER -> R.string.filter_field_publisher
        EditRow.SERIES -> R.string.filter_field_series
        EditRow.FEATURES -> R.string.filter_field_feature
        EditRow.RELEASE -> R.string.meta_release
        EditRow.RATING -> R.string.filter_field_rating
        EditRow.AGE_RATING -> R.string.filter_field_age_rating
        EditRow.PLAYTIME -> R.string.filter_field_playtime
        EditRow.LAST_PLAYED -> R.string.filter_field_last_played
    },
)

private fun rowHint(row: EditRow): Int? = when (row) {
    EditRow.RELEASE, EditRow.LAST_PLAYED -> R.string.meta_date_hint
    EditRow.RATING -> R.string.meta_rating_hint
    EditRow.PLAYTIME -> R.string.meta_playtime_hint
    else -> null
}

private fun splitList(raw: String?): List<String> = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

private fun dateFormat() = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

/** "03.03.2017" or just the year when only that is known. */
private fun releaseText(item: LibraryItemEntity): String? =
    item.releaseDate?.let { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it)) }
        ?: item.releaseYear?.toString()

/** Accepts "2017", "3.3.2017", "03.03.2017" and "2017-03-03"; returns UTC millis and the year. */
private fun parseDate(text: String): Pair<Long, Int>? {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear() }
    Regex("^(\\d{4})$").find(text)?.let { m ->
        val y = m.groupValues[1].toInt()
        return utc.apply { set(y, Calendar.JANUARY, 1) }.timeInMillis to y
    }
    val parts = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").find(text)?.groupValues?.let { Triple(it[3], it[2], it[1]) }
        ?: Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(text)?.groupValues?.let { Triple(it[1], it[2], it[3]) }
        ?: return null
    val (y, mo, d) = Triple(parts.first.toInt(), parts.second.toInt(), parts.third.toInt())
    if (mo !in 1..12 || d !in 1..31) return null
    return utc.apply { set(y, mo - 1, d) }.timeInMillis to y
}
