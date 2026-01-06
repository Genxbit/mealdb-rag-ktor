package com.genxbit.mealdb

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*

fun main() {
    embeddedServer(Netty, port = 8080) {
                install(ContentNegotiation) { json(JsonConfig.json) }

                val http = HttpClient()
                val ingredients = IngredientsService(http)
                val fridgeRun = FridgeRunService(http, ingredients)
                installRoutes(
                        health = HealthService(http),
                        ingredients = IngredientsService(http),
                        fridgeRun = fridgeRun
                )
            }
            .start(wait = true)
}
