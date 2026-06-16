package com.magi.app.v6

/**
 * Incremental (delta) evaluator — the native equivalent of the Web's BIT-DELTA framework.
 *
 * Instead of recomputing the whole objective for every candidate (Evaluator.fullEval,
 * O(S * T * constraints)), this maintains running per-piece penalty totals plus the
 * aggregates needed to update them, and computes the score change caused by a single
 * cell move (i, j): old shift -> new shift, touching only the windows/columns that can
 * actually change.
 *
 * Per-worker mutable state; not thread-safe (each SA worker owns one instance).
 *
 * The delta logic was validated against fullEval with a 20,000-move randomized
 * differential test (zero mismatches on both preview and committed score).
 */
class DeltaEvaluator(private val p: Problem, private val c3RunMode: Boolean = true) {

    private val S = p.S; private val T = p.T; private val K = p.K
    private val a: Array<IntArray>

    // aggregates
    private val cntSS = Array(S) { IntArray(K) }      // per-staff per-shift count
    private val cntDay = Array(K) { IntArray(T) }     // per-shift per-day count

    // running penalty pieces
    private var sc1 = 0L; private var sc2 = 0L; private var sc41 = 0L; private var sc42 = 0L
    private var sc3 = 0L; private var hc3n = 0L; private var sc3m = 0L; private var sc3mn = 0L
    private var hpref = 0L; private var hct = 0L
    private var covP1 = 0L; private var covP2 = 0L

    // stashed deltas from the last preview (applied by commit())
    private var lI = -1; private var lJ = -1; private var lOld = -1; private var lNw = -1
    private var dC1 = 0L; private var dC2 = 0L; private var dC41 = 0L; private var dC42 = 0L
    private var dC3 = 0L; private var dC3n = 0L; private var dC3m = 0L; private var dC3mn = 0L
    private var dPref = 0L; private var dCt = 0L; private var nCovP1 = 0L; private var nCovP2 = 0L

    init {
        a = p.initialAssignment()
        rebuild()
    }

    /** Reset to a fresh assignment and recompute all aggregates / totals. */
    fun reset(init: Array<IntArray>) {
        for (i in 0 until S) System.arraycopy(init[i], 0, a[i], 0, T)
        rebuild()
    }

    fun snapshot(): Array<IntArray> = Array(S) { a[it].copyOf() }

    /** Current shift assigned at (i,j). */
    fun at(i: Int, j: Int): Int = a[i][j]

    /** Fused previewMove + commit for a single cell. Returns the new total score. */
    fun apply(i: Int, j: Int, nw: Int): Long {
        previewMove(i, j, nw)
        commit(i, j, nw)
        return score()
    }

    fun score(): Long = scoreFrom(covP1, covP2)

    private fun scoreFrom(p1: Long, p2: Long): Long {
        val cov = if (p.use2) minOf(p1, if (p2 != 0L) p2 else p1) else p1
        val h1 = hc3n + cov + hpref
        val h2 = hct
        val soft = sc1 + sc2 + sc41 + sc42 + sc3 + sc3m + sc3mn
        return (h1 + h2) * 1_000_000L + soft
    }

    /** Preview the score after moving (i,j) -> nw, stashing deltas for commit(). No mutation of totals. */
    private fun previewMove(i: Int, j: Int, nw: Int): Long {
        val old = a[i][j]
        lI = i; lJ = j; lOld = old; lNw = nw
        if (nw == old) {
            dC1 = 0; dC2 = 0; dC41 = 0; dC42 = 0; dC3 = 0; dC3n = 0; dC3m = 0; dC3mn = 0
            dPref = 0; dCt = 0; nCovP1 = covP1; nCovP2 = covP2
            return score()
        }

        // windowed families (c1, c3 family): before/after via temporary swap
        val bC1 = c1Local(i, j); val bC3 = c3Local(i, j, p.cons3, false)
        val bC3n = c3Local(i, j, p.cons3n, true); val bC3m = c3Local(i, j, p.cons3m, false)
        val bC3mn = c3Local(i, j, p.cons3mn, true)
        a[i][j] = nw
        val aC1 = c1Local(i, j); val aC3 = c3Local(i, j, p.cons3, false)
        val aC3n = c3Local(i, j, p.cons3n, true); val aC3m = c3Local(i, j, p.cons3m, false)
        val aC3mn = c3Local(i, j, p.cons3mn, true)
        a[i][j] = old
        dC1 = (aC1 - bC1); dC3 = (aC3 - bC3); dC3n = (aC3n - bC3n); dC3m = (aC3m - bC3m); dC3mn = (aC3mn - bC3mn)

        // c2 (per-staff total) for shifts old / nw
        var d2 = 0L
        for (c in p.cons2) {
            when (c.shiftIdx) {
                old -> d2 += viol01(cntSS[i][old] - 1 < c.count) - viol01(cntSS[i][old] < c.count)
                nw -> d2 += viol01(cntSS[i][nw] + 1 < c.count) - viol01(cntSS[i][nw] < c.count)
            }
        }
        dC2 = d2

        // ct (LimMin/LimMax) for shifts old / nw
        dCt = (rangeViol(i, old, cntSS[i][old] - 1) - rangeViol(i, old, cntSS[i][old])) +
              (rangeViol(i, nw, cntSS[i][nw] + 1) - rangeViol(i, nw, cntSS[i][nw]))

        // pref (this cell only)
        val w = p.wish[i][j]
        dPref = (if (w >= 0 && nw != w) 1L else 0L) - (if (w >= 0 && old != w) 1L else 0L)

        // c41 (group/day range) on day j — only constraints touching this staff's group & shifts
        val gi = p.sgrp[i]
        var d41 = 0L
        for (c in p.cons41) {
            if (c.groupIdx != gi || (c.shiftIdx != old && c.shiftIdx != nw)) continue
            var z = 0
            for (ii in 0 until S) if (p.sgrp[ii] == c.groupIdx && a[ii][j] == c.shiftIdx) z++
            val za = z + (if (c.shiftIdx == nw) 1 else 0) - (if (c.shiftIdx == old) 1 else 0)
            d41 += viol01(za < c.l || c.u < za) - viol01(z < c.l || c.u < z)
        }
        dC41 = d41

        // c42 (group pair) on day j
        var d42 = 0L
        for (c in p.cons42) {
            var n1 = 0; var n2 = 0
            for (ii in 0 until S) {
                if (p.sgrp[ii] == c.g1 && a[ii][j] == c.s1) n1++
                if (p.sgrp[ii] == c.g2 && a[ii][j] == c.s2) n2++
            }
            val n1a = n1 + (if (c.g1 == gi && c.s1 == nw) 1 else 0) - (if (c.g1 == gi && c.s1 == old) 1 else 0)
            val n2a = n2 + (if (c.g2 == gi && c.s2 == nw) 1 else 0) - (if (c.g2 == gi && c.s2 == old) 1 else 0)
            d42 += n1a.toLong() * n2a.toLong() - n1.toLong() * n2.toLong()
        }
        dC42 = d42

        // covU: update P1/P2 totals for the two affected (shift,day) cells
        var p1 = covP1; var p2 = covP2
        val co = cntDay[old][j]
        if (p.need1[old][j] >= 0) p1 += short0(p.need1[old][j], co - 1) - short0(p.need1[old][j], co)
        if (p.use2 && p.need2[old][j] >= 0) p2 += short0(p.need2[old][j], co - 1) - short0(p.need2[old][j], co)
        val cn = cntDay[nw][j]
        if (p.need1[nw][j] >= 0) p1 += short0(p.need1[nw][j], cn + 1) - short0(p.need1[nw][j], cn)
        if (p.use2 && p.need2[nw][j] >= 0) p2 += short0(p.need2[nw][j], cn + 1) - short0(p.need2[nw][j], cn)
        nCovP1 = p1; nCovP2 = p2

        val dHard = dC3n + (covUOf(p1, p2) - covUOf(covP1, covP2)) + dPref + dCt
        val dSoft = dC1 + dC2 + dC41 + dC42 + dC3 + dC3m + dC3mn
        return score() + dHard * 1_000_000L + dSoft
    }

    /** Apply the stashed move from the last previewMove(i,j,nw). Internal — external callers use apply(). */
    private fun commit(i: Int, j: Int, nw: Int) {
        require(lI >= 0 && i == lI && j == lJ && nw == lNw) { "commit must match last preview" }
        require(a[i][j] == lOld) { "state changed after preview" }
        val old = lOld
        try {
            if (nw == old) return
            a[i][j] = nw
            cntSS[i][old]--; cntSS[i][nw]++
            cntDay[old][j]--; cntDay[nw][j]++
            sc1 += dC1; sc2 += dC2; sc41 += dC41; sc42 += dC42
            sc3 += dC3; hc3n += dC3n; sc3m += dC3m; sc3mn += dC3mn
            hpref += dPref; hct += dCt
            covP1 = nCovP1; covP2 = nCovP2
        } finally {
            // invalidate the stash so a stray double-commit cannot corrupt aggregates
            lI = -1; lJ = -1; lOld = -1; lNw = -1
        }
    }

    // ---- aggregate / total rebuild --------------------------------------------

    private fun rebuild() {
        for (i in 0 until S) java.util.Arrays.fill(cntSS[i], 0)
        for (k in 0 until K) java.util.Arrays.fill(cntDay[k], 0)
        for (i in 0 until S) for (j in 0 until T) { val k = a[i][j]; cntSS[i][k]++; cntDay[k][j]++ }

        sc1 = c1All(); sc2 = c2All(); sc41 = c41All(); sc42 = c42All()
        sc3 = c3All(p.cons3, false); hc3n = c3All(p.cons3n, true)
        sc3m = c3All(p.cons3m, false); sc3mn = c3All(p.cons3mn, true)
        hpref = prefAll(); hct = ctAll()
        val cov = covAll(); covP1 = cov[0]; covP2 = cov[1]
    }

    // ---- helpers ---------------------------------------------------------------

    private fun viol01(b: Boolean): Long = if (b) 1L else 0L
    private fun short0(need: Int, have: Int): Long = if (have < need) (need - have).toLong() else 0L
    private fun covUOf(p1: Long, p2: Long): Long =
        if (p.use2) minOf(p1, if (p2 != 0L) p2 else p1) else p1

    private fun rangeViol(i: Int, k: Int, n: Int): Long {
        val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
        val hl = lo != Int.MIN_VALUE; val hh = hi != Int.MAX_VALUE
        return when {
            hl && !hh -> if (n < lo) 1L else 0L
            !hl && hh -> if (n > hi) 1L else 0L
            hl && hh -> if (n < lo || n > hi) 1L else 0L
            else -> 0L
        }
    }

    private fun c1Local(i: Int, j: Int): Long {
        var tot = 0L
        for (c in p.cons1) {
            val js0 = maxOf(0, j - c.day1 + 1); val js1 = minOf(T - c.day1, j)
            var js = js0
            while (js <= js1) {
                var z = 0; var l = 0
                while (l < c.day1) { if (a[i][js + l] == c.shiftIdx) z++; l++ }
                if (z < c.day2) tot += c.day1
                js++
            }
        }
        return tot
    }

    private fun c3Local(i: Int, j: Int, list: List<C3>, fbd: Boolean): Long {
        var sub = 0L
        for (c in list) {
            val seq = c.seq; val D = seq.size
            if (D == 0) continue
            // [HF507] single-shift run: deficit is per-staff whole-row, not windowed.
            // A move at (i,j) only affects staff i's row, so recompute row i's run deficit
            // (before/after via the caller's swap captures the delta correctly).
            if (!fbd && c3RunMode && C3Run.isSingleShiftSeq(seq)) {
                sub += C3Run.rowDeficit(a, i, seq[0], D)
                continue
            }
            val js0 = maxOf(0, j - D + 1); val js1 = minOf(T - D, j)
            var js = js0
            while (js <= js1) {
                if (a[i][js] == seq[0]) {
                    var z = 0; var l = 1
                    while (l < D) { if (a[i][js + l] == seq[l]) z++; l++ }
                    val fire = if (fbd) (z == D - 1) else (z < D - 1)
                    if (fire) sub += D
                }
                js++
            }
        }
        return sub
    }

    private fun c1All(): Long {
        var tot = 0L
        for (c in p.cons1) for (i in 0 until S) {
            var js = 0
            while (js <= T - c.day1) {
                var z = 0; var l = 0
                while (l < c.day1) { if (a[i][js + l] == c.shiftIdx) z++; l++ }
                if (z < c.day2) tot += c.day1
                js++
            }
        }
        return tot
    }

    private fun c2All(): Long {
        var tot = 0L
        for (c in p.cons2) for (i in 0 until S) if (cntSS[i][c.shiftIdx] < c.count) tot += 1
        return tot
    }

    private fun c41All(): Long {
        var tot = 0L
        for (c in p.cons41) for (j in 0 until T) {
            var z = 0
            for (i in 0 until S) if (p.sgrp[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
            if (z < c.l || c.u < z) tot += 1
        }
        return tot
    }

    private fun c42All(): Long {
        var tot = 0L
        for (c in p.cons42) for (j in 0 until T) {
            var n1 = 0; var n2 = 0
            for (i in 0 until S) {
                if (p.sgrp[i] == c.g1 && a[i][j] == c.s1) n1++
                if (p.sgrp[i] == c.g2 && a[i][j] == c.s2) n2++
            }
            tot += n1.toLong() * n2.toLong()
        }
        return tot
    }

    private fun c3All(list: List<C3>, fbd: Boolean): Long {
        var sub = 0L
        for (c in list) {
            val seq = c.seq; val D = seq.size
            if (D == 0) continue
            // [HF507] non-forbidden single-shift run -> run deficit (per staff whole-row)
            if (!fbd && c3RunMode && C3Run.isSingleShiftSeq(seq)) {
                for (i in 0 until S) sub += C3Run.rowDeficit(a, i, seq[0], D)
                continue
            }
            for (i in 0 until S) {
                var j = 0
                while (j <= T - D) {
                    if (a[i][j] == seq[0]) {
                        var z = 0; var l = 1
                        while (l < D) { if (a[i][j + l] == seq[l]) z++; l++ }
                        val fire = if (fbd) (z == D - 1) else (z < D - 1)
                        if (fire) sub += D
                    }
                    j++
                }
            }
        }
        return sub
    }

    private fun prefAll(): Long {
        var h = 0L
        for (i in 0 until S) for (j in 0 until T) {
            val w = p.wish[i][j]; if (w >= 0 && a[i][j] != w) h++
        }
        return h
    }

    private fun ctAll(): Long {
        var h = 0L
        for (i in 0 until S) for (k in 0 until K) h += rangeViol(i, k, cntSS[i][k])
        return h
    }

    private fun covAll(): LongArray {
        var p1 = 0L; var p2 = 0L
        for (j in 0 until T) for (k in 0 until K) {
            val n = p.need1[k][j]; val have = cntDay[k][j]
            if (n >= 0 && have < n) p1 += (n - have)
            if (p.use2) { val n2 = p.need2[k][j]; if (n2 >= 0 && have < n2) p2 += (n2 - have) }
        }
        return longArrayOf(p1, p2)
    }
}
