package com.nuvio.app.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NetworkErrorInterceptorTest {
    @Test
    fun aRuntimeExceptionThatWrapsAnIoExceptionIsThrownAsThatIoException() {
        val io = IOException("ssl")
        val error = assertFailsWith<IOException> {
            client { throw RuntimeException(io) }.newCall(request()).execute()
        }
        assertSame(io, error)
    }

    @Test
    fun aRuntimeExceptionWithoutAnIoCauseStaysARuntimeException() {
        val cause = IllegalStateException("not io")
        val error = assertFailsWith<RuntimeException> {
            client { throw RuntimeException(cause) }.newCall(request()).execute()
        }
        assertSame(cause, error.cause)
    }

    private fun client(proceed: () -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(NetworkErrorInterceptor())
            .addInterceptor(Interceptor { proceed() })
            .build()

    private fun request(): Request =
        Request.Builder().url("https://example.invalid/poster").build()
}
