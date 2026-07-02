package com.iswhateveroutyet.app.data

import com.iswhateveroutyet.app.model.CategoryFile
import com.iswhateveroutyet.app.model.DataIndex
import com.iswhateveroutyet.app.model.SiteData
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The app is a client of the same static data the website reads — GitHub Pages is the backend. */
const val DATA_BASE = "https://iswhateveroutyet.com/data"

private const val WORKFLOW_RUNS_URL =
    "https://api.github.com/repos/sfryslie/is-whatever-out-yet/actions/workflows/check-models.yml/runs?per_page=1"

val AppJson = Json { ignoreUnknownKeys = true }

class WhateverRepository(private val client: HttpClient) {

    /** Fetch index.json, then every category file in parallel, and flatten — same as index.html. */
    suspend fun load(): SiteData = coroutineScope {
        val bust = "?v=" + Clock.System.now().toEpochMilliseconds()
        val index = AppJson.decodeFromString<DataIndex>(
            client.get("$DATA_BASE/index.json$bust").bodyAsText()
        )
        val files = index.categories.map { cat ->
            async {
                AppJson.decodeFromString<CategoryFile>(
                    client.get("$DATA_BASE/${cat.file}$bust").bodyAsText()
                )
            }
        }.awaitAll()
        SiteData(
            updated = index.updated,
            categories = index.categories.map { it.name },
            items = files.flatMap { it.items },
        )
    }

    /** "Last checked" — when the checker workflow last ran, independent of whether data changed. */
    suspend fun lastChecked(): Instant? = try {
        val body = client.get(WORKFLOW_RUNS_URL).bodyAsText()
        AppJson.parseToJsonElement(body).jsonObject["workflow_runs"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("run_started_at")?.jsonPrimitive?.content
            ?.let { Instant.parse(it) }
    } catch (e: Exception) {
        null
    }
}
