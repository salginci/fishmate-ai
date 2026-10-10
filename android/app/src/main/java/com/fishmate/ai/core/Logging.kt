package com.fishmate.ai.core

import android.util.Log
import com.fishmate.ai.BuildConfig

/**
 * Thin logging facade with redaction.
 *
 * Rules mirrored from the backend (docs/SECURITY.md): never log credentials,
 * never log raw media, and coarsen coordinates. A fishing spot is private data
 * and logcat is readable by anyone with the device plugged in.
 */
object Log2 {
    private const val MAX_TAG = 23 // Android truncates longer tags

    private val sensitive = Regex(
        "(?i)(token|api[_-]?key|secret|password|authorization|access_token)" +
            "(\"?\\s*[:=]\\s*\"?)([^\"\\s,}]+)",
    )

    fun redact(message: String): String =
        sensitive.replace(message) { "${it.groupValues[1]}${it.groupValues[2]}«redacted»" }

    /**
     * Coarsen a position to roughly a kilometre before it reaches a log.
     *
     * Formatted with [java.util.Locale.ROOT] on purpose: in a comma-decimal
     * locale such as Turkish, the default locale would render this as
     * "38,46,27,09" and the lat/lon pair would be unreadable.
     */
    fun coords(lat: Double?, lon: Double?): String =
        if (lat == null || lon == null) "-"
        else String.format(java.util.Locale.ROOT, "%.2f,%.2f", lat, lon)

    private fun tag(raw: String) = if (raw.length <= MAX_TAG) raw else raw.take(MAX_TAG)

    fun d(t: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag(t), redact(message))
    }

    fun i(t: String, message: String) {
        Log.i(tag(t), redact(message))
    }

    fun w(t: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag(t), redact(message), error) else Log.w(tag(t), redact(message))
    }

    fun e(t: String, message: String, error: Throwable? = null) {
        // Crash-reporting integration point: forward to Crashlytics/Sentry here.
        if (error != null) Log.e(tag(t), redact(message), error) else Log.e(tag(t), redact(message))
    }
}
