package com.sankatsetu.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for every color in the app — no inline Color(0x…)
 * anywhere else (Flowpay's own CI-enforced rule, see docs/adr/0002;
 * enforced by convention only, no CI gate wired up yet).
 *
 * Design direction (Post Office Passbook / Ledger Register, see
 * docs/adr/0019-ledger-register-redesign.md): the app reads as a bound
 * ledger, not a chat app or a radio panel — both this file's own prior
 * worlds (docs/adr/0014, docs/adr/0018) are superseded, kept only as git
 * history. A ledger has three inks, not one accent: a pen-ink blue for
 * what you write and interact with, a stamp-ink green for what's
 * confirmed, and a correction-ink red held back for genuine danger only.
 * Identifiers below keep their names from the prior pass (SignalBlue,
 * StatusSafe/Caution/Critical, SurfaceBase…) even though the values and
 * their story changed — renaming every call site across Chat/Pay/
 * Assistant was judged not worth the risk this close to submission; the
 * world lives in the values, the components, and the shapes, not in
 * identifier spelling.
 */
object SankatSetuColors {
    // --- Pen ink: the color you write with. Primary interactive color,
    // focus states, the ruled ledger lines' own faint tint — never status
    // (status has its own inks below), so blue never means "good" or "bad,"
    // only "you can act on this." Modeled on fountain-pen blue-black ink on
    // paper, not a generic app/SaaS blue.
    val SignalBlue = Color(0xFF464FA8)       // light mode: ink on paper
    val SignalBlueDark = Color(0xFF9AA3E8)   // dark mode: ink catching lamplight on night paper

    // --- Status inks: exactly three meanings, each colorblind-legible via
    // a paired glyph/mark + word, never color alone. Stamp-pad ink comes in
    // a handful of standard colors and green is one of them (a "VERIFIED"/
    // "PAID" stamp is a real convention, not an invented one), so the
    // safety-tested green/amber/red vocabulary from prior passes survives
    // unchanged in meaning — only its rendering (a drawn stamp mark, a
    // torn-counterfoil edge) changed. See StampMark.kt / CounterfoilEdge.kt.
    val StatusSafe = Color(0xFF2E9E5B)      // stamp-ink green: delivered, ready, settled, all-clear
    val StatusCaution = Color(0xFFCE9430)   // aged-carbon-copy amber: connecting, pending, handle-soon
    val StatusCritical = Color(0xFFD1483C)  // correction-ink red: genuine danger only — never decorative

    // --- Neutral surface stack: "night paper" — dark is the operating
    // default (see Theme.kt for why), rendered warm-neutral rather than
    // cool-black, since a ledger page is paper, not glass. Named by
    // elevation, not literal shade.
    val SurfaceBase = Color(0xFF121110)         // window background — the page
    val SurfaceRaised = Color(0xFF1C1A17)       // ruled rows, the composer sheet
    val SurfaceOverlay = Color(0xFF262320)      // dialogs, the nav bar
    val SurfaceOverlayHigh = Color(0xFF302C28)  // popovers, menus
    val HairlineOnDark = Color(0x40C9B98A)      // a dim khaki rule — the ledger's own printed line, not plain white-alpha

    val InkOnDark = Color(0xFFF1EDE3)           // primary text — cream, not stark white, matching the warm page
    val InkMutedOnDark = Color(0xFFA79E8E)      // secondary text / captions on dark

    // --- Light theme mirror: real ledger paper — a restrained manila/buff
    // tone, not bright white and deliberately not a cream-and-serif
    // "vintage" cliché (this pass keeps type to plain grotesk/mono
    // throughout, per Operate-mode convention — the paper tone is the only
    // material cue, not paired with display serif or italic accents).
    val SurfaceBaseLight = Color(0xFFF3EEE1)
    val SurfaceRaisedLight = Color(0xFFFBF8F0)
    val SurfaceOverlayLight = Color(0xFFFFFFFF)
    val SurfaceOverlayHighLight = Color(0xFFE9E2D0)
    val HairlineOnLight = Color(0x466B5A2E)     // printed ledger rule on paper

    val InkOnLight = Color(0xFF231F18)
    val InkMutedOnLight = Color(0xFF6E6353)

    // --- A peer with no live link, a convention users already read
    // correctly (a dead link isn't alarming) — now literally "an
    // unfranked, blank ledger line." The read-receipt tick used to have
    // its own ReadBlue here, but on an outgoing bubble now filled with the
    // pen-ink primary color, a same-hue tick was invisible — removed in
    // favor of reusing StatusSafe (the stamp-ink green), see
    // ChatScreen.kt's MessageStatusGlyph.
    val OfflineGray = Color(0xFF7A7264)
}
