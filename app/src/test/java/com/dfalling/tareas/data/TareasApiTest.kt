package com.dfalling.tareas.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server-URL contract on the real client: a URL the login form can't parse
 * is a typed [ApiException.InvalidUrl] (shown as "check the server URL"), never
 * OkHttp's bare IllegalArgumentException from Request.Builder — that used to
 * crash the app ("Invalid URL port") because no error mapping caught it. A
 * parseable URL must proceed all the way to real I/O.
 */
class TareasApiTest {
    private val api = TareasApi()

    @Test
    fun `malformed server urls throw InvalidUrl, not IllegalArgumentException`() {
        val bad = listOf(
            "",
            "not a url",
            "example.com", // no scheme
            "https://host:8443https://", // the crash from the field: pasted after the https:// prefill
            "http://host:port",
        )
        for (url in bad) {
            val thrown =
                runCatching { runBlocking { api.login(url, "smoke@test.dev", "pw") } }
                    .exceptionOrNull()
            assertTrue("expected InvalidUrl for '$url', got $thrown", thrown is ApiException.InvalidUrl)
        }
    }

    @Test
    fun `a parseable server url reaches the network layer`() {
        // Port 1 on localhost refuses instantly, so this fails fast with an
        // IOException — proving URL validation passed and the request was built.
        val thrown =
            runCatching { runBlocking { api.login("http://127.0.0.1:1", "smoke@test.dev", "pw") } }
                .exceptionOrNull()
        assertTrue("expected IOException, got $thrown", thrown is IOException)
    }
}
