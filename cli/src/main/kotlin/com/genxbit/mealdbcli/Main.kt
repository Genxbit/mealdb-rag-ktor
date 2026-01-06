package com.genxbit.mealdbcli

import com.genxbit.mealdbcli.model.FridgeRunRequest
import com.genxbit.mealdbcli.model.FridgeRunResponse
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlin.time.TimeSource

private val DOTENV = dotenv {
    ignoreIfMissing = true
    directory = ".."
    filename = ".env"
}

private fun env(key: String, default: String): String {
    // 1) Real env var wins (Docker / prod)
    val sys = System.getenv(key)
    if (!sys.isNullOrBlank()) return sys

    // 2) Fall back to .env (local dev)
    val fromFile = DOTENV[key]
    if (!fromFile.isNullOrBlank()) return fromFile

    // 3) Default
    return default
}

private fun envInt(key: String): Int? =
    env(key, "").trim().takeIf { it.isNotBlank() }?.toIntOrNull()

private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

fun main(args: Array<String>) = runBlocking {
    val baseUrl = env("MEALDB_SERVER_URL", "http://localhost:8080").trim()

    val max = envInt("MEALDB_MAX")

    val userInput = readUserInput(args)
    if (userInput.isBlank()) {
        printUsageAndExit()
    }

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            // Plan generation can be slow on older hardware → allow long calls
            requestTimeoutMillis = 6.minutes.inWholeMilliseconds
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 6.minutes.inWholeMilliseconds
        }
    }

    val spinner = startSpinnerWithElapsed("Finding meals")
    try {
        val response = fridgeRun(client, baseUrl, userInput, max)
        printPretty(response, userInput)
    } catch (e: Exception) {
        System.err.println("CLI error: ${e.message}")
        exitProcess(2)
    } finally {
        spinner.cancelAndJoin()
        client.close()
    }
}

private fun CoroutineScope.startSpinnerWithElapsed(message: String = "Waiting"): Job = launch(Dispatchers.IO) {
    val frames = charArrayOf('|', '/', '-', '\\')
    val start = TimeSource.Monotonic.markNow()
    var i = 0
    while (isActive) {
        val elapsed = start.elapsedNow().inWholeSeconds
        print("\r$message ${frames[i % frames.size]}  ${elapsed}s")
        System.out.flush()
        i++
        delay(120)
    }
}

private fun readUserInput(args: Array<String>): String {
    val joined = args.joinToString(" ").trim()
    if (joined.isNotBlank()) return joined

    return try {
        readlnOrNull()?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun printUsageAndExit(): Nothing {
    println("No input provided.")
    println(
        """
        Usage:
          ./gradlew :cli:run --args="chicken, lemon, garlic — quick, not spicy"

        Env:
          MEALDB_SERVER_URL=http://localhost:8080
          MEALDB_MAX=12
        """.trimIndent()
    )
    exitProcess(1)
}

private suspend fun fridgeRun(
    client: HttpClient,
    baseUrl: String,
    input: String,
    max: Int?
): FridgeRunResponse {
    val req = FridgeRunRequest(input = input, max = max)

    val resp: HttpResponse = client.post("$baseUrl/api/fridge/run") {
        contentType(ContentType.Application.Json)
        setBody(req)
    }

    // Helpful errors: include response body on failures
    if (!resp.status.isSuccess()) {
        val bodyText = resp.bodyAsText()
        throw RuntimeException(
            buildString {
                append("HTTP ${resp.status.value} ${resp.status.description}\n")
                append("URL: $baseUrl/api/fridge/run\n")
                append("Body:\n$bodyText")
            }
        )
    }

    return resp.body()
}

private fun printPretty(r: FridgeRunResponse, userInput: String) {
    println()
    println("🧊 fridge input: $userInput")
    println("🔎 interpreted: ${r.interpreted.ingredients.joinToString(", ")}")
    println(
        "⚙️  constraints: quick=${r.interpreted.constraints.quick}, notSpicy=${r.interpreted.constraints.notSpicy}"
    )
    println(
        "📦 ingest: candidates=${r.ingest.candidates}, selected=${r.ingest.selected}, cached=${r.ingest.alreadyIndexed}, indexed=${r.ingest.indexed}"
    )
    println()

    if (r.hits.isEmpty()) {
        println("No hits.")
        return
    }

    println("🍽️  top hits:")
    r.hits.take(5).forEachIndexed { idx, h ->
        val cat = h.category ?: "-"
        val area = h.area ?: "-"
        println("  ${idx + 1}. ${h.name}  (match=${h.match}, $cat, $area)  id=${h.id}")
    }

    val plan = r.plan
    if (plan == null) {
        println()
        println("🤖 plan: (not available)")
        return
    }

    println()
    println("🤖 picks:")
    plan.picks.take(3).forEachIndexed { idx, p ->
        println("  ${idx + 1}. ${p.name} (score=${p.simplicityScore})  id=${p.id}")
        println("     ${p.why}")
    }

    // Keep shopping list minimal for demo
    val pantry = plan.shoppingList.pantry.take(5)
    if (pantry.isNotEmpty()) {
        println()
        println("🛒 pantry (suggested): ${pantry.joinToString(", ")}")
    }

    val timeline = plan.timeline.take(5)
    if (timeline.isNotEmpty()) {
        println()
        println("⏱️  timeline (top pick):")
        timeline.forEach { t ->
            println("  ${t.fromMin}-${t.toMin} min: ${t.step}")
        }
    }

    if (plan.sources.isNotEmpty()) {
        println()
        println("📚 sources: ${plan.sources.joinToString(", ")}")
    }
}
