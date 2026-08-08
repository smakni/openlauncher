package com.openlauncher.app.util

import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches one tile and reports what came back.
 *
 * The region downloader can only say that a style yielded nothing, which covers
 * a wrong URL, a rejected key, a template the server does not serve, and no
 * connection at all — four different problems behind one message. Guessing
 * between them is what this replaces: one request, and the status code names
 * the cause outright.
 *
 * Deliberately a plain request rather than anything MapLibre does, so a failure
 * here is about the URL and nothing else.
 */
object TileUrlTester {

    /** A tile over western Europe at low zoom — small, and always populated. */
    private const val TEST_Z = 8
    private const val TEST_X = 128
    private const val TEST_Y = 87

    private const val TIMEOUT_MS = 12_000

    fun test(template: String): String {
        if (template.isBlank()) return "no tile URL set"

        val url = template
            .replace("{z}", TEST_Z.toString())
            .replace("{x}", TEST_X.toString())
            .replace("{y}", TEST_Y.toString())

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // Some tile hosts reject the default Java agent outright, which
                // would read as a broken URL when the URL is fine.
                setRequestProperty("User-Agent", "openlauncher")
            }
            try {
                val code = connection.responseCode
                val type = connection.contentType ?: "?"
                // Read rather than trust the header: a proxy or an error page can
                // return 200 with a body that is not a tile, and the size is what
                // gives that away.
                val bytes = runCatching {
                    (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.readBytes()?.size ?: 0
                }.getOrDefault(0)

                when {
                    code == 200 && bytes > 0 -> "OK — $code, $type, $bytes bytes"
                    code == 200 -> "$code but empty — the URL answers, the tile does not exist"
                    code == 401 || code == 403 -> "$code — key rejected or missing"
                    code == 404 -> "$code — no tile at this path, check the template"
                    else -> "$code — $type, $bytes bytes"
                }
            } finally {
                runCatching { connection.disconnect() }
            }
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}" }
    }
}
