package com.solverin.engine.model

import com.solverin.engine.core.Position

/** Villain decision points that can be node-locked. Frequencies are conditional on villain's range at the node. */
enum class VillainNode {
    /** Raise first in: raise = open frequency. */
    OPEN,
    /** Facing an open: raise = 3bet, call = flat. */
    DEFEND_VS_OPEN,
    /** Opener facing a 3bet: raise = 4bet, call = flat. */
    VS_3BET,
    /** 3bettor facing a 4bet: raise = 5bet jam, call = flat. */
    VS_4BET,
    /** 4bettor facing a jam: call = call the jam. */
    VS_JAM,
    /** Player behind facing open + caller: raise = squeeze. */
    SQUEEZE,
    /** Opener facing a squeeze: raise = 4bet, call = flat. */
    VS_SQUEEZE_OPENER,
    /** Cold caller facing a squeeze: raise = 4bet, call = flat. */
    VS_SQUEEZE_CALLER,
    /** Player not yet in facing an open + 3bet: raise = cold 4bet, call = cold call. */
    COLD_VS_3BET,
    /** Opener whose open was 3bet and then cold 4bet: raise = 5bet jam, call = flat. */
    VS_COLD_4BET,
}

/** Locked response. Null fields keep the profile value. valueShare: 1 = linear, 0.5 = fully polarized. */
data class NodeLock(
    val raise: Double? = null,
    val call: Double? = null,
    val valueShare: Double? = null,
) {
    init {
        require(raise == null || raise in 0.0..1.0)
        require(call == null || call in 0.0..1.0)
        require((raise ?: 0.0) + (call ?: 0.0) <= 1.0 + 1e-9)
        require(valueShare == null || valueShare in 0.0..1.0)
    }
}

/**
 * Node locks (Max Exploit): override villain frequencies at specific nodes.
 * Lookup order: (node, villain, versus) then (node, villain, any).
 */
data class NodeLocks(val locks: Map<Key, NodeLock> = emptyMap()) {
    data class Key(val node: VillainNode, val villain: Position, val versus: Position? = null)

    fun get(node: VillainNode, villain: Position, versus: Position?): NodeLock? =
        (versus?.let { locks[Key(node, villain, it)] }) ?: locks[Key(node, villain, null)]

    fun with(node: VillainNode, villain: Position, lock: NodeLock, versus: Position? = null) =
        NodeLocks(locks + (Key(node, villain, versus) to lock))

    companion object {
        val NONE = NodeLocks()
    }
}
