package io.github.thecodingdad.gamelodge.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.stream.ConnectionIssue
import io.github.thecodingdad.gamelodge.stream.ConnectionWarning
import org.jetbrains.compose.resources.stringResource

/** Small notice over the stream: a warning sign and what is wrong with the connection. */
@Composable
fun ConnectionWarningPill(warning: ConnectionWarning, modifier: Modifier = Modifier) {
    val text = when (warning.issue) {
        ConnectionIssue.FRAME_LOSS -> stringResource(Res.string.conn_warn_frame_loss)
        ConnectionIssue.AUDIO_DROPS -> stringResource(Res.string.conn_warn_audio)
        ConnectionIssue.JITTER -> stringResource(Res.string.conn_warn_jitter)
        ConnectionIssue.LATENCY -> stringResource(Res.string.conn_warn_latency, warning.rttMs)
    }
    // Fixed colours: it sits on the game's picture, not on the launcher's theme.
    Row(
        modifier
            .background(Color(0xCC111318), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}
