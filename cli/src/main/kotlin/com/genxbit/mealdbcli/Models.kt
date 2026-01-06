package com.genxbit.mealdbcli.model

import kotlinx.serialization.Serializable

@Serializable
data class FridgeRunRequest(
    val input: String,
    val max: Int? = null
)

@Serializable
data class Constraints(
    val quick: Boolean = false,
    val notSpicy: Boolean = false
)

@Serializable
data class Interpreted(
    val ingredients: List<String> = emptyList(),
    val constraints: Constraints = Constraints()
)

@Serializable
data class IngestStats(
    val candidates: Int = 0,
    val selected: Int = 0,
    val alreadyIndexed: Int = 0,
    val lookups: Int = 0,
    val indexed: Int = 0
)

@Serializable
data class Hit(
    val id: String,
    val name: String,
    val match: Int,
    val category: String? = null,
    val area: String? = null
)

@Serializable
data class PlanPick(
    val id: String,
    val name: String,
    val simplicityScore: Double,
    val why: String
)

@Serializable
data class ShoppingList(
    val produce: List<String> = emptyList(),
    val pantry: List<String> = emptyList(),
    val protein: List<String> = emptyList(),
    val dairy: List<String> = emptyList(),
    val spices: List<String> = emptyList(),
    val optional: List<String> = emptyList()
)

@Serializable
data class TimelineStep(
    val fromMin: Int,
    val toMin: Int,
    val step: String
)

@Serializable
data class PlanResponse(
    val picks: List<PlanPick> = emptyList(),
    val shoppingList: ShoppingList = ShoppingList(),
    val timeline: List<TimelineStep> = emptyList(),
    val sources: List<String> = emptyList()
)

@Serializable
data class FridgeRunResponse(
    val index: String,
    val interpreted: Interpreted,
    val ingest: IngestStats,
    val hits: List<Hit> = emptyList(),
    val plan: PlanResponse? = null
)
