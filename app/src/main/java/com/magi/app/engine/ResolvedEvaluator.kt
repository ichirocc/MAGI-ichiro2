package com.magi.app.engine

import kotlin.math.abs

class ResolvedEvaluator(
    private val problem: ResolvedProblem,
    private val constraints: ResolvedConstraintSet = ResolvedConstraintSet(problem.state),
) {
    fun penalty(schedule: Array<IntArray>): Long {
        var score = 0L
        score += validityPenalty(schedule)
        score += wishPenalty(schedule)
        score += coveragePenalty(schedule)
        score += sequencePenalty(schedule)
        score += countPenalty(schedule)
        score += rangePenalty(schedule)
        score += spacingPenalty(schedule)
        score += groupRangePenalty(schedule)
        score += groupPairPenalty(schedule)
        score += groupAptPenalty(schedule)
        return score
    }

    private fun validityPenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (i in 0 until problem.staffCount) for (j in 0 until problem.dayCount) {
            val shift = schedule.getOrNull(i)?.getOrNull(j) ?: -1
            if (shift !in 0 until problem.shiftCount) {
                score += 1000L
            } else if (!problem.isAllowed(i, shift)) {
                score += 300L
            }
        }
        return score
    }

    private fun wishPenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (i in 0 until problem.staffCount) for (j in 0 until problem.dayCount) {
            val wish = problem.wishes[i][j]
            if (wish >= 0 && schedule.getOrNull(i)?.getOrNull(j) != wish) score += 10L
        }
        return score
    }

    private fun coveragePenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (k in 0 until problem.shiftCount) for (j in 0 until problem.dayCount) {
            val need = problem.need1[k][j]
            if (need >= 0) {
                var actual = 0
                for (i in 0 until problem.staffCount) if (schedule.getOrNull(i)?.getOrNull(j) == k) actual++
                score += abs(actual - need) * 100L
            }
        }
        return score
    }

    private fun sequencePenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        score += sequencePenaltyFor(schedule, constraints.c3, 50L)
        score += sequencePenaltyFor(schedule, constraints.c3n, 40L)
        score += sequencePenaltyFor(schedule, constraints.c3m, 40L)
        score += sequencePenaltyFor(schedule, constraints.c3mn, 60L)
        return score
    }

    private fun sequencePenaltyFor(schedule: Array<IntArray>, rules: List<C3>, weight: Long): Long {
        var score = 0L
        for (rule in rules) {
            val seq = rule.seq
            if (seq.isEmpty() || seq.size > problem.dayCount) continue
            for (i in 0 until problem.staffCount) {
                for (j in 0..problem.dayCount - seq.size) {
                    var same = true
                    for (p in seq.indices) {
                        if (schedule.getOrNull(i)?.getOrNull(j + p) != seq[p]) {
                            same = false
                            break
                        }
                    }
                    if (same) score += weight
                }
            }
        }
        return score
    }

    private fun countPenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (rule in constraints.c2) {
            for (i in 0 until problem.staffCount) {
                var count = 0
                for (j in 0 until problem.dayCount) if (schedule.getOrNull(i)?.getOrNull(j) == rule.shiftIdx) count++
                if (count > rule.count) score += (count - rule.count) * 20L
            }
        }
        return score
    }

    private fun rangePenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (i in 0 until problem.staffCount) for (k in 0 until problem.shiftCount) {
            val lo = problem.rangeMin[i][k]
            val hi = problem.rangeMax[i][k]
            if (lo == Int.MIN_VALUE && hi == Int.MAX_VALUE) continue
            var count = 0
            for (j in 0 until problem.dayCount) if (schedule.getOrNull(i)?.getOrNull(j) == k) count++
            if (lo != Int.MIN_VALUE && count < lo) score += (lo - count) * 40L
            if (hi != Int.MAX_VALUE && count > hi) score += (count - hi) * 40L
        }
        return score
    }

    private fun spacingPenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (rule in constraints.c1) {
            val minGap = rule.day1.coerceAtLeast(0)
            val maxGap = rule.day2.coerceAtLeast(0)
            if (rule.shiftIdx !in 0 until problem.shiftCount || maxGap == 0) continue
            for (i in 0 until problem.staffCount) {
                var last = -1
                for (j in 0 until problem.dayCount) {
                    if (schedule.getOrNull(i)?.getOrNull(j) == rule.shiftIdx) {
                        if (last >= 0) {
                            val gap = j - last
                            if (minGap > 0 && gap < minGap) score += (minGap - gap) * 30L
                            if (maxGap > 0 && gap > maxGap) score += (gap - maxGap) * 30L
                        }
                        last = j
                    }
                }
            }
        }
        return score
    }

    private fun groupRangePenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (rule in constraints.c41) {
            if (rule.groupIdx !in 0 until problem.groupCount || rule.shiftIdx !in 0 until problem.shiftCount) continue
            var count = 0
            for (i in 0 until problem.staffCount) {
                if (problem.staffGroup[i] != rule.groupIdx) continue
                for (j in 0 until problem.dayCount) {
                    if (schedule.getOrNull(i)?.getOrNull(j) == rule.shiftIdx) count++
                }
            }
            if (count < rule.min) score += (rule.min - count) * 35L
            if (count > rule.max) score += (count - rule.max) * 35L
        }
        return score
    }

    private fun groupPairPenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (rule in constraints.c42) {
            if (rule.groupA !in 0 until problem.groupCount || rule.groupB !in 0 until problem.groupCount) continue
            if (rule.shiftA !in 0 until problem.shiftCount || rule.shiftB !in 0 until problem.shiftCount) continue
            for (j in 0 until problem.dayCount) {
                var hasA = false
                var hasB = false
                for (i in 0 until problem.staffCount) {
                    val group = problem.staffGroup[i]
                    val shift = schedule.getOrNull(i)?.getOrNull(j)
                    if (group == rule.groupA && shift == rule.shiftA) hasA = true
                    if (group == rule.groupB && shift == rule.shiftB) hasB = true
                }
                if (hasA && hasB) score += 45L
            }
        }
        return score
    }

    private fun groupAptPenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        val targets = problem.state.groupShiftApt
        for (g in 0 until problem.groupCount) {
            val row = targets.getOrNull(g) ?: continue
            for (k in 0 until problem.shiftCount) {
                val target = row.getOrNull(k)?.toIntOrNull() ?: continue
                var count = 0
                for (i in 0 until problem.staffCount) {
                    if (problem.staffGroup[i] != g) continue
                    for (j in 0 until problem.dayCount) {
                        if (schedule.getOrNull(i)?.getOrNull(j) == k) count++
                    }
                }
                score += abs(count - target) * 15L
            }
        }
        return score
    }
}
