package com.sankatsetu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sankatsetu.app.ui.theme.ConsoleReadoutStyle

private val TagShape = RoundedCornerShape(2.dp)

/**
 * A secondary status vocabulary for a compact inline word where a full
 * [StampMark] would be too heavy (e.g. a "PENDING"/"CONNECTING" caption
 * next to something already carrying its own mark) — a tinted rectangular
 * tag, monospace uppercase word, never color alone. Deliberately not a
 * rounded pill: the ledger world has no pill-shaped chrome. See
 * docs/adr/0019-ledger-register-redesign.md.
 */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ConsoleReadoutStyle,
        color = color,
        modifier = modifier
            .background(color.copy(alpha = 0.14f), TagShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
