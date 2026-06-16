package com.magi.app.engine

import kotlin.math.exp
import kotlin.random.Random

/**
 * Web/V6 style optimizer bridge for the current Native data model.
 *
 * This ports the important behaviour of the Web optimizer without depending on the DOM/UI layer:
 * 1. repair coverage shortages first,
 * 2. run full-cell best-improvement local search,
 * 3. try pair swaps,
 * 4. finish with a short deterministic simulated-annealing style shake.
 *
 * The objective is the same Native score used by [ResolvedEvaluator], so the UI and engine stay in sync.
 */
class WebSmartOptimizer(
    private val problem: ResolvedProblem,
    private val evaluator: ResolvedEvaluator = ResolvedEvaluator(problem),
) {
    private val delta = DeltaEvaluator(problem, evaluator)

    fun optimize(initial: Array<IntArray>, passes: Int = 6, seed: Int = 2026): Array<IntArray> {
        var best = normalize(initial)
        var bestScore = delta.score(best)
        var current = copyOf(best)
        var currentScore = bestScore

        current = repairCoverage(current)
        currentScore = delta.score(current)
        if (currentScore < bestScore) {
            best = copyOf(current)
            bestScore = currentScore
        }

        repeat(passes.coerceAtLeast(1)) {
            val improved = bestCellPass(current)
            current = improved.first
            currentScore = improved.second
            if (currentScore < bestScore) {
                best = copyOf(current)
                bestScore = currentScore
            }

            val swapped = swapPass(current, currentScore)
            current = swapped.first
            currentScore = swapped.second
            if (currentScore < bestScore) {
                best = copyOf(current)
                bestScore = currentScore
            }
        }

        val annealed = anneal(best, bestScore, seed)
        if (annealed.second < bestScore) best = annealed.first
        return best
    }

    private fun normalize(source: Array<IntArray>): Array<IntArray> = Array(problem.staffCount) { i ->
        IntArray(problem.dayCount) { j ->
            val value = source.getOrNull(i)?.getOrNull(j) ?: 0
            if (value in 0 until problem.shiftCount) value else 0
        }
    }

    private fun copyOf(source: Array<IntArray>): Array<IntArray> = Array(source.size) { source[it].copyOf() }

    private fun bestCellPass(source: Array<IntArray>): Pair<Array<IntArray>, Long> {
        val work = copyOf(source)
        var score = delta.score(work)
        var changed: Boolean
        do {
            changed = false
            for (i in 0 until problem.staffCount) {
                for (j in 0 until problem.dayCount) {
                    val old = work[i][j]
                    var bestShift = old
                    var bestScore = score
                    for (k in allowedOrAll(i)) {
                        if (k == old) continue
                        val s = delta.scoreCellChange(work, i, j, k)
                        if (s < bestScore) {
                            bestScore = s
                            bestShift = k
                        }
                    }
                    if (bestShift != old) {
                        score = delta.applyCellChange(work, i, j, bestShift)
                        changed = true
                    }
                }
            }
        } while (changed)
        return work to score
    }

    private fun swapPass(source: Array<IntArray>, sourceScore: Long): Pair<Array<IntArray>, Long> {
        val work = copyOf(source)
        var score = sourceScore
        for (j in 0 until problem.dayCount) {
            for (a in 0 until problem.staffCount) {
                for (b in a + 1 until problem.staffCount) {
                    val sa = work[a][j]
                    val sb = work[b][j]
                    if (sa == sb) continue
                    if (!canAssign(a, sb) || !canAssign(b, sa)) continue
                    val s = delta.scoreSwap(work, a, b, j)
                    if (s <= score) score = delta.applySwap(work, a, b, j)
                }
            }
        }
        return work to score
    }

    private fun repairCoverage(source: Array<IntArray>): Array<IntArray> {
        val work = copyOf(source)
        for (j in 0 until problem.dayCount) {
            for (k in 0 until problem.shiftCount) {
                val need = problem.need1[k][j]
                if (need < 0) continue
                while (countShiftOnDay(work, j, k) < need) {
                    var bestStaff = -1
                    var bestScore = Long.MAX_VALUE
                    for (i in 0 until problem.staffCount) {
                        if (!canAssign(i, k) || work[i][j] == k) continue
                        val score = delta.scoreCellChange(work, i, j, k)
                        if (score < bestScore) {
                            bestScore = score
                            bestStaff = i
                        }
                    }
                    if (bestStaff < 0) break
                    delta.applyCellChange(work, bestStaff, j, k)
                }
            }
        }
        return work
    }

    private fun anneal(source: Array<IntArray>, sourceScore: Long, seed: Int): Pair<Array<IntArray>, Long> {
        val rnd = Random(seed)
        val work = copyOf(source)
        var score = sourceScore
        var best = copyOf(work)
        var bestScore = score
        val steps = (problem.staffCount * problem.dayCount * problem.shiftCount * 12).coerceAtLeast(80)
        for (step in 0 until steps) {
            if (problem.staffCount == 0 || problem.dayCount == 0 || problem.shiftCount == 0) break
            val i = rnd.nextInt(problem.staffCount)
            val j = rnd.nextInt(problem.dayCount)
            val candidates = allowedOrAll(i)
            if (candidates.isEmpty()) continue
            val old = work[i][j]
            val nw = candidates[rnd.nextInt(candidates.size)]
            if (old == nw) continue
            val next = delta.scoreCellChange(work, i, j, nw)
            val temp = 20.0 * (1.0 - step.toDouble() / steps.toDouble()) + 0.1
            val accept = next <= score || rnd.nextDouble() < exp((score - next).toDouble() / temp)
            if (accept) {
                score = delta.applyCellChange(work, i, j, nw)
                if (score < bestScore) {
                    bestScore = score
                    best = copyOf(work)
                }
            }
        }
        return best to bestScore
    }

    private fun countShiftOnDay(schedule: Array<IntArray>, day: Int, shift: Int): Int {
        var count = 0
        for (i in 0 until problem.staffCount) if (schedule.getOrNull(i)?.getOrNull(day) == shift) count++
        return count
    }

    private fun canAssign(staff: Int, shift: Int): Boolean = problem.isAllowed(staff, shift)

    private fun allowedOrAll(staff: Int): IntArray {
        val group = problem.staffGroup.getOrNull(staff) ?: -1
        val allowed = problem.allowedShiftsByGroup.getOrNull(group) ?: IntArray(0)
        return if (allowed.isNotEmpty()) allowed else IntArray(problem.shiftCount) { it }
    }
}
