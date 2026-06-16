package com.magi.app.engine

/**
 * Small on-device optimizer used by the current native UI.
 *
 * It performs deterministic local search:
 * - Start from the current schedule.
 * - For each staff/day cell, try every shift.
 * - Keep the shift only when the total penalty improves.
 * - Repeat for a small number of passes.
 *
 * This is intentionally simple and safe for UI-thread demo use.
 * A heavier simulated annealing engine can replace this class later.
 */
class SmartOptimizer(
    private val problem: ResolvedProblem,
    private val evaluator: ResolvedEvaluator = ResolvedEvaluator(problem),
) {
    fun improve(schedule: Array<IntArray>, maxPasses: Int = 4): Array<IntArray> {
        val best = schedule.map { it.copyOf() }.toTypedArray()
        var bestScore = evaluator.penalty(best)

        repeat(maxPasses.coerceAtLeast(1)) {
            var changed = false
            for (staff in 0 until problem.staffCount) {
                for (day in 0 until problem.dayCount) {
                    if (staff !in best.indices || day !in best[staff].indices) continue
                    val original = best[staff][day]
                    var localBestShift = original
                    var localBestScore = bestScore

                    for (shift in 0 until problem.shiftCount) {
                        if (shift == original) continue
                        best[staff][day] = shift
                        val score = evaluator.penalty(best)
                        if (score < localBestScore) {
                            localBestScore = score
                            localBestShift = shift
                        }
                    }

                    best[staff][day] = localBestShift
                    if (localBestScore < bestScore) {
                        bestScore = localBestScore
                        changed = true
                    }
                }
            }
            if (!changed) return best
        }
        return best
    }
}
