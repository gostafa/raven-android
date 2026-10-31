package com.gostafa.raven.core.model

import kotlinx.serialization.json.Json

fun normalizeTags(tags: List<String>): List<String> =
    tags.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }

fun parseTags(input: String): List<String> = normalizeTags(input.split(',', '،'))

fun decodeTags(json: String): List<String> =
    runCatching { normalizeTags(Json.decodeFromString<List<String>>(json)) }.getOrDefault(emptyList())

fun matchesTags(tags: List<String>, selected: List<String>): Boolean =
    selected.all { selection -> tags.any { it.equals(selection, ignoreCase = true) } }
