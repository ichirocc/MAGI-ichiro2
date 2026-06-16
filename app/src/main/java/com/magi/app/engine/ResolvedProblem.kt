package com.magi.app.engine

import com.magi.app.model.MagiState

/** Incremental bridge toward the full ZIP engine. */
class ResolvedProblem(val state: MagiState) {
    val staffCount = state.staffCount
    val dayCount = state.dayCount
    val shiftCount = state.shiftCount
    val groupCount = state.groupCount

    val staffGroup = IntArray(staffCount) { i ->
        state.staff[i].groupIdx.coerceIn(0, (groupCount - 1).coerceAtLeast(0))
    }

    val allowedShiftsByGroup: Array<IntArray> = Array(groupCount) { g ->
        val row = state.groupShift.getOrNull(g) ?: emptyList()
        (0 until shiftCount).filter { k -> row.getOrNull(k) == 1 }.toIntArray()
    }

    val wishes: Array<IntArray> = Array(staffCount) { IntArray(dayCount) { -1 } }
    val need1: Array<IntArray> = Array(shiftCount) { IntArray(dayCount) { -1 } }
    val need2: Array<IntArray> = Array(shiftCount) { IntArray(dayCount) { -1 } }
    val rangeMin: Array<IntArray> = Array(staffCount) { IntArray(shiftCount) { Int.MIN_VALUE } }
    val rangeMax: Array<IntArray> = Array(staffCount) { IntArray(shiftCount) { Int.MAX_VALUE } }

    init {
        state.wishes.forEach { (key, value) ->
            val p = key.split(',')
            val i = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val j = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (i in 0 until staffCount && j in 0 until dayCount) wishes[i][j] = value
        }
        for (k in 0 until shiftCount) for (j in 0 until dayCount) {
            need1[k][j] = resolveNeed(k, j, false)
            need2[k][j] = resolveNeed(k, j, true)
        }
        state.staffRange.forEach { (key, value) ->
            val p = key.split(',')
            val i = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val k = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (i in 0 until staffCount && k in 0 until shiftCount) {
                value.lo.toIntOrNull()?.let { rangeMin[i][k] = it }
                value.hi.toIntOrNull()?.let { rangeMax[i][k] = it }
            }
        }
    }

    fun initialAssignment(): Array<IntArray> = Array(staffCount) { i ->
        IntArray(dayCount) { j ->
            val fromSchedule = state.schedule.getOrNull(i)?.getOrNull(j) ?: 0
            val wished = wishes[i][j]
            val candidate = if (wished >= 0) wished else fromSchedule
            if (candidate in 0 until shiftCount) candidate else 0
        }
    }

    fun isAllowed(staffIndex: Int, shiftIndex: Int): Boolean {
        if (staffIndex !in 0 until staffCount || shiftIndex !in 0 until shiftCount) return false
        val group = staffGroup[staffIndex]
        return allowedShiftsByGroup.getOrNull(group)?.contains(shiftIndex) == true
    }

    private fun resolveNeed(k: Int, j: Int, secondPattern: Boolean): Int {
        val override = if (secondPattern) state.needDay2["$k,$j"] else state.needDay1["$k,$j"]
        if (!override.isNullOrBlank()) return override.toIntOrNull() ?: -1
        val fallback = if (secondPattern) state.shifts[k].need2 else state.shifts[k].need1
        return if (fallback.isBlank()) -1 else fallback.toIntOrNull() ?: -1
    }
}
