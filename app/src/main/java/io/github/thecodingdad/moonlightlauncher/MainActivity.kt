package io.github.thecodingdad.moonlightlauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.PcView
import com.limelight.ProfilesActivity
import com.limelight.preferences.StreamSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                    Phase0Screen()
                }
            }
        }
    }
}

/** Temporary entry screen: proves Artemis runs inside our APK. Replaced in phase 2. */
@Composable
private fun Phase0Screen() {
    val context = LocalContext.current
    val initialFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.launcher_name), style = MaterialTheme.typography.displaySmall)
        Text(
            stringResource(R.string.phase0_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            EntryButton(R.string.action_hosts, Modifier.focusRequester(initialFocus)) {
                context.startActivity(Intent(context, PcView::class.java))
            }
            EntryButton(R.string.action_stream_settings) {
                context.startActivity(Intent(context, StreamSettings::class.java))
            }
            EntryButton(R.string.action_profiles) {
                context.startActivity(Intent(context, ProfilesActivity::class.java))
            }
        }
    }

    LaunchedEffect(Unit) { initialFocus.requestFocus() }
}

@Composable
private fun EntryButton(@StringRes label: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .size(width = 240.dp, height = 72.dp)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}
