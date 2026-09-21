package com.sankatsetu.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Spring presets translated from Apple's WWDC18 "damping ratio + response"
 * model into Compose's "damping ratio + stiffness" model (see
 * docs/adr/0018-ui-revamp.md's motion research) — kept to exactly two,
 * matching the research's own guidance: bouncy/expressive motion only for
 * positive confirmations (message delivered, peer connected, IOU sent),
 * calmer motion everywhere else so a crisis app never feels frivolous.
 */
object SankatSetuMotion {
    /** Tight, no-overshoot — button press feedback, anything the person is actively touching right now. */
    val PressSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)

    /** A little life to it — list/card entrances, confirmations. Never used on a screen that's mid-crisis-reading (e.g. an Assistant answer just appearing stays calmer, see EntrySpring). */
    val ConfirmSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** Calm entrance — informational content appearing (answers, messages, list rows). */
    val EntrySpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
}

/**
 * Apple-style press feedback: a subtle scale-down while held, springing
 * back on release, plus a matching haptic tick — applied via
 * `Modifier.pressScale(interactionSource)` on anything clickable that
 * deserves to feel alive (primary buttons, cards, list rows). Distinct from
 * `combinedClickable`'s own ripple, which stays for the discoverability
 * signal; this adds the physical "I felt that" Apple's HIG leans on.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, scaleDown: Float = 0.96f): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = SankatSetuMotion.PressSpring,
        label = "pressScale"
    )
    LaunchedEffect(isPressed) {
        if (isPressed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A moving highlight sweep for loading placeholders — used sparingly (the Assistant's "thinking" state), not as generic chrome. */
@Composable
fun rememberShimmerProgress(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1200), repeatMode = RepeatMode.Restart),
        label = "shimmerProgress"
    )
    return progress
}
