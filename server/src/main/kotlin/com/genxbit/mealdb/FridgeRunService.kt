package com.genxbit.mealdb

import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

class FridgeRunService(
    private val http: HttpClient,
    private val ingredientsService: IngredientsService,
    private val json: Json = JsonConfig.json,
) {
    private val esUrl = env("ES_URL", "http://localhost:9200")
    private val esIndex = env("ES_INDEX", "meals-lab")
    private val mealdbUrl = env("MEALDB_URL", "https://www.themealdb.com/api/json/v1/1")
    private val ollamaUrl = env("OLLAMA_URL", "http://localhost:11434")
    private val model = env("OLLAMA_MODEL", "qwen2.5:1.5b") // keep consistent with IngredientsService

    fun run(req: FridgeRunRequest): FridgeRunResult {
        println("LLM Model: ${model}")
        // 1) Interpret (AI)
        val interpreted = ingredientsService.parse(req.input)
        val ingredients = interpreted.ingredients
        val max = (req.max ?: 25).coerceIn(1, 50)

        if (ingredients.isEmpty()) {
            return FridgeRunResult(
                status = HttpStatusCode.BadRequest,
                body = FridgeRunResponse(
                    index = esIndex,
                    interpreted = interpreted,
                    ingest = IngestStats(0, 0, 0, 0, 0),
                    hits = emptyList(),
                    plan = null
                )
            )
        }

        // 2) MealDB candidates per ingredient (keep overlap counts)
        val perIngSets = ingredients.map { ing ->
            filterByIngredient(ing).map { it.idMeal }.toSet()
        }

        // union size for stats
        val unionIds = perIngSets.fold(emptySet<String>()) { acc, s -> acc + s }

        // count how many ingredient lists each meal appears in
        val overlapCount = mutableMapOf<String, Int>()
        for (s in perIngSets) {
            for (id in s) overlapCount[id] = (overlapCount[id] ?: 0) + 1
        }

        // choose top IDs by overlap (then stable tie-breaker)
        val chosenIds = overlapCount.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key })
            .take(max)
            .map { it.key }

        // 3) Strategy B: ES mget -> missing ids
        val (foundIds, missingIds) = mgetExists(chosenIds)

        // 4) Lookup missing + transform + index
        val now = Instant.now().toString()
        val newDocs = missingIds.map { id ->
            val meal = lookupMeal(id)
            meal.toEsDoc(createdAt = now, lastSeenAt = now)
        }

        if (newDocs.isNotEmpty()) {
            bulkIndex(newDocs)
        }

        // 5) Search ES for top hits
        val docs = searchByIngredients(ingredients, size = 25)

        // 6) Compute matchCount + sort “simple first” (with primary ingredient boost)
        val q = interpreted.ingredients
        val primary = q.firstOrNull()

        val ranked = docs
            .map { d ->
                val match = q.count { it in d.ingredients }
                val primaryBoost = if (primary != null && primary in d.ingredients) 1 else 0
                val score = match + primaryBoost

                Hit(
                    id = d.idMeal,
                    name = d.strMeal,
                    match = match,
                    ingCount = d.ingCount,
                    instrLen = d.instrLen,
                    category = d.strCategory,
                    area = d.strArea,
                    thumbUrl = d.strMealThumb,
                    sourceUrl = d.strSource
                ) to score
            }
            .sortedWith(
                compareByDescending<Pair<Hit, Int>> { it.second }   // score (match + boost)
                    .thenByDescending { it.first.match }
                    .thenBy { it.first.ingCount }
                    .thenBy { it.first.instrLen }
            )
            .map { it.first }
            .take(10)

        // 7) AI Plan (Two-step: rank -> plan best)
        val plan = generatePlanTwoStep(
            userInput = req.input,
            interpreted = interpreted,
            hits = ranked,
            docsById = docs.associateBy { it.idMeal }
        )

        val ingestStats =
            IngestStats(
                candidates = unionIds.size,
                selected = chosenIds.size,
                alreadyIndexed = foundIds.size,
                lookups = missingIds.size,
                indexed = newDocs.size
            )

        return FridgeRunResult(
            status = HttpStatusCode.OK,
            body = FridgeRunResponse(
                index = esIndex,
                interpreted = interpreted,
                ingest = ingestStats,
                hits = ranked,
                plan = plan
            )
        )
    }

    // ----------------------------
    // MealDB (filter + lookup)
    // ----------------------------

    private fun filterByIngredient(ingredient: String): List<MealStub> {
        val q = ingredient.trim().lowercase().replace(" ", "_")
        val url = "$mealdbUrl/filter.php?i=$q"
        val resp = http.get(url)
        if (!resp.isSuccessful) return emptyList()

        val body = resp.body ?: return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val meals = root["meals"] ?: return emptyList()
        if (meals is JsonNull) return emptyList()

        return json.decodeFromJsonElement(meals)
    }

    private fun lookupMeal(idMeal: String): MealFull {
        val url = "$mealdbUrl/lookup.php?i=$idMeal"
        val resp = http.get(url)
        if (!resp.isSuccessful) error("MealDB lookup failed: ${resp.code}")

        val body = resp.body ?: error("Empty MealDB response")
        val root = json.parseToJsonElement(body).jsonObject
        val meals = root["meals"] ?: error("Missing 'meals'")
        val arr = meals.jsonArray
        if (arr.isEmpty()) error("Meal not found: $idMeal")
        return json.decodeFromJsonElement(arr[0])
    }

    // ----------------------------
    // Elasticsearch (Strategy B)
    // ----------------------------

    private fun mgetExists(ids: List<String>): Pair<List<String>, List<String>> {
        if (ids.isEmpty()) return emptyList<String>() to emptyList()

        val reqBody =
            buildJsonObject {
                put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            }.toString()

        val resp = http.post("$esUrl/$esIndex/_mget", reqBody)
        if (!resp.isSuccessful) return emptyList<String>() to ids // treat as all missing if ES fails

        val body = resp.body ?: return emptyList<String>() to ids
        val root = json.parseToJsonElement(body).jsonObject
        val docs = root["docs"]?.jsonArray ?: return emptyList<String>() to ids

        val found = mutableListOf<String>()
        val missing = mutableListOf<String>()

        for (d in docs) {
            val obj = d.jsonObject
            val id = obj["_id"]?.jsonPrimitive?.content ?: continue
            val isFound = obj["found"]?.jsonPrimitive?.booleanOrNull ?: false
            if (isFound) found.add(id) else missing.add(id)
        }
        return found to missing
    }

    private fun bulkIndex(docs: List<EsMealDoc>) {
        val ndjson =
            buildString {
                for (d in docs) {
                    append("""{"index":{"_index":"$esIndex","_id":"${d.idMeal}"}}""")
                    append("\n")
                    append(json.encodeToString(EsMealDoc.serializer(), d))
                    append("\n")
                }
            }

        val resp =
            http.post(
                url = "$esUrl/_bulk?refresh=wait_for",
                body = ndjson,
                contentType = "application/x-ndjson",
            )
        if (!resp.isSuccessful) error("ES bulk failed: ${resp.code} ${resp.body}")
    }

    private fun searchByIngredients(ingredients: List<String>, size: Int): List<EsMealDoc> {
        val shouldTerms = JsonArray(
            ingredients.distinct().map { ing ->
                buildJsonObject {
                    put("term", buildJsonObject {
                        put("ingredients", JsonPrimitive(ing))
                    })
                }
            }
        )

        val queryBody =
            buildJsonObject {
                put("size", size)
                put(
                    "_source",
                    JsonArray(
                        listOf(
                            "idMeal",
                            "strMeal",
                            "strCategory",
                            "strArea",
                            "ingredients",
                            "measures",
                            "strInstructions",
                            "strMealThumb",
                            "strSource",
                            "ingCount",
                            "instrLen",
                            "createdAt",
                            "lastSeenAt",
                        ).map { JsonPrimitive(it) }
                    )
                )
                put(
                    "query",
                    buildJsonObject {
                        put(
                            "bool",
                            buildJsonObject {
                                put("should", shouldTerms)
                                put("minimum_should_match", JsonPrimitive(1))
                            }
                        )
                    }
                )
            }.toString()

        val resp = http.post("$esUrl/$esIndex/_search", queryBody)
        if (!resp.isSuccessful) return emptyList()

        val body = resp.body ?: return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val hits = root["hits"]?.jsonObject?.get("hits")?.jsonArray ?: return emptyList()

        return hits.mapNotNull { h ->
            val src = h.jsonObject["_source"] ?: return@mapNotNull null
            json.decodeFromJsonElement<EsMealDoc>(src)
        }
    }

    // ----------------------------
    // AI Plan (Two-step Ollama)
    // ----------------------------

    private fun generatePlanTwoStep(
        userInput: String,
        interpreted: IngredientsService.IngredientsParseResponse,
        hits: List<Hit>,
        docsById: Map<String, EsMealDoc>,
    ): PlanResponse? {
        if (hits.isEmpty()) return null

        // Keep ranking input small for 3B model
        val rankCandidates = hits.take(5).mapNotNull { docsById[it.id] }
        if (rankCandidates.isEmpty()) return null

        // 1) Rank recipes
        val rank = rankRecipes(
            userInput = userInput,
            interpreted = interpreted,
            candidates = rankCandidates
        ) ?: return null

        // 2) Pick best ID (guard/fallback)
        val topPickIdFromModel = rank.picks.firstOrNull()?.id
        val fallbackId = hits.firstOrNull()?.id
        val topPickId = topPickIdFromModel ?: fallbackId ?: return null

        val topDoc = docsById[topPickId] ?: docsById[fallbackId] ?: return null

        // 3) Build plan only for top pick (NO instructions included)
        val planOnly = buildPlanForTopPick(
            interpreted = interpreted,
            top = topDoc
        ) ?: return null

        // Merge into same PlanResponse structure (unchanged contract)
        return PlanResponse(
            picks = rank.picks,
            shoppingList = planOnly.shoppingList,
            timeline = planOnly.timeline,
            sources = rank.sources.ifEmpty { rank.picks.map { it.id } }
        )
    }

    @Serializable
    data class RankResponse(
        val picks: List<PlanResponse.Pick>,
        val sources: List<String> = emptyList(),
    )

    private fun rankRecipes(
        userInput: String,
        interpreted: IngredientsService.IngredientsParseResponse,
        candidates: List<EsMealDoc>,
    ): RankResponse? {
        val context =
            buildString {
                for (d in candidates) {
                    append("Recipe: ${d.strMeal} (ID: ${d.idMeal})\n")
                    append("Category: ${d.strCategory ?: ""} | Area: ${d.strArea ?: ""}\n")
                    append("IngredientCount: ${d.ingCount} | InstructionLength: ${d.instrLen}\n")
                    append("IngredientsPreview: ${d.ingredients.take(8).joinToString(", ")}\n")
                    append("\n---\n\n")
                }
            }

        val prompt = Prompts.rankRecipes(
            userInput = userInput,
            interpretedIngredients = interpreted.ingredients,
            context = context
        )

        val modelText = ollamaGenerateJson(prompt, numPredict = 320) ?: return null
        return try {
            val jsonObj = extractFirstJsonObject(modelText)
            json.decodeFromString<RankResponse>(jsonObj)
        } catch (e: Exception) {
            println("Rank parse failed: ${e.message}")
            null
        }
    }

    @Serializable
    private data class PlanOnly(
        val shoppingList: PlanResponse.ShoppingList,
        val timeline: List<PlanResponse.TimelineStep>,
    )

    private fun buildPlanForTopPick(
        interpreted: IngredientsService.IngredientsParseResponse,
        top: EsMealDoc,
    ): PlanOnly? {
        val context =
            buildString {
                append("Recipe: ${top.strMeal} (ID: ${top.idMeal})\n")
                append("Category: ${top.strCategory ?: ""} | Area: ${top.strArea ?: ""}\n")
                append("Instructions: ${top.strInstructions ?: ""}\n")
                append("Ingredients:\n")
                for (i in top.ingredients.indices) {
                    val ing = top.ingredients[i]
                    val meas = top.measures.getOrNull(i) ?: ""
                    append("- $ing")
                    if (meas.isNotBlank()) append(" — $meas")
                    append("\n")
                }
            }


        val prompt = Prompts.planForTopPick(
            interpretedJson = json.encodeToString(
                IngredientsService.IngredientsParseResponse.serializer(),
                interpreted
            ),
            context = context
        )

        println("Prompt: ${prompt}")

        val modelText = ollamaGenerateJson(prompt, numPredict = 600) ?: return null

        println("Response: ${modelText}")

        return try {
            val jsonObj = extractFirstJsonObject(modelText)
            json.decodeFromString<PlanOnly>(jsonObj)
        } catch (e: Exception) {
            println("Plan parse failed: ${e.message}")
            null
        }
    }

    private fun ollamaGenerateJson(prompt: String, numPredict: Int): String? {
        val reqBody =
            buildJsonObject {
                put("model", model)
                put("stream", false)
                put("keep_alive", "30m")
                put("format", "json")
                put(
                    "options",
                    buildJsonObject {
                        put("temperature", 0.0)
                        put("num_predict", numPredict)
                    }
                )
                put("prompt", prompt)
            }.toString()

        val resp = http.post("$ollamaUrl/api/generate", reqBody)
        if (!resp.isSuccessful) return null
        val raw = resp.body ?: return null

        val outer = json.parseToJsonElement(raw).jsonObject
        return outer["response"]?.jsonPrimitive?.content
    }

    // ----------------------------
    // Models
    // ----------------------------

    data class FridgeRunResult(val status: HttpStatusCode, val body: FridgeRunResponse)

    @Serializable
    data class FridgeRunRequest(
        val input: String,
        val max: Int? = 25,
    )

    @Serializable
    data class FridgeRunResponse(
        val index: String,
        val interpreted: IngredientsService.IngredientsParseResponse,
        val ingest: IngestStats,
        val hits: List<Hit>,
        val plan: PlanResponse? = null,
    )

    @Serializable
    data class IngestStats(
        val candidates: Int,
        val selected: Int,
        val alreadyIndexed: Int,
        val lookups: Int,
        val indexed: Int,
    )

    @Serializable
    data class Hit(
        val id: String,
        val name: String,
        val match: Int,
        val ingCount: Int,
        val instrLen: Int,
        val category: String? = null,
        val area: String? = null,
        val thumbUrl: String? = null,
        val sourceUrl: String? = null,
    )

    @Serializable
    data class PlanResponse(
        val picks: List<Pick>,
        val shoppingList: ShoppingList,
        val timeline: List<TimelineStep>,
        val sources: List<String> = emptyList(),
    ) {
        @Serializable
        data class Pick(
            val id: String,
            val name: String,
            val simplicityScore: Double,
            val why: String = "",
        )

        @Serializable
        data class ShoppingList(
            val produce: List<String> = emptyList(),
            val pantry: List<String> = emptyList(),
            val protein: List<String> = emptyList(),
            val dairy: List<String> = emptyList(),
            val spices: List<String> = emptyList(),
            val optional: List<String> = emptyList(),
        )

        @Serializable
        data class TimelineStep(
            val fromMin: Int,
            val toMin: Int,
            val step: String,
        )
    }

    @Serializable
    data class MealStub(
        val idMeal: String,
        val strMeal: String? = null,
        val strMealThumb: String? = null,
    )

    // This mirrors MealDB lookup fields you actually use (keep minimal)
    @Serializable
    data class MealFull(
        val idMeal: String,
        val strMeal: String,
        val strCategory: String? = null,
        val strArea: String? = null,
        val strInstructions: String? = null,
        val strMealThumb: String? = null,
        val strSource: String? = null,
        val strYoutube: String? = null,

        // Ingredient/measure 1..20 (minimal: keep as nullable strings)
        val strIngredient1: String? = null, val strMeasure1: String? = null,
        val strIngredient2: String? = null, val strMeasure2: String? = null,
        val strIngredient3: String? = null, val strMeasure3: String? = null,
        val strIngredient4: String? = null, val strMeasure4: String? = null,
        val strIngredient5: String? = null, val strMeasure5: String? = null,
        val strIngredient6: String? = null, val strMeasure6: String? = null,
        val strIngredient7: String? = null, val strMeasure7: String? = null,
        val strIngredient8: String? = null, val strMeasure8: String? = null,
        val strIngredient9: String? = null, val strMeasure9: String? = null,
        val strIngredient10: String? = null, val strMeasure10: String? = null,
        val strIngredient11: String? = null, val strMeasure11: String? = null,
        val strIngredient12: String? = null, val strMeasure12: String? = null,
        val strIngredient13: String? = null, val strMeasure13: String? = null,
        val strIngredient14: String? = null, val strMeasure14: String? = null,
        val strIngredient15: String? = null, val strMeasure15: String? = null,
        val strIngredient16: String? = null, val strMeasure16: String? = null,
        val strIngredient17: String? = null, val strMeasure17: String? = null,
        val strIngredient18: String? = null, val strMeasure18: String? = null,
        val strIngredient19: String? = null, val strMeasure19: String? = null,
        val strIngredient20: String? = null, val strMeasure20: String? = null,
    ) {
        fun toEsDoc(createdAt: String, lastSeenAt: String): EsMealDoc {
            val pairs = extractIngredientPairs()
            val ings = pairs.map { it.first }
            val meas = pairs.map { it.second }

            val instructions = strInstructions ?: ""
            return EsMealDoc(
                idMeal = idMeal,
                strMeal = strMeal,
                strCategory = strCategory,
                strArea = strArea,
                ingredients = ings,
                measures = meas,
                strInstructions = instructions,
                strMealThumb = strMealThumb,
                strSource = strSource,
                strYoutube = strYoutube,
                ingCount = ings.size,
                instrLen = instructions.length,
                createdAt = createdAt,
                lastSeenAt = lastSeenAt
            )
        }

        private fun extractIngredientPairs(): List<Pair<String, String>> {
            val raw = listOf(
                strIngredient1 to strMeasure1,
                strIngredient2 to strMeasure2,
                strIngredient3 to strMeasure3,
                strIngredient4 to strMeasure4,
                strIngredient5 to strMeasure5,
                strIngredient6 to strMeasure6,
                strIngredient7 to strMeasure7,
                strIngredient8 to strMeasure8,
                strIngredient9 to strMeasure9,
                strIngredient10 to strMeasure10,
                strIngredient11 to strMeasure11,
                strIngredient12 to strMeasure12,
                strIngredient13 to strMeasure13,
                strIngredient14 to strMeasure14,
                strIngredient15 to strMeasure15,
                strIngredient16 to strMeasure16,
                strIngredient17 to strMeasure17,
                strIngredient18 to strMeasure18,
                strIngredient19 to strMeasure19,
                strIngredient20 to strMeasure20,
            )

            return raw.mapNotNull { (ing, meas) ->
                val i = ing?.trim().orEmpty()
                if (i.isBlank()) null
                else i.lowercase() to (meas?.trim().orEmpty())
            }
        }
    }

    // What you actually index into ES (matches your mapping)
    @Serializable
    data class EsMealDoc(
        val idMeal: String,
        val strMeal: String,
        val strCategory: String? = null,
        val strArea: String? = null,
        val ingredients: List<String>,
        val measures: List<String>,
        val strInstructions: String,
        val strMealThumb: String? = null,
        val strSource: String? = null,
        val strYoutube: String? = null,
        val ingCount: Int,
        val instrLen: Int,
        val createdAt: String? = null,
        val lastSeenAt: String? = null,
    )
}
