package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import java.util.Random

/**
 * Native port / fusion of the V6 Web optimizer dispatcher.
 *
 * Web V6 chooses V5 / ALNS / RSI / RSI++ by budget and then runs post-passes
 * (HF66/HF67/HF80 family).  This Kotlin version keeps the same public semantics:
 * AUTO chooses an algorithm by time budget, V5 is parallel SA, ALNS uses destroy/repair
 * multi-restart, RSI focuses on the currently most violated family, and RSI++ chains
 * seed -> hypothesis -> refine -> polish.
 */
enum class V6Algorithm { AUTO, V5, ALNS, RSI, RSI_PLUS }

data class V6OptimizerOptions(
    val algorithm: V6Algorithm = V6Algorithm.AUTO,
    val totalBudgetSec: Int = 600,
    val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    val softPolish: Boolean = true,
    val restarts: Int = 2,
    val seed: Long = 0L,
    /** [HF528/532移植] RectSwap2/C1BlockN を RSI 系へ伝播。Web optFlags.rectSwap 既定ON(HF532 恒久ON確定)。 */
    val rectSwap: Boolean = true,
    /** Run the final HF80 epilogue polish inside optimize(). Set false when the caller
     *  (e.g. V6FinalPort.handleOptimize) runs its own post-optimization chain, to avoid
     *  polishing twice. Direct callers keep the default so they still get a polish. */
    val postPolish: Boolean = true,
)

data class V6OptimizerResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val algorithm: V6Algorithm,
    val phaseLogs: List<MirrorLog>,
    val iterations: Long,
    val elapsedMs: Long,
)

object V6NativeOptimizer {
    /** 直近の並列探索で得た「他の案」（採用案以外の候補スケジュール、品質順・最大3件）。 */
    @Volatile var lastAlternatives: List<Array<IntArray>> = emptyList()
        private set

    suspend fun optimize(
        state: MagiState,
        initial: Array<IntArray> = state.schedule.toIntArray2D(),
        options: V6OptimizerOptions = V6OptimizerOptions(),
        onProgress: (String, ViolationReport?, Long, Long) -> Unit = { _, _, _, _ -> },
    ): V6OptimizerResult {
        val started = nowMs()
        lastAlternatives = emptyList()
        val chosen = chooseAlgorithm(options.algorithm, options.totalBudgetSec)
        val p = Problem(state)
        var schedule = hf66DataHardening(state, normalizeSchedule(initial, p), "pre")
        schedule = hf67HardRepair(state, schedule, Random(actualSeed(options.seed) xor 0x67L)).schedule
        var logs = listOf(MirrorLog(tag = "V6Dispatcher", message = "algorithm=$chosen budget=${options.totalBudgetSec}s workers=${options.workers}"))
        // 仕様書 §2.2/§4.1: 最大5仮説を並列探索。
        val w = options.workers.coerceIn(1, 5)
        val full = max(1, options.totalBudgetSec)
        val result = when (chosen) {
            // V5 already runs `workers` parallel SA chains inside SaOptimizer.
            V6Algorithm.V5 -> runV5(state, schedule, options, full, onProgress)
            // ALNS/RSI/RSI++ are run as up to 5 parallel hypotheses with hybrid early-cancel.
            V6Algorithm.ALNS -> runMultiWorker(w, options, onProgress) { o, prog -> runAlns(state, schedule.copy2D(), o, full, prog) }
            V6Algorithm.RSI -> runMultiWorker(w, options, onProgress) { o, prog -> runRsi(state, schedule.copy2D(), o, full, prog) }
            V6Algorithm.RSI_PLUS -> runMultiWorker(w, options, onProgress) { o, prog -> runRsiPlus(state, schedule.copy2D(), o, full, prog) }
            V6Algorithm.AUTO -> error("AUTO must be resolved")
        }
        logs = logs + result.phaseLogs
        // [review #3] Final epilogue polish only when the caller isn't running its own post chain.
        val polished = if (options.postPolish)
            hf80PostPolish(state, result.schedule, max(1, min(30, options.totalBudgetSec / 20)), actualSeed(options.seed) xor 0x80L)
        else PolishResult(result.schedule, emptyList(), 0)
        val finalReport = UnifiedViolationChecker.check(state, polished.schedule)
        logs = logs + polished.logs + MirrorLog(
            tag = "V6Dispatcher",
            message = "完了 algorithm=$chosen HARD=${finalReport.hard} total=${finalReport.total} elapsed=${nowMs() - started}ms",
        )
        return V6OptimizerResult(polished.schedule, finalReport.copy(logs = logs + finalReport.logs), chosen, logs, result.iterations + polished.iterations, nowMs() - started)
    }

    /**
     * Run up to [w] independent hypotheses concurrently (distinct seeds) and keep the best —
     * the native W0..Wn multi-worker pool with the spec's hybrid termination (§2.2/§4.2):
     *  - 絶対評価: the first hypothesis to reach the pass line (HARD=0) cancels the others
     *    immediately (saves battery/heat); the winner finishes its own budget (soft polish).
     *  - 相対評価: if none passes by the deadline, the lowest-penalty hypothesis is adopted.
     * Worker 0's progress is forwarded, prefixed with the number of hypotheses still running.
     */
    private suspend fun runMultiWorker(
        w: Int,
        options: V6OptimizerOptions,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
        run: suspend (V6OptimizerOptions, (String, ViolationReport?, Long, Long) -> Unit) -> V6OptimizerResult,
    ): V6OptimizerResult = coroutineScope {
        if (w <= 1) return@coroutineScope run(options.copy(workers = 1), onProgress)
        val base = actualSeed(options.seed)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val winner = java.util.concurrent.atomic.AtomicInteger(-1)
        val jobs = arrayOfNulls<kotlinx.coroutines.Deferred<V6OptimizerResult>>(w)
        for (i in 0 until w) {
            jobs[i] = async(Dispatchers.Default) {
                val res = run(options.copy(workers = 1, seed = base + (i + 1) * 0x9E3779B1L)) { phase, report, iters, elapsed ->
                    if (i == 0) onProgress("仮説${(w - completed.get()).coerceAtLeast(1)}本探索中 / $phase", report, iters, elapsed)
                    // 絶対評価: 合格ライン(HARD=0)に最初に到達した仮説が、残りを即キャンセル
                    if (report != null && report.hard == 0 && winner.compareAndSet(-1, i)) {
                        for (j in 0 until w) if (j != i) jobs[j]?.cancel()
                    }
                }
                completed.incrementAndGet()
                res
            }
        }
        val results = jobs.mapNotNull { d ->
            try { d?.await() } catch (_: kotlinx.coroutines.CancellationException) { null }
        }
        // 兄弟キャンセル(自己)とユーザー停止(外部)を区別: 外部停止ならここで伝播させる。
        ensureActive()
        val best = if (results.isEmpty()) run(options.copy(workers = 1), onProgress)
        else results.reduce { a, b -> if (better(b.report, a.report)) b else a }
        // 「他の案」: 採用案以外の仮説結果を品質順に保持（重複schedule除外、最大3件）
        lastAlternatives = results.asSequence()
            .filter { it !== best }
            .sortedWith(compareBy({ it.report.hard }, { it.report.total }))
            .map { it.schedule }
            .distinctBy { sch -> sch.joinToString("|") { it.joinToString(",") } }
            .take(3)
            .toList()
        val totalIters = results.sumOf { it.iterations }
        val mode = if (winner.get() >= 0) "合格で早期キャンセル" else "時間内最良採用"
        val extra = MirrorLog(tag = "MultiWorker", message = "仮説 ${w} 本 ($mode) → 採用 HARD=${best.report.hard} total=${best.report.total} 合計iter=${totalIters}")
        best.copy(phaseLogs = best.phaseLogs + extra, iterations = totalIters)
    }

    /** Roulette-wheel operator selection for the adaptive LNS. */
    private fun rouletteSelect(weights: DoubleArray, rng: Random): Int {
        var sum = 0.0
        for (wgt in weights) sum += wgt
        if (sum <= 0.0) return rng.nextInt(weights.size)
        var r = rng.nextDouble() * sum
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0.0) return i
        }
        return weights.size - 1
    }

    fun chooseAlgorithm(requested: V6Algorithm, budgetSec: Int): V6Algorithm {
        if (requested != V6Algorithm.AUTO) return requested
        return when {
            budgetSec <= 30 -> V6Algorithm.V5
            budgetSec <= 180 -> V6Algorithm.ALNS
            budgetSec <= 300 -> V6Algorithm.RSI
            else -> V6Algorithm.RSI_PLUS
        }
    }

    private suspend fun runV5(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val t0 = nowMs()
        val p = Problem(state.withSchedule(initial))
        val ev = Evaluator(p)
        var lastReport: ViolationReport? = null
        val res = SaOptimizer(p, ev).run(
            SaParams(workers = options.workers, budgetMs = budgetSec * 1000L, softPolish = options.softPolish),
        ) { pr ->
            if (pr.elapsedMs % 1000L < 220L) onProgress("V5 SA", lastReport, pr.totalIters, pr.elapsedMs)
        }
        val repaired = hf67HardRepair(state, res.schedule, Random(actualSeed(options.seed) xor 0x5L))
        val report = UnifiedViolationChecker.check(state, repaired.schedule)
        lastReport = report
        val logs = listOf(MirrorLog(tag = "RunMAGI_V5", message = "高速SA完了 HARD=${report.hard} total=${report.total} iter=${res.totalIters}")) + repaired.logs
        return V6OptimizerResult(repaired.schedule, report.copy(logs = logs + report.logs), V6Algorithm.V5, logs, res.totalIters, nowMs() - t0)
    }

    private suspend fun runAlns(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val started = nowMs()
        val rng = Random(actualSeed(options.seed) xor 0xA17A5L)
        val p = Problem(state)
        val restarts = max(1, options.restarts)
        val per = max(1, budgetSec / restarts)
        var globalBest = normalizeSchedule(initial, p)
        var globalReport = UnifiedViolationChecker.check(state, globalBest)
        var itersTotal = 0L
        val logs = ArrayList<MirrorLog>()
        for (r in 0 until restarts) {
            coroutineContext.ensureActive()
            var cur = if (r == 0) globalBest.copy2D() else perturb(state, globalBest, rng, strength = 0.18)
            cur = hf67HardRepair(state, cur, rng).schedule
            var curReport = UnifiedViolationChecker.check(state, cur)
            val deadline = nowMs() + per * 1000L
            var iter = 0L
            // [Adaptive LNS] learned operator weights (roulette-wheel selection + reaction-factor
            // update), per Ropke & Pisinger and recent adaptive-LNS personnel-scheduling work
            // (Ouberkouk, Boufflet & Moukrim, J. Heuristics 2023). Replaces uniform operator choice.
            val opW = DoubleArray(5) { 1.0 }
            val opScore = DoubleArray(5)
            val opCnt = IntArray(5)
            var sinceUpdate = 0
            while (nowMs() < deadline) {
                coroutineContext.ensureActive()
                val op = rouletteSelect(opW, rng)
                val cand = cur.copy2D()
                when (op) {
                    0 -> destroyRepairDay(state, cand, rng)
                    1 -> destroyRepairStaff(state, cand, rng)
                    2 -> destroyRepairViolations(state, cand, curReport, rng)
                    3 -> swapWithinStaff(state, cand, rng)
                    else -> randomAllowedCell(state, cand, rng)
                }
                val fixed = if (iter % 7L == 0L) hf67HardRepair(state, cand, rng).schedule else cand
                val rep = UnifiedViolationChecker.check(state, fixed)
                val temp = max(0.03, (deadline - nowMs()).toDouble() / max(1.0, per * 1000.0))
                val improvedGlobal = better(rep, globalReport)
                val improvedCur = better(rep, curReport)
                val accepted = improvedCur || acceptWorse(rep, curReport, temp, rng)
                if (accepted) {
                    cur = fixed
                    curReport = rep
                    if (improvedGlobal) {
                        globalBest = fixed.copy2D()
                        globalReport = rep
                    }
                }
                // reward: new global best > improving > accepted-worse > rejected
                opScore[op] += if (improvedGlobal) 4.0 else if (improvedCur) 2.0 else if (accepted) 1.0 else 0.2
                opCnt[op]++
                if (++sinceUpdate >= 64) {
                    for (k in opW.indices) {
                        if (opCnt[k] > 0) opW[k] = (0.8 * opW[k] + 0.2 * (opScore[k] / opCnt[k])).coerceAtLeast(0.05)
                        opScore[k] = 0.0; opCnt[k] = 0
                    }
                    sinceUpdate = 0
                }
                iter++
                itersTotal++
                if (iter % 120L == 0L) {
                    onProgress("ALNS restart ${r + 1}/$restarts", globalReport, itersTotal, nowMs() - started)
                    yield()
                }
            }
            logs.add(MirrorLog(iter = itersTotal, tag = "RunMAGI_ALNS", message = "restart=${r + 1}/$restarts best HARD=${globalReport.hard} total=${globalReport.total}"))
        }
        return V6OptimizerResult(globalBest, globalReport.copy(logs = logs + globalReport.logs), V6Algorithm.ALNS, logs, itersTotal, nowMs() - started)
    }

    private suspend fun runRsi(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val started = nowMs()
        val rng = Random(actualSeed(options.seed) xor 0x451L)
        var best = normalizeSchedule(initial, Problem(state))
        var bestReport = UnifiedViolationChecker.check(state, best)
        var iters = 0L
        val rounds = max(2, min(8, budgetSec / 30 + 2))
        val per = max(1, budgetSec / rounds)
        val logs = ArrayList<MirrorLog>()
        for (round in 0 until rounds) {
            coroutineContext.ensureActive()
            val focus = maxViolatedFamily(bestReport)
            val hypothesis = rsiGenerateHypothesis(state, best, bestReport, focus, rng)
            val phase = if (round % 2 == 0) runAlns(state, hypothesis, options.copy(restarts = 1), per, onProgress) else runV5(state, hypothesis, options, per, onProgress)
            iters += phase.iterations
            var candSched = phase.schedule
            var candReport = phase.report
            // [HF361/528/541移植] EarlyChain: Web 内部V5の停滞(reheat)フック(L11705-)に対応する RSI ラウンド境界で発火
            //   Chain3/4 は常時、Rect/BlkN は optFlags.rectSwap(既定ON)に従う — Web 呼出順 e3/e4/e5/e6 と同一。
            run {
                val lr = V6LateOperators.improve(state, candSched, candReport, rng, started + budgetSec * 1000L, rectEnabled = options.rectSwap)
                if (lr.chain3 + lr.chain4 + lr.rect + lr.blkN > 0) {
                    candSched = lr.schedule
                    candReport = lr.report
                    logs.add(MirrorLog(iter = iters, tag = "EarlyChain", message = "早期循環フック改善 (Chain3=${lr.chain3} Chain4=${lr.chain4} Rect=${lr.rect} BlkN=${lr.blkN}) round=${round + 1} HARD=${candReport.hard} total=${candReport.total}"))
                    logs.addAll(lr.logs)
                }
            }
            if (better(candReport, bestReport)) {
                best = candSched.copy2D()
                bestReport = candReport
            }
            logs.add(MirrorLog(iter = iters, tag = "RunMAGI_RSI", message = "round=${round + 1}/$rounds focus=$focus best HARD=${bestReport.hard} total=${bestReport.total}"))
            onProgress("RSI $focus", bestReport, iters, nowMs() - started)
        }
        return V6OptimizerResult(best, bestReport.copy(logs = logs + bestReport.logs), V6Algorithm.RSI, logs, iters, nowMs() - started)
    }

    private suspend fun runRsiPlus(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val started = nowMs()
        val seedSec = max(10, (budgetSec * 0.20).toInt())
        val rsiSec = max(10, (budgetSec * 0.35).toInt())
        val alnsSec = max(10, (budgetSec * 0.30).toInt())
        val polishSec = max(5, budgetSec - seedSec - rsiSec - alnsSec)
        val logs = ArrayList<MirrorLog>()
        val seed = runV5(state, initial, options, seedSec, onProgress)
        logs.add(MirrorLog(tag = "RSIPlus", message = "Phase1 Seed: HARD=${seed.report.hard} total=${seed.report.total}"))
        val rsi = runRsi(state, seed.schedule, options, rsiSec, onProgress)
        val base = if (better(rsi.report, seed.report)) rsi else seed
        logs.add(MirrorLog(tag = "RSIPlus", message = "Phase2 Hypothesis: HARD=${base.report.hard} total=${base.report.total}"))
        val refine = runAlns(state, base.schedule, options.copy(restarts = max(1, options.restarts)), alnsSec, onProgress)
        val best = if (better(refine.report, base.report)) refine else base
        var bestSched = best.schedule
        // [HF361/528/541移植] EarlyChain: Refine 確定後の停滞境界で Chain3/4(常時)+Rect/BlkN(rectSwap)を発火
        run {
            val lr = V6LateOperators.improve(state, bestSched, best.report, Random(actualSeed(options.seed) xor 0x528L), started + budgetSec * 1000L, rectEnabled = options.rectSwap)
            if (lr.chain3 + lr.chain4 + lr.rect + lr.blkN > 0) {
                bestSched = lr.schedule
                logs.add(MirrorLog(tag = "EarlyChain", message = "早期循環フック改善 (Chain3=${lr.chain3} Chain4=${lr.chain4} Rect=${lr.rect} BlkN=${lr.blkN}) HARD=${lr.report.hard} total=${lr.report.total}"))
                logs.addAll(lr.logs)
            }
        }
        val polish = hf80PostPolish(state, bestSched, polishSec, actualSeed(options.seed) xor 0x555L)
        val report = UnifiedViolationChecker.check(state, polish.schedule)
        logs.add(MirrorLog(tag = "RSIPlus", message = "Phase3/4 Refine+Polish: HARD=${report.hard} total=${report.total}"))
        return V6OptimizerResult(
            polish.schedule,
            report.copy(logs = logs + seed.phaseLogs + rsi.phaseLogs + refine.phaseLogs + polish.logs + report.logs),
            V6Algorithm.RSI_PLUS,
            logs + seed.phaseLogs + rsi.phaseLogs + refine.phaseLogs + polish.logs,
            seed.iterations + rsi.iterations + refine.iterations + polish.iterations,
            nowMs() - started,
        )
    }

    private fun hf66DataHardening(state: MagiState, schedule: Array<IntArray>, tag: String): Array<IntArray> {
        val p = Problem(state)
        val out = normalizeSchedule(schedule, p)
        for (i in 0 until p.S) {
            val allowed = p.allowedShiftsForStaff(i)
            val fallback = allowed.firstOrNull() ?: 0
            for (j in 0 until p.T) {
                val k = out[i][j]
                if (k !in 0 until p.K || !p.canDo(i, k)) out[i][j] = fallback
            }
        }
        return out
    }

    private data class RepairResult(val schedule: Array<IntArray>, val logs: List<MirrorLog>)

    private fun hf67HardRepair(state: MagiState, schedule: Array<IntArray>, rng: Random): RepairResult {
        val p = Problem(state)
        val out = hf66DataHardening(state, schedule, "hf67")
        val logs = ArrayList<MirrorLog>()
        var changed = 0

        // Apply feasible wishes first; infeasible wishes are logged by Sanity, not forced.
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val w = p.wish[i][j]
            if (w in 0 until p.K && p.canDo(i, w) && out[i][j] != w) {
                out[i][j] = w
                changed++
            }
        }

        repeat(3) {
            val cov = coverage(p, out)
            val counts = countMatrix(p, out)
            for (j in 0 until p.T) for (k in 0 until p.K) {
                val need = p.need1[k][j]
                if (need <= 0) continue
                var miss = need - cov[j][k]
                while (miss > 0) {
                    val i = bestStaffForCoverage(p, out, counts, j, k)
                    if (i < 0) break
                    val old = out[i][j]
                    if (old == k) break
                    out[i][j] = k
                    cov[j][k]++
                    if (old in 0 until p.K) cov[j][old]--
                    changed++
                    miss--
                }
            }
        }

        // Range lower bounds: fill shortage where possible without touching locked wishes.
        val counts = countMatrix(p, out)
        for (i in 0 until p.S) for (k in 0 until p.K) {
            val lo = p.rangeLo[i][k]
            if (lo == Int.MIN_VALUE || !p.canDo(i, k)) continue
            var need = lo - counts[i][k]
            var guard = 0
            while (need > 0 && guard++ < p.T) {
                var bestJ = -1
                var bestScore = Int.MAX_VALUE
                for (jj in 0 until p.T) {
                    if (p.wish[i][jj] >= 0 || out[i][jj] == k) continue
                    val score = coverageShortageCost(p, out, jj, out[i][jj]) + rng.nextInt(3)
                    if (score < bestScore) {
                        bestScore = score
                        bestJ = jj
                    }
                }
                if (bestJ < 0) break
                val j = bestJ
                val old = out[i][j]
                out[i][j] = k
                if (old in 0 until p.K) counts[i][old]--
                counts[i][k]++
                changed++
                need--
            }
        }
        if (changed > 0) logs.add(MirrorLog(tag = "HF67", message = "HardRepair changed=$changed"))
        return RepairResult(out, logs)
    }

    private data class PolishResult(val schedule: Array<IntArray>, val logs: List<MirrorLog>, val iterations: Long)

    private suspend fun hf80PostPolish(state: MagiState, initial: Array<IntArray>, seconds: Int, seed: Long): PolishResult {
        val started = nowMs()
        val rng = Random(seed)
        var best = initial.copy2D()
        var bestReport = UnifiedViolationChecker.check(state, best)
        var cur = best.copy2D()
        var curReport = bestReport
        var iters = 0L
        val deadline = nowMs() + seconds * 1000L
        while (nowMs() < deadline) {
            coroutineContext.ensureActive()
            val cand = cur.copy2D()
            when (rng.nextInt(4)) {
                0 -> swapWithinStaff(state, cand, rng)
                1 -> randomAllowedCell(state, cand, rng)
                2 -> destroyRepairViolations(state, cand, curReport, rng)
                else -> destroyRepairDay(state, cand, rng)
            }
            val fixed = hf67HardRepair(state, cand, rng).schedule
            val rep = UnifiedViolationChecker.check(state, fixed)
            if (rep.hard <= bestReport.hard && (better(rep, curReport) || acceptWorse(rep, curReport, 0.15, rng))) {
                cur = fixed
                curReport = rep
                if (better(rep, bestReport)) {
                    best = fixed.copy2D()
                    bestReport = rep
                }
            }
            iters++
            if (iters % 150L == 0L) yield()
        }
        val logs = listOf(MirrorLog(iter = iters, tag = "HF80", message = "PostPolish ${nowMs() - started}ms HARD=${bestReport.hard} total=${bestReport.total}"))
        return PolishResult(best, logs, iters)
    }

    private fun bestStaffForCoverage(p: Problem, schedule: Array<IntArray>, counts: Array<IntArray>, j: Int, k: Int): Int {
        var bestI = -1
        var bestScore = Int.MAX_VALUE
        for (i in 0 until p.S) {
            if (!p.canDo(i, k)) continue
            if (p.wish[i][j] >= 0 && p.wish[i][j] != k) continue
            val old = schedule[i][j]
            if (old == k) return i
            val hi = p.rangeHi[i][k]
            val over = if (hi != Int.MAX_VALUE && counts[i][k] >= hi) 500 else 0
            val oldNeedCost = coverageShortageCost(p, schedule, j, old)
            val score = over + counts[i][k] * 3 - oldNeedCost
            if (score < bestScore) { bestScore = score; bestI = i }
        }
        return bestI
    }

    private fun coverageShortageCost(p: Problem, schedule: Array<IntArray>, j: Int, k: Int): Int {
        if (k !in 0 until p.K) return 0
        val need = p.need1[k][j]
        if (need <= 0) return 0
        var cov = 0
        for (i in 0 until p.S) if (schedule[i][j] == k) cov++
        return if (cov <= need) 50 else 0
    }

    private fun destroyRepairDay(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = Problem(state)
        if (p.T == 0) return
        val j = rng.nextInt(p.T)
        val order = ArrayList<Int>(p.S)
        for (idx in 0 until p.S) order.add(idx)
        java.util.Collections.shuffle(order, rng)
        val cov = coverage(p, schedule)
        for (k in 0 until p.K) {
            val need = p.need1[k][j]
            if (need <= 0) continue
            var miss = need - cov[j][k]
            for (i in order) {
                if (miss <= 0) break
                if (p.wish[i][j] >= 0 && p.wish[i][j] != k) continue
                if (p.canDo(i, k) && schedule[i][j] != k) { schedule[i][j] = k; miss-- }
            }
        }
    }

    private fun destroyRepairStaff(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = Problem(state)
        if (p.S == 0) return
        val i = rng.nextInt(p.S)
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isEmpty()) return
        repeat(min(p.T, 3 + rng.nextInt(8))) {
            val j = rng.nextInt(p.T)
            if (p.wish[i][j] < 0) schedule[i][j] = allowed[rng.nextInt(allowed.size)]
        }
    }

    private fun destroyRepairViolations(state: MagiState, schedule: Array<IntArray>, report: ViolationReport, rng: Random) {
        val p = Problem(state)
        val keys = report.violations.keys.toList()
        if (keys.isEmpty()) { randomAllowedCell(state, schedule, rng); return }
        repeat(min(8, keys.size)) {
            val key = keys[rng.nextInt(keys.size)]
            val i = key.substringBefore(',').toIntOrNull() ?: return@repeat
            val j = key.substringAfter(',').toIntOrNull() ?: return@repeat
            if (i !in 0 until p.S || j !in 0 until p.T || p.wish[i][j] >= 0) return@repeat
            val allowed = p.allowedShiftsForStaff(i)
            if (allowed.isNotEmpty()) schedule[i][j] = allowed[rng.nextInt(allowed.size)]
        }
    }

    private fun randomAllowedCell(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = Problem(state)
        if (p.S == 0 || p.T == 0) return
        val i = rng.nextInt(p.S)
        val j = rng.nextInt(p.T)
        if (p.wish[i][j] >= 0) return
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isNotEmpty()) schedule[i][j] = allowed[rng.nextInt(allowed.size)]
    }

    private fun swapWithinStaff(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = Problem(state)
        if (p.S == 0 || p.T < 2) return
        val i = rng.nextInt(p.S)
        var a = rng.nextInt(p.T)
        var b = rng.nextInt(p.T)
        if (a == b) b = (b + 1) % p.T
        if (p.wish[i][a] >= 0 || p.wish[i][b] >= 0) return
        val tmp = schedule[i][a]
        schedule[i][a] = schedule[i][b]
        schedule[i][b] = tmp
    }

    private fun perturb(state: MagiState, base: Array<IntArray>, rng: Random, strength: Double): Array<IntArray> {
        val p = Problem(state)
        val out = base.copy2D()
        val n = max(1, (p.S * p.T * strength).toInt())
        repeat(n) { randomAllowedCell(state, out, rng) }
        return out
    }

    private fun rsiGenerateHypothesis(state: MagiState, base: Array<IntArray>, report: ViolationReport, focus: String, rng: Random): Array<IntArray> {
        val out = base.copy2D()
        val p = Problem(state)
        when (focus) {
            "covU", "c41" -> repeat(8) { destroyRepairDay(state, out, rng) }
            "low", "high", "c2" -> repeat(8) { destroyRepairStaff(state, out, rng) }
            "groupViol", "pref", "c3n" -> {
                val fixed = hf67HardRepair(state, out, rng).schedule
                for (i in 0 until p.S) for (j in 0 until p.T) out[i][j] = fixed[i][j]
            }
            else -> repeat(12) { destroyRepairViolations(state, out, report, rng) }
        }
        return out
    }

    private fun maxViolatedFamily(report: ViolationReport): String {
        val order = listOf("groupViol", "covU", "pref", "c3n", "low", "high", "c41", "c2", "covO", "c42", "c1", "c3", "c3m", "c3mn")
        var best = "total"
        var bestCount = -1
        for (key in order) {
            val n = report.breakdown[key] ?: 0
            if (n > bestCount) {
                bestCount = n
                best = key
            }
        }
        return best
    }

    private fun better(a: ViolationReport, b: ViolationReport): Boolean = when {
        a.hard != b.hard -> a.hard < b.hard
        a.total != b.total -> a.total < b.total
        else -> a.weightedScore < b.weightedScore
    }

    private fun acceptWorse(a: ViolationReport, b: ViolationReport, temp: Double, rng: Random): Boolean {
        if (a.hard > b.hard + 2) return false
        val delta = a.weightedScore - b.weightedScore
        return delta <= 0.0 || rng.nextDouble() < exp(-max(0.0, delta) / (200.0 * temp + 1e-9))
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
    private fun actualSeed(seed: Long): Long = if (seed == 0L) System.nanoTime() else seed
}
