package com.genxbit.mealdb

import io.ktor.http.*
import kotlinx.serialization.Serializable

class HealthService(private val http: HttpClient) {
    fun check(): HealthResult {
        val esUrl = env("ES_URL", "http://localhost:9200")
        val ollamaUrl = env("OLLAMA_URL", "http://localhost:11434")
        val mealdbUrl = env("MEALDB_URL", "https://www.themealdb.com/api/json/v1/1")

        val esOk = http.get(esUrl).isSuccessful
        val ollamaOk = http.get("$ollamaUrl/api/tags").isSuccessful
        val mealdbOk = http.get("$mealdbUrl/random.php").isSuccessful
        val allOk = esOk && ollamaOk && mealdbOk

        val body =
                HealthResponse(
                        status = if (allOk) "ok" else "degraded",
                        elasticsearch = DependencyStatus(url = esUrl, ok = esOk),
                        ollama = DependencyStatus(url = ollamaUrl, ok = ollamaOk),
                        mealdb = DependencyStatus(url = mealdbUrl, ok = mealdbOk),
                )

        return HealthResult(
                status = if (allOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                body = body,
        )
    }

    data class HealthResult(val status: HttpStatusCode, val body: HealthResponse)

    @Serializable
    data class HealthResponse(
            val status: String,
            val elasticsearch: DependencyStatus,
            val ollama: DependencyStatus,
            val mealdb: DependencyStatus,
    )

    @Serializable
    data class DependencyStatus(
            val url: String,
            val ok: Boolean,
    )
}
