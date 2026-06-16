package com.magi.app.engine

import kotlin.math.abs

/**
 * Web-style delta evaluation façade.
 *
 * Counter-backed scoring used by WebSmartOptimizer:
 * - dayShiftCount[shift][day] for required coverage,
 * - staffShiftCount[staff][shift] for staff count/range checks,
 * - groupShiftCount[group][shift] for Web/V6 group-level constraints,
 * - direct row/day scans for sequence, spacing and group-pair constraints.
 */
class DeltaEvaluator(
    private val problem: ResolvedProblem,
    private val evaluator: ResolvedEvaluator = ResolvedEvaluator(problem),
    private val constraints: ResolvedConstraintSet = ResolvedConstraintSet(problem.state),
) {
    fun score(schedule: Array<IntArray>): Long = fastScore(schedule, buildCounters(schedule))

    fun scoreCellChange(
        schedule: Array<IntArray>,
        staffIndex: Int,
        dayIndex: Int,
        nextShift: Int,
    ): Long {
        val row = schedule.getOrNull(staffIndex) ?: return evaluator.penalty(schedule)
        if (dayIndex !in row.indices) return evaluator.penalty(schedule)
        val old = row[dayIndex]
        if (old == nextShift) return score(schedule)
        val counters = buildCounters(schedule)
        adjustCell(counters, staffIndex, old, dayIndex, -1)
        adjustCell(counters, staffIndex, nextShift, dayIndex, 1)
        row[dayIndex] = nextShift
        val result = fastScore(schedule, counters)
        row[dayIndex] = old
        return result
    }

    fun applyCellChange(
        schedule: Array<IntArray>,
        staffIndex: Int,
        dayIndex: Int,
        nextShift: Int,
    ): Long {
        if (staffIndex in schedule.indices && dayIndex in schedule[staffIndex].indices) {
            schedule[staffIndex][dayIndex] = nextShift
        }
        return score(schedule)
    }

    fun scoreSwap(
        schedule: Array<IntArray>,
        staffA: Int,
        staffB: Int,
        dayIndex: Int,
    ): Long {
        val rowA = schedule.getOrNull(staffA) ?: return evaluator.penalty(schedule)
        val rowB = schedule.getOrNull(staffB) ?: return evaluator.penalty(schedule)
        if (dayIndex !in rowA.indices || dayIndex !in rowB.indices) return evaluator.penalty(schedule)
        val a = rowA[dayIndex]
        val b = rowB[dayIndex]
        if (a == b) return score(schedule)
        val counters = buildCounters(schedule)
        adjustCell(counters, staffA, a, dayIndex, -1)
        adjustCell(counters, staffB, b, dayIndex, -1)
        adjustCell(counters, staffA, b, dayIndex, 1)
        adjustCell(counters, staffB, a, dayIndex, 1)
        rowA[dayIndex] = b
        rowB[dayIndex] = a
        val result = fastScore(schedule, counters)
        rowA[dayIndex] = a
        rowB[dayIndex] = b
        return result
    }

    fun applySwap(
        schedule: Array<IntArray>,
        staffA: Int,
        staffB: Int,
        dayIndex: Int,
    ): Long {
        val rowA = schedule.getOrNull(staffA) ?: return evaluator.penalty(schedule)
        val rowB = schedule.getOrNull(staffB) ?: return evaluator.penalty(schedule)
        if (dayIndex !in rowA.indices || dayIndex !in rowB.indices) return evaluator.penalty(schedule)
        val tmp = rowA[dayIndex]
        rowA[dayIndex] = rowB[dayIndex]
        rowB[dayIndex] = tmp
        return score(schedule)
    }

    private data class Counters(
        val dayShiftCount: Array<IntArray>,
        val staffShiftCount: Array<IntArray>,
        val groupShiftCount: Array<IntArray>,
    )

    private fun buildCounters(schedule: Array<IntArray>): Counters {
        val dayShiftCount = Array(problem.shiftCount) { IntArray(problem.dayCount) }
        val staffShiftCount = Array(problem.staffCount) { IntArray(problem.shiftCount) }
        val groupShiftCount = Array(problem.groupCount) { IntArray(problem.shiftCount) }
        for (i in 0 until problem.staffCount) {
            val row = schedule.getOrNull(i) ?: continue
            val group = problem.staffGroup.getOrNull(i) ?: continue
            for (j in 0 until problem.dayCount) {
                val shift = row.getOrNull(j) ?: continue
                if (shift in 0 until problem.shiftCount) {
                    dayShiftCount[shift][j]++
                    staffShiftCount[i][shift]++
                    if (group in 0 until problem.groupCount) groupShiftCount[group][shift]++
                }
            }
        }
        return Counters(dayShiftCount, staffShiftCount, groupShiftCount)
    }

    private fun adjustCell(counters: Counters, staffIndex: Int, shift: Int, dayIndex: Int, amount: Int) {
        if (shift !in 0 until problem.shiftCount) return
        if (staffIndex !in 0 until problem.staffCount) return
        if (dayIndex !in 0 until problem.dayCount) return
        val group = problem.staffGroup.getOrNull(staffIndex) ?: -1
        counters.dayShiftCount[shift][dayIndex] += amount
        counters.staffShiftCount[staffIndex][shift] += amount
        if (group in 0 until problem.groupCount) counters.groupShiftCount[group][shift] += amount
    }

    private fun fastScore(schedule: Array<IntArray>, counters: Counters): Long {
        var score = 0L
        score += validityAndWishPenalty(schedule)
        score += coveragePenalty(counters)
        score += sequencePenalty(schedule)
        score += countPenalty(counters)
        score += rangePenalty(counters)
        score += spacingPenalty(schedule)
        score += groupRangePenalty(counters)
        score += groupPairPenalty(schedule)
        score += groupAptPenalty(counters)
        return score
    }

    private fun validityAndWishPenalty(schedule: Array<IntArray>): Long {
        var score = 0L
        for (i in 0 until problem.staffCount) {
            for (j in 0 until problem.dayCount) {
                val shift = schedule.getOrNull(i)?.getOrNull(j) ?: -1
                if (shift !in 0 until problem.shiftCount) {
                    score += 1000L
                } else if (!problem.isAllowed(i, shift)) {
                    score += 300L
                }
                val wish = problem.wishes[i][j]
                if (wish >= 0 && shift != wish) score += 10L
            }
        }
        return score
    }

    private fun coveragePenalty(counters: Counters): Long {
        var score = 0L
        for (k in 0 until problem.shiftCount) {
            for (j in 0 until problem.dayCount) {
                val need = problem.need1[k][j]
                if (need >= 0) score += abs(counters.dayShiftCount[k][j] - need) * 100L
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

    private fun countPenalty(counters: Counters): Long {
        var score = 0L
        for (rule in constraints.c2) {
            for (i in 0 until problem.staffCount) {
                val count = counters.staffShiftCount[i][rule.shiftIdx]
                if (count > rule.count) score += (count - rule.count) * 20L
            }
        }
        return score
    }

    private fun rangePenalty(counters: Counters): Long {
        var score = 0L
        for (i in 0 until problem.staffCount) {
            for (k in 0 until problem.shiftCount) {
                val lo = problem.rangeMin[i][k]
                val hi = problem.rangeMax[i][k]
                if (lo == Int.MIN_VALUE && hi == Int.MAX_VALUE) continue
                val count = counters.staffShiftCount[i][k]
                if (lo != Int.MIN_VALUE && count < lo) score += (lo - count) * 40L
                if (hi != Int.MAX_VALUE && count > hi) score += (count - hi) * 40L
            }
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

    private fun groupRangePenalty(counters: Counters): Long {
        var score = 0L
        for (rule in constraints.c41) {
            if (rule.groupIdx !in 0 until problem.groupCount || rule.shiftIdx !in 0 until problem.shiftCount) continue
            val count = counters.groupShiftCount[rule.groupIdx][rule.shiftIdx]
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

    private fun groupAptPenalty(counters: Counters): Long {
        var score = 0L
        val targets = problem.state.groupShiftApt
        for (g in 0 until problem.groupCount) {
            val row = targets.getOrNull(g) ?: continue
            for (k in 0 until problem.shiftCount) {
                val target = row.getOrNull(k)?.toIntOrNull() ?: continue
                score += abs(counters.groupShiftCount[g][k] - target) * 15L
            }
        }
        return score
    }
}
