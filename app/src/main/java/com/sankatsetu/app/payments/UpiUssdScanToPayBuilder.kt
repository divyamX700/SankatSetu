package com.sankatsetu.app.payments

/**
 * Validates a scanned VPA and an entered amount before the scan-to-pay flow
 * proceeds — pure Kotlin, unit tested directly, same discipline as
 * [UpiQrParser].
 *
 * This does NOT build a `*99*1*3*<vpa>*<amount>*<remarks>#`-style dial
 * string anymore, even though that format is real and documented (the
 * public NUUP spec at github.com/librefin-in/nuup-specification §1.3). A
 * live device test found no way to get a VPA (which has letters and an
 * `@`) through any Android dial mechanism intact: `Intent.ACTION_DIAL`'s
 * own dial-pad UI mangles it via keypad letter-to-digit mapping before it
 * reaches the network (a real, confirmed "not a valid UPI ID" carrier
 * response on a correctly-scanned VPA); `Intent.ACTION_CALL` — which skips
 * that UI and hands the string straight to the telecom framework — was
 * tried next and failed silently instead (no response at all), consistent
 * with the telecom framework's own number validation rejecting a
 * `@`-containing string before ever placing the call.
 *
 * Tracing Flowpay's own real, shipped `QRScannerActivity.kt` (Apache 2.0)
 * settled it: their actual QR-to-pay code does not embed the VPA into a
 * dial string either. It copies the VPA to the clipboard, then dials the
 * **bare** `*99*1*3#` menu shortcut (Send Money → To VPA, no VPA or amount
 * appended) via `ACTION_CALL`, and the person pastes the VPA and types the
 * amount into the carrier's own live interactive prompt. This project's
 * `PayScreen.kt` now does exactly that — this class exists only to
 * validate the VPA/amount pair before that flow proceeds, matching what
 * the confirmation dialog needs to enable its "Pay" button. See
 * docs/adr/0025-qr-scan-to-pay.md's fourth update for the full story.
 */
object UpiUssdScanToPayBuilder {

    private val WHOLE_OR_DECIMAL_RUPEES_REGEX = Regex("^[0-9]{1,6}(\\.[0-9]{1,2})?$")
    private const val MIN_AMOUNT_RUPEES = 1.0
    private const val MAX_AMOUNT_RUPEES = 100_000.0

    enum class Reason { MISSING_VPA, INVALID_VPA, MISSING_AMOUNT, AMOUNT_NOT_A_NUMBER, AMOUNT_BELOW_MINIMUM, AMOUNT_ABOVE_CAP }

    sealed class Result {
        data object Valid : Result()
        data class Invalid(val reason: Reason) : Result()
    }

    // Same VPA shape UpiQrParser already validated a scanned VPA against —
    // re-checked here too since this also gates a manually corrected/typed
    // VPA, not only an already-validated scanned one.
    private val VPA_REGEX = Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,64}$")

    /** [amountRupees] as a plain decimal string, e.g. "150" or "150.50". */
    fun validate(vpa: String, amountRupees: String): Result {
        val trimmedVpa = vpa.trim()
        if (trimmedVpa.isEmpty()) return Result.Invalid(Reason.MISSING_VPA)
        if (!VPA_REGEX.matches(trimmedVpa)) return Result.Invalid(Reason.INVALID_VPA)

        val trimmedAmount = amountRupees.trim()
        if (trimmedAmount.isEmpty()) return Result.Invalid(Reason.MISSING_AMOUNT)
        if (!WHOLE_OR_DECIMAL_RUPEES_REGEX.matches(trimmedAmount)) return Result.Invalid(Reason.AMOUNT_NOT_A_NUMBER)
        val value = trimmedAmount.toDoubleOrNull() ?: return Result.Invalid(Reason.AMOUNT_NOT_A_NUMBER)
        if (value < MIN_AMOUNT_RUPEES) return Result.Invalid(Reason.AMOUNT_BELOW_MINIMUM)
        if (value > MAX_AMOUNT_RUPEES) return Result.Invalid(Reason.AMOUNT_ABOVE_CAP)

        return Result.Valid
    }
}
