package com.magi.app.engine

import com.magi.app.model.MagiState

class MagiProblem(val state: MagiState) {
    val staffCount: Int = state.staffCount
    val dayCount: Int = state.dayCount
    val shiftCount: Int = state.shiftCount
    val groupCount: Int = state.groupCount

    val staffGroup: IntArray = IntArray(staffCount) { index ->
        state.staff.getOrNull(index)?.groupIdx ?: 0
    }

    val allowedShiftsByGroup: Array<IntArray> = Array(groupCount) { groupIndex ->
        val row = state.groupShift.getOrNull(groupIndex) ?: emptyList()
        (0 until shiftCount).filter { shiftIndex ->
            row.getOrNull(shiftIndex) == 1
        }.toIntArray()
    }

    fun initialAssignment(): Array<IntArray> = Array(staffCount) { staffIndex ->
        IntArray(dayCount) { dayIndex ->
            val raw = state.schedule.getOrNull(staffIndex)?.getOrNull(dayIndex) ?: 0
            if (shiftCount <= 0) 0 else raw.coerceIn(0, shiftCount - 1)
        }
    }
}
