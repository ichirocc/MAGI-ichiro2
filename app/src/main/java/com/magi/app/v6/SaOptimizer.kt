package com.magi.app.v6

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.Random
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.min

/**
 * SA tunables. Defaults mirror the Web baseline SA-ver1 (t0=10, tf=0.1, a=0.975, ll=20).
 *
 * [softPolish] (default OFF) enables a faithful port of the Web PhaseB late-acceptance
 * SOFT-polish (LAHC, lahcHistoryLen=200). A worker switches into PhaseB only after its HARD
 * best has not improved for [hardStallMs] (i.e. the HARD floor is reached), and PhaseB is
 * HARD-guarded — it never accepts a move that raises the achieved HARD level, so it can only
 * reduce SOFT. Left off by default because on short, HARD-time-bound budgets uninterrupted
 * PhaseA SA is at least as good (see README, "Phase 4" — high run-to-run variance).
 */
data class SaParams(
    val t0: Double = 10.0,
    val tf: Double = 0.1,
    val alpha: Double = 0.975,
    val chain: Int = 20,
    val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    val budgetMs: Long = 8_000,
    val softPolish: Boolean = false,
    val hardStallMs: Long = 2_500,
    val lahcLen: Int = 200,
)

data class SaProgress(val bestScore: Long, val totalIters: Long, val elapsedMs: Long)

data class SaResult(
    val schedule: Array<IntArray>,
    val score: Long,
    val totalIters: Long,
    val elapsedMs: Long,
)

/**
 * Parallel SA with incremental (delta) evaluation, a multi-operator neighbourhood, and an
 * optional HARD-guarded PhaseB SOFT-polish. Each coroutine worker owns a [DeltaEvaluator],
 * runs independently with its own RNG, and the global best is kept. The final best is
 * reconciled once with the full Evaluator as a safety net.
 */
class SaOptimizer(private val problem: Problem, private val evaluator: Evaluator) {

    private val M = 1_000_000L

    suspend fun run(
        params: SaParams = SaParams(),
        onProgress: (SaProgress) -> Unit = {},
    ): SaResult = coroutineScope {
        val init = problem.initialAssignment()
        val start = (System.nanoTime() / 1_000_000L)

        var globalBest = evaluator.fullEval(init)
        var globalBestSol = copyOf(init)
        var totalIters = 0L
        val lock = Any()

        fun report() { onProgress(SaProgress(globalBest, totalIters, (System.nanoTime() / 1_000_000L) - start)) }
        report()

        val jobs = (0 until params.workers).map { w ->
            async(Dispatchers.Default) {
                val seed = System.nanoTime() xor (w.toLong() * -0x61c8864680b583ebL)
                runWorker(init, params, Random(seed), start) { localBest, localSol, iters ->
                    synchronized(lock) {
                        totalIters += iters
                        if (localBest < globalBest) { globalBest = localBest; globalBestSol = localSol }
                        report()
                    }
                }
            }
        }
        jobs.awaitAll()

        val finalScore = evaluator.fullEval(globalBestSol)
        synchronized(lock) { globalBest = finalScore; report() }
        SaResult(globalBestSol, finalScore, totalIters, (System.nanoTime() / 1_000_000L) - start)
    }

    private suspend fun runWorker(
        init: Array<IntArray>,
        params: SaParams,
        rng: Random,
        start: Long,
        flush: (Long, Array<IntArray>, Long) -> Unit,
    ) {
        val S = problem.S; val T = problem.T
        val de = DeltaEvaluator(problem)
        de.reset(init)
        var curVal = de.score()
        var best = curVal
        var bestSol = de.snapshot()
        var bestHard = best / M
        var lastHardImprove = (System.nanoTime() / 1_000_000L)

        val cap = T + S + 16
        val bi = IntArray(cap); val bj = IntArray(cap); val bOld = IntArray(cap)
        var bn = 0
        fun applyCell(i: Int, j: Int, nw: Int) {
            if (bn >= cap) return
            bi[bn] = i; bj[bn] = j; bOld[bn] = de.at(i, j); bn++
            de.apply(i, j, nw)
        }
        fun revert() { var k = bn - 1; while (k >= 0) { de.apply(bi[k], bj[k], bOld[k]); k-- }; bn = 0 }
        fun randShiftFor(i: Int): Int {
            val b = problem.bucket[problem.sgrp[i]]
            return if (b.isEmpty()) de.at(i, 0) else b[rng.nextInt(b.size)]
        }
        fun opSingle() {
            val i = rng.nextInt(S); val j = rng.nextInt(T)
            val b = problem.bucket[problem.sgrp[i]]
            if (b.isEmpty()) return
            applyCell(i, j, b[rng.nextInt(b.size)])
        }
        fun opSwapDays() {
            val i = rng.nextInt(S)
            if (T < 2) return
            val j1 = rng.nextInt(T); var j2 = rng.nextInt(T)
            if (j1 == j2) j2 = (j2 + 1) % T
            val o1 = de.at(i, j1); val o2 = de.at(i, j2)
            if (o1 == o2) return
            applyCell(i, j1, o2); applyCell(i, j2, o1)
        }
        fun opBlockFill() {
            val cs = problem.cons1
            if (cs.isEmpty()) { opSingle(); return }
            val c = cs[rng.nextInt(cs.size)]
            val pool = problem.staffForShift[c.shiftIdx]
            if (pool.isEmpty()) { opSingle(); return }
            val i = pool[rng.nextInt(pool.size)]
            val maxStart = T - c.day1
            if (maxStart < 0) { opSingle(); return }
            val js = rng.nextInt(maxStart + 1)
            var l = 0
            while (l < c.day1) { applyCell(i, js + l, c.shiftIdx); l++ }
        }
        fun opLns() {
            when (rng.nextInt(3)) {
                0 -> { val i = rng.nextInt(S); val cnt = 2 + rng.nextInt(min(7, T)); var k = 0
                    while (k < cnt) { applyCell(i, rng.nextInt(T), randShiftFor(i)); k++ } }
                1 -> { val j = rng.nextInt(T); var i = 0
                    while (i < S) { applyCell(i, j, randShiftFor(i)); i++ } }
                else -> { val cnt = 3 + rng.nextInt(8); var k = 0
                    while (k < cnt) { val i = rng.nextInt(S); applyCell(i, rng.nextInt(T), randShiftFor(i)); k++ } }
            }
        }
        val hasC1 = problem.cons1.isNotEmpty()
        fun pickOperator() {
            when (val r = rng.nextInt(100)) {
                in 0 until 60 -> opSingle()
                in 60 until 80 -> opSwapDays()
                in 80 until 92 -> if (hasC1) opBlockFill() else opSingle()
                else -> opLns()
            }
        }

        var itersSinceFlush = 0L
        val flushEvery = 8000
        var phaseB = false
        var hist = LongArray(0)
        var bIt = 0L

        fun timeUp() = (System.nanoTime() / 1_000_000L) - start >= params.budgetMs

        while (!timeUp()) {
            coroutineContext.ensureActive()

            if (!phaseB) {
                // ----- PhaseA: SA, reset-to-best reheat at cooling completion -----
                var t = params.t0
                cooling@ while (t >= params.tf && !timeUp()) {
                    var ls = 0
                    while (ls < params.chain) {
                        bn = 0
                        pickOperator()
                        val cand = de.score()
                        val dE = cand - curVal
                        if (dE <= 0 || exp(-dE.toDouble() / t) > rng.nextDouble()) {
                            curVal = cand
                            if (cand < best) {
                                if (cand / M < bestHard) { bestHard = cand / M; lastHardImprove = (System.nanoTime() / 1_000_000L) }
                                best = cand; bestSol = de.snapshot()
                            }
                            bn = 0
                        } else revert()

                        itersSinceFlush++
                        if (itersSinceFlush >= flushEvery) {
                            flush(best, copyOf(bestSol), itersSinceFlush); itersSinceFlush = 0
                            coroutineContext.ensureActive()
                            if (timeUp()) { flush(best, copyOf(bestSol), 0); return }
                        }
                        if (params.softPolish && (System.nanoTime() / 1_000_000L) - lastHardImprove > params.hardStallMs) {
                            phaseB = true; break@cooling
                        }
                        ls++
                    }
                    t *= params.alpha
                }
                // reheat / or enter PhaseB from the best
                de.reset(bestSol); curVal = best
                if (phaseB) { hist = LongArray(params.lahcLen) { curVal }; bIt = 0 }
            } else {
                // ----- PhaseB: HARD-guarded LAHC SOFT polish -----
                bn = 0
                pickOperator()
                val cand = de.score()
                val candHard = cand / M
                val v = hist[(bIt % params.lahcLen).toInt()]
                if (candHard <= bestHard && (cand <= v || cand <= curVal)) {
                    curVal = cand
                    if (candHard < bestHard) bestHard = candHard
                    if (cand < best) { best = cand; bestSol = de.snapshot() }
                    bn = 0
                } else revert()
                if (curVal < hist[(bIt % params.lahcLen).toInt()]) hist[(bIt % params.lahcLen).toInt()] = curVal
                bIt++

                itersSinceFlush++
                if (itersSinceFlush >= flushEvery) {
                    flush(best, copyOf(bestSol), itersSinceFlush); itersSinceFlush = 0
                    coroutineContext.ensureActive()
                    if (timeUp()) { flush(best, copyOf(bestSol), 0); return }
                }
            }
        }
        flush(best, copyOf(bestSol), itersSinceFlush)
    }

    private fun copyOf(a: Array<IntArray>): Array<IntArray> = Array(a.size) { a[it].copyOf() }
}
