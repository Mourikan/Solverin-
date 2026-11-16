package com.solverin.engine.core

/**
 * 169 strategically distinct starting hands laid out on the standard 13x13 grid.
 * index = row * 13 + col, row/col 0 = Ace .. 12 = Deuce.
 * row == col -> pair, row < col -> suited (upper triangle), row > col -> offsuit.
 */
object HandClass {
    const val COUNT = 169
    const val GRID = 13
    const val TOTAL_COMBOS = 1326

    enum class Kind { PAIR, SUITED, OFFSUIT }

    /** Grid coordinate -> card rank (0 = '2' .. 12 = 'A'). */
    fun rankAt(gridPos: Int): Int = 12 - gridPos
    fun gridPos(rank: Int): Int = 12 - rank

    fun index(highRank: Int, lowRank: Int, suited: Boolean): Int {
        val hi = maxOf(highRank, lowRank)
        val lo = minOf(highRank, lowRank)
        val rh = gridPos(hi)
        val rl = gridPos(lo)
        return when {
            hi == lo -> rh * GRID + rh
            suited -> rh * GRID + rl
            else -> rl * GRID + rh
        }
    }

    fun row(idx: Int) = idx / GRID
    fun col(idx: Int) = idx % GRID

    fun kind(idx: Int): Kind {
        val r = row(idx); val c = col(idx)
        return when {
            r == c -> Kind.PAIR
            r < c -> Kind.SUITED
            else -> Kind.OFFSUIT
        }
    }

    fun highRank(idx: Int): Int = rankAt(minOf(row(idx), col(idx)))
    fun lowRank(idx: Int): Int = rankAt(maxOf(row(idx), col(idx)))
    fun isPair(idx: Int) = row(idx) == col(idx)
    fun isSuited(idx: Int) = row(idx) < col(idx)

    fun combos(idx: Int): Int = when (kind(idx)) {
        Kind.PAIR -> 6
        Kind.SUITED -> 4
        Kind.OFFSUIT -> 12
    }

    fun name(idx: Int): String {
        val h = Card.RANK_CHARS[highRank(idx)]
        val l = Card.RANK_CHARS[lowRank(idx)]
        return when (kind(idx)) {
            Kind.PAIR -> "$h$l"
            Kind.SUITED -> "${h}${l}s"
            Kind.OFFSUIT -> "${h}${l}o"
        }
    }

    fun parse(name: String): Int {
        val n = name.trim()
        require(n.length in 2..3) { "Invalid hand '$name'" }
        val a = Card.rankOf(n[0]); val b = Card.rankOf(n[1])
        if (a == b) { require(n.length == 2); return index(a, b, false) }
        require(n.length == 3) { "Suitedness required for '$name'" }
        return when (n[2].lowercaseChar()) {
            's' -> index(a, b, true)
            'o' -> index(a, b, false)
            else -> throw IllegalArgumentException("Invalid hand '$name'")
        }
    }

    fun fromCards(c1: Int, c2: Int): Int =
        index(Card.rank(c1), Card.rank(c2), Card.suit(c1) == Card.suit(c2))

    /** One concrete combo; ranges are suit-symmetric so it is exact for card-removal math. */
    fun representative(idx: Int): Long {
        val h = highRank(idx); val l = lowRank(idx)
        return when (kind(idx)) {
            Kind.PAIR -> Card.mask(Card.id(h, 0)) or Card.mask(Card.id(h, 1))
            Kind.SUITED -> Card.mask(Card.id(h, 0)) or Card.mask(Card.id(l, 0))
            Kind.OFFSUIT -> Card.mask(Card.id(h, 0)) or Card.mask(Card.id(l, 1))
        }
    }

    /** All 1326 combos as 52-bit masks, grouped by class: [OFFSETS[i], OFFSETS[i+1]). */
    val COMBO_MASKS: LongArray
    val OFFSETS: IntArray

    init {
        val masks = LongArray(TOTAL_COMBOS)
        val offsets = IntArray(COUNT + 1)
        val buckets = Array(COUNT) { ArrayList<Long>(12) }
        for (c1 in 0 until Card.DECK_SIZE) for (c2 in c1 + 1 until Card.DECK_SIZE) {
            buckets[fromCards(c1, c2)].add(Card.mask(c1) or Card.mask(c2))
        }
        var p = 0
        for (i in 0 until COUNT) {
            offsets[i] = p
            for (m in buckets[i]) masks[p++] = m
        }
        offsets[COUNT] = p
        COMBO_MASKS = masks
        OFFSETS = offsets
    }
}
