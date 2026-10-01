package com.musicsyncer.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

object GsonHolder {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ManifestWire::class.java, ManifestWireAdapter)
        .registerTypeAdapter(ConflictWire::class.java, ConflictWireAdapter)
        .create()
}

// Wire DTOs: field names match the PYTHON server (ms/server.py, ms/client.py)
// exactly — snake_case keys, manifest rows as [path, size, mtime_ns, sha256]
// arrays, conflicts as [path, ts_ns, sha256] arrays. Longs (epoch-ns mtimes
// ~1.77e18) must round-trip exactly, so the array adapters use
// JsonPrimitive.getAsLong() — never a Double in between.
data class HandshakeResp(
    @SerializedName("schema_version") val schemaVersion: Int,
    @SerializedName("server_device_id") val serverDeviceId: String,
    @SerializedName("server_journal_head") val serverJournalHead: Long,
    @SerializedName("client_cursor") val clientCursor: Long,
    @SerializedName("token_required") val tokenRequired: Boolean = false,
)
data class ManifestWire(val path: String, val size: Long, val mtimeNs: Long, val sha256: String?)
data class ConflictWire(val path: String, val tsNs: Long, val sha256: String?)
data class SyncRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("server_cursor") val serverCursor: Long,
    @SerializedName("journal_ops") val journalOps: List<JournalOp>,
    @SerializedName("manifest") val manifest: List<ManifestWire>,
)
data class SyncResponse(
    @SerializedName("schema_version") val schemaVersion: Int,
    @SerializedName("server_journal_ops") val serverJournalOps: List<JournalOp>,
    @SerializedName("server_manifest") val serverManifest: List<ManifestWire>,
    @SerializedName("needs_push") val needsPush: List<ManifestWire>,
    @SerializedName("server_journal_head") val serverJournalHead: Long,
)
data class DoneRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("client_journal_head") val clientJournalHead: Long,
    @SerializedName("ts_ns") val tsNs: Long,
    @SerializedName("delete") val delete: List<String> = emptyList(),
    @SerializedName("conflicts") val conflicts: List<ConflictWire> = emptyList(),
)

/** Python manifest row: [path, size, mtime_ns, sha256]. */
private object ManifestWireAdapter : JsonSerializer<ManifestWire>, JsonDeserializer<ManifestWire> {
    override fun serialize(src: ManifestWire, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonArray().apply {
            add(JsonPrimitive(src.path))
            add(JsonPrimitive(src.size))
            add(JsonPrimitive(src.mtimeNs))
            add(src.sha256?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ManifestWire {
        val arr = json.asJsonArray
        return ManifestWire(
            path = arr[0].asString,
            size = (arr[1] as JsonPrimitive).asLong,
            mtimeNs = (arr[2] as JsonPrimitive).asLong,
            sha256 = if (arr[3].isJsonNull) null else arr[3].asString,
        )
    }
}

/** Python conflict tuple: [path, ts_ns, sha256]. */
private object ConflictWireAdapter : JsonSerializer<ConflictWire>, JsonDeserializer<ConflictWire> {
    override fun serialize(src: ConflictWire, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonArray().apply {
            add(JsonPrimitive(src.path))
            add(JsonPrimitive(src.tsNs))
            add(src.sha256?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ConflictWire {
        val arr = json.asJsonArray
        return ConflictWire(
            path = arr[0].asString,
            tsNs = (arr[1] as JsonPrimitive).asLong,
            sha256 = if (arr[2].isJsonNull) null else arr[2].asString,
        )
    }
}
