package com.fishmate.ai.data.repository

import com.fishmate.ai.core.AppError
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.remote.dto.ErrorEnvelope
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.Response

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Turn a Retrofit call into an [AppResult] with a user-presentable error.
 *
 * Every network failure mode is mapped here once: offline, timeout, the
 * backend's structured error envelope, and the unexpected. Nothing above this
 * function ever handles an exception, which is what keeps the "no stack traces
 * in the UI" rule (docs/ARCHITECTURE.md -> "Error handling") true by
 * construction rather than by discipline.
 *
 * **Cancellation is not a failure mode.** A rejected token clears the session,
 * which tears down the scope every in-flight call was launched in, and the
 * calls that die with it used to arrive here as `IOException: Canceled` and
 * `JobCancellationException` -- reported to the angler as "You appear to be
 * offline" over a working connection, which is exactly what Home then showed.
 * Catching a `CancellationException` also breaks structured concurrency: the
 * coroutine goes on running inside a scope that has already been cancelled.
 * So both shapes of cancellation are rethrown, and the caller that was
 * cancelled simply stops.
 */
suspend fun <T> apiCall(
    tag: String = "api",
    block: suspend () -> Response<T>,
): AppResult<T> = try {
    val response = block()
    if (response.isSuccessful) {
        val body = response.body()
        @Suppress("UNCHECKED_CAST")
        when {
            body != null -> AppResult.Ok(body)
            // 204 No Content on a Unit-returning endpoint.
            response.code() == 204 -> AppResult.Ok(Unit as T)
            else -> AppResult.Err(AppError.unknown())
        }
    } else {
        AppResult.Err(parseError(response.code(), response.errorBody()?.string(), tag))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: SocketTimeoutException) {
    Log2.w(tag, "timeout: ${e.message}")
    AppResult.Err(AppError.timeout())
} catch (e: IOException) {
    // OkHttp reports a cancelled call as a plain IOException, so the message
    // is the only thing distinguishing "the angler walked out of signal" from
    // "we cancelled this ourselves a moment ago".
    if (e.isCanceled()) throw CancellationException("call canceled")
    // Offline is normal on a shoreline; log at debug, not as an error.
    Log2.d(tag, "network unavailable: ${e.message}")
    AppResult.Err(AppError.offline())
} catch (e: Exception) {
    Log2.e(tag, "unexpected failure", e)
    AppResult.Err(AppError.unknown(e))
}

/** OkHttp's own word for a call that was cancelled rather than failing. */
internal fun IOException.isCanceled(): Boolean = message?.equals("Canceled", ignoreCase = true) == true

private fun parseError(code: Int, body: String?, tag: String): AppError {
    val parsed = body?.let {
        runCatching { errorJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull()
    }
    if (parsed != null) {
        Log2.i(tag, "api error code=${parsed.error.code} http=$code")
        return AppError(
            code = parsed.error.code,
            message = parsed.error.message,
            retryable = code >= 500 || parsed.error.code == "upstream_unavailable",
        )
    }
    Log2.w(tag, "unparseable error body http=$code")
    return when (code) {
        401 -> AppError.unauthorized()
        403 -> AppError("forbidden", "You do not have access to that.")
        404 -> AppError("not_found", "That could not be found.")
        429 -> AppError("rate_limited", "Give me a moment and try again.", retryable = true)
        in 500..599 -> AppError(
            "server_error",
            "The service is having trouble. Please try again.",
            retryable = true,
        )
        else -> AppError.unknown()
    }
}
