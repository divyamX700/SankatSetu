package com.sankatsetu.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.sankatsetu.app.ui.theme.ConsoleReadoutStyle

/**
 * A drawn ink-stamp seal — a double ring around a center dot, deliberately
 * imperfect (a slight rotation seeded from [seed] so the same status
 * always stamps the same way, never a fresh random wobble on every
 * recomposition) — in place of a flat color-fill pill for a
 * confirmed/settled/ready state. Paired with the status word in the
 * existing monospace instrument register, never color alone. This is
 * drawn UI per craft-floor's ban on emoji/Unicode standing in for icons.
 * See docs/adr/0019-ledger-register-redesign.md.
 */
@Composable
fun StampMark(text: String, color: Color, modifier: Modifier = Modifier, seed: Int = text.hashCode()) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val jitterDeg = ((seed % 7) - 3).toFloat()
        Canvas(Modifier.size(14.dp)) {
            rotate(jitterDeg) {
                val ringWidth = 1.4.dp.toPx()
                drawCircle(color = color, radius = size.minDimension / 2f - ringWidth, style = Stroke(width = ringWidth))
                drawCircle(color = color, radius = size.minDimension / 2f - ringWidth * 3.2f, style = Stroke(width = ringWidth * 0.7f))
                drawCircle(color = color, radius = ringWidth * 0.9f, center = Offset(size.width / 2f, size.height / 2f))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = ConsoleReadoutStyle, color = color)
    }
}
