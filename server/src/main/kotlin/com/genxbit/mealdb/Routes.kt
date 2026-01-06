package com.genxbit.mealdb

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.installRoutes(
    health: HealthService,
    ingredients: IngredientsService,
    fridgeRun: FridgeRunService,
) {
    routing {
        get("/api/health") {
            val result = health.check()
            call.respond(result.status, result.body)
        }

        post("/api/fridge/interpret") {
            val req = call.receive<IngredientsService.IngredientsParseRequest>()
            call.respond(HttpStatusCode.OK, ingredients.parse(req.input))
        }

        post("/api/fridge/run") {
            val req = call.receive<FridgeRunService.FridgeRunRequest>()
            val result = fridgeRun.run(req)
            call.respond(result.status, result.body)
        }
    }
}

