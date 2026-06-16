package com.magi.app.v6

import com.magi.app.model.MagiState

/** Resolved constraint rows in index form (kigou -> indices). */
class C1(@JvmField val day1: Int, @JvmField val shiftIdx: Int, @JvmField val day2: Int)
class C2(@JvmField val shiftIdx: Int, @JvmField val count: Int)
class C3(@JvmField val seq: IntArray)
class C41(@JvmField val groupIdx: Int, @JvmField val shiftIdx: Int, @JvmField val l: Int, @JvmField val u: Int)
class C42(@JvmField val g1: Int, @JvmField val s1: Int, @JvmField val g2: Int, @JvmField val s2: Int)

/**
 * Immutable, index-resolved view of a [MagiState] ready for fast evaluation.
 * Faithfully mirrors the Web worker's prelude + resolveConstraints().
 */
class Problem(val state: MagiState) {
    companion object {
        @Volatile private var cached: Problem? = null

        /**
         * Identity-cached factory. [Problem] is immutable and a pure function of [state]
         * (it re-parses needs/constraints on construction), so the optimizer's many
         * per-iteration evaluations of the same state object reuse one instance instead of
         * rebuilding it every call. The single shared instance is safe across SA workers
         * because nothing mutates Problem after construction.
         */
        fun of(state: MagiState): Problem {
            val c = cached
            if (c != null && c.state === state) return c
            return Problem(state).also { cached = it }
        }
    }

    val S = state.staffCount
    val T = state.dayCount
    val K = state.shiftCount
    val G = state.groupCount
    val use2 = state.use2Patterns

    val sgrp = IntArray(S) { state.staff[it].groupIdx }

    /** Allowed shift indices per group (groupShift[g][k]==1). */
    val bucket: Array<IntArray> = Array(G) { g ->
        val row = state.groupShift.getOrNull(g) ?: emptyList()
        (0 until K).filter { k -> row.getOrNull(k) == 1 }.toIntArray()
    }

    /** Staff indices that may take a given shift (used by block-fill moves). */
    val staffForShift: Array<IntArray> = Array(K) { k ->
        (0 until S).filter { i -> bucket[state.staff[i].groupIdx].contains(k) }.toIntArray()
    }

    /** wish[i][j] = desired shift index, or -1. */
    val wish: Array<IntArray> = Array(S) { IntArray(T) { -1 } }

    /** need1[k][j] / need2[k][j] = required count, or -1 (= no requirement). */
    val need1: Array<IntArray>
    val need2: Array<IntArray>

    /** rangeLo/Hi[i][k] = LimMin/LimMax, or Int.MIN/MAX_VALUE when unset. */
    val rangeLo: Array<IntArray>
    val rangeHi: Array<IntArray>

    val cons1: List<C1>
    val cons2: List<C2>
    val cons3: List<C3>
    val cons3n: List<C3>
    val cons3m: List<C3>
    val cons3mn: List<C3>
    val cons41: List<C41>
    val cons42: List<C42>

    init {
        for ((key, v) in state.wishes) {
            val p = key.split(',')
            val i = p.getOrNull(0)?.toIntOrNull() ?: continue
            val j = p.getOrNull(1)?.toIntOrNull() ?: continue
            if (i in 0 until S && j in 0 until T) wish[i][j] = v
        }

        need1 = Array(K) { IntArray(T) { -1 } }
        need2 = Array(K) { IntArray(T) { -1 } }
        for (k in 0 until K) for (j in 0 until T) {
            need1[k][j] = needAt(k, j, false)
            need2[k][j] = needAt(k, j, true)
        }

        rangeLo = Array(S) { IntArray(K) { Int.MIN_VALUE } }
        rangeHi = Array(S) { IntArray(K) { Int.MAX_VALUE } }
        for ((key, r) in state.staffRange) {
            val p = key.split(',')
            val i = p.getOrNull(0)?.toIntOrNull() ?: continue
            val k = p.getOrNull(1)?.toIntOrNull() ?: continue
            if (i in 0 until S && k in 0 until K) {
                r.lo.trim().toIntOrNull()?.let { rangeLo[i][k] = it }
                r.hi.trim().toIntOrNull()?.let { rangeHi[i][k] = it }
            }
        }

        cons1 = state.cons1.mapNotNull {
            val d1 = it.day1.toIntOrNull() ?: 0
            val si = shiftIdxOf(it.shiftKigou)
            val d2 = it.day2.toIntOrNull() ?: 0
            if (d1 > 0 && si >= 0 && d2 > 0) C1(d1, si, d2) else null
        }
        cons2 = state.cons2.mapNotNull {
            val si = shiftIdxOf(it.shiftKigou)
            val c = it.count.toIntOrNull() ?: 0
            if (si >= 0 && c > 0) C2(si, c) else null
        }
        cons3 = resolveC3(state.cons3)
        cons3n = resolveC3(state.cons3n)
        cons3m = resolveC3(state.cons3m)
        cons3mn = resolveC3(state.cons3mn)
        cons41 = state.cons41.mapNotNull {
            val gi = groupIdxOf(it.groupKigou)
            val si = shiftIdxOf(it.shiftKigou)
            val hasLo = it.l.isNotBlank()
            val hasHi = it.u.isNotBlank()
            val lo = if (hasLo) it.l.toIntOrNull() ?: 0 else 0
            val hi = if (hasHi) it.u.toIntOrNull() ?: Int.MAX_VALUE else Int.MAX_VALUE
            if (gi >= 0 && si >= 0 && (hasLo || hasHi)) C41(gi, si, lo, hi) else null
        }
        cons42 = state.cons42.mapNotNull {
            val g1 = groupIdxOf(it.g1Kigou); val g2 = groupIdxOf(it.g2Kigou)
            val s1 = shiftIdxOf(it.s1Kigou); val s2 = shiftIdxOf(it.s2Kigou)
            if (g1 >= 0 && g2 >= 0 && s1 >= 0 && s2 >= 0) C42(g1, s1, g2, s2) else null
        }
    }

    /** getNeed(k,j,p2): per-day override, falling back to shift default; -1 == none. */
    private fun needAt(k: Int, j: Int, p2: Boolean): Int {
        val map = if (p2) state.needDay2 else state.needDay1
        val v = map["$k,$j"]
        if (!v.isNullOrBlank()) v.trim().toIntOrNull()?.let { return it }
        val def = if (p2) state.shifts[k].need2 else state.shifts[k].need1
        return if (def.isBlank()) -1 else (def.trim().toIntOrNull() ?: -1)
    }

    private fun shiftIdxOf(kigou: String): Int = state.shifts.indexOfFirst { it.kigou == kigou }
    private fun groupIdxOf(kigou: String): Int = state.groups.indexOfFirst { it.kigou == kigou }

    /**
     * resolveC3: truncate the pattern at the first blank symbol; drop the whole row if
     * an interior symbol is unresolvable (mirrors the Web doc7#4 fix — never compact).
     */
    private fun resolveC3(rows: List<com.magi.app.model.C3Row>): List<C3> = rows.mapNotNull { row ->
        val raw = row.pattern
        val end = raw.indexOfFirst { it.isBlank() }
        val body = if (end >= 0) raw.subList(0, end) else raw
        if (body.isEmpty()) return@mapNotNull null
        val seq = IntArray(body.size)
        for (idx in body.indices) {
            val si = shiftIdxOf(body[idx])
            if (si < 0) return@mapNotNull null
            seq[idx] = si
        }
        C3(seq)
    }

    /**
     * Initial assignment from state.schedule, overwriting a cell with its wish only when
     * the wished shift is actually in the staff's allowed bucket (Web HF143 capability guard).
     */
    fun initialAssignment(): Array<IntArray> = Array(S) { i ->
        val b = bucket[sgrp[i]]
        IntArray(T) { j ->
            var k = state.schedule.getOrNull(i)?.getOrNull(j) ?: 0
            val w = wish[i][j]
            if (w >= 0 && b.contains(w)) k = w
            if (k < 0 || k >= K) k = 0
            k
        }
    }
}
