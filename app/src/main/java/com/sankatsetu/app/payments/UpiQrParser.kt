package com.sankatsetu.app.payments

/** One scanned UPI QR code's payload, once validated. See [UpiQrParser]. */
data class UpiQrPayload(
    val vpa: String,
    val payeeName: String,
    /** Rupees, as the QR's own `am` string — empty if the QR didn't fix an amount (the person enters one). */
    val amount: String,
    val transactionNote: String
)

/**
 * Parses a scanned QR payload into a [UpiQrPayload] — pure Kotlin, no
 * Android dependency at all (query-parameter splitting is hand-rolled
 * below rather than via `android.net.Uri`, which throws "not mocked" in
 * this project's plain-JVM unit tests — no Robolectric is set up here, so
 * every other pure domain class in this codebase, e.g.
 * `KnowledgeDocumentParser`/`SosPacket`, keeps the same discipline). Ported
 * from Flowpay's `QRCodeParser` (Apache 2.0) per this project's sourcing
 * policy — see `docs/PRD.md`'s "any open-source repo referenced in this
 * document may be lifted whole or in part" and `docs/adr/0002`. Adapted to
 * this app's naming and to return a plain sealed result instead of
 * Flowpay's Context-dependent `messageFor` extension (this app's UI
 * supplies its own copy at the point it shows a rejection reason).
 *
 * Accepts exactly two shapes, same as Flowpay: a `upi://` URI (the NPCI QR
 * spec) with a structurally valid `pa` (VPA), or a bare VPA string (some
 * merchants print raw-VPA QRs with no wrapper). Everything else is
 * [ParseResult.Invalid] with a reason — this deliberately does NOT
 * regex-fish a `pa=` substring out of arbitrary scanned text, since that
 * would turn any string containing "@" into a payee and walk the person
 * into a payment flow for a QR that was never a payment code at all.
 */
object UpiQrParser {

    // NPCI VPA shape: local part (letters/digits/._-), an @, and an
    // alphanumeric PSP handle starting with a letter.
    private val VPA_REGEX = Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,64}$")
    private val AMOUNT_DECIMAL_REGEX = Regex("^[0-9]+(\\.[0-9]{1,2})?$")

    /** Generic input ceiling for a QR-fixed amount — mirrors Flowpay's own MAX_QR_AMOUNT. */
    private const val MAX_QR_AMOUNT = 100_000.0

    enum class Reason { EMPTY, NOT_A_UPI_QR, MALFORMED, NO_PAYEE_ADDRESS, INVALID_PAYEE_ADDRESS, INVALID_AMOUNT }

    sealed class ParseResult {
        data class Valid(val data: UpiQrPayload) : ParseResult()
        data class Invalid(val reason: Reason) : ParseResult()
    }

    fun parse(qrCode: String): ParseResult {
        val raw = qrCode.trim()
        if (raw.isEmpty()) return ParseResult.Invalid(Reason.EMPTY)

        return when {
            raw.startsWith("upi://", ignoreCase = true) -> parseUpiUri(raw)
            VPA_REGEX.matches(raw) -> ParseResult.Valid(UpiQrPayload(vpa = raw, payeeName = "", amount = "", transactionNote = ""))
            else -> ParseResult.Invalid(Reason.NOT_A_UPI_QR)
        }
    }

    private fun parseUpiUri(raw: String): ParseResult {
        val queryStart = raw.indexOf('?')
        if (queryStart < 0 || queryStart == raw.length - 1) return ParseResult.Invalid(Reason.MALFORMED)
        val params = parseQueryParams(raw.substring(queryStart + 1))

        val vpa = params["pa"].orEmpty().trim()
        if (vpa.isEmpty()) return ParseResult.Invalid(Reason.NO_PAYEE_ADDRESS)
        if (!VPA_REGEX.matches(vpa)) return ParseResult.Invalid(Reason.INVALID_PAYEE_ADDRESS)

        val amountParam = params["am"].orEmpty().trim()
        if (amountParam.isNotEmpty()) {
            val amount = amountParam.toDoubleOrNull()
            if (amount == null || amount <= 0 || amount > MAX_QR_AMOUNT) return ParseResult.Invalid(Reason.INVALID_AMOUNT)
            if (!AMOUNT_DECIMAL_REGEX.matches(amountParam)) return ParseResult.Invalid(Reason.INVALID_AMOUNT)
        }

        val payeeName = params["pn"].orEmpty().trim().replace(Regex("[\\p{Cntrl}]"), "").take(99)
        val note = params["tn"].orEmpty().trim().take(99)

        return ParseResult.Valid(UpiQrPayload(vpa = vpa, payeeName = payeeName, amount = amountParam, transactionNote = note))
    }

    /** Minimal `application/x-www-form-urlencoded` query-string decoder — a scanned QR is plain text, never needs a real URI parser. */
    private fun parseQueryParams(query: String): Map<String, String> =
        query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            runCatching { java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull()
        }.toMap()
}
