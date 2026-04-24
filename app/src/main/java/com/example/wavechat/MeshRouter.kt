package com.example.wavechat

import android.util.Log

/**
 * MeshRouter — Core mesh relay engine.
 *
 * Responsibilities
 * ─────────────────
 * 1. Track every message ID we have already seen → discard duplicates.
 * 2. Store undelivered messages (Store-and-Forward / DTN).
 * 3. Decide whether an incoming message should be relayed to all
 *    connected peers (Epidemic routing).
 * 4. On reconnection, flush the pending queue to newly available peers.
 *
 * Usage
 * ─────
 * val router = MeshRouter(myAddress) { msg, peerAddress ->
 *     // your BLE send function
 *     bleManager.sendToPeer(msg, peerAddress)
 * }
 *
 * // When a message arrives over BLE:
 * router.onMessageReceived(rawBytes, fromAddress) { displayMsg ->
 *     showInUI(displayMsg)          // only called for NEW messages
 * }
 *
 * // When a new peer connects:
 * router.onPeerConnected(peerAddress)
 */
class MeshRouter(
    private val myAddress: String,
    private val sendToPeer: (MeshMessage, String) -> Unit
) {
    private val TAG = "WaveChat-MeshRouter"

    // IDs of every message we have seen — prevents re-relay loops
    private val seenIds = LinkedHashSet<String>()

    // Messages we could not deliver yet (peer was out of range)
    private val pendingQueue = ArrayDeque<MeshMessage>()

    companion object {
        private const val MAX_SEEN   = 500   // cap memory usage
        private const val MAX_PENDING = 100  // max queued messages
    }

    // ──────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────

    /**
     * Call this every time raw bytes arrive from any BLE peer.
     *
     * @param bytes       Raw bytes from the GATT write.
     * @param fromAddress BLE address of the sender.
     * @param onNewMessage Lambda called ONLY when this is a brand-new
     *                     message destined for (or passing through) us.
     *                     Provides the [MeshMessage] for UI display.
     */
    fun onMessageReceived(
        bytes: ByteArray,
        fromAddress: String,
        connectedPeers: List<String>,
        onNewMessage: (MeshMessage) -> Unit
    ) {
        val msg = MeshMessage.fromBytes(bytes) ?: run {
            Log.w(TAG, "Could not parse message from $fromAddress")
            return
        }

        // 1. Duplicate check
        if (msg.id in seenIds) {
            Log.d(TAG, "Duplicate dropped: ${msg.id}")
            return
        }

        // 2. TTL / hop check
        if (!msg.isAlive()) {
            Log.d(TAG, "Expired message dropped: ${msg.id} hops=${msg.hopCount}")
            return
        }

        // 3. Mark as seen
        markSeen(msg.id)

        // 4. Deliver to local UI only if this device is the intended recipient.
        //    Middle relay devices skip this silently — they only forward.
        if (msg.isForMe(myAddress)) {
            onNewMessage(msg)
        } else {
            Log.d(TAG, "Transit msg ${msg.id} not for me (recipient=${msg.recipientId}) — relaying silently")
        }

        // 5. Relay to all OTHER connected peers (Epidemic routing)
        val relayed = msg.relayed()
        if (relayed.isAlive()) {
            val targets = connectedPeers.filter { it != fromAddress }
            if (targets.isEmpty()) {
                // No peers available — store for later
                enqueuePending(relayed)
                Log.d(TAG, "No peers to relay to — queued: ${msg.id}")
            } else {
                targets.forEach { peer ->
                    Log.d(TAG, "Relaying ${msg.id} → $peer (hop ${relayed.hopCount})")
                    sendToPeer(relayed, peer)
                }
            }
        }
    }

    /**
     * Call this when you originate a new message from this device.
     * Marks it seen (so we don't relay our own) and sends to all peers.
     */
    fun originateMessage(text: String, connectedPeers: List<String>,
                         recipientId: String = MeshMessage.BROADCAST_ID): MeshMessage {
        val msg = MeshMessage(senderId = myAddress, recipientId = recipientId, text = text)
        markSeen(msg.id)

        if (connectedPeers.isEmpty()) {
            enqueuePending(msg)
            Log.d(TAG, "No peers — message queued: ${msg.id}")
        } else {
            connectedPeers.forEach { peer ->
                Log.d(TAG, "Sending ${msg.id} → $peer")
                sendToPeer(msg, peer)
            }
        }
        return msg
    }

    /**
     * Call this whenever a new BLE peer connects.
     * Flushes the pending (store-and-forward) queue to that peer.
     */
    fun onPeerConnected(peerAddress: String) {
        Log.d(TAG, "Peer connected: $peerAddress — flushing ${pendingQueue.size} pending msgs")
        val toSend = pendingQueue.toList()
        pendingQueue.clear()

        toSend.filter { it.isAlive() }.forEach { msg ->
            Log.d(TAG, "Flushing pending ${msg.id} → $peerAddress")
            sendToPeer(msg, peerAddress)
        }
    }

    /** Returns how many messages are sitting in the pending queue. */
    fun pendingCount(): Int = pendingQueue.size

    // ──────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────

    private fun markSeen(id: String) {
        if (seenIds.size >= MAX_SEEN) {
            seenIds.remove(seenIds.first())   // evict oldest
        }
        seenIds.add(id)
    }

    private fun enqueuePending(msg: MeshMessage) {
        if (pendingQueue.size >= MAX_PENDING) {
            pendingQueue.removeFirst()        // drop oldest if full
        }
        pendingQueue.addLast(msg)
    }
}