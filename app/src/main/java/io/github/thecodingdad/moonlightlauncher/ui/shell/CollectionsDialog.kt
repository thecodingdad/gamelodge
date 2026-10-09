package io.github.thecodingdad.moonlightlauncher.ui.shell

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.CollectionEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal

/** Toggle an entry's membership in collections, or create a new one. */
@Composable
fun CollectionsDialog(
    entry: LibraryEntry?,
    collections: List<Pair<CollectionEntity, Set<String>>>,
    onToggle: (collectionId: String, member: Boolean) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalKeyboardController.current
    val newTitle = stringResource(R.string.collection_new_name)
    Modal(visible = entry != null, onDismiss = onDismiss, title = stringResource(R.string.collections_title), maxWidth = 520.dp) {
        val e = entry ?: return@Modal
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
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
                stringResource(R.string.collection_new), icon = Icons.Filled.Add, primary = collections.isEmpty(),
                onClick = { keyboard.open(newTitle, "") { name -> if (name.isNotBlank()) onCreate(name.trim()) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
