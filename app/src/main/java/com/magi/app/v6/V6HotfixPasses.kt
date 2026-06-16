package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Native replacements for the Web-only post-optimization hotfix modules.
 *
 * The Web V6 calls HF80 -> HF67 -> HF66 -> HF70 after each optimizer run from
 * inside App.handleOptimize().  Android does not have window.HFxx modules, so the
 * passes live here as pure Kotlin and can be called from ViewModel/tests.
 */
data class HF80Result(
    val newSchedule: Array<IntArray>,
    val beforeHard: Int,
    val afterHard: Int,
    val beforeScore: Double,
    val afterScore: Double,
    val cycles: Int,
    val applied: Boolean,
    val reason: String,
    val logs: List<MirrorLog>,
)

data class HF67Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val swapsApplied: Int,
    val shortageSwaps: Int,
    val capacitySwaps: Int,
    val swapsRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF66Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val movesApplied: Int,
    val shortageMoves: Int,
    val capacityMoves: Int,
    val movesRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF70Result(
    val anomalies: Int,
    val message: String,
    val advice: String,
    val logs: List<MirrorLog>,
)

data class V6PostOptimizationResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val hf80: HF80Result,
    val hf67: HF67Result,
    val hf66: HF66Result,
    val hf70: HF70Result,
    val logs: List<MirrorLog>,
)

object V6HotfixPasses {
    fun runPostOptimization(
        state: MagiState,
        schedule: Array<IntArray>,
        algoName: String,
        seed: Long = System.nanoTime(),
    ): V6PostOptimizationResult {
        var work = schedule.copy2D()
        val logs = ArrayList<MirrorLog>()

        val r80 = applyHF80StrategicOscillation(state, work, maxCycles = 3, seed = seed xor 0x80L)
        work = r80.newSchedule.copy2D()
        logs.addAll(r80.logs)

        val r67 = applyHF67InterStaffSwap(state, work, maxSwaps = 30)
        work = r67.newSchedule.copy2D()
        logs.addAll(r67.logs)

        val r66 = applyHF66IntraStaffRedistribution(state, work, maxMoves = 30)
        work = r66.newSchedule.copy2D()
        logs.addAll(r66.logs)

        val report = UnifiedViolationChecker.check(state, work)
        val r70 = detectHF70Anomalies(state, work, algoName, report)
        logs.addAll(r70.logs)

        val allLogs = ArrayList<MirrorLog>()
        allLogs.addAll(logs)
        allLogs.addAll(report.logs)
        return V6PostOptimizationResult(work, report.copy(logs = allLogs), r80, r67, r66, r70, logs)
    }

    fun applyHF80StrategicOscillation(
        state: MagiState,
        schedule: Array<IntArray>,
        maxCycles: Int = 3,
        seed: Long = System.nanoTime(),
    ): HF80Result {
        val p = Problem(state)
        val rng = Random(seed)
        val before = UnifiedViolationChecker.check(state, schedule)
        var best = normalizeSchedule(schedule, p)
        var bestReport = before
        var applied = false
        var usedCycles = 0
        val cycleMax = max(0, maxCycles)
        var cycle = 0
        while (cycle < cycleMax) {
            val cand = best.copy2D()
            val strength = max(1, (p.S * p.T * (0.03 + cycle * 0.02)).toInt())
            var t = 0
            while (t < strength) {
                if (p.S > 0 && p.T > 0) {
                    val i = rng.nextInt(p.S)
                    val j = rng.nextInt(p.T)
                    if (p.wish[i][j] < 0) {
                        val allowed = p.allowedShiftsForStaff(i)
                        if (allowed.isNotEmpty()) cand[i][j] = allowed[rng.nextInt(allowed.size)]
                    }
                }
                t++
            }
            val polished = localBestImprovement(state, cand, 250 + cycle * 120, rng)
            val rep = UnifiedViolationChecker.check(state, polished)
            usedCycles = cycle + 1
            if (isBetter(rep, bestReport)) {
                best = polished
                bestReport = rep
                applied = true
            }
            cycle++
        }
        val reason = if (applied) "strategic oscillation accepted" else "no improving oscillation"
        val logs = listOf(MirrorLog(tag = "HF80", message = "SO applied=$applied HARD ${before.hard}->${bestReport.hard} score ${before.weightedScore.toLong()}->${bestReport.weightedScore.toLong()} cycles=$usedCycles"))
        return HF80Result(best, before.hard, bestReport.hard, before.weightedScore, bestReport.weightedScore, usedCycles, applied, reason, logs)
    }

    fun applyHF67InterStaffSwap(state: MagiState, schedule: Array<IntArray>, maxSwaps: Int = 30): HF67Result {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var current = before
        var swaps = 0
        var shortage = 0
        var capacity = 0
        var rollback = 0

        while (swaps < maxSwaps) {
            val counts = countMatrix(p, work)
            var best: SwapCandidate? = null
            var bestReport: ViolationReport? = null
            for (k in 0 until p.K) {
                val lows = ArrayList<Int>()
                val highs = ArrayList<Int>()
                for (i in 0 until p.S) {
                    if (p.canDo(i, k) && p.rangeLo[i][k] != Int.MIN_VALUE && counts[i][k] < p.rangeLo[i][k]) lows.add(i)
                    if (counts[i][k] > effectiveHi(p, i, k)) highs.add(i)
                }
                for (to in lows) {
                    for (from in highs) {
                        if (to == from) continue
                        val cand = trySwapShiftBetweenStaff(p, work, from, to, k) ?: continue
                        val rep = UnifiedViolationChecker.check(state, cand.first)
                        val ref = bestReport ?: current
                        if (isBetter(rep, ref)) {
                            best = cand.second
                            bestReport = rep
                        }
                    }
                }
            }
            if (best == null || bestReport == null) break
            val b = best
            val next = work.copy2D()
            val tmp = next[b.fromStaff][b.fromDay]
            next[b.fromStaff][b.fromDay] = next[b.toStaff][b.toDay]
            next[b.toStaff][b.toDay] = tmp
            work = next
            current = bestReport
            swaps++
            shortage++
            if (current.soft < before.soft) capacity++
        }
        if (swaps == 0) {
            val improved = localPairwiseStaffSwap(state, work, maxSwaps)
            work = improved.first
            swaps = improved.second
            rollback = improved.third
            current = UnifiedViolationChecker.check(state, work)
            capacity = swaps
        }
        val logs = listOf(MirrorLog(tag = "HF67", message = "inter-staff swap applied=$swaps rollback=$rollback total ${before.total}->${current.total}"))
        return HF67Result(work, before.total, current.total, swaps, shortage, capacity, rollback, logs)
    }

    fun applyHF66IntraStaffRedistribution(state: MagiState, schedule: Array<IntArray>, maxMoves: Int = 30): HF66Result {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var current = before
        var moves = 0
        var shortageMoves = 0
        var capacityMoves = 0
        var rollback = 0

        while (moves < maxMoves) {
            val counts = countMatrix(p, work)
            var bestMove: MoveCandidate? = null
            var bestReport: ViolationReport? = null
            for (i in 0 until p.S) {
                val lows = ArrayList<Int>()
                val highs = ArrayList<Int>()
                for (k in 0 until p.K) {
                    if (p.canDo(i, k) && p.rangeLo[i][k] != Int.MIN_VALUE && counts[i][k] < p.rangeLo[i][k]) lows.add(k)
                    if (counts[i][k] > effectiveHi(p, i, k)) highs.add(k)
                }
                for (want in lows) for (give in highs) {
                    for (j in 0 until p.T) {
                        if (work[i][j] != give || p.wish[i][j] >= 0) continue
                        val cand = work.copy2D()
                        cand[i][j] = want
                        val rep = UnifiedViolationChecker.check(state, cand)
                        if (isBetter(rep, bestReport ?: current)) {
                            bestMove = MoveCandidate(i, j, give, want)
                            bestReport = rep
                        }
                    }
                }
            }
            val mv = bestMove ?: break
            work[mv.staff][mv.day] = mv.toShift
            current = bestReport ?: UnifiedViolationChecker.check(state, work)
            moves++
            shortageMoves++
            if (current.soft < before.soft) capacityMoves++
        }
        if (moves == 0) {
            val rng = Random(0x66L)
            var t = 0
            while (t < maxMoves) {
                if (p.S > 0 && p.T > 0) {
                    val cand = work.copy2D()
                    val i = rng.nextInt(p.S)
                    val j = rng.nextInt(p.T)
                    if (p.wish[i][j] < 0) {
                        val allowed = p.allowedShiftsForStaff(i)
                        if (allowed.isNotEmpty()) {
                            val old = cand[i][j]
                            cand[i][j] = allowed[rng.nextInt(allowed.size)]
                            if (cand[i][j] != old) {
                                val rep = UnifiedViolationChecker.check(state, cand)
                                if (isBetter(rep, current)) {
                                    work = cand
                                    current = rep
                                    moves++
                                    capacityMoves++
                                } else {
                                    rollback++
                                }
                            }
                        }
                    }
                }
                t++
            }
        }
        val logs = listOf(MirrorLog(tag = "HF66", message = "intra-staff redistribution applied=$moves rollback=$rollback total ${before.total}->${current.total}"))
        return HF66Result(work, before.total, current.total, moves, shortageMoves, capacityMoves, rollback, logs)
    }

    fun detectHF70Anomalies(
        state: MagiState,
        schedule: Array<IntArray>,
        algoName: String,
        report: ViolationReport = UnifiedViolationChecker.check(state, schedule),
    ): HF70Result {
        val invalid = invalidAssignmentCount(state, schedule)
        val impossible = V6SanityPort.detectImpossibleWishes(state).size
        val hardCore = report.hard - (report.breakdown["pref"] ?: 0)
        val issues = ArrayList<String>()
        if (invalid > 0) issues.add("担当不可/範囲外配置 $invalid 件")
        if (impossible > 0) issues.add("不可能希望 $impossible 件")
        if (hardCore > 0) issues.add("希望以外HARD $hardCore 件")
        val msg = if (issues.isEmpty()) "HF70: $algoName 異常なし" else "HF70: ${issues.joinToString(" / ")}"
        val advice = if (issues.isEmpty()) "" else "設定(ws1/担当範囲), 希望(ws3), 必要人数, 連勤禁止条件を確認してください"
        val level = if (issues.isEmpty()) "I" else "W"
        val logs = listOf(MirrorLog(level = level, tag = "HF70", message = msg + if (advice.isNotBlank()) " — $advice" else ""))
        return HF70Result(issues.size, msg, advice, logs)
    }

    private data class SwapCandidate(val fromStaff: Int, val fromDay: Int, val toStaff: Int, val toDay: Int)
    private data class MoveCandidate(val staff: Int, val day: Int, val fromShift: Int, val toShift: Int)

    private fun trySwapShiftBetweenStaff(p: Problem, schedule: Array<IntArray>, from: Int, to: Int, shift: Int): Pair<Array<IntArray>, SwapCandidate>? {
        val fromDays = ArrayList<Int>()
        val toDays = ArrayList<Int>()
        for (j in 0 until p.T) {
            if (schedule[from][j] == shift && p.wish[from][j] < 0) fromDays.add(j)
            if (schedule[to][j] != shift && p.wish[to][j] < 0 && p.canDo(to, shift) && p.canDo(from, schedule[to][j])) toDays.add(j)
        }
        for (jf in fromDays) for (jt in toDays) {
            val cand = schedule.copy2D()
            val tmp = cand[from][jf]
            cand[from][jf] = cand[to][jt]
            cand[to][jt] = tmp
            return Pair(cand, SwapCandidate(from, jf, to, jt))
        }
        return null
    }

    private fun localPairwiseStaffSwap(state: MagiState, schedule: Array<IntArray>, maxSwaps: Int): Triple<Array<IntArray>, Int, Int> {
        val p = Problem(state)
        var work = schedule.copy2D()
        var current = UnifiedViolationChecker.check(state, work)
        var applied = 0
        var rollback = 0
        loop@ for (i in 0 until p.S) for (i2 in i + 1 until p.S) for (j in 0 until p.T) {
            if (applied >= maxSwaps) break@loop
            if (p.wish[i][j] >= 0 || p.wish[i2][j] >= 0) continue
            val a = work[i][j]
            val b = work[i2][j]
            if (a == b || !p.canDo(i, b) || !p.canDo(i2, a)) continue
            val cand = work.copy2D()
            cand[i][j] = b
            cand[i2][j] = a
            val rep = UnifiedViolationChecker.check(state, cand)
            if (isBetter(rep, current)) {
                work = cand
                current = rep
                applied++
            } else {
                rollback++
            }
        }
        return Triple(work, applied, rollback)
    }

    private fun localBestImprovement(state: MagiState, schedule: Array<IntArray>, tries: Int, rng: Random): Array<IntArray> {
        val p = Problem(state)
        var best = schedule.copy2D()
        var bestReport = UnifiedViolationChecker.check(state, best)
        var t = 0
        val maxTry = max(0, tries)
        while (t < maxTry) {
            if (p.S > 0 && p.T > 0) {
                val cand = best.copy2D()
                val i = rng.nextInt(p.S)
                val j = rng.nextInt(p.T)
                if (p.wish[i][j] < 0) {
                    val allowed = p.allowedShiftsForStaff(i)
                    if (allowed.isNotEmpty()) {
                        cand[i][j] = allowed[rng.nextInt(allowed.size)]
                        val rep = UnifiedViolationChecker.check(state, cand)
                        if (isBetter(rep, bestReport)) {
                            best = cand
                            bestReport = rep
                        }
                    }
                }
            }
            t++
        }
        return best
    }

    private fun effectiveHi(p: Problem, i: Int, k: Int): Int {
        val hi = p.rangeHi[i][k]
        return if (hi == Int.MAX_VALUE) Int.MAX_VALUE / 4 else hi
    }

    private fun invalidAssignmentCount(state: MagiState, schedule: Array<IntArray>): Int {
        val p = Problem(state)
        val s = normalizeSchedule(schedule, p)
        var n = 0
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = s[i][j]
            if (k !in 0 until p.K || !p.canDo(i, k)) n++
        }
        return n
    }

    private fun isBetter(a: ViolationReport, b: ViolationReport): Boolean {
        if (a.hard != b.hard) return a.hard < b.hard
        if (a.total != b.total) return a.total < b.total
        return a.weightedScore < b.weightedScore
    }
}
