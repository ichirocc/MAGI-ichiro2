package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6PortAnalyzerTest {
    @Test
    fun v6OverviewComputesAptAndRisk() {
        val st = MagiState(
            startDate = "2025-12-01",
            endDate = "2025-12-03",
            shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "1", "1")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = true,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "2")),
            schedule = listOf(listOf(1, 1, 1), listOf(0, 0, 0)),
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val report = UnifiedViolationChecker.check(st)
        val v6 = V6PortAnalyzer.analyze(st, st.schedule.toIntArray2D(), report)
        assertEquals(3, v6.demand)
        assertEquals(100, v6.coveragePct)
        assertTrue(v6.aptPenalty > 0.0)
        assertTrue(v6.sanityNotes.any { it.contains("groupShiftApt") })
    }
}
