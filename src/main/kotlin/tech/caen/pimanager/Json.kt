package tech.caen.pimanager

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.time.Instant

/** Instance JSON partagée (sérialisation des réponses + parsing du fichier de statut). */
@OptIn(ExperimentalSerializationApi::class)
val appJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

fun nowMillis(): Long = System.currentTimeMillis()

fun nowIso(): String = Instant.now().toString()

fun Long.toIso(): String = Instant.ofEpochMilli(this).toString()
