package com.magi.app.engine

import com.magi.app.model.C1Row
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConsistencyCheckTest {
    @Test
    fun initialScheduleScoresAreConsistent() {
        val problem = ResolvedProblem(sampleState())

        val result = EngineConsistencyCheck.checkInitial(problem)

        assertTrue(result.message, result.ok)
        assertEquals(result.resolvedScore, result.deltaScore)
        assertEquals(result.resolvedScore, result.analysisScore)
    }

    @Test
    fun editedScheduleScoresAreConsistent() {
        val problem = ResolvedProblem(sampleState())
        val schedule = problem.initialAssignment()
        schedule[0][1] = 1

        val result = EngineConsistencyCheck.check(problem, schedule)

        assertTrue(result.message, result.ok)
        assertEquals(result.resolvedScore, result.deltaScore)
        assertEquals(result.resolvedScore, result.analysisScore)
    }

    private fun sampleState(): MagiState = MagiState(
        startDate = "2026-01-01",
        endDate = "2026-01-03",
        shifts = listOf(
            Shift(name = "Day", kigou = "D", need1 = "1", need2 = ""),
            Shift(name = "Night", kigou = "N", need1 = "1", need2 = ""),
        ),
        groups = listOf(Group(name = "All", kigou = "A")),
        staff = listOf(
            Staff(name = "Alice", groupIdx = 0),
            Staff(name = "Bob", groupIdx = 0),
        ),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("1", "1")),
        schedule = listOf(
            listOf(0, 1, 0),
            listOf(1, 0, 1),
        ),
        wishes = mapOf("0,0" to 0, "1,2" to 1),
        staffRange = mapOf("0,1" to Range(lo = "1", hi = "2")),
        needDay1 = emptyMap(),
        needDay2 = emptyMap(),
        cons1 = listOf(C1Row(day1 = "1", shiftKigou = "N", day2 = "2")),
        cons2 = listOf(C2Row(shiftKigou = "N", count = "2")),
        cons3 = listOf(C3Row(listOf("N", "N"))),
        cons3n = emptyList(),
        cons3m = emptyList(),
        cons3mn = emptyList(),
        cons41 = listOf(C41Row(groupKigou = "A", shiftKigou = "D", l = "2", u = "4")),
        cons42 = listOf(C42Row(g1Kigou = "A", g2Kigou = "A", s1Kigou = "D", s2Kigou = "N")),
    )
}
