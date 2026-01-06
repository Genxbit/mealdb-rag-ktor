package com.genxbit.mealdb

import java.time.Duration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Transport-only HTTP wrapper. No domain knowledge. */
class HttpClient {
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .writeTimeout(Duration.ofSeconds(30))
                    .readTimeout(Duration.ofMinutes(5))
                    .callTimeout(Duration.ofMinutes(5))
                    .build()

    fun get(url: String): HttpResponse = execute(Request.Builder().url(url).get().build())

    fun post(
            url: String,
            body: String,
            contentType: String = "application/json",
    ): HttpResponse =
            execute(
                    Request.Builder()
                            .url(url)
                            .post(body.toRequestBody(contentType.toMediaType()))
                            .build()
            )

    private fun execute(request: Request): HttpResponse =
            try {
                client.newCall(request).execute().use { resp ->
                    HttpResponse(resp.code, resp.body?.string())
                }
            } catch (e: Exception) {
                HttpResponse(0, e.message)
            }
}

data class HttpResponse(
        val code: Int,
        val body: String?,
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}
