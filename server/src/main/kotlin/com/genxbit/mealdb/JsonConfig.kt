package com.genxbit.mealdb

import kotlinx.serialization.json.Json

object JsonConfig {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
