package io.github.thecodingdad.gamelodge.ha.mqtt

import androidx.compose.ui.graphics.ImageBitmap

/** JPEG of an encoded image (any format the platform decodes), fit into [maxSize] px; null if it can't be read. */
expect fun jpegOf(bytes: ByteArray, maxSize: Int): ByteArray?

/** JPEG of a decoded image, fit into [maxSize] px. */
expect fun jpegOf(image: ImageBitmap, maxSize: Int): ByteArray?
