package com.magi.app.engine

/**
 * C3 run-mode helpers from P11.
 *
 * For a non-forbidden single-shift C3 sequence, for example D-D-D meaning
 * "prefer a run of 3 consecutive D shifts", the Web V6 evaluator uses run
 * deficit rather than per-window matching. This helper keeps that logic isolated
 * so the current evaluator can adopt it safely in a later pass.
 */
object C3Run {
    /** True iff [seq] is a non-empty run of the same shift index. */
    fun isSingleShiftSeq(seq: IntArray): Boolean {
        if (seq.isEmpty()) return false
        for (index in 1 until seq.size) {
            if (seq[index] != seq[0]) return false
        }
        return true
    }

    /**
     * Run deficit for staff [staffIndex]'s row over shift [shiftIndex] wanting runs of length [length].
     *
     * Each run of assigned days shorter than [length] adds the missing amount. For example,
     * wanting N-N-N and seeing a run of N-N adds 1; seeing a single N adds 2.
     */
    fun rowDeficit(schedule: Array<IntArray>, staffIndex: Int, shiftIndex: Int, length: Int): Long {
        if (staffIndex !in schedule.indices || length <= 0) return 0L
        val row = schedule[staffIndex]
        var deficit = 0L
        var runLength = 0
        var day = 0
        while (day <= row.size) {
            val on = day < row.size && row[day] == shiftIndex
            if (on) {
                runLength++
            } else if (runLength > 0) {
                val missing = length - runLength
                if (missing > 0) deficit += missing.toLong()
                runLength = 0
            }
            day++
        }
        return deficit
    }
}
