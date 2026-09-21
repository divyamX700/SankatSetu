package com.sankatsetu.app.payments

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Places a USSD/IVR call for `*99#` (NPCI's USSD-based UPI, works on any
 * phone with no internet, see docs/PRD.md §F3) and UPI 123Pay's IVR
 * numbers, via [Intent.ACTION_CALL] — placed immediately, requiring
 * `CALL_PHONE`, exactly matching Flowpay's own `CallManager.handleUSSDCall`
 * (Apache 2.0; ACTION_CALL, `Uri.encode()`'d `tel:` URI, no separate
 * confirmation step). This app's own confirmation UI (see `PayScreen.kt`'s
 * `ScanToPayConfirmDialog`, or a direct tap on the plain USSD/123Pay
 * buttons) is the one deliberate action a payment needs — the same trust
 * model any UPI app's own "Pay" button already uses.
 *
 * This was previously [Intent.ACTION_DIAL] specifically to avoid
 * `CALL_PHONE` — deliberately, per this file's own prior doc comment,
 * reasoning that a real financial action should always need the account
 * holder's own final tap in the system dialer. That reasoning didn't
 * survive contact with a real device: `ACTION_DIAL` opens the dialer's own
 * numeric keypad UI to preview the string before calling, and a VPA (which
 * has letters and an `@`) gets silently mangled by that UI's keypad
 * letter-to-digit mapping (the old T9/vanity-number convention) before it
 * ever reaches the network — confirmed live, dialing a real scanned VPA
 * produced a real carrier error ("not a valid UPI ID") from a corrupted
 * string, even though the VPA was scanned and displayed correctly right up
 * until the dial. `ACTION_CALL` hands the string straight to the telephony
 * stack with no keypad UI in between, so it isn't subject to that
 * mangling — this is *why* Flowpay's own real, shipped app uses it despite
 * the extra permission and despite giving up the dialer's own last-tap
 * step. See docs/adr/0025-qr-scan-to-pay.md's third update for the full
 * story, including the live test that found the `ACTION_DIAL` bug.
 */
object UssdDialer {
    /** [ussdCode] like `*99#` or a fully-built `*99*1*3*vpa*amount*remarks#` string; `#`/`*`/`@` are percent-encoded as required for a `tel:` URI. */
    fun openDialer(context: Context, ussdCode: String) {
        val encoded = Uri.encode(ussdCode)
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
