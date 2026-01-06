package com.genxbit.mealdb

import io.github.cdimascio.dotenv.dotenv

private val DOTENV = run {
    val local = java.io.File(".env")
    if (local.exists()) {
        dotenv { ignoreIfMissing = true; filename = ".env" }
    } else {
        dotenv { ignoreIfMissing = true; directory = ".."; filename = ".env" }
    }
}

internal fun env(key: String, default: String): String {
    val sys = System.getenv(key)
    if (!sys.isNullOrBlank()) return sys

    val fromFile = DOTENV[key]
    if (!fromFile.isNullOrBlank()) return fromFile

    return default
}


internal fun extractFirstJsonObject(text: String): String {
    val start = text.indexOf('{')
    if (start == -1) error("No JSON found in model output")

    var depth = 0
    for (i in start until text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return text.substring(start, i + 1)
        }
    }
    error("Unclosed JSON object in model output")
}
