package com.genxbit.mealdb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class IngredientsService(
        private val http: HttpClient,
        private val json: Json = JsonConfig.json,
) {
    fun parse(input: String): IngredientsParseResponse {
        val ollamaUrl = env("OLLAMA_URL", "http://localhost:11434")
        val model = env("OLLAMA_MODEL", "qwen2.5:1.5b")

        val requestBody =
                buildJsonObject {
                            put("model", model)
                            put("stream", false)
                            put("keep_alive", "30m")
                            put(
                                    "options",
                                    buildJsonObject {
                                        put("temperature", 0.0)
                                        put("num_predict", 120)
                                    }
                            )
                            put("prompt", Prompts.ingredientsParse(input))

                        }
                        .toString()

        val resp =
                http.post(
                        url = "$ollamaUrl/api/generate",
                        body = requestBody,
                )
        if (!resp.isSuccessful) error("Ollama failed: ${resp.code}")
        val raw = resp.body ?: error("Empty Ollama response")

        // raw is JSON with a field "response" (string) that contains extra text.
        val outer = json.parseToJsonElement(raw).jsonObject
        val modelText =
                outer["response"]?.jsonPrimitive?.content ?: error("Missing 'response' from Ollama")

        val jsonObj = extractFirstJsonObject(modelText)
        return json.decodeFromString(jsonObj)
    }

    @Serializable data class IngredientsParseRequest(val input: String)

    @Serializable
    data class IngredientsParseResponse(
            val ingredients: List<String>,
            val constraints: Constraints = Constraints(),
    ) {
        @Serializable
        data class Constraints(
                val quick: Boolean = false,
                val notSpicy: Boolean = false,
        )
    }
}
