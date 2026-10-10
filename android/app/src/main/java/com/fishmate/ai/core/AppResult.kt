package com.fishmate.ai.core

/**
 * Result type carrying a *user-presentable* failure.
 *
 * The backend returns `{error: {code, message}}`; [AppError] preserves the
 * machine code so the UI can localize, and the server message as a fallback.
 * Nothing in the UI layer ever sees an exception or a stack trace.
 */
sealed interface AppResult<out T> {
    data class Ok<out T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): AppError? = (this as? Err)?.error
}

data class AppError(
    val code: String,
    val message: String,
    /** True when retrying later is plausible (offline, upstream hiccup). */
    val retryable: Boolean = false,
    val cause: Throwable? = null,
) {
    companion object {
        const val CODE_OFFLINE = "offline"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_UNAUTHORIZED = "unauthorized"
        const val CODE_UNKNOWN = "unknown"

        fun offline() = AppError(
            CODE_OFFLINE,
            "You appear to be offline. Your data is saved on this device.",
            retryable = true,
        )

        fun timeout() = AppError(
            CODE_TIMEOUT,
            "That took too long. Check your connection and try again.",
            retryable = true,
        )

        fun unauthorized() = AppError(CODE_UNAUTHORIZED, "Please sign in again.")

        fun unknown(cause: Throwable? = null) = AppError(
            CODE_UNKNOWN,
            "Something went wrong. Please try again.",
            retryable = true,
            cause = cause,
        )
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.onOk(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Ok) action(value)
    return this
}

inline fun <T> AppResult<T>.onErr(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Err) action(error)
    return this
}
