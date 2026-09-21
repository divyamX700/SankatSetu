package com.sankatsetu.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A dashed, notched border standing in for a real receipt book's tear-off
 * counterfoil — the ledger world's way of marking a row as still pending,
 * not yet torn free and settled. Used for an unsettled mesh IOU, never for
 * a real UPI/USSD entry (those get [StampMark] instead, since they're the
 * app's core payment feature and an IOU is deliberately secondary — see
 * docs/adr/0019-ledger-register-redesign.md and the user's own instruction
 * that the IOU voucher stays one entry type, not its own identity.
 */
fun Modifier.counterfoilEdge(color: Color, cornerRadius: Dp = 3.dp): Modifier = this
    .drawBehind {
        val strokeWidthPx = 1.4.dp.toPx()
        drawRoundRect(
            color = color,
            style = Stroke(
                width = strokeWidthPx,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
            ),
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
    }
    .padding(1.dp)
