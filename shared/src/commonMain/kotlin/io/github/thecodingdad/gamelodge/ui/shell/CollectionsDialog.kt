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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.CollectionEntity
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.resources.*

/** Toggle an entry's membership in collections, or create a new one. */
@Composable
fun CollectionsDialog(
    entry: LibraryEntry?,
    collections: List<Pair<CollectionEntity, List<String>>>,
    onToggle: (collectionId: String, member: Boolean) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalKeyboardController.current
    val newTitle = stringResource(Res.string.collection_new_name)
    Modal(visible = entry != null, onDismiss = onDismiss, title = stringResource(Res.string.collections_title), maxWidth = 520.dp) {
        val e = entry ?: return@Modal
        Column(
            Modifier.weight(1f, fill = false).popupHeightIn(380.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            collections.forEach { (collection, members) ->
                val member = e.item.id in members
                LauncherButton(
                    collection.name,
                    onClick = { onToggle(collection.id, !member) },
                    icon = if (member) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LauncherButton(
                stringResource(Res.string.collection_new), icon = Icons.Filled.Add, primary = collections.isEmpty(),
                onClick = { keyboard.open(newTitle, "") { name -> if (name.isNotBlank()) onCreate(name.trim()) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
