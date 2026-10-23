package com.fishmate.ai.data.remote

import com.fishmate.ai.core.Log2
import java.io.IOException
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the bearer token and a request id that matches the backend's logs. */
class AuthInterceptor(private val authStore: AuthStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Accept", "application/json")
            // Echoed by the backend, so a user-reported problem can be traced
            // to exact server logs.
            .header("x-request-id", UUID.randomUUID().toString().replace("-", "").take(16))

        // Auth endpoints must not carry a stale token.
        val path = original.url.encodedPath
        if (!path.contains("/auth/")) {
            authStore.tokenBlocking()?.let { builder.header("Authorization", "Bearer $it") }
        }
        return chain.proceed(builder.build())
    }
}

/**
 * Drops the stored token when the backend rejects it.
 *
 * The app trusts the saved token at startup instead of validating it, so that
 * a cold start with no signal opens on Home rather than on a sign-in screen it
 * cannot get past. The cost of that choice is this: when the token really is
 * finished, the first call says so, and something has to act on it. Clearing
 * here makes `AuthStore.token` emit null, which is what `RootViewModel` turns
 * into a trip back to sign-in.
 *
 * Auth endpoints are exempt. A 401 from `/auth/login` is a wrong password, and
 * clearing on it would log out whoever is already signed in because somebody
 * mistyped.
 */
class UnauthorizedInterceptor(private val authStore: AuthStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 401 && !chain.request().url.encodedPath.contains("/auth/")) {
            Log2.i("Auth", "backend rejected the session token; signing out")
            authStore.clearBlocking()
        }
        return response
    }
}

/**
 * Retries idempotent requests once on a transient network failure.
 *
 * Fishing happens where signal is marginal; a single retry converts many
 * "something went wrong" messages into a working request. Only GETs are
 * retried, so a catch is never logged twice.
 *
 * A cancelled call is never retried. Cancellation is this app's own decision
 * -- the screen went away, or a rejected token tore the session down -- and
 * retrying it burns two `Thread.sleep`s per request on a call whose answer
 * nobody is waiting for. Measured on a signed-out launch: three requests, each
 * retried three times, all of them already dead.
 */
class RetryInterceptor(private val maxRetries: Int = 2) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)

        var lastError: IOException? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                lastError = e
                if (chain.call().isCanceled()) throw e
                if (attempt < maxRetries) {
                    Log2.d("Retry", "GET ${request.url.encodedPath} attempt ${attempt + 1} failed")
                    Thread.sleep(200L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("request failed")
    }
}
