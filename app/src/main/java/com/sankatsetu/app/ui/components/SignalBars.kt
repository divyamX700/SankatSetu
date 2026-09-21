package com.sankatsetu.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A four-bar signal-strength glyph, like a radio's own signal meter,
 * replacing a plain colored dot for peer connection state. [filledBars]
 * (0-4) reads as strength, [tint] carries the state's meaning — this is
 * drawn UI per craft-floor's ban on emoji/Unicode standing in for icons,
 * not a system icon-font glyph. See
 * docs/adr/0014-field-radio-design-language.md.
 */
@Composable
fun SignalBars(
    filledBars: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    emptyTint: Color = tint.copy(alpha = 0.25f)
) {
    val clamped = filledBars.coerceIn(0, 4)
    Canvas(modifier = modifier.size(width = 18.dp, height = 14.dp)) {
        val barCount = 4
        val gap = size.width * 0.12f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val barHeightFraction = 0.4f + (i * 0.2f)
            val barHeight = size.height * barHeightFraction
            val x = i * (barWidth + gap)
            val y = size.height - barHeight
            drawRoundRect(
                color = if (i < clamped) tint else emptyTint,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                style = if (i < clamped) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = 1.dp.toPx())
            )
        }
    }
}
