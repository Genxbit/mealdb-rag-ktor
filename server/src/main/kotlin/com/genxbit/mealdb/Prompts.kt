package com.genxbit.mealdb

import kotlinx.serialization.json.Json


object Prompts {

    fun ingredientsParse(userInput: String): String =
        """
Return STRICT JSON only (no markdown).
Schema:
{"ingredients":["..."],"constraints":{"quick":true|false,"notSpicy":true|false}}

Rules:
- Only include ingredients explicitly written by the user. Do NOT add or infer ingredients.
- Translate ingredients to English if needed (MealDB uses English). Translation must be 1:1 (no extra ingredients).
- Normalize ingredients: lowercase, replace '_' with space, trim, singularize simple plurals when appropriate.
- If unsure whether a word is an ingredient, ignore it.
- Detect constraints in ANY language:
  - quick=true if user implies fast/quick cooking
  - notSpicy=true if user implies not spicy/mild/no heat
- Always include both constraint keys. If not mentioned, set false.
- No duplicates. Keep ingredients short (1–3 words).

Examples:
Input: chicken — quick, not spicy
Output: {"ingredients":["chicken"],"constraints":{"quick":true,"notSpicy":true}}

Input: kyckling, citron, vitlök — snabbt, inte starkt
Output: {"ingredients":["chicken","lemon","garlic"],"constraints":{"quick":true,"notSpicy":true}}

User input: $userInput
Output:
""".trimIndent()

    fun rankRecipes(
        userInput: String,
        interpretedIngredients: List<String>,
        context: String
    ): String =
        """
You are a cooking assistant.

Goal: rank recipes for simplicity + match to the user's fridge ingredients.
You MUST NOT create shopping lists or timelines. Only ranking.

Output MUST be a single valid JSON object.
No markdown. No extra text.

User input:
$userInput

Interpreted ingredients:
${interpretedIngredients.joinToString(", ")}

TASK:
1) Rank recipes best→worst for simplicity and ingredient match.
2) picks MUST include EVERY recipe ID present in CONTEXT exactly once.
3) sources MUST match picks IDs exactly in the same order (IDs only).

STRICT JSON RULES (MUST FOLLOW):
- Each picks item MUST contain ALL keys: id, name, simplicityScore, why
- why is REQUIRED for EVERY pick.
- If you cannot think of a good why, set: "why": "simple"
- simplicityScore MUST be a number 0..10 (not a string).

why RULES:
- max 12 words
- concrete (fewer steps/ingredients/faster), mention tradeoff if relevant
- never empty

RECIPE DETECTION:
- In CONTEXT, each recipe starts with:
  "Recipe: <name> (ID: <id>)"
- Extract all IDs from those lines.

HARD COUNT REQUIREMENT:
- picks MUST contain EXACTLY as many items as "Recipe:" lines in CONTEXT.
- Include all extracted IDs exactly once.
- sources = [picks[0].id, picks[1].id, ...]

EXAMPLE OUTPUT (VALID):
{
  "picks": [
    { "id":"111", "name":"Recipe A", "simplicityScore": 8, "why":"few ingredients, quick prep" },
    { "id":"222", "name":"Recipe B", "simplicityScore": 6, "why":"more steps, but uses ingredients" }
  ],
  "sources": ["111","222"]
}

Return JSON schema:
{
  "picks": [
    { "id":"...", "name":"...", "simplicityScore": 0-10, "why":"..." }
  ],
  "sources": ["id1"]
}

CONTEXT:
$context
        """.trimIndent()

    fun planForTopPick(
        interpretedJson: String,
        context: String
    ): String =
        """
You are a cooking assistant.

Use ONLY the recipe in CONTEXT. Do not invent anything.
Output MUST be a single valid JSON object matching the schema below.
No markdown. No extra text.

Interpreted (JSON):
$interpretedJson

TASK:
Create:
- shoppingList (from Ingredients list + pantry staples)
- timeline (FULL cooking timeline including prep + waiting + cooking time)

STRICT NO MIXING:
- CONTEXT contains exactly 1 recipe.
- Do NOT include anything not in its Ingredients list (except pantry staples).

PANTRY STAPLES ALLOWED (only if needed):
salt, black pepper, cooking oil, butter, onion, garlic, water

SHOPPING LIST RULES (MUST FOLLOW):
- shoppingList MUST include keys: produce, pantry, protein, dairy, spices, optional (use [] if empty).
- Values MUST be arrays of STRINGS only.
- Each item MUST be in the exact format: "name — qty"
- If qty is unknown, use: "to taste" or "as needed" or "1".
- Do NOT include empty strings.
- Do NOT add any ingredient not in CONTEXT + pantry staples.
- Max 10 items per category.

TIMELINE RULES (FULL COOKING TIME):
- timeline MUST contain 6 to 12 steps.
- Timeline MUST include: prep steps, any marinating/refrigerating/resting time, heating time, cooking time, serving.
- Steps MUST be chronological and increasing (fromMin < toMin).
- Final timeline step MUST be serving/plating.
- Total time can be longer than 30 minutes (no max).
- Each step max 16 words.
- Use the recipe’s actual times when present (e.g. refrigerate 60 minutes).

HARD SELF-CHECK (MUST DO BEFORE OUTPUT):
- Ensure timeline has 6..12 steps.
- Ensure ALL shoppingList categories exist (even if empty arrays).
- Ensure every shoppingList item contains " — " (dash with spaces).

Return JSON schema (must include ALL keys):
{
  "shoppingList": {
    "produce": [ "item — qty" ],
    "pantry": [ "item — qty" ],
    "protein": [ "item — qty" ],
    "dairy": [ "item — qty" ],
    "spices": [ "item — qty" ],
    "optional": [ "item — qty" ]
  },
  "timeline": [
    { "fromMin": 0, "toMin": 5, "step": "..." }
  ]
}

CONTEXT:
$context
        """.trimIndent()
}
