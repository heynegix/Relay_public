package com.example.relay.pcgateway

/** Leading characters that a spreadsheet interprets as the start of a formula. */
private val CSV_FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')

/**
 * Encodes a single value as one CSV cell for any Gateway export (messages, audit, ...).
 *
 * Two responsibilities are handled together on purpose:
 *  - Neutralise spreadsheet formula injection. A cell beginning with `=`, `+`, `-`, or `@`
 *    is executed as a formula when the export is opened in Excel/Sheets, so it is prefixed
 *    with a single quote to force it back to text.
 *  - Quote/escape cells that contain the CSV delimiter, quotes, or newlines.
 *
 * This logic previously existed as two independent private copies. The audit export was
 * hardened against formula injection while the message export was not, so an attacker-supplied
 * `messageId`/`originDeviceId` (only length-validated on ingest) reached `/api/messages/export.csv`
 * unescaped. Keeping a single implementation prevents that class of divergence from recurring.
 */
internal fun csvSafeCell(value: String): String {
    val guarded = if (value.firstOrNull() in CSV_FORMULA_TRIGGERS) "'$value" else value
    return if (guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${guarded.replace("\"", "\"\"")}\""
    } else {
        guarded
    }
}
