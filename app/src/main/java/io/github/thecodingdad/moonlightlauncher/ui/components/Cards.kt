package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/**
 * Requests focus once if this element is the one the user left from. Used so that
 * returning from the detail screen lands exactly where the user was.
 */
@Composable
fun Modifier.restoreFocusIf(shouldFocus: Boolean): Modifier {
    val requester = remember { FocusRequester() }
    val target by androidx.compose.runtime.rememberUpdatedState(shouldFocus)
    // Only when the element appears (screen opened, filter switched, back from details) or the
    // user picks up the controller. Not whenever it becomes the remembered element: during fast
    // navigation late requests of two elements would keep stealing focus from each other.
    val controllerMode = isControllerMode()
    LaunchedEffect(controllerMode) {
        // The element the user just touched wins over the remembered one.
        if (controllerMode && target && !TouchFocus.justRestored()) runCatching { requester.requestFocus() }
    }
    return this.focusRequester(requester)
}

/** Portrait game cover with source badges. */
@Composable
fun CoverCard(
    entry: LibraryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = LauncherTheme.dimens.coverWidth,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    showTitle: Boolean = false,
    animated: Boolean = false,
) {
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) { focused ->
            ArtworkImage(entry.coverModel(animated && focused), entry.item.title, Modifier.fillMaxSize())
            SourceBadges(entry, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (entry.item.favorite) {
                Icon(
                    Icons.Filled.Favorite, null, tint = Color(0xFFFF5C8A),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp),
                )
            }
        }
        if (showTitle) {
            Text(
                entry.item.title,
                style = MaterialTheme.typography.labelMedium,
                color = LauncherTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun SourceBadges(entry: LibraryEntry, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (entry.localTarget != null) Badge(Icons.Filled.PhoneAndroid)
        if (entry.remoteTargets.isNotEmpty()) Badge(Icons.Filled.Cloud)
    }
}

@Composable
private fun Badge(icon: ImageVector) {
    Box(
        Modifier.size(24.dp).background(Color(0xAA000000), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** Square tile for apps: Android icon for local apps, host artwork for remote ones. */
@Composable
fun AppTile(
    entry: LibraryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
) {
    // Narrow screens: smaller tiles so four fit next to each other.
    val size = if (LocalCompactWidth.current) LauncherTheme.dimens.iconTile * 0.6f else LauncherTheme.dimens.iconTile
    val colors = LauncherTheme.colors
    Column(modifier.width(size + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            modifier = Modifier.size(size),
            color = colors.surfaceRaised,
            focusScale = 1.1f,
        ) {
            val pkg = entry.localTarget?.packageName
            if (pkg != null) {
                AppIcon(pkg, Modifier.align(Alignment.Center).size(size * 0.62f))
            } else {
                ArtworkImage(entry.coverModel(), entry.item.title, Modifier.fillMaxSize(), showTitleOnPlaceholder = true)
            }
            // In the apps grid the section already says where an app lives; badges only
            // help when an app is available both locally and on a host.
            if (entry.localTarget != null && entry.remoteTargets.isNotEmpty()) {
                SourceBadges(entry, Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
        Text(
            entry.item.title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Large landscape card (resume card, carousel highlight). */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.PlayArrow,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LauncherTheme.colors
    FocusSurface(onClick = onClick, onFocused = onFocused, onLongClick = onLongClick, modifier = modifier, focusScale = 1.04f) {
        ArtworkImage(image, title, Modifier.fillMaxSize(), showTitleOnPlaceholder = false)
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = colors.onAccent)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
            }
        }
    }
}

/** Shape helper used by several screens. */
val CardShape = RoundedCornerShape(14.dp)
