package com.magi.app.v6

/**
 * Faithful port of the Web worker's `fullEval`.
 *
 * Lexicographic objective:  score = (hard1 + hard2) * 1_000_000 + soft
 *   hard1 = c3n (forbidden seq) + covU (per-day need shortfall, MIN=OR over P1/P2) + pref
 *   hard2 = ct  (LimMin / LimMax range violations)
 *   soft  = c1 (window) + c2 (per-staff total) + c41 (group/day range)
 *           + c42 (group pair conflict) + c3 (want seq) + c3m + c3mn
 *
 * The solution `a[i][j]` is the assigned shift index (exactly one shift per cell),
 * the equivalent of the Web's one-hot `x[i][j][k] === 1`.
 *
 * Phase 1 recomputes the whole objective per candidate (no BIT-DELTA). It is exact;
 * native speed absorbs the cost. Δ-evaluation is a later optimization.
 */
class Evaluator(private val p: Problem, private val c3RunMode: Boolean = true) {

    fun fullEval(a: Array<IntArray>): Long {
        val S = p.S; val T = p.T; val K = p.K
        var hard1 = 0L
        var hard2 = 0L
        var soft = 0L

        // c1: every window of length day1 must contain >= day2 of shiftIdx
        for (c in p.cons1) {
            val d1 = c.day1; val si = c.shiftIdx; val d2 = c.day2
            for (i in 0 until S) {
                var j = 0
                while (j <= T - d1) {
                    var z = 0
                    var l = 0
                    while (l < d1) { if (a[i][j + l] == si) z++; l++ }
                    if (z < d2) soft += d1
                    j++
                }
            }
        }

        // c2: per-staff total of a shift must reach count
        for (c in p.cons2) {
            for (i in 0 until S) {
                var z = 0
                for (j in 0 until T) if (a[i][j] == c.shiftIdx) z++
                if (z < c.count) soft += 1
            }
        }

        // c41: per-day, count of (group, shift) must lie in [l, u]
        for (c in p.cons41) {
            for (j in 0 until T) {
                var z = 0
                for (i in 0 until S) if (p.sgrp[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
                if (z < c.l || c.u < z) soft += 1
            }
        }

        // c42: per-day, (g1,s1) co-occurring with (g2,s2) is penalized per pair
        for (c in p.cons42) {
            for (j in 0 until T) {
                var n1 = 0; var n2 = 0
                for (i in 0 until S) {
                    if (p.sgrp[i] == c.g1 && a[i][j] == c.s1) n1++
                    if (p.sgrp[i] == c.g2 && a[i][j] == c.s2) n2++
                }
                soft += n1.toLong() * n2.toLong()
            }
        }

        // c3 family
        soft += c3check(a, p.cons3, false)
        hard1 += c3check(a, p.cons3n, true)    // forbidden -> display HARD
        soft += c3check(a, p.cons3m, false)
        soft += c3check(a, p.cons3mn, true)

        // pref: wished cell not honored -> display HARD
        for (i in 0 until S) for (j in 0 until T) {
            val w = p.wish[i][j]
            if (w >= 0 && a[i][j] != w) hard1 += 1
        }

        // ct: per-staff per-shift count range (LimMin / LimMax)
        val ssn = Array(S) { IntArray(K) }
        for (i in 0 until S) for (j in 0 until T) ssn[i][a[i][j]]++
        for (i in 0 until S) for (k in 0 until K) {
            val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
            val n = ssn[i][k]
            val hasLo = lo != Int.MIN_VALUE; val hasHi = hi != Int.MAX_VALUE
            if (hasLo && !hasHi) { if (n < lo) hard2 += 1 }
            else if (!hasLo && hasHi) { if (n > hi) hard2 += 1 }
            else if (hasLo && hasHi) { if (n < lo || n > hi) hard2 += 1 }
        }

        // covU: per-day need shortfall. MIN=OR two-generation design (P1 vs P2).
        var c2v1 = 0L; var c2v2 = 0L
        for (j in 0 until T) {
            for (k in 0 until K) {
                val n = p.need1[k][j]
                if (n >= 0) {
                    var dsn = 0
                    for (i in 0 until S) if (a[i][j] == k) dsn++
                    if (dsn < n) c2v1 += (n - dsn)
                }
                if (p.use2) {
                    val n2 = p.need2[k][j]
                    if (n2 >= 0) {
                        var dsn2 = 0
                        for (i in 0 until S) if (a[i][j] == k) dsn2++
                        if (dsn2 < n2) c2v2 += (n2 - dsn2)
                    }
                }
            }
        }
        hard1 += if (p.use2) minOf(c2v1, if (c2v2 != 0L) c2v2 else c2v1) else c2v1

        return (hard1 + hard2) * 1_000_000L + soft
    }

    /** Returns the hard / soft split for display (運用違反 vs SOFT). */
    fun split(score: Long): Pair<Long, Long> = (score / 1_000_000L) to (score % 1_000_000L)

    private fun c3check(a: Array<IntArray>, list: List<C3>, forbidden: Boolean): Long {
        val S = p.S; val T = p.T
        var sub = 0L
        for (c in list) {
            val seq = c.seq
            val D = seq.size
            if (D == 0) continue
            val first = seq[0]
            // [HF507] non-forbidden single-shift run -> run deficit (per staff whole-row)
            if (!forbidden && c3RunMode && C3Run.isSingleShiftSeq(seq)) {
                for (i in 0 until S) sub += C3Run.rowDeficit(a, i, first, D)
                continue
            }
            for (i in 0 until S) {
                var j = 0
                while (j <= T - D) {
                    if (a[i][j] == first) {
                        var z = 0
                        var l = 1
                        while (l < D) { if (a[i][j + l] == seq[l]) z++; l++ }
                        val fire = if (forbidden) (z == D - 1) else (z < D - 1)
                        if (fire) sub += D
                    }
                    j++
                }
            }
        }
        return sub
    }
}
