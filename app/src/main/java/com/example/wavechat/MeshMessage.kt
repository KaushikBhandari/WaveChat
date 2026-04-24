package com.example.wavechat
import org.json.JSONObject
import java.util.UUID

/**
 * Represents a message travelling through the mesh network.
 *
 * @param id        Unique ID so every device can detect + discard duplicates.
 * @param senderId  BLE address of the original sender.
 * @param text      The actual chat text.
 * @param hopCount  How many devices this message has passed through (max = MAX_HOPS).
 * @param timestamp Unix ms — used for ordering and TTL checks.
 */
data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val recipientId: String = BROADCAST_ID, // target device ID; BROADCAST_ID = show to all
    val text: String,
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val MAX_HOPS     = 5
        const val TTL_MS       = 5 * 60_000
        const val BROADCAST_ID = "BROADCAST" // special value → deliver to every device

        /** Deserialise from the raw bytes received over BLE. */
        fun fromBytes(bytes: ByteArray): MeshMessage? = try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            MeshMessage(
                id          = json.getString("id"),
                senderId    = json.getString("sender"),
                recipientId = json.optString("recipient", BROADCAST_ID),
                text        = json.getString("text"),
                hopCount    = json.getInt("hop"),
                timestamp   = json.getLong("ts")
            )
        } catch (e: Exception) { null }
    }

    /** Serialise to bytes for BLE transmission. */
    fun toBytes(): ByteArray = JSONObject().apply {
        put("id",        id)
        put("sender",    senderId)
        put("recipient", recipientId)
        put("text",      text)
        put("hop",       hopCount)
        put("ts",        timestamp)
    }.toString().toByteArray(Charsets.UTF_8)

    /**
     * Returns true if [myAddress] should display this message in the UI.
     * Middle relay devices return false for messages passing through them.
     */
    fun isForMe(myAddress: String): Boolean =
        recipientId == BROADCAST_ID ||
                recipientId == myAddress    ||
                senderId    == myAddress

    /** True if this message should still be forwarded. */
    fun isAlive(): Boolean =
        hopCount < MAX_HOPS &&
                (System.currentTimeMillis() - timestamp) < TTL_MS

    /** Return a copy with hop count incremented (used by relay). */
    fun relayed(): MeshMessage = copy(hopCount = hopCount + 1)
}