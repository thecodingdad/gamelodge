package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nayuki.qrcodegen.QrCode

/** Black modules on white, drawn from [text]. */
@Composable
fun QrImage(text: String, modifier: Modifier) {
    val code = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier) {
        val cell = size.minDimension / code.size
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (code.getModule(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** [text] as a QR code on a white card (a quiet zone around it, readable on dark themes too). */
@Composable
fun QrCard(text: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(size / 16)) {
        QrImage(text, Modifier.size(size))
    }
}
