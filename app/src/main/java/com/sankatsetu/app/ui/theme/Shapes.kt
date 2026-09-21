package com.sankatsetu.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * A ledger's rows are ruled rectangles, not floating rounded cards — this
 * scale is deliberately tight, almost square, the opposite move from the
 * prior "precision instrument" pass's already-tight-but-still-rounded
 * cards (docs/adr/0018). Rounding survives only where Material's own
 * platform conventions need it (dialogs, menus) or where a little softness
 * reads as a deliberate seal/stamp shape rather than indecision. See
 * docs/adr/0019-ledger-register-redesign.md.
 */
val SankatSetuShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),   // status tags, small badges
    small = RoundedCornerShape(5.dp),        // buttons, compact controls
    medium = RoundedCornerShape(3.dp),       // ledger rows — almost square, ruled not rounded
    large = RoundedCornerShape(6.dp),        // the primary "I'm Safe" stamped action, highlighted rows
    extraLarge = RoundedCornerShape(16.dp)   // sheets, dialogs — Material platform convention, not the ledger's own vocabulary
)

/** Fully rounded — reserved for genuinely circular glyphs (StampMark). Not part of [Shapes] since Material doesn't have a "pill" slot; the ledger world itself has no pill-shaped chrome, unlike the prior pass. */
val PillShape = RoundedCornerShape(percent = 50)
