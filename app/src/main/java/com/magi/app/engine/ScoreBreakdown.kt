package com.magi.app.engine

import kotlin.math.abs

data class ScoreBreakdown(
    val total: Long,
    val invalidAssignments: Int,
    val wishMismatches: Int,
    val coverageGaps: Int,
    val rangeWarnings: Int,
    val spacingWarnings: Int,
    val sequenceWarnings: Int,
    val groupRangeWarnings: Int,
    val groupPairWarnings: Int,
    val groupAptGaps: Int,
) {
    val summary: String
        get() = "無効:$invalidAssignments / 希望:$wishMismatches / 人数:$coverageGaps / 範囲:$rangeWarnings / 間隔:$spacingWarnings / パターン:$sequenceWarnings / グループ:$groupRangeWarnings"
}

class ScoreAnalyzer(
    private val problem: ResolvedProblem,
    private val evaluator: ResolvedEvaluator = ResolvedEvaluator(problem),
    private val constraints: ResolvedConstraintSet = ResolvedConstraintSet(problem.state),
) {
    fun analyze(schedule: Array<IntArray>): ScoreBreakdown {
        var invalid = 0
        var wish = 0
        var coverage = 0
        var range = 0

        for (i in 0 until problem.staffCount) {
            for (j in 0 until problem.dayCount) {
                val shift = schedule.getOrNull(i)?.getOrNull(j) ?: -1
                if (shift !in 0 until problem.shiftCount || !problem.isAllowed(i, shift)) invalid++
                val wished = problem.wishes[i][j]
                if (wished >= 0 && shift != wished) wish++
            }
        }

        for (k in 0 until problem.shiftCount) {
            for (j in 0 until problem.dayCount) {
                val need = problem.need1[k][j]
                if (need >= 0) {
                    var actual = 0
                    for (i in 0 until problem.staffCount) {
                        if (schedule.getOrNull(i)?.getOrNull(j) == k) actual++
                    }
                    coverage += abs(actual - need)
                }
            }
        }

        for (i in 0 until problem.staffCount) {
            for (k in 0 until problem.shiftCount) {
                val lo = problem.rangeMin[i][k]
                val hi = problem.rangeMax[i][k]
                if (lo == Int.MIN_VALUE && hi == Int.MAX_VALUE) continue
                var count = 0
                for (j in 0 until problem.dayCount) {
                    if (schedule.getOrNull(i)?.getOrNull(j) == k) count++
                }
                if (lo != Int.MIN_VALUE && count < lo) range++
                if (hi != Int.MAX_VALUE && count > hi) range++
            }
        }

        return ScoreBreakdown(
            total = evaluator.penalty(schedule),
            invalidAssignments = invalid,
            wishMismatches = wish,
            coverageGaps = coverage,
            rangeWarnings = range,
            spacingWarnings = countSpacingWarnings(schedule),
            sequenceWarnings = countSequenceWarnings(schedule),
            groupRangeWarnings = countGroupRangeWarnings(schedule),
            groupPairWarnings = countGroupPairWarnings(schedule),
            groupAptGaps = countGroupAptGaps(schedule),
        )
    }

    private fun countSequenceWarnings(schedule: Array<IntArray>): Int {
        return countSequenceWarningsFor(schedule, constraints.c3) +
            countSequenceWarningsFor(schedule, constraints.c3n) +
            countSequenceWarningsFor(schedule, constraints.c3m) +
            countSequenceWarningsFor(schedule, constraints.c3mn)
    }

    private fun countSequenceWarningsFor(schedule: Array<IntArray>, rules: List<C3>): Int {
        var count = 0
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
                    if (same) count++
                }
            }
        }
        return count
    }

    private fun countSpacingWarnings(schedule: Array<IntArray>): Int {
        var count = 0
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
                            if (minGap > 0 && gap < minGap) count++
                            if (maxGap > 0 && gap > maxGap) count++
                        }
                        last = j
                    }
                }
            }
        }
        return count
    }

    private fun countGroupRangeWarnings(schedule: Array<IntArray>): Int {
        var warnings = 0
        for (rule in constraints.c41) {
            if (rule.groupIdx !in 0 until problem.groupCount || rule.shiftIdx !in 0 until problem.shiftCount) continue
            var count = 0
            for (i in 0 until problem.staffCount) {
                if (problem.staffGroup[i] != rule.groupIdx) continue
                for (j in 0 until problem.dayCount) {
                    if (schedule.getOrNull(i)?.getOrNull(j) == rule.shiftIdx) count++
                }
            }
            if (count < rule.min) warnings++
            if (count > rule.max) warnings++
        }
        return warnings
    }

    private fun countGroupPairWarnings(schedule: Array<IntArray>): Int {
        var warnings = 0
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
                if (hasA && hasB) warnings++
            }
        }
        return warnings
    }

    private fun countGroupAptGaps(schedule: Array<IntArray>): Int {
        var gaps = 0
        val targets = problem.state.groupShiftApt
        for (g in 0 until problem.groupCount) {
            val row = targets.getOrNull(g) ?: continue
            for (k in 0 until problem.shiftCount) {
                val target = row.getOrNull(k)?.toIntOrNull() ?: continue
                var actual = 0
                for (i in 0 until problem.staffCount) {
                    if (problem.staffGroup[i] != g) continue
                    for (j in 0 until problem.dayCount) {
                        if (schedule.getOrNull(i)?.getOrNull(j) == k) actual++
                    }
                }
                gaps += abs(actual - target)
            }
        }
        return gaps
    }
}
