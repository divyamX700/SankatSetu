package com.sankatsetu.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A solid border plus a diagonal hazard-stripe corner marker — the SOS
 * surface's own break from the ledger's calm vocabulary, deliberately not
 * [counterfoilEdge]'s soft tear-dash (that means "pending, not yet
 * settled," a different register entirely) and not [StampMark]'s round
 * seal (that means "confirmed, good news"). A continuous rectilinear
 * stripe reads as hazard tape, a real physical convention for "something
 * dangerous is behind this line," which is exactly the one moment in this
 * app's whole vocabulary that should look unlike anything else on the
 * page. See docs/TODO.md's SOS ideation.
 */
fun Modifier.hazardEdge(color: Color, cornerRadius: Dp = 3.dp): Modifier = this
    .drawBehind {
        val strokeWidthPx = 1.6.dp.toPx()
        drawRoundRect(
            color = color,
            style = Stroke(width = strokeWidthPx),
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
        val stripeBandPx = 6.dp.toPx()
        clipRect(left = 0f, top = 0f, right = stripeBandPx, bottom = size.height) {
            val periodPx = 9.dp.toPx()
            val stripeWidthPx = 2.2.dp.toPx()
            var x = -size.height
            while (x < stripeBandPx + size.height) {
                drawLine(
                    color = color,
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = stripeWidthPx
                )
                x += periodPx
            }
        }
    }
    .padding(1.dp)
